"""混合检索：向量 top15 ∪ BM25 top15 → RRF(k=60)
=================================================

设计 §5：`vector_hits = store.query(q_vec, top_k=15, category, threshold=0.3)`；
`bm25_hits = bm25.search(q, 15, category)`；`RRF(doc)=Σ 1/(60+rank+1)`；
融合后候选 ≤30，取 top 后交 LLM-as-rerank。

为什么 RRF：只看名次不看分数量纲，避免「余弦相似度」与「BM25 分数」不可比的问题；
两路都靠前的片段自然排前（向量擅长语义、BM25 擅长专名/数字精确匹配）。

保留 `score` 字段 = 向量相似度（前端展示「匹配度」）；BM25-only 命中记 0，
融合排序用 `rrf_score`，两者不混用。

W32 加固（两项 P0）：
    1. **RRF 确定性**：融合排序键固定为 `(-rrf, chunk_id)`。
       W31 用 `fused.sort(key=lambda item: -item[1])` + `set()` 迭代，同分时顺序依赖
       **进程级 str hash 随机化**（PYTHONHASHSEED），同库同问两次 MRR 会漂移
       （实测 0.9487 / 0.9516）；现在同分一律按 chunk_id 字典序，byte 级可复现。
    2. **强制项目作用域**：`search(..., project_id=...)`；未传 → 默认平台级 `ALL` 并打
       WARNING（`rag.scope`）；向量路 where `project_id IN (项目…, 'ALL')`，BM25 路同口径；
       另在应用层做**生效期兜底**（`effective_from/to` + `archived`），防库里残留过期可召回条目。
"""

from __future__ import annotations

import time
from dataclasses import dataclass, field, replace
from typing import Mapping

from .bm25 import BM25Index
from .config import Settings
from .embedder import Embedder
from .scope import DedupedLogger, ScopeInfo, describe, is_recallable, resolve_project_scope
from .store import RetrievedChunk, VectorStore


def fuse_rrf(
    vector_rank: Mapping[str, int],
    bm25_rank: Mapping[str, int],
    k: float = 60,
) -> list[tuple[str, float]]:
    """RRF 融合（**确定性**）：返回按 `(-rrf, chunk_id)` 升序的 `[(chunk_id, rrf)]`。

    输入：
        vector_rank : {chunk_id: 0-based 名次}
        bm25_rank   : {chunk_id: 0-based 名次}
        k           : RRF 常数（设计 §5 = 60）
    输出：融合序列；同分按 chunk_id 字典序（跨进程/跨次运行一致）。

    RRF(doc) = Σ_routes 1/(k + rank + 1)
    """
    merged: list[tuple[str, float]] = []
    for cid in set(vector_rank) | set(bm25_rank):
        rrf = 0.0
        if cid in vector_rank:
            rrf += 1.0 / (k + vector_rank[cid] + 1)
        if cid in bm25_rank:
            rrf += 1.0 / (k + bm25_rank[cid] + 1)
        merged.append((cid, rrf))
    merged.sort(key=lambda item: (-item[1], item[0]))
    return merged


@dataclass
class HybridResult:
    """混合检索结果。"""

    chunks: list[RetrievedChunk] = field(default_factory=list)
    vector_hits: int = 0
    bm25_hits: int = 0
    latency_ms: int = 0
    backend: str = ""
    vector_latency_ms: int = 0
    bm25_latency_ms: int = 0
    embed_latency_ms: int = 0
    # W32：项目作用域快照 + 生效期过滤计数（可审计）
    scope: ScopeInfo = field(default_factory=ScopeInfo)
    filtered_effective: int = 0
    filtered_archived: int = 0

    def to_dict(self) -> dict[str, object]:
        return {
            "vector_hits": self.vector_hits,
            "bm25_hits": self.bm25_hits,
            "latency_ms": self.latency_ms,
            "backend": self.backend,
            "vector_latency_ms": self.vector_latency_ms,
            "bm25_latency_ms": self.bm25_latency_ms,
            "embed_latency_ms": self.embed_latency_ms,
            "scope": self.scope.to_dict(),
            "filtered_effective": self.filtered_effective,
            "filtered_archived": self.filtered_archived,
        }


