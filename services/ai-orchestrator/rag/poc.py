"""poc CLI：RAG 金标集评测（W32：项目作用域 + 拒答阈值 + 条件重排）
====================================================================

用法（在 `services/ai-orchestrator` 目录）：
    # 主跑：全量 258 题、重排关（默认 off）、强制项目作用域 + 阈值扫描 + 前后对照
    python -m rag.poc --env-file "<REF_RAG_PROJECT>\\.env" \
        --golden tests/rag/golden/knowledge-golden-v2.jsonl --rerank=off

    # 条件重排抽样（≤40 题；1.5s 预算口径）
    python -m rag.poc ... --rerank=conditional --rerank-limit 40
    # 条件重排抽样 @25s（看真实增益 + 缓存命中率；同 run 内 repeat=2）
    python -m rag.poc ... --rerank=conditional --rerank-limit 12 --rerank-timeout-ms 25000 \
        --rerank-max-tokens 1024 --rerank-repeat 2
    # 可选 always 抽样（≤10 题）
    python -m rag.poc ... --rerank=conditional --rerank-alt=always --rerank-alt-limit 10

W32 一次 run 产出（写入 `tests/rag/results/rag-poc-w32.json`）：
    1. `primary`：**强制项目作用域** + 重排关的逐题明细与主指标（Hit@5 / MRR / 延迟 / 负样本）；
    2. `project_scope`：**过滤前（UNSCOPED）vs 过滤后（project+ALL）** 对照
       （Hit@5/MRR/跨项目干扰命中率/过期泄漏/负样本拒答）；
    3. `guard`：阈值扫描曲线（0.15—0.5 + 扩展点）、最优点、最终「阈值+双低」口径；
    4. `rerank_runs`：`--rerank` 主 run（+ `--rerank-alt` 次 run）的触发率/超时率/
       解析失败率/缓存命中率/子集 off-on 对照；
    5. `breakdown`：按 variant / project 分组。

指标口径（设计 §7，**不美化**）：
    - Hit@5：gold 知识出现在检索前 5（knowledge_id 命中）；MRR：首个 gold 的 1/rank；
    - **answerable（W32 上移）**：`rag.guard` 判定；负样本「拒答率」= answerable=false 占比；
      「误拒率」= 正样本被 answerable=false 的占比（目标 ≤0.05）；
    - 过期泄漏 / 跨项目干扰：`expect_not_ids` 出现在 top5 即计泄漏（期望 0）；
    - 延迟：检索、重排、单问总耗时的 P50/P95；
    - 重排：触发/未触发、超时、解析失败、缓存命中，全部如实计数。

向后兼容：`--rerank-mode off|conditional|always`（W31 参数）仍可用；`--rerank` 为 W32 正式参数
（多 `shadow` 模式，默认 `off`）。
"""

from __future__ import annotations

import argparse
import json
import statistics
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Sequence

from .config import REPO_DIR, SERVICE_DIR, Settings, load_settings, mask_secret
from .guard import (
    DEFAULT_SCAN_GRID,
    EXTENDED_SCAN_GRID,
    AnswerGuard,
    choose_threshold,
    decide,
    scan_thresholds,
    score_profile,
)
from .hybrid import HybridRetriever
from .llm import probe_models
from .reranker import RerankController
from .scope import UNSCOPED
from .store import VectorStore
from . import ingest as ingest_module


# ---------------------------------------------------------------- 工具

def percentile(values: list[float], ratio: float) -> float:
    """线性插值分位数（ratio ∈ [0,1]；空列表返回 0）。"""
    if not values:
        return 0.0
    data = sorted(float(v) for v in values)
    if len(data) == 1:
        return data[0]
    position = (len(data) - 1) * min(max(ratio, 0.0), 1.0)
    low = int(position)
    high = min(low + 1, len(data) - 1)
    weight = position - low
    return data[low] * (1 - weight) + data[high] * weight


def latency_stats(values: list[float]) -> dict[str, float]:
    """延迟统计：count/mean/P50/P95/max。"""
    if not values:
        return {"count": 0, "mean": 0.0, "p50": 0.0, "p95": 0.0, "max": 0.0}
    return {
        "count": len(values),
        "mean": round(statistics.fmean(values), 2),
        "p50": round(percentile(values, 0.50), 2),
        "p95": round(percentile(values, 0.95), 2),
        "max": round(max(values), 2),
    }


def load_golden(path: Path, limit: int | None = None) -> list[dict[str, Any]]:
    """读取金标 JSONL（跳过空行/以 # 开头的注释行）。"""
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            rows.append(json.loads(line))
    if limit:
        rows = rows[:limit]
    return rows


def unique_ordered(values: list[str]) -> list[str]:
    """去重保序（命中列表用）。"""
    seen: set[str] = set()
    out: list[str] = []
    for value in values:
        if value not in seen:
            seen.add(value)
            out.append(value)
    return out


def score_top(chunks: list[Any]) -> float:
    """候选集的最高向量相似度（BM25-only 命中为 0）。"""
    return max((float(getattr(chunk, "score", 0.0) or 0.0) for chunk in chunks), default=0.0)


def pick_sample_indices(total: int, limit: int | None) -> list[int]:
    """抽样下标：均匀跨全量取样（覆盖全部题型/项目），保持升序。"""
    if not limit or limit >= total:
        return list(range(total))
    limit = max(1, int(limit))
    step = total / limit
    return sorted({min(int(index * step), total - 1) for index in range(limit)})


def golden_project(question: dict[str, Any], settings: Settings, override: str | None = None) -> str:
    """单题项目作用域：CLI 覆盖 > 金标 project > 配置默认 > 平台级 ALL。"""
    if override:
        return override
    value = str(question.get("project") or "").strip()
    if value:
        return value
    return (settings.default_project_id or "").strip() or settings.global_project_id


def _hit_rank(ids: list[str], gold_ids: list[str]) -> int | None:
    """首个 gold 知识在知识序列中的名次（1 起）。"""
    for rank, knowledge_id in enumerate(ids, start=1):
        if knowledge_id in gold_ids:
            return rank
    return None


# ---------------------------------------------------------------- 单题评测

