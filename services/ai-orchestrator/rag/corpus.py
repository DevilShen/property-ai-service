"""演示语料加载（`rag/corpus/*.md`，W31）
========================================

职责：把 `rag/corpus/` 下的虚构演示文档解析成与 Java `GET /api/v1/knowledge`
**同构**的 item（knowledgeId/title/content/status/...），供 ingest / reindex /
build_golden 复用同一条切分与元数据链路。

文档格式（Markdown + 极简 front matter，不引入 PyYAML 依赖）：

    # 演示语料（mock），非知识库正式数据
    ---
    knowledgeId: C-YQY-001
    title: 云栖苑 物业费单价与计费口径
    projectId: P-YQY
    category: fee
    status: published
    version: V2.0
    owner: 财务主管
    effectiveFrom: 2026-01-01
    effectiveTo:            # 空 = 长期有效
    serviceScope: 住宅
    source: 演示物业服务合同 §3.1
    demo: true
    ---
    正文（Markdown：标题层级 / 条款 / 表格 / 代码块 / 问答对均可）

约束（AGENTS.md §2 NEVER #8 / #11）：
    - 语料**全部虚构**：无真实手机号/身份证/姓名/真实价格承诺；项目名仅用演示项目
      （P-YQY 云栖苑 / P-JZY 金竹苑 / P-LVY 绿苑，见 shared DEMO_PROJECTS）；
    - `demo: true` + 文件头注释声明「演示语料（mock），非知识库正式数据」；
    - 加载时用 `screener.contains_pii()` 做一次自检，命中即抛错（不能把 PII 带进索引）。
"""

from __future__ import annotations

import re
from pathlib import Path
from typing import Any

from .screener import contains_pii

# 元数据白名单：front matter 只接受这些键（未知键忽略并记录，避免拼写错误静默生效）
KNOWN_KEYS = frozenset(
    {
        "knowledgeId",
        "title",
        "projectId",
        "category",
        "status",
        "version",
        "owner",
        "effectiveFrom",
        "effectiveTo",
        "serviceScope",
        "source",
        "demo",
    }
)
REQUIRED_KEYS = ("knowledgeId", "title", "projectId", "category", "status")
VALID_STATUS = frozenset({"published", "draft", "pending_review", "rejected", "expired"})
_FRONT_MATTER_RE = re.compile(r"\A---\s*\n(.*?)\n---\s*\n?", re.DOTALL)
# 允许 front matter 前有「演示语料」说明注释行（# / > 注释，供人工阅读；不入正文）
_LEADING_NOTE_RE = re.compile(r"\A(?:[ \t]*(?:#[^\n]*|>[^\n]*)\n)+")
_DEMO_NOTE = "演示语料（mock），非知识库正式数据"


class CorpusError(ValueError):
    """语料格式错误（缺字段/重复 knowledgeId/含 PII）。"""


def _strip_quotes(value: str) -> str:
    value = value.strip()
    if len(value) >= 2 and value[0] == value[-1] and value[0] in {'"', "'"}:
        return value[1:-1]
    return value


def parse_front_matter(text: str) -> tuple[dict[str, str], str]:
    """解析 `---` 包裹的极简 YAML（key: value），返回 (元数据, 正文)。

    输入：文档全文
    输出：(meta, body)；无 front matter 时 meta={} 且 body=原文
    """
    raw = (text or "").replace("\r\n", "\n").replace("\r", "\n")
    raw = _LEADING_NOTE_RE.sub("", raw, count=1)  # 去掉文件头的「演示语料」说明注释
    match = _FRONT_MATTER_RE.match(raw)
    if not match:
        return {}, raw.strip()
    meta: dict[str, str] = {}
    for line in match.group(1).split("\n"):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if ":" not in line:
            continue
        key, _, value = line.partition(":")
        key = key.strip()
        value = _strip_quotes(value.split("  #")[0])
        if key:
            meta[key] = value
    return meta, raw[match.end() :].strip()


