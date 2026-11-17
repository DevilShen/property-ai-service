"""索引管道（W31）：切分/脱敏 → Embedding → 向量库 → 失效扫描 + 审计
=====================================================================

职责：把「取数 → 筛/切 → 向量化 → 入库 → 失效扫描」抽成可复用管道，供：
    - `rag.ingest`（全量重建：Java KB 44 条 + corpus 演示语料）；
    - `rag.reindex`（增量：`reindex(knowledgeId)` / `remove` / `expire_scan` / `supersede`）；
    - `rag.selfcheck`（读 manifest + 库内记录做质量自检）。

审计（本地 JSONL，不入库、不含密钥/正文）：
    `.data/rag/index-audit.jsonl`，每行一条：
    `{ts, action, knowledge_id, chunks_before, chunks_after, status, detail, actor}`

失效口径（设计 §4：过期条目不得作为答案依据）：
    - `effectiveTo < today` → `expire_scan` 把 status 改为 `expired` 且 `archived=true`；
    - 记录**保留在库中**（归档，供审计/引用回溯），但不在召回集合（`recall_status=("published",)`）；
    - `supersede` 把旧版本标 `superseded` + `archived=true`，再写入新版本 chunk。
"""

from __future__ import annotations

import json
import time
from datetime import date, datetime, timezone
from pathlib import Path
from typing import Any, Callable

from .chunker import chunk_knowledge
from .config import Settings, category_name, global_project_id
from .corpus import corpus_stats, load_corpus
from .embedder import Embedder
from .screener import dedup_chunks, screen
from .store import BaseStore, create_store, source_hash


# --------------------------------------------------------------------------- 审计

def _now_iso() -> str:
    """本地时区 ISO 时间（审计/报告用）。"""
    return datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")


def audit_append(settings: Settings, action: str, **fields: Any) -> dict[str, Any]:
    """追加一条索引审计（JSONL）；写失败只告警不抛（不因审计失败丢索引结果）。

    输入：
        settings : 配置（audit_path 默认 `.data/rag/index-audit.jsonl`）
        action   : index | reindex | remove | expire_scan | supersede | selfcheck
        fields   : 结构化明细（不含正文/密钥）
    输出：写入的审计 dict
    """
    entry: dict[str, Any] = {"ts": _now_iso(), "action": action, "actor": "rag.indexer"}
    entry.update(fields)
    try:
        path = Path(settings.audit_path)
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("a", encoding="utf-8", newline="\n") as handle:
            handle.write(json.dumps(entry, ensure_ascii=False) + "\n")
    except Exception:  # pragma: no cover - 审计写失败不影响主链路
        pass
    return entry


def read_audit(settings: Settings, limit: int | None = None) -> list[dict[str, Any]]:
    """读取审计 JSONL（最近 limit 条；文件不存在返回空列表）。"""
    path = Path(settings.audit_path)
    if not path.is_file():
        return []
    rows: list[dict[str, Any]] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            rows.append(json.loads(line))
        except json.JSONDecodeError:
            continue
    return rows[-limit:] if limit else rows


# --------------------------------------------------------------------------- 语料/取数

def normalize_item(item: dict[str, Any]) -> dict[str, Any]:
    """补全 item 的索引必需字段（projectId/serviceScope/status/version），不覆盖已有值。"""
    normalized = dict(item)
    normalized["knowledgeId"] = str(item.get("knowledgeId") or item.get("knowledge_id") or "").strip()
    normalized["projectId"] = str(item.get("projectId") or item.get("project_id") or "").strip() or global_project_id()
    normalized["serviceScope"] = str(item.get("serviceScope") or item.get("service_scope") or "").strip() or "平台"
    normalized["status"] = str(item.get("status") or "").strip() or "published"
    normalized["version"] = str(item.get("version") or "").strip()
    normalized["title"] = str(item.get("title") or "").strip()
    normalized["content"] = str(item.get("content") or "")
    if "source" not in normalized or normalized.get("source") in (None, ""):
        normalized["source"] = f"{normalized['knowledgeId']} {normalized['version']}".strip()
    return normalized


def load_corpus_items(settings: Settings) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    """加载演示语料（`rag/corpus/*.md`）并归一字段。"""
    items = [normalize_item(item) for item in load_corpus(settings.corpus_dir)]
    return items, corpus_stats(items)