def evaluate_question(
    question: dict[str, Any],
    retriever: HybridRetriever,
    settings: Settings,
    top_k: int,
    *,
    project_override: str | None = None,
    unscoped: bool = False,
) -> dict[str, Any]:
    """对单题跑一次「项目作用域检索」（重排关，供主指标/阈值扫描用）。"""
    qid = str(question.get("id") or "")
    text = str(question.get("question") or "")
    gold_ids = [str(x) for x in (question.get("gold_ids") or [])]
    expect_not_ids = [str(x) for x in (question.get("expect_not_ids") or [])]
    negative = bool(question.get("negative"))
    retrievable = bool(question.get("retrievable", True))
    scope_project = UNSCOPED if unscoped else golden_project(question, settings, project_override)

    total_started = time.perf_counter()
    hybrid_result = retriever.search(
        text,
        top_k=settings.rerank_candidates,
        category=question.get("category") or None,
        recall_per_route=settings.recall_per_route,
        project_id=scope_project,
    )
    search_ms = int((time.perf_counter() - total_started) * 1000)
    candidates = hybrid_result.chunks
    top = candidates[:top_k]
    ids = unique_ordered([chunk.knowledge_id for chunk in top])
    hit_rank = _hit_rank(ids, gold_ids) if gold_ids else None
    profile = score_profile(top, question=text)
    leaks = [kid for kid in ids if kid in expect_not_ids]

    return {
        "id": qid,
        "question": text,
        "category": question.get("category") or "",
        "variant": question.get("variant") or "",
        "project": question.get("project") or "",
        "scope": hybrid_result.scope.to_dict(),
        "negative": negative,
        "retrievable": retrievable,
        "gold_ids": gold_ids,
        "expect_not_ids": expect_not_ids,
        "expect_not_kind": str(question.get("expect_not_kind") or ""),
        "retrieval": {
            "ids": ids,
            "chunk_ids": [chunk.chunk_id for chunk in top],
            "scores": [round(float(chunk.score), 4) for chunk in top],
            "hit_rank": hit_rank,
            "top1": round(float(profile["top1"]), 4),
            "top2": round(float(profile["top2"]), 4),
            "gap": round(float(profile["gap"]), 4),
            "coverage": round(float(profile["coverage"]), 4),
            "candidates": int(profile["candidates"]),
            "leaks": leaks,
            "expired_leaks": leaks if question.get("expect_not_kind") == "expired" else [],
            "project_leaks": leaks if question.get("expect_not_kind") == "other_project" else [],
        },
        "search": {
            "latency_ms": search_ms,
            "embed_latency_ms": hybrid_result.embed_latency_ms,
            "vector_latency_ms": hybrid_result.vector_latency_ms,
            "bm25_latency_ms": hybrid_result.bm25_latency_ms,
            "vector_hits": hybrid_result.vector_hits,
            "bm25_hits": hybrid_result.bm25_hits,
            "candidates": len(candidates),
            "backend": hybrid_result.backend,
            "filtered_effective": hybrid_result.filtered_effective,
            "filtered_archived": hybrid_result.filtered_archived,
        },
        "total_ms": int((time.perf_counter() - total_started) * 1000),
        "rerank": {},
    }


# ---------------------------------------------------------------- 指标

def _guard_config(
    settings: Settings,
    *,
    threshold: float | None = None,
    min_gap: float | None = None,
    flat_margin: float | None = None,
    coverage_min: float | None = None,
    gap_rule: bool | None = None,
    coverage_rule: bool | None = None,
) -> dict[str, Any]:
    """当前/覆盖后的 guard 配置（写进 JSON 便于复核）。"""
    return {
        "answer_threshold": round(float(settings.answer_threshold if threshold is None else threshold), 4),
        "answer_min_gap": round(float(settings.answer_min_gap if min_gap is None else min_gap), 4),
        "answer_flat_margin": round(float(settings.answer_flat_margin if flat_margin is None else flat_margin), 4),
        "answer_coverage_min": round(float(settings.answer_coverage_min if coverage_min is None else coverage_min), 4),
        "gap_rule": bool(settings.answer_gap_rule if gap_rule is None else gap_rule),
        "coverage_rule": bool(settings.answer_coverage_rule if coverage_rule is None else coverage_rule),
    }


def apply_guard(record: dict[str, Any], guard_config: dict[str, Any]) -> dict[str, Any]:
    """对单题检索画像做 answerable 判定（不重复检索）。"""
    retrieval = record.get("retrieval") or {}
    profile = {
        "candidates": int(retrieval.get("candidates") or 0),
        "top1": float(retrieval.get("top1") or 0.0),
        "top2": float(retrieval.get("top2") or 0.0),
        "gap": float(retrieval.get("gap") or 0.0),
        "coverage": float(retrieval.get("coverage") or 0.0),
    }
    result = decide(
        profile,
        float(guard_config["answer_threshold"]),
        min_gap=float(guard_config["answer_min_gap"]),
        flat_margin=float(guard_config["answer_flat_margin"]),
        gap_rule=bool(guard_config["gap_rule"]),
        coverage_min=float(guard_config.get("answer_coverage_min") or 0.0),
        coverage_rule=bool(guard_config.get("coverage_rule", False)),
    )
    return result.to_dict()


def pass_metrics(records: Sequence[dict[str, Any]], guard_config: dict[str, Any], *, with_breakdown: bool = False) -> dict[str, Any]:
    """一次检索 pass 的完整指标（正样本 / 负样本 / 拒答 / 泄漏 / 延迟）。"""
    positives = [r for r in records if not r["negative"] and r.get("retrievable", True)]
    negatives = [r for r in records if r["negative"]]
    hit_ranks = [r["retrieval"]["hit_rank"] for r in positives]
    hits = sum(1 for rank in hit_ranks if rank)
    mrr = sum(1.0 / rank for rank in hit_ranks if rank) / len(positives) if positives else 0.0

    pos_refused = 0
    answered_and_hit = 0
    for record in positives:
        decision = apply_guard(record, guard_config)
        if not decision["answerable"]:
            pos_refused += 1
        elif record["retrieval"]["hit_rank"]:
            answered_and_hit += 1
    neg_refused = 0
    for record in negatives:
        if not apply_guard(record, guard_config)["answerable"]:
            neg_refused += 1

    expired_rows = [r for r in records if r.get("expect_not_kind") == "expired" and r.get("expect_not_ids")]
    project_rows = [r for r in records if r.get("expect_not_kind") == "other_project" and r.get("expect_not_ids")]
    expired_leaked = [r for r in expired_rows if r["retrieval"].get("expired_leaks")]
    project_leaked = [r for r in project_rows if r["retrieval"].get("project_leaks")]

    metrics: dict[str, Any] = {
        "questions": len(records),
        "positive_count": len(positives),
        "negative_count": len(negatives),
        "hit_at_5": round(hits / len(positives), 4) if positives else 0.0,
        "mrr": round(mrr, 4),
        "hits": hits,
        "positive_refused": pos_refused,
        "false_refusal_rate": round(pos_refused / len(positives), 4) if positives else 0.0,
        "answered_positives": len(positives) - pos_refused,
        "answerable_hit_at_5": round(answered_and_hit / (len(positives) - pos_refused), 4)
        if len(positives) - pos_refused
        else 0.0,
        "negative_refused": neg_refused,
        "negative_refusal_rate": round(neg_refused / len(negatives), 4) if negatives else 0.0,
        "expect_not": {
            "expired": {
                "guarded": len(expired_rows),
                "leaked": len(expired_leaked),
                "leak_rate": round(len(expired_leaked) / len(expired_rows), 4) if expired_rows else 0.0,
            },
            "project_distractor": {
                "guarded": len(project_rows),
                "hit": len(project_leaked),
                "hit_rate": round(len(project_leaked) / len(project_rows), 4) if project_rows else 0.0,
            },
            "leaked_ids": unique_ordered(
                [kid for r in expired_leaked + project_leaked for kid in (r["retrieval"].get("leaks") or [])]
            ),
        },
        "search_latency_ms": latency_stats([float(r["search"]["latency_ms"]) for r in records]),
        "total_latency_ms": latency_stats([float(r["total_ms"]) for r in records]),
        "guard": dict(guard_config),
    }
    if with_breakdown:
        metrics["by_variant"] = breakdown_by(records, "variant", guard_config)
        metrics["by_project"] = breakdown_by(records, "project", guard_config)
    return metrics


