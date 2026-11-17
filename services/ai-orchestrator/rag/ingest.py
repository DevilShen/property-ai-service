"""ingest CLI：Java 知识库 + 演示语料 → 脱敏/切分 → Embedding → ChromaDB
==========================================================================

用法（在 `services/ai-orchestrator` 目录）：
    python -m rag.ingest --rebuild                              # KB + 演示语料（默认）
    python -m rag.ingest --rebuild --env-file "<REF_RAG_PROJECT>\\.env"
    python -m rag.ingest --rebuild --mock-embed                  # 离线（哈希向量）
    python -m rag.ingest --rebuild --no-corpus                   # 只索引 Java KB 44 条（W30 口径）
    python -m rag.ingest --limit 10                              # 小样本验证
    python -m rag.ingest --source-json kb.json                   # 从本地 dump 入库（离线/测试）

口径（设计 §4 / W31）：
    - 登录 Java 演示 JWT（默认 it-01 / demo123），`GET /api/v1/knowledge?page=&pageSize=`
      **pageSize 上限 100**（实测 200 → 400 E-1001），按 total 分页拉全；
    - **W31 默认合并 `rag/corpus/*.md` 演示语料**（虚构 mock，见 corpus/README.md），
      与 KB 条目合并切分/去重后索引 ≥200 chunk；
    - 索引全部拉到条目（含非 published，metadata 带 status），**召回默认只取 published**
      （生产口径；非 published 条目用于报告《语料/索引统计》并验证状态隔离）；
    - 入库前必过 screener（手机号/身份证/邮箱/敏感词掩码），日志不落正文；
    - 索引结束后自动跑 `expire_scan`：过期条目 status→expired + archived=true（保留归档、
      移出可召回集合），并写审计 `.data/rag/index-audit.jsonl`。

输出：`services/ai-orchestrator/.data/rag/ingest-manifest.json`（计数/模型/是否 mock/耗时/
chunk_ids/embedding 统计/expire 结果，不含密钥与正文），供 PoC / selfcheck / 报告引用。
"""

from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable

from .config import Settings, load_settings, mask_secret
from .embedder import Embedder
from .indexer import (
    audit_append,
    chunk_stats,
    embed_chunks,
    expire_scan,
    load_corpus_items,
    normalize_item,
    prepare_chunks,
)
from .store import VectorStore, create_store

# 向后兼容：W30 的 `from rag.ingest import prepare_chunks` 继续可用
__all__ = [
    "DataFetchError",
    "fetch_knowledge",
    "load_source_json",
    "login",
    "main",
    "prepare_chunks",
    "run",
]


class DataFetchError(RuntimeError):
    """取数失败（登录/HTTP/JSON）。"""


def _http_json(url: str, token: str | None = None, payload: dict[str, Any] | None = None, timeout: int = 30) -> Any:
    """极简 HTTP JSON 客户端（stdlib，避免额外依赖）。"""
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(url, data=data, headers=headers, method="POST" if data else "GET")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = response.read().decode("utf-8")
    except urllib.error.HTTPError as exc:
        detail = ""
        try:
            detail = exc.read().decode("utf-8", errors="ignore")[:300]
        except Exception:  # pragma: no cover
            pass
        raise DataFetchError(f"{url} → HTTP {exc.code}：{detail}") from exc
    except urllib.error.URLError as exc:
        raise DataFetchError(f"{url} → 连接失败：{exc.reason}") from exc
    try:
        return json.loads(body)
    except json.JSONDecodeError as exc:  # pragma: no cover
        raise DataFetchError(f"{url} 返回非 JSON") from exc


def login(base_url: str, account: str, password: str, timeout: int = 15) -> str:
    """登录 Java 演示 JWT，返回 token。"""
    url = f"{base_url.rstrip('/')}/api/v1/auth/login"
    data = _http_json(url, payload={"account": account, "password": password}, timeout=timeout)
    token = ((data or {}).get("data") or {}).get("token")
    if not token:
        raise DataFetchError("登录成功但未取到 token（检查 /api/v1/auth/login 响应结构）")
    return str(token)


