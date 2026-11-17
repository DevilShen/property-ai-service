"""LLM-as-rerank（RankGPT 式）
=============================

设计 §5：候选编号 `[1]…[n]`（每条截 200 字）→ system「你是信息检索排序助手」
+ user「问题 + 文档列表 + 只输出编号如 3,1,2」→ `temperature=0`、超时
`RERANK_TIMEOUT_MS`（1500）→ 正则解析（去重/越界/补尾）→ 失败/超时/mock → 原序透传
并标 `rerank=passthrough`。

实测约束（2026-11-16，写入报告）：
    - k3 / kimi-for-coding 只接受 temperature=1（传 0 → 400），无法真正用 temperature=0；
      LLM 客户端自动去掉 temperature 重试并标记 `temperature_fallback`；
    - 推理型模型 max_tokens=128/512 时 reasoning 吃光预算 → 正文空（finish_reason=length），
      此时按「解析失败」原序透传，绝不阻塞问答；
    - 单次重排 15 候选实测 P95 ≈ 6—9s，**远超 1.5s 预算** → 默认超时下 100% 透传，
      PoC 用 `--rerank-timeout-ms 20000` 才能测出重排真实增益（数据如实记录）。
"""

from __future__ import annotations

import hashlib
import re
import time
from dataclasses import dataclass, field, replace
from typing import Any

from .config import Settings
from .llm import LLMClient, LLMEmptyContentError, LLMError, LLMResult, LLMTimeoutError
from .store import RetrievedChunk

RERANK_SYSTEM_PROMPT = (
    "你是一个信息检索排序助手。给定一个问题和一个文档列表，"
    "请判断每篇文档与问题的相关程度，并按相关程度从高到低输出文档编号。"
)


def build_rerank_messages(
    question: str,
    chunks: list[RetrievedChunk],
    max_chars_per_doc: int = 200,
) -> list[dict[str, str]]:
    """组装重排 Prompt。

    输入：
        question          : 用户问题
        chunks            : 候选片段（按 1..n 编号）
        max_chars_per_doc : 每条截断字符数（控制请求长度/成本，设计 §5 取 200）
    输出：OpenAI messages
    """
    lines = [f"问题：{question}", "", "文档列表："]
    for index, chunk in enumerate(chunks, start=1):
        text = chunk.text.strip()
        if max_chars_per_doc > 0 and len(text) > max_chars_per_doc:
            text = text[:max_chars_per_doc] + "…"
        lines.append(f"[{index}] {text}")
    lines.append("")
    lines.append("请按相关程度从高到低输出文档编号，用逗号分隔（例如：3,1,2）。只输出编号，不要任何解释。")
    return [
        {"role": "system", "content": RERANK_SYSTEM_PROMPT},
        {"role": "user", "content": "\n".join(lines)},
    ]


_INT_RE = re.compile(r"\d+")


def parse_ranking(text: str, n: int) -> list[int] | None:
    """解析模型输出的编号序列 → 0-based 顺序。

    输入：
        text : 模型原始输出（如 "3,1,2" / "1 2 3" / "编号：3,1,2。"）
        n    : 候选总数
    输出：长度恰为 n 的 0-based 顺序；**无任何合法编号时返回 None**（调用方降级透传）。

    容错：
        - 抽取全部整数，按出现顺序；
        - 去重、过滤越界（<1 或 >n）；
        - 未出现的编号按原顺序补尾（视为相关性最低）。
    """
    if n <= 0:
        return None
    numbers = [int(x) for x in _INT_RE.findall(text or "")]
    seen: set[int] = set()
    order: list[int] = []
    for value in numbers:
        if 1 <= value <= n and value not in seen:
            seen.add(value)
            order.append(value - 1)
    if not order:
        return None
    order.extend(index for index in range(n) if (index + 1) not in seen)
    return order


@dataclass
class RerankOutcome:
    """一次重排的结果与降级标记。"""

    chunks: list[RetrievedChunk] = field(default_factory=list)
    reranked: bool = False
    status: str = "passthrough"  # ok | passthrough | disabled | too_few | timeout | error | parse_error | mock | no_key
    error: str = ""
    latency_ms: int = 0
    usage: dict[str, Any] = field(default_factory=dict)
    raw_output: str = ""
    model: str = ""
    temperature_fallback: bool = False
    candidates: int = 0

    def to_dict(self) -> dict[str, Any]:
        return {
            "reranked": self.reranked,
            "status": self.status,
            "error": self.error,
            "latency_ms": self.latency_ms,
            "usage": self.usage,
            "model": self.model,
            "temperature_fallback": self.temperature_fallback,
            "candidates": self.candidates,
            "raw_output": self.raw_output[:120],
        }