def breakdown_by(records: Sequence[dict[str, Any]], key: str, guard_config: dict[str, Any]) -> dict[str, Any]:
    """按字段分组统计 Hit@5 / MRR / 负样本拒答率。"""
    groups: dict[str, list[dict[str, Any]]] = {}
    for record in records:
        groups.setdefault(str(record.get(key) or "unknown"), []).append(record)
    out: dict[str, Any] = {}
    for value, rows in sorted(groups.items()):
        entry = pass_metrics(rows, guard_config)
        out[value] = {
            "total": entry["questions"],
            "positive_count": entry["positive_count"],
            "negative_count": entry["negative_count"],
            "hit_at_5": entry["hit_at_5"],
            "mrr": entry["mrr"],
            "negative_refusal_rate": entry["negative_refusal_rate"],
            "false_refusal_rate": entry["false_refusal_rate"],
            "project_distractor_hit_rate": entry["expect_not"]["project_distractor"]["hit_rate"],
            "expired_leak_rate": entry["expect_not"]["expired"]["leak_rate"],
        }
    return out


def refusal_reason_counts(records: Sequence[dict[str, Any]], guard_config: dict[str, Any]) -> dict[str, int]:
    """拒答原因分布（no_evidence / low_confidence / flat_confidence）。"""
    counts: dict[str, int] = {}
    for record in records:
        decision = apply_guard(record, guard_config)
        if decision["answerable"]:
            continue
        reason = str(decision["refusal_reason"] or "unknown")
        counts[reason] = counts.get(reason, 0) + 1
    return dict(sorted(counts.items()))


# ---------------------------------------------------------------- 重排 run

def run_rerank(
    label: str,
    mode: str,
    golden: Sequence[dict[str, Any]],
    records: list[dict[str, Any]],
    retriever: HybridRetriever,
    settings: Settings,
    top_k: int,
    *,
    limit: int | None,
    repeat: int = 1,
    project_override: str | None = None,
    guard_config: dict[str, Any] | None = None,
    print_fn: Callable[[str], None] = print,
) -> dict[str, Any]:
    """在均匀抽样子集上跑重排（off/on 同一子集对照），并把结果写回 records[index]["rerank"][label]。"""
    indices = pick_sample_indices(len(golden), limit)
    print_fn(f"[poc] 重排 run={label} mode={mode} 抽样 {len(indices)}/{len(golden)} 题（repeat={repeat}）")
    controller = RerankController(settings)
    guard = AnswerGuard(
        settings,
        threshold=float(guard_config["answer_threshold"]),
        min_gap=float(guard_config["answer_min_gap"]),
        flat_margin=float(guard_config["answer_flat_margin"]),
        gap_rule=bool(guard_config["gap_rule"]),
        coverage_min=float(guard_config.get("answer_coverage_min") or 0.0),
        coverage_rule=bool(guard_config.get("coverage_rule", False)),
    )
    rows: list[dict[str, Any]] = []
    for pass_index in range(max(1, repeat)):
        for number, index in enumerate(indices, start=1):
            question = golden[index]
            text = str(question.get("question") or "")
            scope_project = golden_project(question, settings, project_override)
            started = time.perf_counter()
            hybrid = retriever.search(
                text,
                top_k=settings.rerank_candidates,
                category=question.get("category") or None,
                recall_per_route=settings.recall_per_route,
                project_id=scope_project,
            )
            decision = controller.apply(text, hybrid.chunks, top_n=top_k, project_id=scope_project)
            elapsed = int((time.perf_counter() - started) * 1000)
            gold_ids = [str(x) for x in (question.get("gold_ids") or [])]
            expect_not_ids = [str(x) for x in (question.get("expect_not_ids") or [])]
            on_ids = unique_ordered([chunk.knowledge_id for chunk in decision.chunks])
            on_hit_rank = _hit_rank(on_ids, gold_ids) if gold_ids else None
            profile = score_profile(decision.chunks, question=text)
            guard_result = guard.evaluate(decision.chunks, question=text)
            row = {
                "id": str(question.get("id") or ""),
                "index": index,
                "pass": pass_index,
                "variant": question.get("variant") or "",
                "negative": bool(question.get("negative")),
                "retrievable": bool(question.get("retrievable", True)),
                "gold_ids": gold_ids,
                "expect_not_ids": expect_not_ids,
                "expect_not_kind": str(question.get("expect_not_kind") or ""),
                "scope": hybrid.scope.to_dict(),
                "off": {
                    "ids": records[index]["retrieval"]["ids"],
                    "hit_rank": records[index]["retrieval"]["hit_rank"],
                    "top1": records[index]["retrieval"]["top1"],
                    "gap": records[index]["retrieval"]["gap"],
                },
                "on": {
                    "ids": on_ids,
                    "hit_rank": on_hit_rank,
                    "top1": round(float(profile["top1"]), 4),
                    "top2": round(float(profile["top2"]), 4),
                    "gap": round(float(profile["gap"]), 4),
                    "coverage": round(float(profile["coverage"]), 4),
                    "leaks": [kid for kid in on_ids if kid in expect_not_ids],
                    "answerable": guard_result.answerable,
                    "refusal_reason": guard_result.refusal_reason,
                    "triggered": decision.triggered,
                    "trigger_reason": decision.trigger_reason,
                    "status": decision.status,
                    "cached": decision.cached,
                    "applied": decision.applied,
                    "error": decision.error,
                    "latency_ms": decision.latency_ms,
                    "model": decision.model,
                    "temperature_fallback": decision.temperature_fallback,
                    "candidates": decision.candidates,
                    "shadow_ids": decision.shadow_ids,
                    "total_ms": elapsed,
                },
            }
            rows.append(row)
            records[index]["rerank"][label] = row["on"]
            if number % 10 == 0 or number == len(indices):
                print_fn(f"[poc] 重排进度 {number}/{len(indices)}（pass {pass_index + 1}/{max(1, repeat)}）")

    # 子集指标（off 取主 pass 的同题结果；on 取重排结果）
    subset_off = [records[index] for index in indices]
    on_records: list[dict[str, Any]] = []
    for row in rows:
        if row["pass"] != 0:
            continue
        on_records.append(
            {
                "negative": row["negative"],
                "retrievable": row["retrievable"],
                "gold_ids": row["gold_ids"],
                "expect_not_ids": row["expect_not_ids"],
                "expect_not_kind": row["expect_not_kind"],
                "retrieval": {
                    "ids": row["on"]["ids"],
                    "hit_rank": row["on"]["hit_rank"],
                    "top1": row["on"]["top1"],
                    "top2": row["on"]["top2"],
                    "gap": row["on"]["gap"],
                    "coverage": row["on"]["coverage"],
                    "candidates": row["on"]["candidates"],
                    "leaks": row["on"]["leaks"],
                    "expired_leaks": row["on"]["leaks"] if row["expect_not_kind"] == "expired" else [],
                    "project_leaks": row["on"]["leaks"] if row["expect_not_kind"] == "other_project" else [],
                },
                "search": {"latency_ms": 0},
                "total_ms": row["on"]["total_ms"],
            }
        )
    guard_config = guard_config or _guard_config(settings)
    off_subset_metrics = pass_metrics(subset_off, guard_config)
    on_subset_metrics = pass_metrics(on_records, guard_config)
    statuses: dict[str, int] = {}
    for row in rows:
        if row["pass"] != 0:
            continue
        statuses[row["on"]["status"]] = statuses.get(row["on"]["status"], 0) + 1
    applied = sum(1 for row in rows if row["pass"] == 0 and row["on"]["applied"])
    second_pass = [row for row in rows if row["pass"] > 0]
    return {
        "label": label,
        "mode": mode,
        "sample": {
            "size": len(indices),
            "total": len(golden),
            "indices": indices,
            "repeat": max(1, repeat),
        },
        "stats": controller.stats(),
        "status_counts": dict(sorted(statuses.items())),
        "applied": applied,
        "metrics": {
            "off_subset": off_subset_metrics,
            "on_subset": on_subset_metrics,
            "delta": {
                "hit_at_5": round(on_subset_metrics["hit_at_5"] - off_subset_metrics["hit_at_5"], 4),
                "mrr": round(on_subset_metrics["mrr"] - off_subset_metrics["mrr"], 4),
            },
        },
        "rerank_latency_ms_all": latency_stats([float(row["on"]["latency_ms"]) for row in rows if row["pass"] == 0]),
        "rerank_latency_ms_total": latency_stats([float(row["on"]["total_ms"]) for row in rows if row["pass"] == 0]),
        "cache_second_pass": {
            "rows": len(second_pass),
            "cache_hits": sum(1 for row in second_pass if row["on"]["cached"]),
            "cache_second_pass_hit_rate": round(
                sum(1 for row in second_pass if row["on"]["cached"]) / len(second_pass), 4
            )
            if second_pass
            else 0.0,
        },
        "per_question": rows,
    }