def fetch_knowledge(
    base_url: str,
    token: str,
    page_size: int = 100,
    limit: int | None = None,
    timeout: int = 30,
) -> list[dict[str, Any]]:
    """分页拉取知识列表（pageSize ≤ 100）。

    输入：
        base_url  : Java 服务地址（如 http://localhost:4000）
        token     : 演示 JWT
        page_size : 每页条数（自动夹到 1—100）
        limit     : 最多拉取条数（None = 全部）
    输出：knowledge item 列表
    """
    page_size = max(1, min(int(page_size), 100))
    items: list[dict[str, Any]] = []
    page = 1
    total = None
    while True:
        url = f"{base_url.rstrip('/')}/api/v1/knowledge?page={page}&pageSize={page_size}"
        data = _http_json(url, token=token, timeout=timeout)
        payload = (data or {}).get("data") or {}
        batch = payload.get("items") or []
        total = payload.get("total", total)
        items.extend(batch)
        if limit is not None and len(items) >= limit:
            items = items[:limit]
            break
        if not batch or (total is not None and len(items) >= int(total)):
            break
        page += 1
        if page > 50:  # 防御：避免服务端分页异常导致死循环
            break
    return items


def load_source_json(path: str | Path) -> list[dict[str, Any]]:
    """从本地 JSON 读取知识条目（离线/测试用；结构同 API 的 items）。"""
    raw = json.loads(Path(path).read_text(encoding="utf-8"))
    if isinstance(raw, dict):
        raw = raw.get("items") or raw.get("data") or []
    return list(raw)


def _status_counts(items: list[dict[str, Any]], key: str) -> dict[str, int]:
    """按字段计数（status/category 统计用）。"""
    counts: dict[str, int] = {}
    for item in items:
        value = str(item.get(key) or "unknown")
        counts[value] = counts.get(value, 0) + 1
    return dict(sorted(counts.items()))


