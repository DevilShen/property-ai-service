"""向量库抽象（ChromaDB / pgvector 骨架 / 内存回退）
=====================================================

设计 §3：本地 ChromaDB（持久化 `.data/rag/chroma`，collection `property_kb`，余弦距离），
生产切 pgvector —— 本模块对外是 `VectorStore` 抽象（`store.BaseStore` 实现族），
切换只改 `f`actory/config，不动检索/评测代码。

W31 结构：
    BaseStore      : 统一接口（add_chunks / query / all_chunks / count /
                     delete_by_knowledge / reset / iter_records / update_metadata）
    MemoryStore    : 纯内存（numpy 无关的余弦），**后端如实暴露 backend="memory"**
    ChromaStore    : ChromaDB 持久化；初始化失败 → **自动回退 MemoryStore** 并记录告警
    PgVectorStore  : pgvector 接口实现骨架；无驱动/无 DSN/不可达 → 抛 `PgVectorUnavailable`
                     **可读错误，不静默回退**（避免报告把 pgvector 说成已上线）
    create_store() : factory(backend)，`VectorStore` 为兼容门面（等价 create_store）

元数据字段（设计 §4.5 + W31 项目作用域）：
    knowledge_id / title / category / category_name / status / version / owner /
    source / section_path / chunk_seq / effective_from / effective_to / source_hash /
    project_id / service_scope / block_type / archived
"""

from __future__ import annotations

import hashlib
import math
from dataclasses import dataclass, field, replace
from pathlib import Path
from typing import Any, Iterable

from .config import Settings, category_name, global_project_id


@dataclass
class RetrievedChunk:
    """检索命中的片段（向量/BM25/混合三路共用同一结构）。"""

    chunk_id: str
    text: str
    knowledge_id: str = ""
    title: str = ""
    category: str = ""
    status: str = ""
    version: str = ""
    owner: str = ""
    source: str = ""
    section_path: str = ""
    chunk_seq: int = 0
    score: float = 0.0  # 向量余弦相似度（BM25-only 命中记 0）
    rrf_score: float = 0.0  # RRF 融合分（仅混合检索填）
    bm25_score: float = 0.0  # BM25 原始分（仅混合检索填）
    rerank_rank: int | None = None  # 重排后的名次（1 起；未重排为 None）
    project_id: str = ""
    service_scope: str = ""
    block_type: str = ""
    archived: bool = False
    # W32：生效期（召回层二次兜底用；空 = 不限）
    effective_from: str = ""
    effective_to: str = ""

    @property
    def category_name(self) -> str:
        return category_name(self.category)

    @property
    def doc_key(self) -> str:
        """知识级去重键（同一知识的多个 chunk 归为一篇文档）。"""
        return self.knowledge_id or self.chunk_id

    def to_dict(self, with_text: bool = True) -> dict[str, Any]:
        data = {
            "chunk_id": self.chunk_id,
            "knowledge_id": self.knowledge_id,
            "title": self.title,
            "category": self.category,
            "category_name": self.category_name,
            "status": self.status,
            "version": self.version,
            "owner": self.owner,
            "source": self.source,
            "section_path": self.section_path,
            "chunk_seq": self.chunk_seq,
            "project_id": self.project_id,
            "service_scope": self.service_scope,
            "block_type": self.block_type,
            "archived": self.archived,
            "effective_from": self.effective_from,
            "effective_to": self.effective_to,
            "score": round(float(self.score), 6),
            "rrf_score": round(float(self.rrf_score), 6),
            "bm25_score": round(float(self.bm25_score), 6),
            "rerank_rank": self.rerank_rank,
        }
        if with_text:
            data["text"] = self.text
        return data


@dataclass
class QueryResult:
    """一次向量查询的结果（附带后端信息，便于报告区分 chroma / memory / pgvector）。"""

    chunks: list[RetrievedChunk] = field(default_factory=list)
    backend: str = ""
    latency_ms: int = 0


def source_hash(text: str) -> str:
    """正文 hash（去重/变更检测用，不落明文）。"""
    return hashlib.sha256((text or "").encode("utf-8")).hexdigest()[:16]


def _cosine(a: list[float], b: list[float]) -> float:
    """余弦相似度（内存回退用；输入维度不一致抛 ValueError）。"""
    if len(a) != len(b):
        raise ValueError(f"向量维度不一致：{len(a)} vs {len(b)}")
    dot = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a)) or 1.0
    nb = math.sqrt(sum(y * y for y in b)) or 1.0
    return dot / (na * nb)


