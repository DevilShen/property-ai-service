"""selfcheck CLI：索引质量自检（W31）
=====================================

用法（在 `services/ai-orchestrator` 目录）：
    python -m rag.selfcheck
    python -m rag.selfcheck --golden tests/rag/golden/knowledge-golden-v2.jsonl --json
    python -m rag.selfcheck --out .data/rag/selfcheck.json

检查项（任一 error → 退出码 1；warning 只提示）：
    1. `manifest_present`        ：`.data/rag/ingest-manifest.json` 存在且与当前库对应；
    2. `count_consistent`        ：库内条数 == manifest `chunks_written`；
    3. `ids_match_manifest`      ：库内 chunk_id 集合与 manifest 一致（孤儿 / 缺失）；
    4. `no_orphan_chunks`        ：不存在「manifest 里没有」的孤儿 chunk；
    5. `no_duplicate_source_hash`：不同 chunk 的 `source_hash` 不重复（重复=切片/去重异常）；
    6. `no_empty_vector`         ：无空向量（norm=0，会污染余弦检索）；
    7. `dim_consistent`          ：向量维度 == `EMBED_DIM`（默认 1024）；
    8. `no_expired_recallable`   ：不存在「已过有效期但 status ∈ 召回状态且未归档」的残留；
    9. `metadata_required`       ：每条 chunk 具备 project_id/title/source_hash/chunk_seq 等必需元数据；
    10.`archived_not_recallable` ：archived=true 的 chunk 不得处于召回状态；
    11.`golden_gold_ids_present` ：金标 gold_ids 全部在索引中（若金标文件存在）。

输出：JSON（stdout + 可选 --out 文件），包含每项检查的 ok/count/detail 与总数；非零退出码。
"""

from __future__ import annotations

import argparse
import json
import sys
from datetime import date, datetime, timezone
from pathlib import Path
from typing import Any

from .config import Settings, load_settings
from .embedder import vector_norm
from .indexer import audit_append
from .store import BaseStore, create_store


def _now_iso() -> str:
    return datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")


def _check(name: str, ok: bool, detail: str, count: int = 0, severity: str = "error") -> dict[str, Any]:
    """构造一条检查结果（ok=False 且 severity=error → 退出码非零）。"""
    return {"name": name, "ok": bool(ok), "severity": severity if not ok else "ok", "count": int(count), "detail": detail}


def load_manifest(settings: Settings) -> dict[str, Any] | None:
    """读取 ingest manifest（不存在/损坏返回 None）。"""
    path = Path(settings.data_dir) / "ingest-manifest.json"
    try:
        if path.is_file():
            return json.loads(path.read_text(encoding="utf-8"))
    except Exception:  # pragma: no cover - 损坏按缺失处理
        return None
    return None


def load_golden_gold_ids(path: Path) -> list[str]:
    """读取金标 JSONL 的 gold_ids（去重、跳过注释/负样本）。"""
    gold_ids: list[str] = []
    if not Path(path).is_file():
        return gold_ids
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        try:
            row = json.loads(line)
        except json.JSONDecodeError:
            continue
        if row.get("negative"):
            continue
        for gold in row.get("gold_ids") or []:
            if gold not in gold_ids:
                gold_ids.append(str(gold))
    return gold_ids