def run(
    settings: Settings,
    *,
    rebuild: bool = False,
    mock_embed: bool = False,
    limit: int | None = None,
    source_json: str | Path | None = None,
    published_only: bool = False,
    corpus: bool = True,
    print_fn: Callable[[str], None] = print,
) -> dict[str, Any]:
    """执行一次 ingest（取数 → 筛/切 → 向量化 → 写库 → 过期扫描 → manifest）。

    输入：
        settings      : 配置（含 knowledge_api / 账号 / corpus_dir）
        rebuild       : 是否清空 collection 后重建
        mock_embed    : 是否用哈希 mock 向量（离线）
        limit         : 只取前 N 条 KB（小样本验证）
        source_json   : 从本地 JSON 取 KB（跳过 HTTP）
        published_only: 只索引 published（生产口径验证）
        corpus        : 是否合并 `rag/corpus/*.md` 演示语料（默认 True）
    输出：manifest dict（同时写入 .data/rag/ingest-manifest.json）
    """
    started = time.perf_counter()
    effective = settings.with_overrides(embed_provider="mock") if mock_embed else settings
    if mock_embed:
        print_fn("[ingest] 使用 mock embedding（确定性哈希向量，离线模式）")

    # 1) 取数：Java KB
    if source_json:
        kb_items = [normalize_item(item) for item in load_source_json(source_json)]
        if limit:
            kb_items = kb_items[:limit]
        source_desc = f"local:{source_json}"
    else:
        token = login(effective.knowledge_api, effective.knowledge_account, effective.knowledge_password)
        raw_items = fetch_knowledge(
            effective.knowledge_api, token, page_size=effective.knowledge_page_size, limit=limit
        )
        kb_items = [normalize_item(item) for item in raw_items]
        source_desc = effective.knowledge_api
    print_fn(f"[ingest] KB 取数 {len(kb_items)} 条（来源 {source_desc}）")

    kb_status_counts = _status_counts(kb_items, "status")
    kb_category_counts = _status_counts(kb_items, "category")

    # 2) 演示语料（W31）：虚构 mock Markdown，与 KB 合并索引
    corpus_items: list[dict[str, Any]] = []
    corpus_summary: dict[str, Any] = {"enabled": bool(corpus), "docs": 0}
    if corpus:
        corpus_items, corpus_summary = load_corpus_items(effective)
        corpus_summary["enabled"] = True
        print_fn(
            f"[ingest] 演示语料 {len(corpus_items)} 篇（{effective.corpus_dir}；"
            f"项目 {corpus_summary.get('by_project')}；状态 {corpus_summary.get('by_status')}）"
        )

    items = list(kb_items) + list(corpus_items)
    if not items:
        raise DataFetchError("未取到任何知识条目（检查 Java 服务与权限 / corpus 目录）")

    if published_only:
        items = [item for item in items if str(item.get("status") or "") == "published"]
        print_fn(f"[ingest] 仅索引 published：{len(items)} 条")

    # 3) 脱敏 + 结构感知切分 + 去重
    chunks, stats = prepare_chunks(items, effective)
    print_fn(
        f"[ingest] 切分 {stats['chunks_before_dedup']} 块 → 去重后 {len(chunks)} 块"
        f"（脱敏：手机 {stats['masked_phone']}/身份证 {stats['masked_id']}/邮箱 {stats['masked_email']}；"
        f"丢弃空内容 {stats['empty_content']}）"
    )

    # 4) 向量化（batch 16 / LRU / 重试 / 维度校验 / 失败降级 mock 并告警）
    embedder = Embedder(effective, alert_fn=lambda message: print_fn(f"[ingest][alert] {message}"))
    vectors, embed_stats = embed_chunks(effective, chunks, embedder=embedder, print_fn=print_fn)

    # 5) 写库
    store = create_store(effective)
    if rebuild:
        store.reset()
        print_fn(f"[ingest] 已清空 collection={effective.chroma_collection}")
    written = store.add_chunks(chunks, vectors)
    backend = store.backend
    for warning in store.init_warnings:
        print_fn(f"[ingest][warn] {warning}")
    count = store.count()
    print_fn(f"[ingest] 写入 {written} 块（store={backend}，collection 总数 {count}）")

    # 6) 过期扫描（剔除可召回集合、保留归档）+ 审计
    expire = expire_scan(effective, store=store, print_fn=print_fn)

    stats_summary = chunk_stats(chunks, items)
    manifest = {
        "generated_at": datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds"),
        "source": source_desc,
        "knowledge_api": effective.knowledge_api,
        "sources": {
            "kb": {
                "items": len(kb_items),
                "status_counts": kb_status_counts,
                "category_counts": kb_category_counts,
                "path": source_desc,
            },
            "corpus": corpus_summary,
        },
        "items_fetched": len(items),
        "status_counts": _status_counts(items, "status"),
        "category_counts": _status_counts(items, "category"),
        "project_counts": _status_counts(items, "projectId"),
        "indexed_statuses": sorted({str(c.get("status") or "") for c in chunks}),
        "retrievable_statuses": list(effective.recall_status),
        "chunks": len(chunks),
        "chunks_written": written,
        "collection_count": count,
        "chunk_ids": [str(c["chunk_id"]) for c in chunks],
        "chunk_stats": stats_summary,
        "store_backend": backend,
        "mock_embed": bool(mock_embed),
        "embed_provider": effective.embed_provider,
        "embed_model": effective.embed_model,
        "embed_dim": effective.embed_dim,
        "embed_batch_size": effective.embed_batch_size,
        "embed": embed_stats,
        "expire_scan": expire,
        "knowledge_ids": sorted({c["knowledge_id"] for c in chunks}),
        "screening": {
            key: stats[key]
            for key in ("masked_phone", "masked_id", "masked_email", "masked_sensitive", "dropped_lines")
        },
        "chunking": {
            "chunk_size": effective.chunk_size,
            "chunk_overlap": effective.chunk_overlap,
            "chunk_min_length": effective.chunk_min_length,
            "dedup_threshold": effective.dedup_similarity,
            "before_dedup": stats["chunks_before_dedup"],
            "duplicates_dropped": stats["duplicates_dropped"],
        },
        "duration_ms": int((time.perf_counter() - started) * 1000),
        "llm_key_present": bool(effective.llm_api_key),
        "embed_key_present": bool(effective.embed_api_key),
        "masked_secrets": {
            "llm_api_key": mask_secret(effective.llm_api_key),
            "embed_api_key": mask_secret(effective.embed_api_key),
        },
    }
    manifest_path = effective.data_dir / "ingest-manifest.json"
    try:
        manifest_path.parent.mkdir(parents=True, exist_ok=True)
        manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
        print_fn(f"[ingest] manifest → {manifest_path}")
    except Exception as exc:  # pragma: no cover
        print_fn(f"[ingest][warn] manifest 写入失败：{exc}")

    audit_append(
        effective,
        "index",
        rebuild=bool(rebuild),
        source=source_desc,
        kb_items=len(kb_items),
        corpus_docs=len(corpus_items),
        items=len(items),
        chunks=len(chunks),
        chunks_written=written,
        collection_count=count,
        store_backend=backend,
        duration_ms=manifest["duration_ms"],
        embed_degraded=bool(embed_stats.get("degraded")),
    )
    return manifest