# ---------------------------------------------------------------- summary

def build_summary(
    *,
    settings: Settings,
    store: VectorStore,
    golden: Sequence[dict[str, Any]],
    golden_path: Path,
    primary_records: list[dict[str, Any]],
    unscoped_records: list[dict[str, Any]] | None,
    guard_report: dict[str, Any],
    rerank_runs: dict[str, Any],
    index_manifest: dict[str, Any] | None,
    notes: list[str],
    label: str,
) -> dict[str, Any]:
    """组装 W32 输出 JSON。"""
    guard_config = guard_report["final_config"]
    primary_metrics = pass_metrics(primary_records, guard_config, with_breakdown=True)
    primary_metrics["refusal_reason_counts"] = refusal_reason_counts(primary_records, guard_config)
    summary: dict[str, Any] = {
        "generated_at": datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds"),
        "label": label,
        "task": "task-78",
        "poc": "RAG W32 · 检索层加固（项目作用域 / 拒答上移 / RRF 确定性 / 条件重排 / 内部检索服务）",
        "config": settings.safe_dict(),
        "environment": {
            "mock_embed": settings.is_mock_embed,
            "embed_provider": settings.embed_provider,
            "vector_backend": store.backend,
            "llm_provider": settings.llm_provider,
            "llm_model": settings.llm_model,
            "rerank_model": settings.effective_rerank_model,
            "rerank_mode": settings.effective_rerank_mode,
            "rerank_timeout_ms": settings.rerank_timeout_ms,
            "rerank_max_tokens": settings.rerank_max_tokens,
            "rerank_low_conf_gap": settings.rerank_low_conf_gap,
            "rerank_cache_ttl_s": settings.rerank_cache_ttl_s,
            "rrf_k": settings.rrf_k,
            "score_threshold": settings.effective_score_threshold,
            "answer_threshold": settings.answer_threshold,
            "answer_min_gap": settings.answer_min_gap,
            "answer_flat_margin": settings.answer_flat_margin,
            "answer_coverage_min": settings.answer_coverage_min,
            "answer_gap_rule": settings.answer_gap_rule,
            "answer_coverage_rule": settings.answer_coverage_rule,
            "recall_status": list(settings.recall_status),
            "secrets_masked": {
                "llm_api_key": mask_secret(settings.llm_api_key),
                "embed_api_key": mask_secret(settings.embed_api_key),
            },
        },
        "index": {
            "collection": settings.chroma_collection,
            "chunk_count": store.count(),
            "backend": store.backend,
            "manifest": index_manifest,
            "chunk_stats": (index_manifest or {}).get("chunk_stats"),
            "expire_scan": (index_manifest or {}).get("expire_scan"),
            "sources": (index_manifest or {}).get("sources"),
            "duration_ms": (index_manifest or {}).get("duration_ms"),
            "embed": (index_manifest or {}).get("embed"),
        },
        "golden": {
            "path": str(golden_path),
            "total": len(golden),
            "knowledge_questions": sum(1 for q in golden if not q.get("negative")),
            "negative_questions": sum(1 for q in golden if q.get("negative")),
            "retrievable_positive": sum(1 for q in golden if not q.get("negative") and q.get("retrievable", True)),
            "expect_not_questions": sum(1 for q in golden if q.get("expect_not_ids")),
            "by_variant": _count_by(golden, "variant"),
            "by_project": _count_by(golden, "project"),
        },
        "primary": {
            "note": "强制项目作用域（project_id IN (项目…,'ALL')）+ 重排关；主指标口径",
            "metrics": primary_metrics,
            "per_question": primary_records,
        },
        "guard": guard_report,
        "rerank_runs": rerank_runs,
        "notes": notes,
    }
    if unscoped_records is not None:
        before = pass_metrics(unscoped_records, guard_config, with_breakdown=True)
        summary["project_scope"] = {
            "comparison": "before=UNSCOPED（W31 口径，不按项目过滤） vs after=project+ALL（W32 强制作用域）",
            "before": {
                "scope": "UNSCOPED",
                "metrics": before,
                "by_variant": before["by_variant"],
            },
            "after": {
                "scope": "project+ALL",
                "metrics": primary_metrics,
                "by_variant": primary_metrics["by_variant"],
            },
            "delta": {
                "project_distractor_hit_rate": round(
                    primary_metrics["expect_not"]["project_distractor"]["hit_rate"]
                    - before["expect_not"]["project_distractor"]["hit_rate"],
                    4,
                ),
                "expired_leak_rate": round(
                    primary_metrics["expect_not"]["expired"]["leak_rate"]
                    - before["expect_not"]["expired"]["leak_rate"],
                    4,
                ),
                "hit_at_5": round(primary_metrics["hit_at_5"] - before["hit_at_5"], 4),
                "mrr": round(primary_metrics["mrr"] - before["mrr"], 4),
                "negative_refusal_rate": round(
                    primary_metrics["negative_refusal_rate"] - before["negative_refusal_rate"], 4
                ),
            },
            "note": "过滤前/前对照用于量化 W31「跨项目干扰 93%」；before 与 after 同一批题、同一 guard 口径",
        }
        summary["unscoped_per_question"] = unscoped_records
    summary["secondary"] = {
        "non_retrievable_positive_questions": sum(
            1 for question in golden if not question.get("negative") and not question.get("retrievable", True)
        ),
        "note": "非 published / 过期条目不进入召回（生产口径），不计入 Hit@5 主指标",
    }
    return summary


def _count_by(rows: Sequence[dict[str, Any]], key: str) -> dict[str, int]:
    counts: dict[str, int] = {}
    for row in rows:
        value = str(row.get(key) or "unknown")
        counts[value] = counts.get(value, 0) + 1
    return dict(sorted(counts.items()))