def load_kb_items(
    settings: Settings,
    *,
    source_json: str | Path | None = None,
    limit: int | None = None,
    fetch: bool = True,
) -> list[dict[str, Any]]:
    """从 Java KB API（或本地 JSON）取知识条目并归一字段。

    输入：settings / source_json（离线 dump）/ limit（只取前 N）/ fetch（False → 不取数）
    输出：知识 item 列表（已 normalize_item）
    """
    if not fetch:
        return []
    if source_json:
        from .ingest import load_source_json

        items = load_source_json(source_json)
    else:
        from .ingest import fetch_knowledge, login

        token = login(settings.knowledge_api, settings.knowledge_account, settings.knowledge_password)
        items = fetch_knowledge(
            settings.knowledge_api, token, page_size=settings.knowledge_page_size, limit=limit
        )
    if limit:
        items = items[:limit]
    return [normalize_item(item) for item in items]


# --------------------------------------------------------------------------- 切分/向量化

def prepare_chunks(items: list[dict[str, Any]], settings: Settings) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    """脱敏 + 结构感知切分 + 去重，返回 chunk 列表与统计。

    输入：knowledge items（KB item 或 corpus 文档）、settings
    输出：(chunks, stats)
    """
    chunks: list[dict[str, Any]] = []
    stats: dict[str, Any] = {
        "items": len(items),
        "empty_content": 0,
        "masked_phone": 0,
        "masked_id": 0,
        "masked_email": 0,
        "masked_sensitive": 0,
        "dropped_lines": 0,
        "chunks_before_dedup": 0,
        "duplicates_dropped": 0,
    }
    for raw_item in items:
        item = normalize_item(raw_item)
        content = str(item.get("content") or "").strip()
        if not content:
            stats["empty_content"] += 1
            continue
        screened = screen(
            content,
            sensitive_words=settings.sensitive_words,
            mask_token=settings.mask_token,
            mask_email_enabled=True,
        )
        stats["masked_phone"] += screened.masked_phone
        stats["masked_id"] += screened.masked_id
        stats["masked_email"] += screened.masked_email
        stats["masked_sensitive"] += screened.masked_sensitive
        stats["dropped_lines"] += screened.dropped_lines

        title = str(item.get("title") or "")
        title_screened = screen(title, sensitive_words=settings.sensitive_words, mask_token=settings.mask_token).text
        safe_item = dict(item)
        safe_item["content"] = screened.text
        safe_item["title"] = title_screened or title
        for chunk in chunk_knowledge(
            safe_item,
            size=settings.chunk_size,
            overlap=settings.chunk_overlap,
            min_length=settings.chunk_min_length,
        ):
            chunk["source_hash"] = chunk.get("source_hash") or source_hash(chunk["text"])
            chunks.append(chunk)

    stats["chunks_before_dedup"] = len(chunks)
    kept, dropped = dedup_chunks(chunks, threshold=settings.dedup_similarity)
    stats["duplicates_dropped"] = dropped
    return kept, stats


def embed_chunks(
    settings: Settings,
    chunks: list[dict[str, Any]],
    *,
    embedder: Embedder | None = None,
    print_fn: Callable[[str], None] = print,
) -> tuple[list[list[float]], dict[str, Any]]:
    """把 chunk 批量向量化（batch 16 / LRU / 重试 / 维度校验 / mock 降级告警）。"""
    embedder = embedder or Embedder(settings)
    started = time.perf_counter()
    vectors, embed_stats = embedder.embed_documents([c["embed_text"] for c in chunks]) if chunks else ([], None)
    elapsed_ms = int((time.perf_counter() - started) * 1000)
    embedder.save_cache()
    stats = embed_stats.to_dict() if embed_stats is not None else {}
    stats["elapsed_ms"] = elapsed_ms
    stats["lru_size"] = embedder.lru_size
    stats["disk_cache_size"] = embedder.disk_cache_size
    stats["alerts"] = list(embedder.alerts)
    stats["degraded"] = bool(embedder.degraded)
    if embedder.degraded:
        for alert in embedder.alerts:
            print_fn(f"[indexer][alert] {alert}")
    print_fn(
        f"[indexer] Embedding {len(vectors)} 条（provider={settings.embed_provider}, model={settings.embed_model}, "
        f"dim={settings.embed_dim}, batch={settings.embed_batch_size}, cache_hits={stats.get('cache_hits', 0)}, "
        f"{elapsed_ms}ms）"
    )
    return vectors, stats