def build_parser() -> argparse.ArgumentParser:
    """CLI 参数（保持 W30 参数兼容，新增语料开关）。"""
    parser = argparse.ArgumentParser(description="RAG ingest：KB + 演示语料 → 切分/脱敏 → Embedding → ChromaDB")
    parser.add_argument("--knowledge-api", default="http://localhost:4000", help="Java 服务地址")
    parser.add_argument("--env-file", default=None, help="参考工程 .env（只读，密钥不入库）")
    parser.add_argument("--rebuild", action="store_true", help="清空 collection 重建")
    parser.add_argument("--mock-embed", action="store_true", help="使用 mock 哈希向量（离线）")
    parser.add_argument("--limit", type=int, default=None, help="只入库前 N 条 KB（corpus 不受限）")
    parser.add_argument("--source-json", default=None, help="从本地 JSON 取 KB（离线/测试）")
    parser.add_argument("--published-only", action="store_true", help="只索引 published（生产口径）")
    parser.add_argument("--no-corpus", action="store_true", help="不合并演示语料（W30 口径：仅 Java KB）")
    parser.add_argument("--corpus-dir", default=None, help="覆盖演示语料目录（默认 rag/corpus）")
    parser.add_argument("--chunk-size", type=int, default=None, help="覆盖 CHUNK_SIZE")
    parser.add_argument("--account", default=None, help="登录账号（默认 it-01）")
    parser.add_argument("--password", default=None, help="登录密码（默认 demo123）")
    return parser


def main(argv: list[str] | None = None) -> int:
    """CLI 入口；返回进程退出码。"""
    args = build_parser().parse_args(argv)
    overrides: dict[str, Any] = {}
    if args.knowledge_api:
        overrides["knowledge_api"] = args.knowledge_api
    if args.chunk_size:
        overrides["chunk_size"] = args.chunk_size
    if args.account:
        overrides["knowledge_account"] = args.account
    if args.password:
        overrides["knowledge_password"] = args.password
    if args.corpus_dir:
        overrides["corpus_dir"] = Path(args.corpus_dir)
    settings = load_settings(env_file=args.env_file, overrides=overrides)
    try:
        manifest = run(
            settings,
            rebuild=args.rebuild,
            mock_embed=args.mock_embed,
            limit=args.limit,
            source_json=args.source_json,
            published_only=args.published_only,
            corpus=not args.no_corpus,
        )
    except DataFetchError as exc:
        print(f"[ingest][error] {exc}", file=sys.stderr)
        return 2
    return 0 if manifest.get("chunks_written", 0) else 1


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
