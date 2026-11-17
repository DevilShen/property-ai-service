"""检索层拒答守卫（W32）：answerable / confidence / refusal_reason
==================================================================

W31 实测问题（`tests/rag/results/rag-poc-w31.json`）：
    - 负样本（26 条：hard_negative 12 / expired_trap 8 / cross_project 6）**拒答率 0%**：
      召回层 `score_threshold=0.3` 只挡「弱命中」，而负样本 top1 实测 **0.31—0.52**，
      全部越过召回阈值被当成「有证据」；
    - 正样本 top1 中位数 0.718、p5 0.488 → 单靠「top1 < T」无法同时做到
      「负样本拒答 ≥0.90」与「误拒 ≤0.05」，必须叠加**区分度**判据。

W32 口径（answerable 上移到检索层，生成层只消费结论）：
    1. 无候选 → `answerable=false` / `refusal_reason=no_evidence`；
    2. 最高向量相似度 `top1 < RAG_ANSWER_THRESHOLD` → `low_confidence`（绝对值低）；
    3. **双低**：`top1 < threshold + RAG_ANSWER_FLAT_MARGIN` 且
       `top1 - top2 < RAG_ANSWER_MIN_GAP` → `flat_confidence`
       （「像所有文档都差不多相关」= 典型 hard negative 特征）；
    4. 其它 → `answerable=true`，`confidence=top1`。

阈值选择：`RAG_ANSWER_THRESHOLD` 默认值来自 258 题金标的阈值扫描曲线
（`--thresholds` 网格 + 扩展网格，目标负样本拒答率 ≥0.90 且误拒率 ≤0.05），
见 `_dev/reports/RAG-W32-检索加固.md` 与 `tests/rag/results/rag-poc-w32.json` 的
`guard.threshold_scan`；**不拍脑袋取值**。

`top1/top2` 口径：按**知识级**（knowledge_id）去重后的最高/次高向量相似度，
避免同一篇知识的多个 chunk 占满 top2 让分差虚小。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Iterable, Sequence

from .config import Settings

REFUSAL_NO_EVIDENCE = "no_evidence"
REFUSAL_LOW_CONFIDENCE = "low_confidence"
REFUSAL_FLAT_CONFIDENCE = "flat_confidence"
REFUSAL_HARD_NEGATIVE = "hard_negative"

#: 阈值扫描网格（设计要求 0.15—0.5；W32 实测负样本 top1 上限 0.60 → 证据不足时扩展）
DEFAULT_SCAN_GRID: tuple[float, ...] = (0.15, 0.2, 0.25, 0.3, 0.35, 0.4, 0.5)
EXTENDED_SCAN_GRID: tuple[float, ...] = (0.52, 0.55, 0.58, 0.6, 0.65, 0.7)


def question_coverage(question: str, text: str) -> float:
    """hard-negative 特征：问题词在最高分文档正文里的覆盖率（0—1）。

    为什么需要：W32 实测 hard_negative / cross_project 负样本的 top1 相似度
    **0.31—0.52**（与低分正样本重叠），仅靠绝对阈值无法在误拒 ≤0.05 内把负样本拒答
    做到 0.90；但这些负样本问的是「知识库没写的事」，**问题里的实词在召回正文里几乎
    不出现**（实测覆盖率中位数 0.06 vs 正样本 0.48），是可解释的 hard-negative 特征。

    口径：问题与正文都走 `rag.bm25.tokenize`（中文 char-bigram + 数字/英文词），
    覆盖率 = |问题词 ∩ 正文词| / |问题词|；空问题返回 0。
    """
    from .bm25 import tokenize

    query_tokens = set(tokenize(question or ""))
    if not query_tokens:
        return 0.0
    doc_tokens = set(tokenize(text or ""))
    return len(query_tokens & doc_tokens) / len(query_tokens)


@dataclass
class GuardResult:
    """一次 answerable 判定结果（服务响应字段同源）。"""

    answerable: bool
    refusal_reason: str = ""
    confidence: float = 0.0
    threshold: float = 0.0
    top1: float = 0.0
    top2: float = 0.0
    gap: float = 0.0
    candidates: int = 0
    coverage: float = 0.0  # hard-negative 特征：问题词在 top1 正文的覆盖率
    rule: str = "ok"  # ok | no_evidence | low_confidence | hard_negative | flat_confidence
    detail: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        return {
            "answerable": self.answerable,
            "refusal_reason": self.refusal_reason,
            "confidence": round(float(self.confidence), 4),
            "threshold": round(float(self.threshold), 4),
            "top1": round(float(self.top1), 4),
            "top2": round(float(self.top2), 4),
            "gap": round(float(self.gap), 4),
            "candidates": int(self.candidates),
            "coverage": round(float(self.coverage), 4),
            "rule": self.rule,
            "detail": dict(self.detail),
        }


def score_profile(chunks: Iterable[Any], question: str | None = None) -> dict[str, Any]:
    """候选片段 → 证据画像（知识级 top1/top2/gap + hard-negative 覆盖率）。

    输入：
        chunks   : 检索结果（RetrievedChunk 序列，任意顺序）
        question : 用户问题（给了才计算覆盖率；服务/评测都会传）
    输出：`{candidates, top1, top2, gap, coverage, scores}`；无命中时数值全 0。
    """
    rows: list[tuple[float, str]] = []
    seen: set[str] = set()
    ordered: list[tuple[float, str]] = []
    for chunk in chunks:
        key = str(getattr(chunk, "knowledge_id", "") or getattr(chunk, "chunk_id", ""))
        score = float(getattr(chunk, "score", 0.0) or 0.0)
        ordered.append((score, key))
    ordered.sort(key=lambda item: (-item[0], item[1]))
    for score, key in ordered:
        if key in seen:
            continue
        seen.add(key)
        rows.append((score, key))
    top1 = rows[0][0] if rows else 0.0
    top2 = rows[1][0] if len(rows) > 1 else 0.0
    coverage = 0.0
    if question and ordered:
        # top1 片段正文（ordered 已按分数降序，取第一条有正文的）
        for chunk in chunks:
            if float(getattr(chunk, "score", 0.0) or 0.0) == top1:
                coverage = question_coverage(question, str(getattr(chunk, "text", "") or ""))
                break
    return {
        "candidates": len(ordered),
        "top1": top1,
        "top2": top2,
        "gap": max(0.0, top1 - top2),
        "coverage": coverage,
        "scores": [round(score, 6) for score, _key in ordered],
    }


def decide(
    profile: dict[str, Any],
    threshold: float,
    *,
    min_gap: float = 0.0,
    flat_margin: float = 0.0,
    gap_rule: bool = True,
    coverage_min: float = 0.0,
    coverage_rule: bool = False,
) -> GuardResult:
    """按画像 + 阈值做 answerable 判定（纯函数，供扫描复用）。

    规则顺序：无证据 → 绝对阈值 → hard-negative 覆盖率 → 双低。
    """
    candidates = int(profile.get("candidates") or 0)
    top1 = float(profile.get("top1") or 0.0)
    top2 = float(profile.get("top2") or 0.0)
    gap = max(0.0, top1 - top2)
    coverage = float(profile.get("coverage") or 0.0)
    base = dict(
        confidence=top1,
        threshold=float(threshold),
        top1=top1,
        top2=top2,
        gap=gap,
        candidates=candidates,
        coverage=coverage,
    )
    if candidates <= 0:
        return GuardResult(answerable=False, refusal_reason=REFUSAL_NO_EVIDENCE, rule="no_evidence", **base)
    if top1 < threshold:
        return GuardResult(answerable=False, refusal_reason=REFUSAL_LOW_CONFIDENCE, rule="low_confidence", **base)
    if coverage_rule and coverage_min > 0 and coverage < coverage_min:
        return GuardResult(
            answerable=False,
            refusal_reason=REFUSAL_HARD_NEGATIVE,
            rule="hard_negative",
            detail={"coverage_min": coverage_min},
            **base,
        )
    if gap_rule and top1 < threshold + max(0.0, flat_margin) and gap < max(0.0, min_gap):
        return GuardResult(
            answerable=False,
            refusal_reason=REFUSAL_FLAT_CONFIDENCE,
            rule="flat_confidence",
            detail={"min_gap": min_gap, "flat_margin": flat_margin},
            **base,
        )
    return GuardResult(answerable=True, refusal_reason="", rule="ok", **base)


class AnswerGuard:
    """answerable 守卫（检索层统一入口）。

    输入：
        settings : 配置（answer_threshold / answer_min_gap / answer_flat_margin）
        threshold: 覆盖阈值（评测扫描用；None → settings.answer_threshold）
        min_gap  : 覆盖「双低」分差下限（None → settings.answer_min_gap）
        flat_margin: 覆盖「双低」绝对分窗口（None → settings.answer_flat_margin）
        gap_rule : 是否启用「双低」规则（阈值扫描时关掉 → 得到干净的绝对阈值曲线）
    """

    def __init__(
        self,
        settings: Settings,
        *,
        threshold: float | None = None,
        min_gap: float | None = None,
        flat_margin: float | None = None,
        gap_rule: bool | None = None,
        coverage_min: float | None = None,
        coverage_rule: bool | None = None,
    ) -> None:
        self.settings = settings
        self.threshold = float(settings.answer_threshold if threshold is None else threshold)
        self.min_gap = float(settings.answer_min_gap if min_gap is None else min_gap)
        self.flat_margin = float(settings.answer_flat_margin if flat_margin is None else flat_margin)
        self.gap_rule = bool(settings.answer_gap_rule if gap_rule is None else gap_rule)
        self.coverage_min = float(settings.answer_coverage_min if coverage_min is None else coverage_min)
        self.coverage_rule = bool(settings.answer_coverage_rule if coverage_rule is None else coverage_rule)

    def evaluate(self, chunks: Sequence[Any], question: str | None = None) -> GuardResult:
        """对最终候选集做 answerable 判定（传 question 才启用 hard-negative 覆盖率规则）。"""
        return decide(
            score_profile(chunks, question=question),
            self.threshold,
            min_gap=self.min_gap,
            flat_margin=self.flat_margin,
            gap_rule=self.gap_rule,
            coverage_min=self.coverage_min,
            coverage_rule=self.coverage_rule and bool(question),
        )

    def with_threshold(self, threshold: float, *, gap_rule: bool | None = None) -> "AnswerGuard":
        """派生同参数、不同阈值的守卫（扫描用）。"""
        return AnswerGuard(
            self.settings,
            threshold=threshold,
            min_gap=self.min_gap,
            flat_margin=self.flat_margin,
            gap_rule=self.gap_rule if gap_rule is None else gap_rule,
            coverage_min=self.coverage_min,
            coverage_rule=self.coverage_rule,
        )


def scan_thresholds(
    profiles: Sequence[dict[str, Any]],
    thresholds: Sequence[float],
    *,
    min_gap: float = 0.0,
    flat_margin: float = 0.0,
    gap_rule: bool = False,
    coverage_min: float = 0.0,
    coverage_rule: bool = False,
) -> list[dict[str, Any]]:
    """在「已检索好的画像」上扫描阈值（不重复检索，结果与重跑检索一致）。

    输入：
        profiles   : 每题 `score_profile` + `negative` / `retrievable` 字段
        thresholds : 阈值网格
        min_gap / flat_margin / gap_rule / coverage_min / coverage_rule : 见 `decide`
    输出：每点一条
        `{threshold, negative_refusal_rate, false_refusal_rate, negative_refused,
          positive_refused, positives, negatives, answered_positives}`
    """
    rows: list[dict[str, Any]] = []
    for threshold in thresholds:
        negatives = [p for p in profiles if p.get("negative")]
        positives = [p for p in profiles if not p.get("negative") and p.get("retrievable", True)]
        kwargs = dict(
            min_gap=min_gap,
            flat_margin=flat_margin,
            gap_rule=gap_rule,
            coverage_min=coverage_min,
            coverage_rule=coverage_rule,
        )
        neg_refused = sum(1 for p in negatives if not decide(p, threshold, **kwargs).answerable)
        pos_refused = sum(1 for p in positives if not decide(p, threshold, **kwargs).answerable)
        rows.append(
            {
                "threshold": round(float(threshold), 4),
                "negatives": len(negatives),
                "positive_count": len(positives),
                "negative_refused": neg_refused,
                "negative_refusal_rate": round(neg_refused / len(negatives), 4) if negatives else 0.0,
                "positive_refused": pos_refused,
                "false_refusal_rate": round(pos_refused / len(positives), 4) if positives else 0.0,
                "answered_positives": len(positives) - pos_refused,
                "gap_rule": bool(gap_rule),
                "coverage_rule": bool(coverage_rule),
            }
        )
    return rows


def choose_threshold(
    curve: Sequence[dict[str, Any]],
    *,
    max_false_refusal: float = 0.05,
    min_negative_refusal: float = 0.90,
) -> dict[str, Any]:
    """从扫描曲线选最优点。

    规则（按优先级）：
        1. 可行域 = 误拒率 ≤ max_false_refusal；在可行域内取负样本拒答率最高者；
        2. 达标（负样本拒答率 ≥ min_negative_refusal）→ `feasible=true`；
        3. 不可行（没有任何点满足误拒约束）→ 取「误拒率最小」的点并标 `feasible=false`，
           报告如实记录（不允许为了达标牺牲正样本）。

    输入：`scan_thresholds` 输出；`max_false_refusal`；`min_negative_refusal`
    输出：`{threshold, feasible, ...所选点的曲线, targets:{...}}`
    """
    if not curve:
        raise ValueError("阈值曲线为空，无法选择阈值")
    feasible_rows = [row for row in curve if row["false_refusal_rate"] <= max_false_refusal]
    pool = feasible_rows or list(curve)
    if feasible_rows:
        best = max(pool, key=lambda row: (row["negative_refusal_rate"], -row["false_refusal_rate"], row["threshold"]))
    else:  # 兜底：误拒率最小（并列取阈值低者，尽量少误拒）
        best = min(pool, key=lambda row: (row["false_refusal_rate"], row["threshold"]))
    return {
        "threshold": best["threshold"],
        "feasible": bool(best["negative_refusal_rate"] >= min_negative_refusal and best["false_refusal_rate"] <= max_false_refusal),
        "selected": dict(best),
        "targets": {
            "max_false_refusal_rate": max_false_refusal,
            "min_negative_refusal_rate": min_negative_refusal,
        },
        "feasible_points": len(feasible_rows),
        "grid_size": len(curve),
    }


__all__ = [
    "DEFAULT_SCAN_GRID",
    "EXTENDED_SCAN_GRID",
    "REFUSAL_FLAT_CONFIDENCE",
    "REFUSAL_HARD_NEGATIVE",
    "REFUSAL_LOW_CONFIDENCE",
    "REFUSAL_NO_EVIDENCE",
    "AnswerGuard",
    "GuardResult",
    "choose_threshold",
    "decide",
    "question_coverage",
    "scan_thresholds",
    "score_profile",
]