def build_guard_report(
    records: Sequence[dict[str, Any]],
    settings: Settings,
    *,
    thresholds: Sequence[float] | None = None,
    print_fn: Callable[[str], None] = print,
) -> dict[str, Any]:
    """阈值扫描 → 选最优 → 生成最终 guard 报告（含最终口径指标与拒答分布）。

    两段扫描（都在**已检索好的画像**上算，不重复检索）：
        1. `threshold_scan`：**设计要求的一维绝对阈值曲线**（0.15/0.2/0.25/0.3/0.35/0.4/0.5
           + 扩展点；关掉双低/覆盖率规则 → 得到干净曲线，报告表格直接引用）；
        2. `feature_grid`：阈值 + 双低分差 + hard-negative 覆盖率 的联合网格，在
           「误拒率 ≤0.05」约束下取「负样本拒答率最高」的组合作为**最终配置**
           （写回 `RAG_ANSWER_*` 默认值的数据依据）。
    """
    grid = list(thresholds or (list(DEFAULT_SCAN_GRID) + list(EXTENDED_SCAN_GRID)))
    profiles = [
        {
            "id": record["id"],
            "negative": record["negative"],
            "retrievable": record.get("retrievable", True),
            "candidates": record["retrieval"]["candidates"],
            "top1": record["retrieval"]["top1"],
            "top2": record["retrieval"]["top2"],
            "gap": record["retrieval"]["gap"],
            "coverage": record["retrieval"].get("coverage") or 0.0,
        }
        for record in records
    ]
    # 1) 设计要求的一维绝对阈值曲线（纯净口径）
    curve = scan_thresholds(profiles, grid, gap_rule=False, coverage_rule=False)
    chosen = choose_threshold(curve)

    # 2) 联合特征网格（阈值 × 双低 × hard-negative 覆盖率）
    grid_rows: list[dict[str, Any]] = []
    for threshold in (0.40, 0.42, 0.44, 0.46, 0.48, 0.50, 0.52):
        for coverage_min in (0.0, 0.08, 0.10, 0.12, 0.15, 0.20):
            for flat_margin in (0.0, 0.04, 0.06, 0.10):
                for min_gap in (0.0, 0.02, 0.03, 0.05):
                    config = _guard_config(
                        settings,
                        threshold=threshold,
                        min_gap=min_gap,
                        flat_margin=flat_margin,
                        coverage_min=coverage_min,
                        gap_rule=min_gap > 0,
                        coverage_rule=coverage_min > 0,
                    )
                    metrics = pass_metrics(records, config)
                    grid_rows.append(
                        {
                            "threshold": round(threshold, 4),
                            "coverage_min": round(coverage_min, 4),
                            "flat_margin": round(flat_margin, 4),
                            "min_gap": round(min_gap, 4),
                            "negative_refusal_rate": metrics["negative_refusal_rate"],
                            "false_refusal_rate": metrics["false_refusal_rate"],
                            "negative_refused": metrics["negative_refused"],
                            "positive_refused": metrics["positive_refused"],
                        }
                    )
    feasible = [row for row in grid_rows if row["false_refusal_rate"] <= 0.05]
    pool = feasible or grid_rows
    if feasible:
        best = max(
            pool,
            key=lambda row: (
                row["negative_refusal_rate"],
                -row["false_refusal_rate"],
                -row["coverage_min"],
                -row["flat_margin"],
            ),
        )
    else:  # 兜底：误拒最小
        best = min(pool, key=lambda row: (row["false_refusal_rate"], row["threshold"]))
    final_config = _guard_config(
        settings,
        threshold=float(best["threshold"]),
        min_gap=float(best["min_gap"]),
        flat_margin=float(best["flat_margin"]),
        coverage_min=float(best["coverage_min"]),
        gap_rule=float(best["min_gap"]) > 0,
        coverage_rule=float(best["coverage_min"]) > 0,
    )
    final_config["source"] = "feature_grid（阈值+双低+hard-negative 覆盖率，误拒≤0.05 约束下负样本拒答最高）"
    final_metrics = pass_metrics(records, final_config)
    feasible_sorted = sorted(feasible, key=lambda row: (-row["negative_refusal_rate"], row["false_refusal_rate"]))[:15]

    print_fn(
        f"[poc][guard] 一维阈值扫描 {len(curve)} 点 → 纯阈值最优 {chosen['threshold']:.2f}"
        f"（负样本拒答 {chosen['selected']['negative_refusal_rate']:.2%}，"
        f"误拒 {chosen['selected']['false_refusal_rate']:.2%}，feasible={chosen['feasible']}）"
    )
    print_fn(
        f"[poc][guard] 联合网格 {len(grid_rows)} 组（可行域 {len(feasible)} 组）→ 最终配置 "
        f"阈值={final_config['answer_threshold']}，覆盖率≥{final_config['answer_coverage_min']}，"
        f"双低 gap<{final_config['answer_min_gap']} 且 top1<{final_config['answer_threshold'] + final_config['answer_flat_margin']:.2f}"
    )
    print_fn(
        f"[poc][guard] 最终：负样本拒答 {final_metrics['negative_refusal_rate']:.2%}"
        f"（{final_metrics['negative_refused']}/{final_metrics['negative_count']}），"
        f"误拒 {final_metrics['false_refusal_rate']:.2%}（{final_metrics['positive_refused']} 题）"
    )
    return {
        "final_config": final_config,
        "settings_defaults": _guard_config(settings),
        "threshold_scan": curve,
        "chosen": chosen,
        "feature_grid": {
            "grid_size": len(grid_rows),
            "feasible_size": len(feasible),
            "constraint": {"max_false_refusal_rate": 0.05},
            "best": dict(best),
            "top_feasible": feasible_sorted,
        },
        "final": {
            "threshold": final_config["answer_threshold"],
            "coverage_min": final_config["answer_coverage_min"],
            "negative_refusal_rate": final_metrics["negative_refusal_rate"],
            "false_refusal_rate": final_metrics["false_refusal_rate"],
            "negative_refused": final_metrics["negative_refused"],
            "positive_refused": final_metrics["positive_refused"],
            "refusal_reason_counts": refusal_reason_counts(records, final_config),
        },
        "grid": [round(float(value), 4) for value in grid],
    }


# ---------------------------------------------------------------- 主流程

def ensure_index(
    settings: Settings,
    rebuild: bool,
    print_fn: Callable[[str], None],
    source_json: str | None = None,
) -> dict[str, Any] | None:
    """确保向量库有数据；为空时自动 ingest 一次。返回最新 manifest（可能为 None）。"""
    store = VectorStore(settings)
    count = store.count()
    manifest_path = settings.data_dir / "ingest-manifest.json"
    if count > 0 and not rebuild:
        manifest = None
        try:
            if manifest_path.is_file():
                manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        except Exception:  # pragma: no cover
            manifest = None
        print_fn(f"[poc] 索引已存在：{count} 块（backend={store.backend}），跳过 ingest")
        return manifest

    print_fn(f"[poc] 索引为空/要求重建 → 执行 ingest（backend={store.backend}）")
    return ingest_module.run(
        settings,
        rebuild=rebuild,
        mock_embed=settings.is_mock_embed,
        source_json=source_json,
        print_fn=print_fn,
    )


