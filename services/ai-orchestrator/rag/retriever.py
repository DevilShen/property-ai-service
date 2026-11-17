"""上下文排版与检索器（设计 §5 / §6）
=====================================

职责：
    1. `Retriever.search`：纯向量检索基线（消融对照用）；
    2. `format_context`：把命中片段排成 `[n] 来源：…｜分类：…｜标题：…｜相似度：0.83`
       + 正文，总字符 ≤ CONTEXT_MAX_CHARS（至少保留第一条），并**返回实际入选片段**，
       保证生成答案里的 `[n]` 与来源卡片一一对应（防引用错位）。

W32：`search` 增加**强制项目作用域** `project_id`（未传 → 默认平台级 ALL + WARNING 日志），
并把 `project_id IN (项目…, 'ALL')`、`status=published`、生效期三件套下推到检索；
另做生效期应用层兜底（`rag.scope.is_recallable`）。
"""

from __future__ import annotations

from .config import Settings, category_name
from .embedder import Embedder
from .scope import DedupedLogger, ScopeInfo, describe, is_recallable, resolve_project_scope
from .store import RetrievedChunk, VectorStore


class Retriever:
    """纯向量检索器（基线；生产主链路用 HybridRetriever）。

    输入：settings / store / embedder（缺省按配置构造）
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
        #: 最近一次检索的作用域快照（服务/评测可读，便于审计）
        self.last_scope: ScopeInfo = ScopeInfo()
        #: 作用域告警去重（长跑评测/服务不刷屏；语义与 rag.scope WARNING 一致）
        self._scope_log = DedupedLogger()

    def search(
        self,
        question: str,
        top_k: int | None = None,
        category: str | list[str] | None = None,
        status: str | list[str] | None = None,
        score_threshold: float | None = None,
        project_id: str | list[str] | None = None,
        *,
        project: str | list[str] | None = None,
    ) -> list[RetrievedChunk]:
        """向量检索（cosine，按相似度降序）。

        输入：
            question        : 用户问题
            top_k           : 返回条数（缺省 settings.top_k）
            category        : 分类过滤
            status          : 状态过滤（缺省 settings.recall_status = published）
            score_threshold : 相似度下限（缺省按 mock/真实自动选）
            project_id      : **项目作用域**（str/列表；``"*"`` = 显式不过滤，仅评测/排障）
            project         : W31 兼容别名（等价 project_id）
        输出：RetrievedChunk 列表（已按项目作用域 + 生效期兜底过滤）
        """
        question = (question or "").strip()
        requested = project_id if project_id is not None else project
        scope = resolve_project_scope(requested, self.settings, log=self._scope_log)
        self.last_scope = describe(scope, requested)
        if not question:
            return []
        threshold = self.settings.effective_score_threshold if score_threshold is None else score_threshold
        vector = self.embedder.embed_query(question)
        chunks = self.store.query(
            query_embedding=vector,
            top_k=int(top_k or self.settings.top_k),
            category=category,
            status=status if status is not None else self.settings.recall_status,
            score_threshold=threshold,
            project=scope or None,
        ).chunks
        return [chunk for chunk in chunks if is_recallable(chunk)]


def format_source(chunk: RetrievedChunk) -> str:
    """来源展示串：`知识ID V版本` 或 source 字段。"""
    if chunk.source:
        return chunk.source
    parts = [chunk.knowledge_id, chunk.version]
    return " ".join(p for p in parts if p) or "-"


def format_header(index: int, chunk: RetrievedChunk) -> str:
    """单条片段的编号头：`[n] 来源：…｜分类：…｜标题：…｜相似度：0.83`。"""
    return (
        f"[{index}] 来源：{format_source(chunk)}｜分类：{chunk.category_name or category_name(chunk.category)}"
        f"｜标题：{chunk.title or '-'}｜相似度：{chunk.score:.2f}"
    )


def format_context(
    chunks: list[RetrievedChunk],
    max_chars: int = 6000,
) -> tuple[str, list[RetrievedChunk]]:
    """把检索结果排版成 LLM 上下文。

    输入：
        chunks    : 检索结果（已按最终顺序）
        max_chars : 正文总字符上限（缺省 settings.context_max_chars=6000）
    输出：(上下文文本, 实际入选片段列表)

    规则：
        - 编号从 1 连续递增，与实际入选片段严格一致；
        - 至少保留第一条（即使单条超限，也不能给模型空上下文）；
        - 超限后按排名截断，不截断单条正文（保引用完整性）。
    """
    max_chars = int(max_chars or 6000)
    blocks: list[str] = []
    used: list[RetrievedChunk] = []
    total = 0
    for index, chunk in enumerate(chunks, start=1):
        block = f"{format_header(index, chunk)}\n{chunk.text.strip()}"
        if used and total + len(block) > max_chars:
            break
        blocks.append(block)
        used.append(chunk)
        total += len(block) + 2  # 块间空行
    return "\n\n".join(blocks), used