class PgVectorUnavailable(RuntimeError):
    """pgvector 后端不可用（驱动缺失/DSN 未配置/连接失败）——明确报错，不静默回退。"""


# --------------------------------------------------------------------------- 抽象

class BaseStore:
    """向量库统一接口（实现族：MemoryStore / ChromaStore / PgVectorStore）。"""

    backend: str = "abstract"

    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self.collection_name = settings.chroma_collection
        self._init_warnings: list[str] = []

    @property
    def init_warnings(self) -> list[str]:
        return list(self._init_warnings)

    # ---- 元数据 ----
    def _record_metadata(self, chunk: dict[str, Any]) -> dict[str, Any]:
        """chunk dict → 向量库元数据（Chroma 值只能是 str/int/float/bool）。"""
        return {
            "knowledge_id": str(chunk.get("knowledge_id") or ""),
            "title": str(chunk.get("title") or ""),
            "category": str(chunk.get("category") or ""),
            "category_name": str(chunk.get("category_name") or ""),
            "status": str(chunk.get("status") or ""),
            "version": str(chunk.get("version") or ""),
            "owner": str(chunk.get("owner") or ""),
            "source": str(chunk.get("source") or ""),
            "section_path": str(chunk.get("section_path") or ""),
            "chunk_seq": int(chunk.get("chunk_seq") or 0),
            "effective_from": str(chunk.get("effective_from") or ""),
            "effective_to": str(chunk.get("effective_to") or ""),
            "source_hash": str(chunk.get("source_hash") or source_hash(str(chunk.get("text") or ""))),
            "project_id": str(chunk.get("project_id") or global_project_id()),
            "service_scope": str(chunk.get("service_scope") or "平台"),
            "block_type": str(chunk.get("block_type") or ""),
            "archived": bool(chunk.get("archived") or False),
        }

    @staticmethod
    def _project_values(project: str | Iterable[str] | None) -> list[str]:
        """项目过滤 → 值列表（空/None/"all" = 不过滤）。"""
        if not project or project == "all":
            return []
        if isinstance(project, str):
            values = [project.strip()]
        else:
            values = [str(item).strip() for item in project if str(item).strip()]
        if not values:
            return []
        # 平台级知识（global_project_id）对所有项目可见
        if global_project_id() not in values:
            values.append(global_project_id())
        return list(dict.fromkeys(values))

    def build_where(
        self,
        category: str | list[str] | tuple[str, ...] | None = None,
        status: str | list[str] | tuple[str, ...] | None = None,
        project: str | list[str] | tuple[str, ...] | None = None,
    ) -> dict[str, Any] | None:
        """构造 Chroma where 子句（category / status / project 的白名单过滤）。"""
        clauses: list[dict[str, Any]] = []

        def _clause(key: str, value: Any) -> None:
            if value is None or value == "" or value == "all":
                return
            if isinstance(value, str):
                clauses.append({key: {"$eq": value}})
            else:
                clauses.append({key: {"$in": list(value)}})

        _clause("category", category)
        _clause("status", status)
        projects = self._project_values(project)
        if projects:
            if len(projects) == 1:
                clauses.append({"project_id": {"$eq": projects[0]}})
            else:
                clauses.append({"project_id": {"$in": projects}})
        if not clauses:
            return None
        if len(clauses) == 1:
            return clauses[0]
        return {"$and": clauses}

    # ---- 过滤（内存后端等共用）----
    @staticmethod
    def _match(value: Any, allowed: Any) -> bool:
        """None/"all" 全通过；str 单值；序列白名单。"""
        if not allowed or allowed == "all":
            return True
        if isinstance(allowed, str):
            return value == allowed
        return value in allowed

    def _matches_meta(self, meta: dict[str, Any], category: Any, status: Any, project: Any) -> bool:
        if not self._match(meta.get("category"), category):
            return False
        if not self._match(meta.get("status"), status):
            return False
        projects = self._project_values(project)
        if projects and meta.get("project_id") not in projects:
            return False
        return True

    # ---- 接口（子类实现）----
    def add_chunks(self, chunks: list[dict[str, Any]], embeddings: list[list[float]]) -> int:
        raise NotImplementedError

    def query(
        self,
        query_embedding: list[float],
        top_k: int = 15,
        category: str | list[str] | None = None,
        status: str | list[str] | None = None,
        score_threshold: float | None = None,
        project: str | list[str] | None = None,
    ) -> QueryResult:
        raise NotImplementedError

    def all_chunks(self) -> list[RetrievedChunk]:
        raise NotImplementedError

    def count(self) -> int:
        raise NotImplementedError

    def delete_by_knowledge(self, knowledge_id: str) -> int:
        raise NotImplementedError

    def reset(self) -> None:
        raise NotImplementedError

    def iter_records(self) -> list[dict[str, Any]]:
        """返回全部记录（chunk_id/text/metadata/vector），供 selfcheck 自检。"""
        raise NotImplementedError

    def update_metadata(self, chunk_ids: list[str], patch: dict[str, Any]) -> int:
        """批量更新元数据（expire_scan / supersede 用；不动向量与正文）。"""
        raise NotImplementedError