def run_poc(
    settings: Settings,
    *,
    limit: int | None = None,
    rerank_mode: str | None = None,
    rerank_limit: int | None = None,
    rerank_alt: str | None = None,
    rerank_alt_limit: int | None = None,
    rerank_repeat: int = 1,
    project: str | None = None,
    unscoped: bool = False,
    skip_ablation: bool = False,
    thresholds: Sequence[float] | None = None,
    generate: bool = False,
    probe: bool = False,
    rebuild_index: bool = False,
    source_json: str | None = None,
    label: str = "",
    out_path: Path | None = None,
    print_fn: Callable[[str], None] = print,
) -> dict[str, Any]:
    """跑一遍 W32 评测并写 JSON（返回 summary）。"""
    mode = (rerank_mode or settings.effective_rerank_mode).strip().lower()
    if mode not in {"off", "conditional", "always", "shadow"}:
        raise ValueError(f"rerank 模式非法：{mode}（允许 off|conditional|always|shadow）")
    golden_path = Path(settings.golden_path)
    if not golden_path.is_file():
        raise FileNotFoundError(f"金标集不存在：{golden_path}（先跑 tests/rag/build_golden.py）")
    golden = load_golden(golden_path, limit=limit)
    print_fn(f"[poc] 金标 {len(golden)} 题（{golden_path}）；重排模式={mode}；项目覆盖={project or '按金标/默认'}")

    manifest = ensure_index(settings, rebuild_index, print_fn, source_json=source_json)
    store = VectorStore(settings)
    retriever = HybridRetriever(settings, store=store)

    notes: list[str] = []
    if settings.is_mock_embed:
        notes.append("Embedding=mock（离线哈希向量），相似度分布与真实向量不可比")
    notes.append(
        f"强制项目作用域：project_id IN (项目…, '{settings.global_project_id}')；"
        "金标未带 project 的题按平台级 ALL 检索（scope.resolve 会打 WARNING 日志）"
    )
    if mode != "off":
        notes.append(
            f"重排={mode}，模型={settings.effective_rerank_model}，timeout={settings.rerank_timeout_ms}ms，"
            f"max_tokens={settings.rerank_max_tokens}，触发 gap<{settings.rerank_low_conf_gap}"
        )
    if probe:
        print_fn("[poc] 探测中转模型可用性（403 证据）…")
        probe_rows = probe_models(settings, ["deepseek-v4-flash", "k3", "kimi-for-coding"], timeout_ms=30000)
        notes.append("模型探测见 llm_probe")
        for item in probe_rows:
            print_fn(f"[poc][probe] {item['model']}: ok={item['ok']} error={item.get('error') or '-'}")
    else:
        probe_rows = None

    # ---- 1) 主 pass：强制项目作用域 + 重排关 ----
    started = time.perf_counter()
    primary_records: list[dict[str, Any]] = []
    for position, question in enumerate(golden, start=1):
        primary_records.append(
            evaluate_question(question, retriever, settings, settings.top_k, project_override=project, unscoped=unscoped)
        )
        if position % 50 == 0 or position == len(golden):
            print_fn(f"[poc] 主 pass 检索进度 {position}/{len(golden)}")
    duration_ms = int((time.perf_counter() - started) * 1000)

    # ---- 2) 对照 pass：不按项目过滤（W31 口径）----
    unscoped_records: list[dict[str, Any]] | None = None
    if not skip_ablation and not unscoped:
        ablation_started = time.perf_counter()
        unscoped_records = []
        for position, question in enumerate(golden, start=1):
            unscoped_records.append(
                evaluate_question(
                    question, retriever, settings, settings.top_k, project_override=project, unscoped=True
                )
            )
            if position % 50 == 0 or position == len(golden):
                print_fn(f"[poc] 对照 pass（不过滤项目）进度 {position}/{len(golden)}")
        print_fn(f"[poc] 对照 pass 完成：{int((time.perf_counter() - ablation_started) * 1000)}ms")

    # ---- 3) 阈值扫描 + guard ----
    guard_report = build_guard_report(primary_records, settings, thresholds=thresholds, print_fn=print_fn)

    # ---- 4) 重排 run（主 + 可选次 run）----
    rerank_runs: dict[str, Any] = {}
    guard_config = guard_report["final_config"]
    if mode != "off":
        rerank_runs[mode] = run_rerank(
            mode,
            mode,
            golden,
            primary_records,
            retriever,
            settings,
            settings.top_k,
            limit=rerank_limit if rerank_limit is not None else 40,
            repeat=rerank_repeat,
            project_override=project,
            guard_config=guard_config,
            print_fn=print_fn,
        )
    alt = (rerank_alt or "").strip().lower()
    if alt:
        if alt not in {"off", "conditional", "always", "shadow"}:
            raise ValueError(f"--rerank-alt 模式非法：{alt}")
        if alt != "off":
            alt_settings = settings.with_overrides(rerank_mode=alt)
            rerank_runs[f"alt_{alt}"] = run_rerank(
                f"alt_{alt}",
                alt,
                golden,
                primary_records,
                retriever,
                alt_settings,
                settings.top_k,
                limit=rerank_alt_limit if rerank_alt_limit is not None else 10,
                repeat=1,
                project_override=project,
                guard_config=guard_config,
                print_fn=print_fn,
            )

    summary = build_summary(
        settings=settings,
        store=store,
        golden=golden,
        golden_path=golden_path,
        primary_records=primary_records,
        unscoped_records=unscoped_records,
        guard_report=guard_report,
        rerank_runs=rerank_runs,
        index_manifest=manifest,
        notes=notes,
        label=label or f"rerank={mode}",
    )
    summary["llm_probe"] = probe_rows
    summary["duration_ms"] = duration_ms
    summary["duration_ms_total"] = int((time.perf_counter() - started) * 1000)
    summary["questions_per_second"] = round(len(golden) / (duration_ms / 1000), 3) if duration_ms else 0.0
    if generate:
        summary["generation"] = _run_generation_sample(settings, primary_records, print_fn)

    target = Path(out_path) if out_path else default_results_path(settings, golden_path)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print_fn(f"[poc] 结果 → {target}")
    _print_summary(summary, print_fn)
    return summary


def _run_generation_sample(
    settings: Settings,
    records: list[dict[str, Any]],
    print_fn: Callable[[str], None],
    sample: int = 3,
) -> dict[str, Any]:
    """可选：对前 N 题跑一次四约束生成（验证链路可跑/可降级，不计入 Hit@5）。"""
    from .generation import answer

    retriever = HybridRetriever(settings)
    results: list[dict[str, Any]] = []
    for record in records[:sample]:
        hybrid_result = retriever.search(
            record["question"], top_k=settings.top_k, project_id=record.get("scope", {}).get("project_id") or None
        )
        result = answer(record["question"], hybrid_result.chunks, settings)
        results.append(
            {
                "id": record["id"],
                "question": record["question"],
                "mode": result.mode,
                "answerable": result.answerable,
                "citation_valid": result.citation_valid,
                "degraded": result.degraded,
                "answer_chars": len(result.answer),
                "usage": result.usage,
            }
        )
        print_fn(f"[poc][gen] {record['id']} mode={result.mode} answerable={result.answerable} chars={len(result.answer)}")
    return {"sample": sample, "results": results}