# --------------------------------------------------------------------------- 失效扫描

def _today(now: str | None = None) -> str:
    """当前日期（YYYY-MM-DD）；now 可指定用于测试。"""
    if now:
        return str(now)[:10]
    return date.today().isoformat()


def expire_scan(
    settings: Settings,
    *,
    store: BaseStore | None = None,
    now: str | None = None,
    audit: bool = True,
    print_fn: Callable[[str], None] = print,
) -> dict[str, Any]:
    """扫描过期知识：从可召回集合剔除并归档（保留 chunk，不删除）。

    判定：`effective_to` 非空且 `< now` → status 改为 `settings.expire_status`（默认 expired）、
          `archived=true`；已归档的不重复处理。

    输入：settings / store（缺省按配置构造）/ now（YYYY-MM-DD，测试注入）
    输出：{scanned, expired_chunks, expired_knowledge_ids, updated, already_archived, today}
    """
    store = store or create_store(settings)
    today = _today(now)
    records = store.iter_records()
    to_update: list[str] = []
    expired_ids: set[str] = set()
    already = 0
    for record in records:
        meta = record.get("metadata") or {}
        effective_to = str(meta.get("effective_to") or "").strip()
        if not effective_to or effective_to >= today:
            continue
        if bool(meta.get("archived")):
            already += 1
            continue
        to_update.append(str(record.get("chunk_id") or ""))
        expired_ids.add(str(meta.get("knowledge_id") or ""))
    updated = store.update_metadata(
        [cid for cid in to_update if cid],
        {"status": settings.expire_status, "archived": True, "expired_at": today},
    ) if to_update else 0
    result = {
        "today": today,
        "scanned": len(records),
        "expired_chunks": len(to_update),
        "expired_knowledge_ids": sorted(kid for kid in expired_ids if kid),
        "updated": updated,
        "already_archived": already,
        "expire_status": settings.expire_status,
        "store_backend": store.backend,
    }
    if to_update:
        print_fn(
            f"[indexer] 过期归档 {updated} 块（知识 {len(expired_ids)} 条，status→{settings.expire_status}，"
            "保留归档不删除）"
        )
    if audit:
        audit_append(settings, "expire_scan", **result)
    return result


# --------------------------------------------------------------------------- 增量操作