class MemoryStore(BaseStore):
    """纯内存向量库（离线/单测/降级回退；backend 如实为 "memory"）。"""

    backend = "memory"

    def __init__(self, settings: Settings) -> None:
        super().__init__(settings)
        self._records: dict[str, dict[str, Any]] = {}

    def add_chunks(self, chunks: list[dict[str, Any]], embeddings: list[list[float]]) -> int:
        """写入片段（同 chunk_id 覆盖）。"""
        if len(chunks) != len(embeddings):
            raise ValueError(f"chunks({len(chunks)}) 与 embeddings({len(embeddings)}) 长度不一致")
        for chunk, vec in zip(chunks, embeddings):
            self._records[str(chunk["chunk_id"])] = {
                "text": str(chunk.get("text") or ""),
                "metadata": self._record_metadata(chunk),
                "vector": list(map(float, vec)),
            }
        return len(chunks)

    def query(
        self,
        query_embedding: list[float],
        top_k: int = 15,
        category: str | list[str] | None = None,
        status: str | list[str] | None = None,
        score_threshold: float | None = None,
        project: str | list[str] | None = None,
    ) -> QueryResult:
        if not query_embedding:
            return QueryResult(chunks=[], backend=self.backend)
        scored: list[tuple[float, str, dict[str, Any]]] = []
        for cid, record in self._records.items():
            meta = record["metadata"]
            if not self._matches_meta(meta, category, status, project):
                continue
            similarity = _cosine(query_embedding, record["vector"])
            if score_threshold is not None and similarity < score_threshold:
                continue
            scored.append((similarity, cid, record))
        scored.sort(key=lambda item: -item[0])
        chunks: list[RetrievedChunk] = []
        for similarity, cid, record in scored[:top_k]:
            meta = record["metadata"]
            chunks.append(self._to_chunk(cid, record["text"], meta, score=float(similarity)))
        return QueryResult(chunks=chunks, backend=self.backend)

    @staticmethod
    def _to_chunk(cid: str, text: str, meta: dict[str, Any], score: float = 0.0) -> RetrievedChunk:
        return RetrievedChunk(
            chunk_id=cid,
            text=str(text or ""),
            knowledge_id=str(meta.get("knowledge_id") or ""),
            title=str(meta.get("title") or ""),
            category=str(meta.get("category") or ""),
            status=str(meta.get("status") or ""),
            version=str(meta.get("version") or ""),
            owner=str(meta.get("owner") or ""),
            source=str(meta.get("source") or ""),
            section_path=str(meta.get("section_path") or ""),
            chunk_seq=int(meta.get("chunk_seq") or 0),
            project_id=str(meta.get("project_id") or ""),
            service_scope=str(meta.get("service_scope") or ""),
            block_type=str(meta.get("block_type") or ""),
            archived=bool(meta.get("archived") or False),
            effective_from=str(meta.get("effective_from") or ""),
            effective_to=str(meta.get("effective_to") or ""),
            score=float(score),
        )

    def all_chunks(self) -> list[RetrievedChunk]:
        return [self._to_chunk(cid, record["text"], record["metadata"]) for cid, record in self._records.items()]

    def count(self) -> int:
        return len(self._records)

    def delete_by_knowledge(self, knowledge_id: str) -> int:
        knowledge_id = (knowledge_id or "").strip()
        if not knowledge_id:
            return 0
        ids = [cid for cid, record in self._records.items() if record["metadata"].get("knowledge_id") == knowledge_id]
        for cid in ids:
            self._records.pop(cid, None)
        return len(ids)

    def reset(self) -> None:
        self._records.clear()

    def iter_records(self) -> list[dict[str, Any]]:
        return [
            {
                "chunk_id": cid,
                "text": record["text"],
                "metadata": dict(record["metadata"]),
                "vector": list(record["vector"]),
            }
            for cid, record in self._records.items()
        ]

    def update_metadata(self, chunk_ids: list[str], patch: dict[str, Any]) -> int:
        updated = 0
        for cid in chunk_ids:
            record = self._records.get(str(cid))
            if record is None:
                continue
            record["metadata"].update({k: v for k, v in patch.items()})
            updated += 1
        return updated