def _print_summary(summary: dict[str, Any], print_fn: Callable[[str], None]) -> None:
    """控制台打印对照表（报告表格同源）。"""
    primary = summary["primary"]["metrics"]
    print_fn("")
    print_fn("=== W32 主指标（强制项目作用域 + 重排关） ===")
    print_fn(f"{'正样本数':<24}{primary['positive_count']:>10}")
    print_fn(f"{'Hit@5':<24}{primary['hit_at_5']:>10.4f}")
    print_fn(f"{'MRR':<24}{primary['mrr']:>10.4f}")
    print_fn(
        f"{'检索 P50/P95(ms)':<22}{primary['search_latency_ms']['p50']:>8.1f}/{primary['search_latency_ms']['p95']:<8.1f}"
    )
    print_fn(
        f"{'单问总 P50/P95(ms)':<21}{primary['total_latency_ms']['p50']:>8.1f}/{primary['total_latency_ms']['p95']:<8.1f}"
    )
    print_fn(f"{'负样本拒答率':<23}{primary['negative_refusal_rate']:>10.4f}（{primary['negative_refused']}/{primary['negative_count']}）")
    print_fn(f"{'误拒率(目标≤0.05)':<21}{primary['false_refusal_rate']:>10.4f}（{primary['positive_refused']} 题）")
    print_fn(f"{'拒答原因分布':<23}{json.dumps(primary['refusal_reason_counts'], ensure_ascii=False)}")
    print_fn(
        f"{'过期泄漏率(期望0)':<21}{primary['expect_not']['expired']['leak_rate']:>10.4f}"
        f"（受检 {primary['expect_not']['expired']['guarded']} 题）"
    )
    print_fn(
        f"{'跨项目干扰命中率':<22}{primary['expect_not']['project_distractor']['hit_rate']:>10.4f}"
        f"（受检 {primary['expect_not']['project_distractor']['guarded']} 题）"
    )
    scope_cmp = summary.get("project_scope")
    if scope_cmp:
        before = scope_cmp["before"]["metrics"]
        after = scope_cmp["after"]["metrics"]
        print_fn("")
        print_fn("=== 项目作用域对照（before=不过滤 / after=强制过滤） ===")
        print_fn(f"{'指标':<26}{'before':>12}{'after':>12}")
        print_fn(f"{'Hit@5':<26}{before['hit_at_5']:>12.4f}{after['hit_at_5']:>12.4f}")
        print_fn(f"{'MRR':<26}{before['mrr']:>12.4f}{after['mrr']:>12.4f}")
        print_fn(
            f"{'跨项目干扰命中率':<25}{before['expect_not']['project_distractor']['hit_rate']:>12.4f}"
            f"{after['expect_not']['project_distractor']['hit_rate']:>12.4f}"
        )
        print_fn(
            f"{'负样本拒答率':<26}{before['negative_refusal_rate']:>12.4f}{after['negative_refusal_rate']:>12.4f}"
        )
    guard = summary.get("guard") or {}
    if guard:
        print_fn("")
        print_fn("=== 拒答阈值扫描（纯阈值曲线；最终口径含双低规则） ===")
        print_fn(f"{'阈值':>8}{'负样本拒答':>14}{'误拒率':>12}")
        for row in guard.get("threshold_scan", []):
            print_fn(
                f"{row['threshold']:>8.2f}{row['negative_refusal_rate']:>14.4f}{row['false_refusal_rate']:>12.4f}"
            )
        final = guard.get("final") or {}
        print_fn(
            f"选中阈值 {final.get('threshold')}｜负样本拒答 {final.get('negative_refusal_rate')}"
            f"｜误拒 {final.get('false_refusal_rate')}"
        )
    for label, run in (summary.get("rerank_runs") or {}).items():
        stats = run.get("stats") or {}
        print_fn("")
        print_fn(f"=== 重排 run={label}（mode={run.get('mode')}，样本 {run['sample']['size']} 题） ===")
        print_fn(
            f"触发 {stats.get('triggered')}/{stats.get('requests')}（{stats.get('trigger_rate'):.2%}）"
            f"｜超时 {stats.get('timeouts')}（{stats.get('timeout_rate'):.2%}）"
            f"｜解析失败 {stats.get('parse_errors')}｜缓存命中 {stats.get('cache', {}).get('hit_rate')}"
        )
        metrics = run.get("metrics") or {}
        off_m = metrics.get("off_subset") or {}
        on_m = metrics.get("on_subset") or {}
        print_fn(
            f"子集 Hit@5 off={off_m.get('hit_at_5')} on={on_m.get('hit_at_5')}"
            f"｜MRR off={off_m.get('mrr')} on={on_m.get('mrr')}"
            f"｜重排延迟 P50/P95={run.get('rerank_latency_ms_all', {}).get('p50')}/"
            f"{run.get('rerank_latency_ms_all', {}).get('p95')}ms"
        )
    print_fn(f"{'耗时(主 pass)':<24}{summary.get('duration_ms', 0)}ms（{summary.get('golden', {}).get('total', '-')} 题）")
    print_fn("")


# ---------------------------------------------------------------- CLI

def build_overrides(args: argparse.Namespace) -> dict[str, Any]:
    """CLI → Settings overrides。"""
    overrides: dict[str, Any] = {}
    if args.knowledge_api:
        overrides["knowledge_api"] = args.knowledge_api
    if args.llm_model:
        overrides["llm_model"] = args.llm_model
    if args.rerank_model:
        overrides["rerank_model"] = args.rerank_model
    if args.rerank_timeout_ms:
        overrides["rerank_timeout_ms"] = args.rerank_timeout_ms
    if args.rerank_max_tokens:
        overrides["rerank_max_tokens"] = args.rerank_max_tokens
    if args.rerank_candidates:
        overrides["rerank_candidates"] = args.rerank_candidates
    if args.top_k:
        overrides["top_k"] = args.top_k
    if args.recall_per_route:
        overrides["recall_per_route"] = args.recall_per_route
    if args.threshold is not None:
        overrides["score_threshold"] = args.threshold
    if args.answer_threshold is not None:
        overrides["answer_threshold"] = args.answer_threshold
    if args.answer_min_gap is not None:
        overrides["answer_min_gap"] = args.answer_min_gap
    if args.answer_flat_margin is not None:
        overrides["answer_flat_margin"] = args.answer_flat_margin
    if args.answer_coverage_min is not None:
        overrides["answer_coverage_min"] = args.answer_coverage_min
    if args.answer_coverage_rule is not None:
        overrides["answer_coverage_rule"] = args.answer_coverage_rule
    if args.answer_gap_rule is not None:
        overrides["answer_gap_rule"] = args.answer_gap_rule
    if args.golden:
        overrides["golden_path"] = resolve_input_path(args.golden)
    if args.llm_provider:
        overrides["llm_provider"] = args.llm_provider
    if args.mock_embed:
        overrides["embed_provider"] = "mock"
    mode = args.rerank or args.rerank_mode
    if mode:
        overrides["rerank_mode"] = mode
    if args.rerank_low_conf_gap is not None:
        overrides["rerank_low_conf_gap"] = args.rerank_low_conf_gap
    if args.rerank_trigger_top1 is not None:
        overrides["rerank_trigger_top1"] = args.rerank_trigger_top1
    if args.rerank_trigger_gap is not None:
        overrides["rerank_trigger_gap"] = args.rerank_trigger_gap
    if args.project:
        overrides["default_project_id"] = args.project
    return overrides


def resolve_input_path(value: str) -> str:
    """把 `--golden` 等相对路径解析成绝对路径（**cwd → 仓库根 → service 目录** 依次尝试）。

    为什么需要：`python -m rag.poc` 必须在 `services/ai-orchestrator` 下执行，
    但文档里的 `--golden tests/rag/golden/...` 是仓库根相对路径；`config._abs` 只按
    service 目录解析，会把仓库根路径拼错。这里按三个候选依次探测，存在即用，
    都不存在则原样返回（由 run_poc 抛可读的 FileNotFoundError）。
    """
    path = Path(value)
    if path.is_absolute():
        return str(path)
    candidates = [Path.cwd() / path, REPO_DIR / path, SERVICE_DIR / path]
    for candidate in candidates:
        if candidate.is_file():
            return str(candidate)
    return str(path)


