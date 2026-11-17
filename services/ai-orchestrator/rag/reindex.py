"""reindex CLI：增量索引 / 下架 / 过期扫描 / 版本替代（W31）
=============================================================

用法（在 `services/ai-orchestrator` 目录）：
    python -m rag.reindex --knowledge-id K-002                 # 增量重建一条知识（KB）
    python -m rag.reindex --knowledge-id C-YQY-001             # 增量重建一条演示语料
    python -m rag.reindex --knowledge-id K-002 --remove        # 下架（删除该知识全部 chunk）
    python -m rag.reindex --expire-scan                       # 扫描过期 → 归档（保留、移出召回）
    python -m rag.reindex --knowledge-id K-002 --supersede V2.0  # 版本替代（旧版本归档）
    python -m rag.reindex --history 20                        # 查看最近审计（JSONL）
    python -m rag.reindex --knowledge-id K-002 --env-file "<REF_RAG_PROJECT>\\.env"

契约：
    - 只动索引（`.data/rag/chroma` + 审计 `.data/rag/index-audit.jsonl`），不改 Java 知识库；
    - 过期/替代只「归档不删除」（status→expired/superseded + archived=true），可回溯；
    - 审计 JSONL 每行一条，不含密钥与正文；
    - 退出码：0 成功；2 取数/知识不存在；3 其它错误。
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

from .config import load_settings
from .indexer import (
    expire_scan,
    read_audit,
    reindex_knowledge,
    remove_knowledge,
    supersede,
)
from .store import create_store


def build_parser() -> argparse.ArgumentParser:
    """CLI 参数。"""
    parser = argparse.ArgumentParser(description="RAG 增量索引：reindex / remove / expire-scan / supersede")
    parser.add_argument("--knowledge-id", default=None, help="知识 ID（K-* 走 Java KB；C-* 走 corpus）")
    parser.add_argument("--remove", action="store_true", help="下架：删除该知识全部 chunk")
    parser.add_argument("--supersede", default=None, metavar="VERSION", help="用指定版本替代旧版本（旧版本归档）")
    parser.add_argument("--expire-scan", action="store_true", help="扫描过期条目 → status=expired + 归档")
    parser.add_argument("--now", default=None, help="过期扫描基准日（YYYY-MM-DD，测试/回放用）")
    parser.add_argument("--history", type=int, default=None, metavar="N", help="打印最近 N 条审计记录")
    parser.add_argument("--env-file", default=None, help="参考工程 .env（只读，密钥不入库）")
    parser.add_argument("--knowledge-api", default=None, help="Java 服务地址")
    parser.add_argument("--mock-embed", action="store_true", help="mock embedding（离线）")
    parser.add_argument("--json", action="store_true", help="以 JSON 输出结果（便于脚本解析）")
    return parser


def main(argv: list[str] | None = None) -> int:
    """CLI 入口；返回进程退出码。"""
    args = build_parser().parse_args(argv)
    overrides: dict[str, Any] = {}
    if args.knowledge_api:
        overrides["knowledge_api"] = args.knowledge_api
    if args.mock_embed:
        overrides["embed_provider"] = "mock"
    settings = load_settings(env_file=args.env_file, overrides=overrides)

    try:
        store = create_store(settings)
        if args.history:
            rows = read_audit(settings, limit=args.history)
            if args.json:
                print(json.dumps(rows, ensure_ascii=False, indent=2))
            else:
                for row in rows:
                    print(json.dumps(row, ensure_ascii=False))
            return 0

        results: list[dict[str, Any]] = []
        if args.expire_scan or not (args.knowledge_id or args.history):
            results.append(expire_scan(settings, store=store, now=args.now))
        if args.knowledge_id and args.remove:
            results.append(remove_knowledge(settings, args.knowledge_id, store=store))
        elif args.knowledge_id and args.supersede:
            results.append(supersede(settings, args.knowledge_id, args.supersede, store=store))
        elif args.knowledge_id:
            results.append(reindex_knowledge(settings, args.knowledge_id, store=store))

        if not results:
            print("[reindex][error] 未指定动作：请给 --knowledge-id / --expire-scan / --history", file=sys.stderr)
            return 2
        summary = {"results": results, "audit_path": str(settings.audit_path), "store_backend": store.backend}
        if args.json:
            print(json.dumps(summary, ensure_ascii=False, indent=2))
        else:
            print(
                f"[reindex] 完成 {len(results)} 项动作；store={store.backend}"
                f"（collection 共 {store.count()} 块）；审计 → {settings.audit_path}"
            )
        return 0
    except LookupError as exc:
        print(f"[reindex][error] {exc}", file=sys.stderr)
        return 2
    except Exception as exc:  # noqa: BLE001 - CLI 层统一错误出口
        print(f"[reindex][error] {type(exc).__name__}: {exc}", file=sys.stderr)
        return 3


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