class ChromaStore(BaseStore):
    """ChromaDB 持久化（默认后端）；初始化失败 → 自动回退 MemoryStore 并告警。"""

    backend = "chroma"

    def __init__(self, settings: Settings) -> None:
        super().__init__(settings)
        self._client = None
        self._collection = None
        self._fallback: MemoryStore | None = None
        self._init_chroma()

    def _init_chroma(self) -> None:
        try:
            import chromadb

            Path(self.settings.chroma_dir).mkdir(parents=True, exist_ok=True)
            self._client = chromadb.PersistentClient(path=str(self.settings.chroma_dir))
            self._collection = self._client.get_or_create_collection(
                name=self.collection_name,
                metadata={"hnsw:space": "cosine"},
            )
            self.backend = "chroma"
        except Exception as exc:  # pragma: no cover - 环境相关
            self._init_warnings.append(
                f"ChromaDB 初始化失败，回退内存向量库：{type(exc).__name__}: {str(exc)[:200]}"
            )
            self._client = None
            self._collection = None
            self._fallback = MemoryStore(self.settings)
            self.backend = "memory"

    # ---- 写入 ----
    def add_chunks(self, chunks: list[dict[str, Any]], embeddings: list[list[float]]) -> int:
        if len(chunks) != len(embeddings):
            raise ValueError(f"chunks({len(chunks)}) 与 embeddings({len(embeddings)}) 长度不一致")
        if self._fallback is not None:
            return self._fallback.add_chunks(chunks, embeddings)
        if not chunks:
            return 0
        self._collection.upsert(
            ids=[str(c["chunk_id"]) for c in chunks],
            documents=[str(c.get("text") or "") for c in chunks],
            embeddings=[list(map(float, e)) for e in embeddings],
            metadatas=[self._record_metadata(c) for c in chunks],
        )
        return len(chunks)

    # ---- 查询 ----
    def query(
        self,
        query_embedding: list[float],
        top_k: int = 15,
        category: str | list[str] | None = None,
        status: str | list[str] | None = None,
        score_threshold: float | None = None,
        project: str | list[str] | None = None,
    ) -> QueryResult:
        if self._fallback is not None:
            return self._fallback.query(
                query_embedding,
                top_k=top_k,
                category=category,
                status=status,
                score_threshold=score_threshold,
                project=project,
            )
        if not query_embedding:
            return QueryResult(chunks=[], backend=self.backend)
        where = self.build_where(category=category, status=status, project=project)
        result = self._collection.query(
            query_embeddings=[list(map(float, query_embedding))],
            n_results=max(1, int(top_k)),
            where=where,
            include=["documents", "metadatas", "distances"],
        )
        ids = (result.get("ids") or [[]])[0]
        docs = (result.get("documents") or [[]])[0]
        metas = (result.get("metadatas") or [[]])[0]
        distances = (result.get("distances") or [[]])[0]
        chunks: list[RetrievedChunk] = []
        for cid, doc, meta, dist in zip(ids, docs, metas, distances):
            meta = meta or {}
            similarity = 1.0 - float(dist)  # cosine 距离 → 相似度
            if score_threshold is not None and similarity < score_threshold:
                continue
            chunks.append(MemoryStore._to_chunk(str(cid), str(doc or ""), meta, score=similarity))
        return QueryResult(chunks=chunks, backend=self.backend)

    # ---- 全量 / 计数 / 删除 ----
    def _get_all(self, include: list[str]) -> dict[str, Any]:
        return self._collection.get(include=include)

    def all_chunks(self) -> list[RetrievedChunk]:
        if self._fallback is not None:
            return self._fallback.all_chunks()
        data = self._get_all(["documents", "metadatas"])
        return [
            MemoryStore._to_chunk(str(cid), str(doc or ""), meta or {})
            for cid, doc, meta in zip(data.get("ids") or [], data.get("documents") or [], data.get("metadatas") or [])
        ]

    def count(self) -> int:
        if self._fallback is not None:
            return self._fallback.count()
        return int(self._collection.count())

    def delete_by_knowledge(self, knowledge_id: str) -> int:
        knowledge_id = (knowledge_id or "").strip()
        if not knowledge_id:
            return 0
        if self._fallback is not None:
            return self._fallback.delete_by_knowledge(knowledge_id)
        where = {"knowledge_id": {"$eq": knowledge_id}}
        before = self._collection.get(where=where, include=[]).get("ids") or []
        if before:
            self._collection.delete(where=where)
        return len(before)

    def reset(self) -> None:
        if self._fallback is not None:
            self._fallback.reset()
            return
        try:
            self._client.delete_collection(self.collection_name)
        except Exception:  # pragma: no cover - 不存在则忽略
            pass
        self._collection = self._client.get_or_create_collection(
            name=self.collection_name,
            metadata={"hnsw:space": "cosine"},
        )

    def iter_records(self) -> list[dict[str, Any]]:
        if self._fallback is not None:
            return self._fallback.iter_records()
        data = self._get_all(["documents", "metadatas", "embeddings"])
        ids = data.get("ids") or []
        docs = data.get("documents") or []
        metas = data.get("metadatas") or []
        embeds = data.get("embeddings")
        records: list[dict[str, Any]] = []
        for index, cid in enumerate(ids):
            vector: list[float] = []
            if embeds is not None and index < len(embeds) and embeds[index] is not None:
                vector = [float(x) for x in embeds[index]]
            records.append(
                {
                    "chunk_id": str(cid),
                    "text": str(docs[index] if index < len(docs) else ""),
                    "metadata": dict(metas[index] or {}) if index < len(metas) else {},
                    "vector": vector,
                }
            )
        return records

    def update_metadata(self, chunk_ids: list[str], patch: dict[str, Any]) -> int:
        if self._fallback is not None:
            return self._fallback.update_metadata(chunk_ids, patch)
        ids = [str(cid) for cid in chunk_ids if str(cid)]
        if not ids:
            return 0
        current = self._collection.get(ids=ids, include=["metadatas"])
        wanted = set(ids)
        found_ids = [str(cid) for cid in (current.get("ids") or []) if str(cid) in wanted]
        if not found_ids:
            return 0
        metas = []
        for meta in current.get("metadatas") or []:
            merged = dict(meta or {})
            merged.update({k: v for k, v in patch.items()})
            metas.append(merged)
        self._collection.update(ids=found_ids, metadatas=metas)
        return len(found_ids)