class Reranker:
    """LLM-as-rerank 重排器。

    输入：
        settings : 配置（enabled/候选数/topN/超时/模型/温度/max_tokens）
        llm      : LLM 客户端；None → 按配置构造（模型取 effective_rerank_model）
    """

    def __init__(self, settings: Settings, llm: LLMClient | None = None) -> None:
        self.settings = settings
        self.llm = llm or LLMClient(settings, model=settings.effective_rerank_model)
        self.model = settings.effective_rerank_model

    @property
    def is_enabled(self) -> bool:
        """是否真正执行重排（关闭 / mock / 无 Key → 不调用，原序透传）。"""
        return bool(self.settings.rerank_enabled) and self.llm.can_call

    def rerank(
        self,
        question: str,
        chunks: list[RetrievedChunk],
        top_n: int | None = None,
    ) -> RerankOutcome:
        """重排候选片段。

        输入：
            question : 用户问题
            chunks   : 候选片段（已按 RRF 降序），长度 ≤ RERANK_CANDIDATES
            top_n    : 保留条数（缺省 RERANK_TOP_N）
        输出：RerankOutcome（chunks 为重排后的前 top_n 条；失败时原序透传）
        """
        top_n = int(top_n or self.settings.rerank_top_n)
        started = time.perf_counter()
        candidates = len(chunks)

        def _passthrough(status: str, error: str = "", usage: dict | None = None, raw: str = "", model: str = "", temp_fb: bool = False) -> RerankOutcome:
            return RerankOutcome(
                chunks=chunks[:top_n],
                reranked=False,
                status=status,
                error=error,
                latency_ms=int((time.perf_counter() - started) * 1000),
                usage=usage or {},
                raw_output=raw,
                model=model or self.model,
                temperature_fallback=temp_fb,
                candidates=candidates,
            )

        if not chunks:
            return _passthrough("disabled")
        if not self.settings.rerank_enabled:
            return _passthrough("disabled")
        if self.llm.is_mock:
            return _passthrough("mock")
        if not self.llm.can_call:
            return _passthrough("no_key")
        if len(chunks) < 2:
            return _passthrough("too_few")

        messages = build_rerank_messages(question, chunks, self.settings.rerank_max_chars_per_doc)
        try:
            result: LLMResult = self.llm.complete(
                messages,
                temperature=self.settings.rerank_temperature,
                max_tokens=self.settings.rerank_max_tokens,
                model=self.model,
                timeout_ms=self.settings.rerank_timeout_ms,
                retries=0,
            )
        except LLMEmptyContentError as exc:
            return _passthrough("parse_error", error=str(exc))
        except LLMTimeoutError as exc:
            return _passthrough("timeout", error=str(exc))
        except LLMError as exc:
            return _passthrough("error", error=str(exc))
        except Exception as exc:  # noqa: BLE001 - 重排是增强，任何异常都不阻塞
            return _passthrough("error", error=f"{type(exc).__name__}: {exc}")

        order = parse_ranking(result.text, len(chunks))
        latency_ms = int((time.perf_counter() - started) * 1000)
        if order is None:
            return RerankOutcome(
                chunks=chunks[:top_n],
                reranked=False,
                status="parse_error",
                error=f"无合法编号，原序透传（输出：{result.text[:60]!r}）",
                latency_ms=latency_ms,
                usage=result.usage,
                raw_output=result.text,
                model=result.model,
                temperature_fallback=result.temperature_fallback,
                candidates=candidates,
            )

        ranked = [chunks[index] for index in order]
        # 保留向量相似度作为展示分；额外记录重排名次
        ranked = [replace(chunk, rerank_rank=rank) for rank, chunk in enumerate(ranked, start=1)]
        return RerankOutcome(
            chunks=ranked[:top_n],
            reranked=True,
            status="ok",
            latency_ms=latency_ms,
            usage=result.usage,
            raw_output=result.text,
            model=result.model,
            temperature_fallback=result.temperature_fallback,
            candidates=candidates,
        )


# --------------------------------------------------------------------------- W32 策略层
#
# W31 实测：条件重排 24 题采样触发 12 题 → 5 ok / **7 timeout（58%）**，k3 单次 6—26s，
# 1.5s 预算下几乎必然透传；且每次调用都付费。W32 把「要不要重排 / 结果能不能复用 /
# 是否只 shadow」收敛到一个控制器里，CLI/服务共用同一口径，统计可审计。