class HybridRetriever:
    """混合检索器（向量 + BM25 + RRF）。

    输入：
        settings : 配置
        store    : 向量库（缺省按配置构造）
        embedder : 向量器（缺省按配置构造，务必与入库同一模型）
    """

    def __init__(
        self,
        settings: Settings,
        store: VectorStore | None = None,
        embedder: Embedder | None = None,
    ) -> None:
        self.settings = settings
        self.store = store or VectorStore(settings)
        self.embedder = embedder or Embedder(settings)
        self._bm25: BM25Index | None = None
        self._chunks_by_id: dict[str, RetrievedChunk] = {}
        #: 作用域告警去重（长跑评测/服务不刷屏；语义与 rag.scope WARNING 一致）
        self._scope_log = DedupedLogger()

    # ---- BM25 懒构建 ----
    def ensure_index(self) -> None:
        """首次检索时从向量库读全部片段构建 BM25（千级语料 <1s）。"""
        if self._bm25 is not None:
            return
        chunks = self.store.all_chunks()
        self._chunks_by_id = {c.chunk_id: c for c in chunks}
        index = BM25Index()
        index.build([(c.chunk_id, c.text, c.category, c.status, c.project_id) for c in chunks])
        self._bm25 = index

    def invalidate(self) -> None:
        """索引变更（导入 / 撤销 / 重建）后失效缓存：下次检索按库内最新片段重建。

        task-85：`POST /internal/rag/index|remove` 写入/删除 chunk 后必须调用，否则 BM25
        缓存仍持有已删除片段 → 撤销后仍可召回（向量路已无该块，BM25 路却命中）。
        """
        self._bm25 = None
        self._chunks_by_id = {}

    @property
    def bm25(self) -> BM25Index:
        self.ensure_index()
        assert self._bm25 is not None
        return self._bm25

    # ---- 检索 ----
    def search(
        self,
        question: str,
        top_k: int | None = None,
        category: str | list[str] | None = None,
        status: str | list[str] | None = None,
        recall_per_route: int | None = None,
        score_threshold: float | None = None,
        project_id: str | list[str] | None = None,
        *,
        project: str | list[str] | None = None,
    ) -> HybridResult:
        """混合检索（项目作用域必填；未传 → 默认平台级 ALL + 显式 WARNING 日志）。

        输入：
            question         : 用户问题
            top_k            : 融合后返回条数（缺省 settings.rerank_candidates，供重排）
            category         : 分类过滤
            status           : 状态过滤（缺省 settings.recall_status = published）
            recall_per_route : 每路召回条数（缺省 settings.recall_per_route=15）
            score_threshold  : 向量相似度下限（缺省按 mock/真实自动选）
            project_id       : **项目作用域**（str/列表；``"*"`` = 显式不过滤，仅评测/排障）。
                               过滤口径 `project_id IN (所选项目…, 'ALL')`，ALL=平台级知识。
            project          : W31 兼容别名（等价 project_id；两者都给时 project_id 优先）
        输出：HybridResult（chunks 按 RRF 降序；score 保留向量相似度；scope 为作用域快照）
        """
        question = (question or "").strip()
        started = time.perf_counter()
        requested = project_id if project_id is not None else project
        scope = resolve_project_scope(requested, self.settings, log=self._scope_log)
        scope_info = describe(scope, requested)
        if not question:
            return HybridResult(backend=self.store.backend, scope=scope_info)

        top_k = int(top_k or self.settings.rerank_candidates)
        per_route = int(recall_per_route or self.settings.recall_per_route)
        threshold = self.settings.effective_score_threshold if score_threshold is None else score_threshold
        status_filter = status if status is not None else self.settings.recall_status
        scope_filter = scope or None  # () → 不过滤（显式 UNSCOPED）
        self.ensure_index()

        # 1) 向量路
        embed_started = time.perf_counter()
        query_vector = self.embedder.embed_query(question)
        embed_ms = int((time.perf_counter() - embed_started) * 1000)
        vector_started = time.perf_counter()
        vector_result = self.store.query(
            query_embedding=query_vector,
            top_k=per_route,
            category=category,
            status=status_filter,
            score_threshold=threshold,
            project=scope_filter,
        )
        vector_ms = int((time.perf_counter() - vector_started) * 1000)
        vector_chunks, dropped_effective, dropped_archived = self._apply_recall_fallback(vector_result.chunks)
        vector_rank = {hit.chunk_id: idx for idx, hit in enumerate(vector_chunks)}

        # 2) BM25 路（同口径项目过滤）
        bm25_started = time.perf_counter()
        bm25_raw = self.bm25.search(
            question,
            top_k=per_route,
            category=category,
            status=status_filter,
            project=list(scope) or None,
        )
        bm25_hits = [
            (cid, score) for cid, score in bm25_raw if self._recallable_by_id(cid)
        ]
        bm25_ms = int((time.perf_counter() - bm25_started) * 1000)
        bm25_rank = {cid: idx for idx, (cid, _score) in enumerate(bm25_hits)}

        # 3) RRF 融合（确定性排序键：-rrf, chunk_id）
        fused = fuse_rrf(vector_rank, bm25_rank, k=float(self.settings.rrf_k))

        # 4) 映射回 chunk（保留向量相似度；每知识最多 max_per_knowledge 块）
        vector_by_id = {hit.chunk_id: hit for hit in vector_chunks}
        bm25_by_id = dict(bm25_hits)
        result: list[RetrievedChunk] = []
        per_knowledge: dict[str, int] = {}
        for cid, rrf in fused:
            base = vector_by_id.get(cid) or self._chunks_by_id.get(cid)
            if base is None:
                continue
            doc_key = base.doc_key
            used = per_knowledge.get(doc_key, 0)
            if used >= max(1, self.settings.max_per_knowledge):
                continue
            per_knowledge[doc_key] = used + 1
            if cid in vector_by_id:
                chunk = replace(base, score=vector_by_id[cid].score)
            else:
                chunk = replace(base, score=0.0)
            result.append(
                replace(
                    chunk,
                    rrf_score=rrf,
                    bm25_score=float(bm25_by_id.get(cid, 0.0)),
                )
            )
            if len(result) >= top_k:
                break

        return HybridResult(
            chunks=result,
            vector_hits=len(vector_chunks),
            bm25_hits=len(bm25_hits),
            latency_ms=int((time.perf_counter() - started) * 1000),
            backend=self.store.backend,
            vector_latency_ms=vector_ms,
            bm25_latency_ms=bm25_ms,
            embed_latency_ms=embed_ms,
            scope=scope_info,
            filtered_effective=dropped_effective,
            filtered_archived=dropped_archived,
        )

    # ---- 生效期兜底 ----
    def _apply_recall_fallback(
        self, chunks: list[RetrievedChunk]
    ) -> tuple[list[RetrievedChunk], int, int]:
        """应用层兜底：剔除已归档 / 不在生效期的召回（status 由向量库 where 过滤）。"""
        kept: list[RetrievedChunk] = []
        dropped_effective = 0
        dropped_archived = 0
        for chunk in chunks:
            if chunk.archived:
                dropped_archived += 1
                continue
            if not is_recallable(chunk):
                dropped_effective += 1
                continue
            kept.append(chunk)
        return kept, dropped_effective, dropped_archived

    def _recallable_by_id(self, chunk_id: str) -> bool:
        """BM25 命中 → 用库内元数据做同样的生效期兜底。"""
        chunk = self._chunks_by_id.get(chunk_id)
        if chunk is None:
            return True  # 元数据缺失时不做二次过滤（保持与 W31 相同，不静默丢召回）
        return is_recallable(chunk)