def run_selfcheck(
    settings: Settings,
    *,
    store: BaseStore | None = None,
    manifest: dict[str, Any] | None = None,
    golden_path: Path | None = None,
    today: str | None = None,
) -> dict[str, Any]:
    """执行全部自检，返回结果 dict（不打印、不退出，便于单测调用）。"""
    store = store or create_store(settings)
    manifest = manifest if manifest is not None else load_manifest(settings)
    today = today or date.today().isoformat()
    records = store.iter_records()
    index_ids = {str(r.get("chunk_id") or "") for r in records}
    manifest_ids = [str(cid) for cid in (manifest or {}).get("chunk_ids") or []]
    manifest_id_set = set(manifest_ids)

    checks: list[dict[str, Any]] = []

    # 1) manifest
    checks.append(
        _check(
            "manifest_present",
            manifest is not None and bool(manifest_ids),
            f"manifest={'存在' if manifest else '缺失'}；chunk_ids={len(manifest_ids)}；"
            f"路径={Path(settings.data_dir) / 'ingest-manifest.json'}",
        )
    )

    # 2) 条数一致
    expected = int((manifest or {}).get("chunks_written") or len(manifest_ids) or 0)
    actual = store.count()
    checks.append(
        _check(
            "count_consistent",
            expected == actual,
            f"库内 {actual} 块 / manifest {expected} 块（backend={store.backend}）",
            count=abs(actual - expected),
        )
    )

    # 3) id 集合一致
    orphan_index = sorted(index_ids - manifest_id_set) if manifest_id_set else []
    missing_index = sorted(manifest_id_set - index_ids) if manifest_id_set else []
    checks.append(
        _check(
            "ids_match_manifest",
            not orphan_index and not missing_index,
            f"孤儿 {len(orphan_index)} / 缺失 {len(missing_index)}",
            count=len(orphan_index) + len(missing_index),
        )
    )

    # 4) 孤儿 chunk（manifest 无、索引有；manifest 缺失时降级为 warning）
    checks.append(
        _check(
            "no_orphan_chunks",
            not orphan_index,
            "无孤儿 chunk" if not orphan_index else f"孤儿 chunk 示例：{orphan_index[:5]}",
            count=len(orphan_index),
            severity="error" if manifest_id_set else "warning",
        )
    )

    # 5) 重复 source_hash
    hash_buckets: dict[str, list[str]] = {}
    for record in records:
        meta = record.get("metadata") or {}
        digest = str(meta.get("source_hash") or "")
        if not digest:
            continue
        hash_buckets.setdefault(digest, []).append(str(record.get("chunk_id") or ""))
    duplicates = {digest: ids for digest, ids in hash_buckets.items() if len(ids) > 1}
    checks.append(
        _check(
            "no_duplicate_source_hash",
            not duplicates,
            "无重复 sourceHash"
            if not duplicates
            else f"重复 sourceHash {len(duplicates)} 组，示例：{list(duplicates.values())[:3]}",
            count=len(duplicates),
        )
    )

    # 6) 空向量
    empty_vectors = [str(r.get("chunk_id") or "") for r in records if vector_norm(list(r.get("vector") or [])) == 0.0]
    checks.append(
        _check(
            "no_empty_vector",
            not empty_vectors,
            "无空向量（norm=0）" if not empty_vectors else f"空向量 {len(empty_vectors)} 条：{empty_vectors[:5]}",
            count=len(empty_vectors),
        )
    )

    # 7) 维度一致
    dims: dict[int, int] = {}
    for record in records:
        dim = len(list(record.get("vector") or []))
        dims[dim] = dims.get(dim, 0) + 1
    bad_dim = {dim: count for dim, count in dims.items() if dim != settings.embed_dim}
    checks.append(
        _check(
            "dim_consistent",
            not bad_dim,
            f"维度分布 {dict(sorted(dims.items()))} / 期望 {settings.embed_dim}"
            + (f"；异常 {bad_dim}" if bad_dim else ""),
            count=sum(bad_dim.values()),
        )
    )

    # 8) 过期残留（已过有效期但仍是召回状态、且未归档）
    recall_statuses = set(settings.recall_status)
    expired_residual = []
    for record in records:
        meta = record.get("metadata") or {}
        effective_to = str(meta.get("effective_to") or "").strip()
        if not effective_to or effective_to >= today:
            continue
        if str(meta.get("status") or "") in recall_statuses and not bool(meta.get("archived")):
            expired_residual.append(str(record.get("chunk_id") or ""))
    checks.append(
        _check(
            "no_expired_recallable",
            not expired_residual,
            "无过期残留（过期条目均已归档）"
            if not expired_residual
            else f"过期仍可召回 {len(expired_residual)} 条：{expired_residual[:5]}",
            count=len(expired_residual),
        )
    )

    # 9) 必需元数据
    missing_meta = []
    for record in records:
        meta = record.get("metadata") or {}
        for key in ("knowledge_id", "title", "source_hash", "project_id", "status"):
            if not str(meta.get(key) or "").strip():
                missing_meta.append({"chunk_id": record.get("chunk_id"), "missing": key})
                break
    checks.append(
        _check(
            "metadata_required",
            not missing_meta,
            "必需元数据齐全（knowledge_id/title/source_hash/project_id/status）"
            if not missing_meta
            else f"缺元数据 {len(missing_meta)} 条：{missing_meta[:3]}",
            count=len(missing_meta),
        )
    )

    # 10) archived 不得在召回状态
    archived_hit = [
        str(r.get("chunk_id") or "")
        for r in records
        if bool((r.get("metadata") or {}).get("archived"))
        and str((r.get("metadata") or {}).get("status") or "") in recall_statuses
    ]
    checks.append(
        _check(
            "archived_not_recallable",
            not archived_hit,
            "归档 chunk 均不在召回状态" if not archived_hit else f"归档但可召回 {len(archived_hit)} 条：{archived_hit[:5]}",
            count=len(archived_hit),
        )
    )

    # 11) 金标 gold_ids 是否都在索引
    golden_path = Path(golden_path or settings.golden_v2_path)
    gold_ids = load_golden_gold_ids(golden_path)
    indexed_knowledge = {str((r.get("metadata") or {}).get("knowledge_id") or "") for r in records}
    missing_gold = [gold for gold in gold_ids if gold not in indexed_knowledge]
    checks.append(
        _check(
            "golden_gold_ids_present",
            not missing_gold,
            f"金标 {golden_path.name}：gold 知识 {len(gold_ids)} 条，缺失 {len(missing_gold)}"
            + (f"：{missing_gold[:5]}" if missing_gold else "（全部可检索）"),
            count=len(missing_gold),
            severity="error" if gold_ids else "warning",
        )
    )

    errors = [check for check in checks if not check["ok"] and check["severity"] == "error"]
    warnings = [check for check in checks if not check["ok"] and check["severity"] == "warning"]
    result = {
        "generated_at": _now_iso(),
        "task": "task-77",
        "status": "PASS" if not errors else "FAIL",
        "store_backend": store.backend,
        "collection": settings.chroma_collection,
        "collection_count": actual,
        "chunks_checked": len(records),
        "embed_dim": settings.embed_dim,
        "recall_status": list(settings.recall_status),
        "manifest": {
            "present": manifest is not None,
            "chunks": len(manifest_ids),
            "chunks_written": (manifest or {}).get("chunks_written"),
            "generated_at": (manifest or {}).get("generated_at"),
            "sources": (manifest or {}).get("sources"),
        },
        "checks": checks,
        "summary": {"checks": len(checks), "errors": len(errors), "warnings": len(warnings)},
    }
    return result