@dataclass
class CacheStats:
    """重排缓存统计（命中率 = hits / (hits + misses)）。"""

    hits: int = 0
    misses: int = 0
    puts: int = 0
    evictions: int = 0
    expired: int = 0

    @property
    def hit_rate(self) -> float:
        total = self.hits + self.misses
        return round(self.hits / total, 4) if total else 0.0

    def to_dict(self) -> dict[str, Any]:
        return {
            "hits": self.hits,
            "misses": self.misses,
            "puts": self.puts,
            "evictions": self.evictions,
            "expired": self.expired,
            "hit_rate": self.hit_rate,
        }


class RerankCache:
    """重排结果缓存（W32）。

    key = `(query_hash, project_id, ids_hash)`：
        - `query_hash`：问题原文（strip 后）sha256 前 16 位；
        - `project_id`：项目作用域（同问不同项目不得复用排序）；
        - `ids_hash`：候选 chunk_id 序列 sha256 前 16 位（候选集变了就不能复用名次）。
    value = 解析后的 0-based 名次序列；TTL 缺省 600s（`RAG_RERANK_CACHE_TTL`），
    条目上限 `RAG_RERANK_CACHE_SIZE`（超出按插入顺序淘汰最旧）。
    """

    def __init__(self, ttl_s: int = 600, max_entries: int = 512) -> None:
        self.ttl_s = max(0, int(ttl_s))
        self.max_entries = max(1, int(max_entries))
        self._data: dict[str, tuple[float, list[int]]] = {}
        self.stats = CacheStats()

    @staticmethod
    def make_key(question: str, project_id: str | list[str] | None, chunks: list[RetrievedChunk]) -> str:
        query_hash = hashlib.sha256((question or "").strip().encode("utf-8")).hexdigest()[:16]
        ids_hash = hashlib.sha256("\x1f".join(c.chunk_id for c in chunks).encode("utf-8")).hexdigest()[:16]
        project = project_id if isinstance(project_id, str) else ",".join(project_id or [])
        return f"{query_hash}|{(project or '').strip()}|{ids_hash}"

    def get(self, key: str, now: float | None = None) -> list[int] | None:
        entry = self._data.get(key)
        if entry is None:
            self.stats.misses += 1
            return None
        stored_at, order = entry
        current = time.monotonic() if now is None else now
        if current - stored_at > self.ttl_s:
            self._data.pop(key, None)
            self.stats.expired += 1
            self.stats.misses += 1
            return None
        self.stats.hits += 1
        return list(order)

    def put(self, key: str, order: list[int], now: float | None = None) -> None:
        if not order:
            return
        if key not in self._data and len(self._data) >= self.max_entries:
            self._data.pop(next(iter(self._data)), None)
            self.stats.evictions += 1
        self._data[key] = (time.monotonic() if now is None else now, list(order))
        self.stats.puts += 1

    def clear(self) -> None:
        self._data.clear()

    def __len__(self) -> int:  # pragma: no cover - 调试辅助
        return len(self._data)


@dataclass
class RerankDecision:
    """一次「是否重排 + 重排结果」的完整决策记录。"""

    chunks: list[RetrievedChunk] = field(default_factory=list)
    mode: str = "off"
    triggered: bool = False
    trigger_reason: str = ""
    cached: bool = False
    applied: bool = False  # 排序是否真的生效（shadow 恒 False）
    status: str = "off"  # off | skipped_high_conf | too_few | ok | cache_hit | timeout | error | parse_error | disabled | mock | no_key | shadow:*
    error: str = ""
    latency_ms: int = 0
    model: str = ""
    temperature_fallback: bool = False
    candidates: int = 0
    shadow_ids: list[str] = field(default_factory=list)
    outcome: RerankOutcome | None = None

    @property
    def degraded(self) -> bool:
        """重排链路是否降级（触发但没拿到可用排序）。"""
        return self.triggered and not self.applied and self.status not in {"skipped_high_conf"}

    def to_dict(self) -> dict[str, Any]:
        return {
            "mode": self.mode,
            "triggered": self.triggered,
            "trigger_reason": self.trigger_reason,
            "cached": self.cached,
            "applied": self.applied,
            "status": self.status,
            "error": self.error,
            "latency_ms": self.latency_ms,
            "model": self.model,
            "temperature_fallback": self.temperature_fallback,
            "candidates": self.candidates,
            "degraded": self.degraded,
            "shadow_ids": self.shadow_ids,
            "chunk_ids": [chunk.chunk_id for chunk in self.chunks],
        }