class PgVectorStore(BaseStore):
    """pgvector 后端（W31 仅接口骨架：明确抛可读错误，不静默回退）。

    生产落库口径（`docs/ops/PG-schema.sql` 追加的 `knowledge_chunks` 表）：
        embedding vector(1024)、HNSW(cosine) 索引、project_id/category/status b-tree 索引。

    当前状态（W31）：
        - 仓库未引入 `psycopg`/`psycopg2` 驱动（AGENTS.md：不新增依赖）；
        - 无 PG 实例可达；
        → 构造时即抛 `PgVectorUnavailable`，错误信息包含缺失项与下一步动作，
          **绝不静默回退 Chroma/Memory**（避免报告把 pgvector 说成"已上线"）。
    """

    backend = "pgvector"

    #: 建表/检索 SQL 骨架（驱动接入后按此实现，字段与 Chroma metadata 对齐）
    SQL_SCHEMA = """
CREATE TABLE IF NOT EXISTS {table} (
    chunk_id       TEXT PRIMARY KEY,
    knowledge_id   TEXT NOT NULL,
    project_id     TEXT NOT NULL DEFAULT 'ALL',
    title          TEXT NOT NULL DEFAULT '',
    category       TEXT NOT NULL DEFAULT '',
    status         TEXT NOT NULL DEFAULT '',
    version        TEXT NOT NULL DEFAULT '',
    owner          TEXT NOT NULL DEFAULT '',
    source         TEXT NOT NULL DEFAULT '',
    section_path   TEXT NOT NULL DEFAULT '',
    chunk_seq      INT  NOT NULL DEFAULT 0,
    service_scope  TEXT NOT NULL DEFAULT '平台',
    effective_from DATE,
    effective_to   DATE,
    source_hash    TEXT NOT NULL,
    archived       BOOLEAN NOT NULL DEFAULT FALSE,
    embedding      vector(1024) NOT NULL,
    content        TEXT NOT NULL
);
"""

    def __init__(self, settings: Settings) -> None:
        super().__init__(settings)
        missing: list[str] = []
        try:  # pragma: no cover - 依赖环境
            import psycopg  # noqa: F401
        except Exception:
            missing.append("psycopg（PostgreSQL 驱动）未安装")
        if not (settings.pg_dsn or "").strip():
            missing.append("RAG_PG_DSN 未配置")
        if missing:
            raise PgVectorUnavailable(
                "PgVectorStore 不可用：" + "；".join(missing)
                + "。W31 仅提供接口骨架，请先 `pip install psycopg[binary]` 并配置 RAG_PG_DSN，"
                "再按 docs/ops/PG-schema.sql 的 knowledge_chunks 表实现；本模式下**不会**静默回退 Chroma。"
            )
        raise PgVectorUnavailable(  # pragma: no cover - W31 未实现执行层
            "PgVectorStore 执行层尚未实现（W31 交付为接口骨架）：请实现 add_chunks/query/iter_records 等"
            "方法后启用；本模式下不会静默回退 Chroma。"
        )

    def add_chunks(self, chunks: list[dict[str, Any]], embeddings: list[list[float]]) -> int:  # pragma: no cover
        raise PgVectorUnavailable("PgVectorStore.add_chunks 未实现（W31 骨架）")

    def query(self, query_embedding: list[float], **kwargs: Any) -> QueryResult:  # pragma: no cover
        raise PgVectorUnavailable("PgVectorStore.query 未实现（W31 骨架）")

    def all_chunks(self) -> list[RetrievedChunk]:  # pragma: no cover
        raise PgVectorUnavailable("PgVectorStore.all_chunks 未实现（W31 骨架）")

    def count(self) -> int:  # pragma: no cover
        raise PgVectorUnavailable("PgVectorStore.count 未实现（W31 骨架）")

    def delete_by_knowledge(self, knowledge_id: str) -> int:  # pragma: no cover
        raise PgVectorUnavailable("PgVectorStore.delete_by_knowledge 未实现（W31 骨架）")

    def reset(self) -> None:  # pragma: no cover
        raise PgVectorUnavailable("PgVectorStore.reset 未实现（W31 骨架）")

    def iter_records(self) -> list[dict[str, Any]]:  # pragma: no cover
        raise PgVectorUnavailable("PgVectorStore.iter_records 未实现（W31 骨架）")

    def update_metadata(self, chunk_ids: list[str], patch: dict[str, Any]) -> int:  # pragma: no cover
        raise PgVectorUnavailable("PgVectorStore.update_metadata 未实现（W31 骨架）")