def write_result(result: dict[str, Any], out_path: Path | None) -> None:
    """把自检结果写 JSON 文件（--out）。"""
    if out_path is None:
        return
    try:
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    except Exception as exc:  # pragma: no cover
        print(f"[selfcheck][warn] 结果写入失败：{exc}", file=sys.stderr)


def build_parser() -> argparse.ArgumentParser:
    """CLI 参数。"""
    parser = argparse.ArgumentParser(description="RAG 索引质量自检：条数/孤儿/过期残留/重复 hash/空向量/维度")
    parser.add_argument("--golden", default=None, help="金标 JSONL（默认 knowledge-golden-v2.jsonl）")
    parser.add_argument("--out", default=None, help="结果 JSON 输出路径（默认只打印）")
    parser.add_argument("--today", default=None, help="过期判定基准日（YYYY-MM-DD，测试/回放用）")
    parser.add_argument("--json", action="store_true", help="仅输出 JSON（不打印摘要行）")
    parser.add_argument("--env-file", default=None, help="参考工程 .env（只读，默认不需要）")
    return parser


def main(argv: list[str] | None = None) -> int:
    """CLI 入口；PASS → 0，FAIL → 1。"""
    args = build_parser().parse_args(argv)
    settings = load_settings(env_file=args.env_file)
    try:
        result = run_selfcheck(
            settings,
            golden_path=Path(args.golden) if args.golden else None,
            today=args.today,
        )
    except Exception as exc:  # noqa: BLE001 - 自检失败也要给出可读错误
        print(f"[selfcheck][error] {type(exc).__name__}: {exc}", file=sys.stderr)
        return 2
    out_path = Path(args.out) if args.out else (Path(settings.data_dir) / "selfcheck.json")
    write_result(result, out_path)
    audit_append(
        settings,
        "selfcheck",
        status=result["status"],
        errors=result["summary"]["errors"],
        warnings=result["summary"]["warnings"],
        collection_count=result["collection_count"],
    )
    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        print(
            f"[selfcheck] {result['status']}：检查 {result['summary']['checks']} 项，"
            f"error {result['summary']['errors']} / warning {result['summary']['warnings']}；"
            f"collection={result['collection_count']} 块（backend={result['store_backend']}）"
        )
        for check in result["checks"]:
            flag = "PASS" if check["ok"] else check["severity"].upper()
            print(f"  [{flag:6}] {check['name']}: {check['detail']}")
        print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["status"] == "PASS" else 1


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