class RerankController:
    """重排策略控制器（W32）：off | conditional | always | shadow。

    规则（`--rerank=...`，缺省 off）：
        - `off`        ：不调用，原序返回；
        - `conditional`：**候选≥2 且 top1-top2 < RAG_RERANK_LOW_CONF_GAP(0.05)** 才调用；
                         （可选附加：`RAG_RERANK_TRIGGER_TOP1>0` 时 top1 低于该值也触发）
        - `always`     ：候选≥2 一律调用；
        - `shadow`     ：触发口径同 conditional，但**只记录 shadow 排序、不生效**（返回融合序），
                         用于离线评估重排价值而不改变线上答案。

    超时：`RERANK_TIMEOUT_MS`（缺省 1500）透传给 LLM 客户端；超时/解析失败/异常 → 原序透传
    并计入 `timeout_rate` / `parse_error_rate`。缓存见 `RerankCache`。
    """

    def __init__(
        self,
        settings: Settings,
        reranker: Reranker | None = None,
        cache: RerankCache | None = None,
    ) -> None:
        self.settings = settings
        self.mode = settings.effective_rerank_mode
        self.reranker = reranker if reranker is not None else (
            Reranker(settings) if self.mode != "off" else None
        )
        self.cache = cache or RerankCache(
            ttl_s=settings.rerank_cache_ttl_s, max_entries=settings.rerank_cache_size
        )
        self._status_counts: dict[str, int] = {}
        self._trigger_reasons: dict[str, int] = {}
        self._calls = 0
        self._triggered = 0
        self._skipped = 0
        self._applied = 0

    # ---- 触发判定 ----
    def should_trigger(self, chunks: list[RetrievedChunk]) -> tuple[bool, str]:
        """返回 (是否触发, 原因)。原因形如 `low_gap(0.012<0.05)` / `high_conf(...)` / `too_few`。"""
        if self.mode == "off":
            return False, "mode_off"
        if len(chunks) < 2:
            return False, "too_few"
        top1 = max(float(chunk.score or 0.0) for chunk in chunks)
        scores = sorted({float(chunk.score or 0.0) for chunk in chunks}, reverse=True)
        top2 = scores[1] if len(scores) > 1 else 0.0
        gap = max(0.0, top1 - top2)
        if self.mode == "always":
            return True, "always"
        low_gap = float(self.settings.rerank_low_conf_gap)
        if gap < low_gap:
            return True, f"low_gap({gap:.3f}<{low_gap})"
        extra_top1 = float(self.settings.rerank_trigger_top1 or 0.0)
        if extra_top1 > 0 and top1 < extra_top1:
            return True, f"low_top1({top1:.3f}<{extra_top1})"
        return False, f"high_conf(top1={top1:.3f},gap={gap:.3f})"

    # ---- 执行 ----
    def apply(
        self,
        question: str,
        chunks: list[RetrievedChunk],
        top_n: int | None = None,
        project_id: str | list[str] | None = None,
    ) -> RerankDecision:
        """按模式决定是否重排 → 返回决策（含最终 chunks，长度 = top_n）。"""
        top_n = int(top_n or self.settings.rerank_top_n)
        candidates = len(chunks)
        if self.mode == "off" or not chunks:
            decision = RerankDecision(
                chunks=chunks[:top_n], mode=self.mode, status="off" if chunks else "disabled",
                candidates=candidates,
            )
            self._count(decision)
            return decision

        triggered, reason = self.should_trigger(chunks)
        if not triggered:
            decision = RerankDecision(
                chunks=chunks[:top_n], mode=self.mode, triggered=False, trigger_reason=reason,
                status="skipped_high_conf" if reason.startswith("high_conf") else "too_few",
                candidates=candidates,
            )
            self._count(decision)
            return decision

        key = RerankCache.make_key(question, project_id, chunks)
        cached_order = self.cache.get(key)
        if cached_order is not None and len(cached_order) == candidates:
            ordered = [chunks[index] for index in cached_order]
            ranks = [replace(chunk, rerank_rank=rank) for rank, chunk in enumerate(ordered, start=1)]
            if self.mode == "shadow":
                chunks_out, applied, status = chunks[:top_n], False, "cache_hit"
                shadow_ids = [chunk.chunk_id for chunk in ranks[:top_n]]
            else:
                chunks_out, applied, status = ranks[:top_n], True, "cache_hit"
                shadow_ids = []
            decision = RerankDecision(
                chunks=chunks_out, mode=self.mode, triggered=True, trigger_reason=reason,
                cached=True, applied=applied, status=status,
                latency_ms=0, model=(self.reranker.model if self.reranker else ""),
                candidates=candidates, shadow_ids=shadow_ids,
            )
            self._count(decision)
            return decision

        assert self.reranker is not None, "mode!=off 时必须构造 Reranker"
        started = time.perf_counter()
        outcome = self.reranker.rerank(question, chunks, top_n=len(chunks))
        elapsed = int((time.perf_counter() - started) * 1000)
        self._calls += 1
        order = None
        if outcome.reranked:
            rank_by_id = {chunk.chunk_id: rank for rank, chunk in enumerate(outcome.chunks, start=1) if chunk.rerank_rank}
            order = sorted(range(candidates), key=lambda index: rank_by_id.get(chunks[index].chunk_id, candidates + index))
            self.cache.put(key, order)

        if self.mode == "shadow":
            decision = RerankDecision(
                chunks=chunks[:top_n], mode=self.mode, triggered=True, trigger_reason=reason,
                cached=False, applied=False, status=f"shadow:{outcome.status}",
                error=outcome.error, latency_ms=elapsed, model=outcome.model,
                temperature_fallback=outcome.temperature_fallback, candidates=candidates,
                shadow_ids=[chunk.chunk_id for chunk in outcome.chunks[:top_n]],
                outcome=outcome,
            )
            self._count(decision)
            return decision

        decision = RerankDecision(
            chunks=outcome.chunks[:top_n] if outcome.reranked else chunks[:top_n],
            mode=self.mode, triggered=True, trigger_reason=reason, cached=False,
            applied=outcome.reranked, status=outcome.status, error=outcome.error,
            latency_ms=elapsed, model=outcome.model,
            temperature_fallback=outcome.temperature_fallback, candidates=candidates,
            outcome=outcome,
        )
        if decision.applied:
            self._applied += 1
        self._count(decision)
        return decision

    # ---- 统计 ----
    def _count(self, decision: RerankDecision) -> None:
        self._status_counts[decision.status] = self._status_counts.get(decision.status, 0) + 1
        if decision.triggered:
            self._triggered += 1
            bucket = decision.trigger_reason.split("(")[0] or "unknown"
            self._trigger_reasons[bucket] = self._trigger_reasons.get(bucket, 0) + 1
        else:
            self._skipped += 1

    def stats(self) -> dict[str, Any]:
        """触发率 / 超时率 / 解析失败率 / 缓存命中率 / 状态分布（写评测报告用）。"""
        total = self._triggered + self._skipped
        statuses = dict(sorted(self._status_counts.items()))
        timeouts = statuses.get("timeout", 0)
        parse_errors = statuses.get("parse_error", 0)
        errors = statuses.get("error", 0)
        disabled = statuses.get("disabled", 0) + statuses.get("mock", 0) + statuses.get("no_key", 0)
        return {
            "mode": self.mode,
            "requests": total,
            "triggered": self._triggered,
            "skipped": self._skipped,
            "trigger_rate": round(self._triggered / total, 4) if total else 0.0,
            "applied": self._applied,
            "applied_rate": round(self._applied / total, 4) if total else 0.0,
            "llm_calls": self._calls,
            "timeouts": timeouts,
            "timeout_rate": round(timeouts / self._triggered, 4) if self._triggered else 0.0,
            "parse_errors": parse_errors,
            "parse_error_rate": round(parse_errors / self._triggered, 4) if self._triggered else 0.0,
            "errors": errors,
            "disabled": disabled,
            "status_counts": statuses,
            "trigger_reasons": dict(sorted(self._trigger_reasons.items())),
            "cache": self.cache.stats.to_dict(),
            "timeout_ms": self.settings.rerank_timeout_ms,
            "low_conf_gap": self.settings.rerank_low_conf_gap,
            "cache_ttl_s": self.cache.ttl_s,
        }


__all__ = [
    "CacheStats",
    "RERANK_SYSTEM_PROMPT",
    "RerankCache",
    "RerankController",
    "RerankDecision",
    "RerankOutcome",
    "Reranker",
    "build_rerank_messages",
    "parse_ranking",
]