def _resolve_item(
    settings: Settings,
    knowledge_id: str,
    *,
    item: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """取单条知识：显式 item → corpus（C-*）→ Java KB API。"""
    if item is not None:
        return normalize_item(item)
    knowledge_id = (knowledge_id or "").strip()
    if not knowledge_id:
        raise ValueError("knowledge_id 不能为空")
    if knowledge_id.startswith("C-"):
        items, _stats = load_corpus_items(settings)
        for candidate in items:
            if candidate["knowledgeId"] == knowledge_id:
                return candidate
        raise LookupError(f"演示语料中不存在 {knowledge_id}（检查 rag/corpus/*.md）")
    items = load_kb_items(settings, fetch=True)
    for candidate in items:
        if candidate["knowledgeId"] == knowledge_id:
            return candidate
    raise LookupError(f"知识库中不存在 {knowledge_id}（检查 Java :4000 /api/v1/knowledge）")


def _index_single_item(
    settings: Settings,
    item: dict[str, Any],
    store: BaseStore,
    embedder: Embedder,
    *,
    print_fn: Callable[[str], None] = print,
) -> dict[str, Any]:
    """单条知识：切分 → 向量化 → 覆盖写入（同知识旧 chunk 先删）。"""
    chunks, stats = prepare_chunks([item], settings)
    if not chunks:
        return {"chunks": 0, "stats": stats}
    vectors, embed_stats = embed_chunks(settings, chunks, embedder=embedder, print_fn=print_fn)
    removed = store.delete_by_knowledge(item["knowledgeId"])
    written = store.add_chunks(chunks, vectors)
    return {"chunks": written, "removed": removed, "stats": stats, "embed": embed_stats}


def reindex_knowledge(
    settings: Settings,
    knowledge_id: str,
    *,
    item: dict[str, Any] | None = None,
    store: BaseStore | None = None,
    embedder: Embedder | None = None,
    print_fn: Callable[[str], None] = print,
) -> dict[str, Any]:
    """增量重建一条知识（delete_by_knowledge → 重新切分/向量化/写入）。

    输入：
        settings     : 配置
        knowledge_id : 知识 ID（K-* 走 Java KB；C-* 走 corpus）
        item         : 直接给 item（测试/离线），优先于远程取数
    输出：{action, knowledge_id, chunks, removed, chunks_after, ...}（并写审计 JSONL）
    """
    store = store or create_store(settings)
    embedder = embedder or Embedder(settings)
    resolved = _resolve_item(settings, knowledge_id, item=item)
    result = _index_single_item(settings, resolved, store, embedder, print_fn=print_fn)
    expire = expire_scan(settings, store=store, audit=False, print_fn=print_fn)
    entry = {
        "knowledge_id": resolved["knowledgeId"],
        "title": resolved.get("title", ""),
        "status": resolved.get("status", ""),
        "version": resolved.get("version", ""),
        "project_id": resolved.get("projectId", ""),
        "chunks": result.get("chunks", 0),
        "chunks_removed": result.get("removed", 0),
        "collection_count": store.count(),
        "store_backend": store.backend,
        "embed": result.get("embed", {}),
        "expire": expire,
    }
    audit_append(settings, "reindex", **entry)
    print_fn(
        f"[indexer] reindex {entry['knowledge_id']}：新 chunk {entry['chunks']}，"
        f"删除旧 chunk {entry['chunks_removed']}，库内共 {entry['collection_count']} 块"
    )
    return {"action": "reindex", **entry}


def remove_knowledge(
    settings: Settings,
    knowledge_id: str,
    *,
    store: BaseStore | None = None,
    print_fn: Callable[[str], None] = print,
) -> dict[str, Any]:
    """删除一条知识的全部 chunk（下架/撤回），并写审计。"""
    store = store or create_store(settings)
    knowledge_id = (knowledge_id or "").strip()
    if not knowledge_id:
        raise ValueError("knowledge_id 不能为空")
    removed = store.delete_by_knowledge(knowledge_id)
    entry = {
        "knowledge_id": knowledge_id,
        "chunks_removed": removed,
        "collection_count": store.count(),
        "store_backend": store.backend,
    }
    audit_append(settings, "remove", **entry)
    print_fn(f"[indexer] remove {knowledge_id}：删除 {removed} 块，库内共 {entry['collection_count']} 块")
    return {"action": "remove", **entry}


def supersede(
    settings: Settings,
    knowledge_id: str,
    version: str,
    *,
    item: dict[str, Any] | None = None,
    store: BaseStore | None = None,
    embedder: Embedder | None = None,
    print_fn: Callable[[str], None] = print,
) -> dict[str, Any]:
    """用新版本替代旧版本：旧 chunk 标 `superseded` + 归档（保留向量，可回溯），再写入新版本。

    实现要点：同一 knowledge_id 的新旧 chunk **id 相同**（`{kid}#{seq}`），若直接覆盖会
    丢掉旧版本；因此先把旧 chunk 以 `f"{chunk_id}@V{旧版本}"` 的归档 id 重新写回
    （status=superseded、archived=true），再写入新版本 chunk。

    输入：
        knowledge_id : 知识 ID
        version      : 新版本号（写入新 chunk 的 version 字段）
    输出：{action, knowledge_id, version, archived_chunks, chunks, chunks_after, ...}
    """
    store = store or create_store(settings)
    embedder = embedder or Embedder(settings)
    resolved = _resolve_item(settings, knowledge_id, item=item)
    resolved["version"] = str(version or resolved.get("version") or "")
    records = [r for r in store.iter_records() if str((r.get("metadata") or {}).get("knowledge_id") or "") == knowledge_id]
    archived = 0
    archive_chunks: list[dict[str, Any]] = []
    archive_vectors: list[list[float]] = []
    if records:
        # 1) 先摘出旧 chunk（含向量），删除同知识旧记录（新 chunk 复用原 chunk_id）
        for record in records:
            meta = dict(record.get("metadata") or {})
            old_version = str(meta.get("version") or "old")
            archive_chunks.append(
                {
                    "chunk_id": f"{record.get('chunk_id')}@V{old_version}",
                    "text": str(record.get("text") or ""),
                    "knowledge_id": knowledge_id,
                    "title": meta.get("title", ""),
                    "category": meta.get("category", ""),
                    "category_name": meta.get("category_name", ""),
                    "status": "superseded",
                    "version": old_version,
                    "owner": meta.get("owner", ""),
                    "source": meta.get("source", ""),
                    "section_path": meta.get("section_path", ""),
                    "chunk_seq": int(meta.get("chunk_seq") or 0),
                    "effective_from": meta.get("effective_from", ""),
                    "effective_to": meta.get("effective_to", ""),
                    "project_id": meta.get("project_id", ""),
                    "service_scope": meta.get("service_scope", ""),
                    "block_type": meta.get("block_type", ""),
                    "source_hash": meta.get("source_hash", ""),
                    "archived": True,
                }
            )
            archive_vectors.append(list(record.get("vector") or []))
        store.delete_by_knowledge(knowledge_id)
    # 2) 写入新版本 chunk（独立 chunk_id 集合 + 新向量）
    chunks, _stats = prepare_chunks([resolved], settings)
    new_vectors: list[list[float]] = []
    embed_stats: dict[str, Any] = {}
    if chunks:
        new_vectors, embed_stats = embed_chunks(settings, chunks, embedder=embedder, print_fn=print_fn)
        store.add_chunks(chunks, new_vectors)
    # 3) 归档旧版本（id 追加 @V旧版本，保留向量可回溯；不参与召回）
    if archive_chunks:
        archived = store.add_chunks(archive_chunks, archive_vectors)
    result = {"chunks": len(chunks), "embed": embed_stats}
    entry = {
        "knowledge_id": knowledge_id,
        "version": resolved.get("version", ""),
        "archived_chunks": archived,
        "chunks": result.get("chunks", 0),
        "collection_count": store.count(),
        "store_backend": store.backend,
        "embed": result.get("embed", {}),
    }
    audit_append(settings, "supersede", **entry)
    print_fn(
        f"[indexer] supersede {knowledge_id} → {entry['version']}：归档旧 chunk {archived} 块"
        f"（id 追加 @V旧版本，保留可回溯），写入新 chunk {entry['chunks']} 块"
    )
    return {"action": "supersede", **entry}


# --------------------------------------------------------------------------- 统计辅助

def chunk_stats(chunks: list[dict[str, Any]], items: list[dict[str, Any]]) -> dict[str, Any]:
    """chunk 统计：条数/知识数/项目/分类/状态/结构类型/长度（报告与自检用）。"""
    by_project: dict[str, int] = {}
    by_category: dict[str, int] = {}
    by_status: dict[str, int] = {}
    by_block_type: dict[str, int] = {}
    by_kind_source: dict[str, int] = {}
    for chunk in chunks:
        project = str(chunk.get("project_id") or "")
        category = str(chunk.get("category") or "")
        status = str(chunk.get("status") or "")
        block_type = str(chunk.get("block_type") or "")
        by_project[project] = by_project.get(project, 0) + 1
        by_category[category] = by_category.get(category, 0) + 1
        by_status[status] = by_status.get(status, 0) + 1
        by_block_type[block_type] = by_block_type.get(block_type, 0) + 1
        kind = "corpus" if str(chunk.get("knowledge_id") or "").startswith("C-") else "kb"
        by_kind_source[kind] = by_kind_source.get(kind, 0) + 1
    lengths = [len(str(chunk.get("text") or "")) for chunk in chunks]
    return {
        "chunks": len(chunks),
        "knowledge_items": len({str(chunk.get("knowledge_id") or "") for chunk in chunks}),
        "by_project": dict(sorted(by_project.items())),
        "by_category": dict(sorted(by_category.items())),
        "by_status": dict(sorted(by_status.items())),
        "by_block_type": dict(sorted(by_block_type.items())),
        "by_source_kind": dict(sorted(by_kind_source.items())),
        "length_chars": {
            "min": min(lengths) if lengths else 0,
            "max": max(lengths) if lengths else 0,
            "avg": round(sum(lengths) / len(lengths), 1) if lengths else 0.0,
        },
        "items": len(items),
        "category_names": sorted({category_name(str(chunk.get("category") or "")) for chunk in chunks}),
    }