def parse_document(text: str, path: str | Path = "") -> dict[str, Any] | None:
    """解析单篇 Markdown 语料 → knowledge item；无 front matter 返回 None（如 README）。

    输入：
        text : 文档全文
        path : 文件路径（仅用于错误信息/来源展示）
    输出：item dict（与 Java API item 同构，额外带 demo=True）
    异常：缺必需字段 / 状态非法 / 含明文 PII → CorpusError
    """
    meta, body = parse_front_matter(text)
    if not meta:
        return None
    missing = [key for key in REQUIRED_KEYS if not meta.get(key)]
    if missing:
        raise CorpusError(f"{path} 缺少必需元数据：{missing}")
    status = meta.get("status", "").strip()
    if status not in VALID_STATUS:
        raise CorpusError(f"{path} status 非法：{status!r}（允许 {sorted(VALID_STATUS)}）")
    if not body.strip():
        raise CorpusError(f"{path} 正文为空")
    demo = str(meta.get("demo", "")).strip().lower() in {"1", "true", "yes", "y"}
    if not demo:
        raise CorpusError(f"{path} 缺少 demo: true 声明（演示语料必须标注 mock，禁止伪装正式数据）")
    if contains_pii(body) or contains_pii(meta.get("title", "")):
        raise CorpusError(f"{path} 含明文手机号/身份证/邮箱（演示语料必须虚构脱敏）")

    item: dict[str, Any] = {
        "knowledgeId": meta["knowledgeId"].strip(),
        "title": meta["title"].strip(),
        "projectId": meta["projectId"].strip(),
        "category": meta["category"].strip(),
        "status": status,
        "version": meta.get("version", "").strip(),
        "owner": meta.get("owner", "").strip() or "知识运营",
        "effectiveFrom": meta.get("effectiveFrom", "").strip(),
        "effectiveTo": meta.get("effectiveTo", "").strip(),
        "serviceScope": meta.get("serviceScope", "").strip() or "平台",
        "source": meta.get("source", "").strip() or f"演示语料 {meta['knowledgeId']}",
        "content": body,
        "demo": demo,
        "badcase": False,
        "refs": [],
    }
    return item


def load_corpus(corpus_dir: str | Path) -> list[dict[str, Any]]:
    """加载目录下全部 `*.md` 语料（跳过 README/无 front matter 的说明文件）。

    输入：corpus_dir（不存在 → 返回空列表，由调用方决定是否报错）
    输出：item 列表（按 knowledgeId 排序）
    异常：格式错误 / knowledgeId 重复 / 含 PII → CorpusError（不静默跳过坏文件）
    """
    directory = Path(corpus_dir)
    if not directory.is_dir():
        return []
    items: list[dict[str, Any]] = []
    seen: dict[str, Path] = {}
    for path in sorted(directory.glob("*.md")):
        item = parse_document(path.read_text(encoding="utf-8"), path)
        if item is None:
            continue
        kid = item["knowledgeId"]
        if kid in seen:
            raise CorpusError(f"knowledgeId 重复：{kid}（{seen[kid].name} 与 {path.name}）")
        seen[kid] = path
        items.append(item)
    return sorted(items, key=lambda entry: str(entry.get("knowledgeId") or ""))


def corpus_stats(items: list[dict[str, Any]]) -> dict[str, Any]:
    """语料统计：条数 / 项目 / 分类 / 状态 / 版本 / 生效期 / 正文字符。

    输入：corpus item 列表
    输出：可写入报告/JSON 的统计 dict（不含正文）
    """
    by_project: dict[str, int] = {}
    by_category: dict[str, int] = {}
    by_status: dict[str, int] = {}
    by_version: dict[str, int] = {}
    lengths: list[int] = []
    expired: list[str] = []
    for item in items:
        by_project[str(item.get("projectId") or "")] = by_project.get(str(item.get("projectId") or ""), 0) + 1
        by_category[str(item.get("category") or "")] = by_category.get(str(item.get("category") or ""), 0) + 1
        by_status[str(item.get("status") or "")] = by_status.get(str(item.get("status") or ""), 0) + 1
        by_version[str(item.get("version") or "")] = by_version.get(str(item.get("version") or ""), 0) + 1
        lengths.append(len(str(item.get("content") or "")))
        effective_to = str(item.get("effectiveTo") or "").strip()
        if effective_to and effective_to < _today():
            expired.append(str(item.get("knowledgeId") or ""))
    return {
        "total": len(items),
        "by_project": by_project,
        "by_category": by_category,
        "by_status": by_status,
        "by_version": by_version,
        "demo_docs": sum(1 for item in items if item.get("demo")),
        "long_docs_ge_2000": sum(1 for length in lengths if length >= 2000),
        "content_chars": {"min": min(lengths) if lengths else 0, "max": max(lengths) if lengths else 0},
        "expired_by_date": expired,
        "demo_note": _DEMO_NOTE,
    }


def _today() -> str:
    """今天（本地日期，YYYY-MM-DD）；测试可 monkeypatch。"""
    from datetime import date

    return date.today().isoformat()