def default_results_path(settings: Settings, golden_path: Path) -> Path:
    """默认输出路径：v2 金标 → rag-poc-w32.json；否则 W30 的 rag-poc.json。"""
    if "v2" in golden_path.name:
        return Path(settings.results_w32_path)
    return Path(settings.results_path)


def build_parser() -> argparse.ArgumentParser:
    """CLI 参数（W30/W31 参数全兼容；W32 新增 --rerank / --project / 阈值扫描参数）。"""
    parser = argparse.ArgumentParser(description="RAG PoC 金标评测（W32：项目作用域 + 拒答 + 条件重排）")
    parser.add_argument("--knowledge-api", default=None, help="Java 服务地址（ingest 用）")
    parser.add_argument("--env-file", default=None, help="参考工程 .env（只读；密钥不入库）")
    parser.add_argument("--limit", type=int, default=None, help="只评测前 N 题（小样本）")
    parser.add_argument("--golden", default=None, help="金标 JSONL 路径（v2 默认输出 rag-poc-w32.json）")
    parser.add_argument("--out", default=None, help="输出 JSON 路径")
    parser.add_argument("--label", default="", help="本次 run 标签（写入 JSON）")
    parser.add_argument("--mock-embed", action="store_true", help="mock embedding（离线）")
    parser.add_argument("--llm-provider", default=None, help="relay | mock | retrieval_only")
    parser.add_argument("--llm-model", default=None, help="LLM 模型（PoC 默认 k3）")
    parser.add_argument("--no-rerank", action="store_true", help="关闭重排（等价 --rerank=off，W30 兼容）")
    parser.add_argument(
        "--rerank",
        default=None,
        choices=["off", "conditional", "always", "shadow"],
        help="重排模式（W32 正式参数，缺省 off）",
    )
    parser.add_argument(
        "--rerank-mode",
        default=None,
        choices=["off", "always", "conditional", "shadow"],
        help="重排模式（W31 兼容别名，与 --rerank 等价）",
    )
    parser.add_argument("--rerank-limit", type=int, default=None, help="重排抽样子集大小（缺省 40）")
    parser.add_argument("--rerank-alt", default=None, choices=["off", "conditional", "always", "shadow"], help="次 run 重排模式（如 always）")
    parser.add_argument("--rerank-alt-limit", type=int, default=None, help="次 run 抽样大小（缺省 10）")
    parser.add_argument("--rerank-repeat", type=int, default=1, help="同一子集重复跑几遍（2 = 验证缓存命中）")
    parser.add_argument("--rerank-low-conf-gap", type=float, default=None, help="条件触发：top1-top2 小于该值（缺省 0.05）")
    parser.add_argument("--rerank-trigger-top1", type=float, default=None, help="条件触发附加：top1 低于该值（缺省 0=关闭）")
    parser.add_argument("--rerank-trigger-gap", type=float, default=None, help="(W31 兼容) 等价 --rerank-low-conf-gap")
    parser.add_argument("--rerank-model", default=None, help="重排模型（缺省随 LLM 模型）")
    parser.add_argument("--rerank-timeout-ms", type=int, default=None, help="重排超时（缺省 1500）")
    parser.add_argument("--rerank-max-tokens", type=int, default=None, help="重排输出上限（推理型需 1024）")
    parser.add_argument("--rerank-candidates", type=int, default=None, help="重排候选数（缺省 15）")
    parser.add_argument("--top-k", type=int, default=None, help="最终 top K（缺省 5）")
    parser.add_argument("--recall-per-route", type=int, default=None, help="每路召回数（缺省 15）")
    parser.add_argument("--threshold", type=float, default=None, help="召回层向量相似度阈值（缺省 0.3）")
    parser.add_argument("--answer-threshold", type=float, default=None, help="answerable 绝对阈值（缺省取配置/RAG_ANSWER_THRESHOLD）")
    parser.add_argument("--answer-min-gap", type=float, default=None, help="answerable 双低分差下限")
    parser.add_argument("--answer-flat-margin", type=float, default=None, help="answerable 双低绝对分窗口")
    parser.add_argument("--answer-coverage-min", type=float, default=None, help="hard-negative 覆盖率下限（缺省 0.10；0=关闭）")
    parser.add_argument("--answer-coverage-rule", type=_parse_bool, default=None, help="是否启用 hard-negative 覆盖率规则（true/false）")
    parser.add_argument("--answer-gap-rule", type=_parse_bool, default=None, help="是否启用双低规则（true/false）")
    parser.add_argument("--thresholds", default=None, help="阈值扫描网格（逗号分隔，如 0.15,0.3,0.5；缺省内置网格）")
    parser.add_argument("--project", default=None, help="强制项目作用域（缺省按金标 project / 平台级 ALL）")
    parser.add_argument("--no-project-filter", action="store_true", help="主 pass 也不按项目过滤（排障/复现 W31）")
    parser.add_argument("--skip-ablation", action="store_true", help="跳过「不过滤项目」对照 pass（省时）")
    parser.add_argument("--probe", action="store_true", help="探测中转模型可用性（403 证据）")
    parser.add_argument("--generate", action="store_true", help="对前 3 题跑一次生成（验证链路）")
    parser.add_argument("--rebuild-index", action="store_true", help="评测前强制重建索引")
    parser.add_argument("--source-json", default=None, help="从本地知识 JSON 建索引（离线/测试）")
    return parser


def _parse_bool(value: str) -> bool:
    """CLI 布尔解析（true/false/1/0/yes/no）。"""
    return str(value).strip().lower() in {"1", "true", "yes", "on", "y"}


def _parse_thresholds(value: str | None) -> list[float] | None:
    """逗号分隔阈值 → 浮点列表（非法值忽略）。"""
    if not value:
        return None
    out: list[float] = []
    for part in value.split(","):
        part = part.strip()
        if not part:
            continue
        try:
            out.append(float(part))
        except ValueError:
            continue
    return out or None


def main(argv: list[str] | None = None) -> int:
    """CLI 入口。"""
    args = build_parser().parse_args(argv)
    settings = load_settings(env_file=args.env_file, overrides=build_overrides(args))
    mode = "off" if args.no_rerank else (args.rerank or args.rerank_mode)
    if args.no_rerank:
        settings = settings.with_overrides(rerank_mode="off")
    try:
        run_poc(
            settings,
            limit=args.limit,
            rerank_mode=mode,
            rerank_limit=args.rerank_limit,
            rerank_alt=args.rerank_alt,
            rerank_alt_limit=args.rerank_alt_limit,
            rerank_repeat=args.rerank_repeat,
            project=args.project,
            unscoped=args.no_project_filter,
            skip_ablation=args.skip_ablation,
            thresholds=_parse_thresholds(args.thresholds),
            generate=args.generate,
            probe=args.probe,
            rebuild_index=args.rebuild_index,
            source_json=args.source_json,
            label=args.label,
            out_path=Path(args.out) if args.out else None,
        )
    except FileNotFoundError as exc:
        print(f"[poc][error] {exc}", file=sys.stderr)
        return 2
    except ingest_module.DataFetchError as exc:
        print(f"[poc][error] {exc}", file=sys.stderr)
        return 3
    return 0


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