# --------------------------------------------------------------------------- 工厂

def create_store(settings: Settings, backend: str | None = None) -> BaseStore:
    """向量库工厂：chroma（默认，失败回退 memory）/ memory / pgvector（骨架，报错不静默）。

    输入：
        settings : 配置（vector_store 决定默认后端）
        backend  : 显式覆盖后端（chroma|memory|pgvector）
    输出：BaseStore 实例（`backend` 属性如实反映真实生效后端）
    异常：backend=pgvector 且不可用 → PgVectorUnavailable（可读错误，不回退）
    """
    name = (backend or settings.vector_store or "chroma").strip().lower()
    if name in {"memory", "mem"}:
        return MemoryStore(settings)
    if name in {"pg", "pgvector", "postgres", "postgresql"}:
        return PgVectorStore(settings)
    return ChromaStore(settings)


class VectorStore(BaseStore):
    """兼容门面：`VectorStore(settings)` ≡ `create_store(settings)`。

    W30 代码与单测均以 `VectorStore(settings)` 构造，`backend` 属性读取真实后端；
    这里用 `__new__` 分派到 factory 返回的具体实现，保持既有调用方式不变。
    """

    def __new__(cls, settings: Settings, backend: str | None = None) -> BaseStore:  # type: ignore[misc]
        if cls is VectorStore:
            return create_store(settings, backend=backend)
        return super().__new__(cls)  # pragma: no cover


__all__ = [
    "BaseStore",
    "ChromaStore",
    "MemoryStore",
    "PgVectorStore",
    "PgVectorUnavailable",
    "QueryResult",
    "RetrievedChunk",
    "VectorStore",
    "create_store",
    "source_hash",
]
