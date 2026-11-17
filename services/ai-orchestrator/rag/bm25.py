"""BM25 稀疏检索（中文 char-bigram + 数字/英文词）
=================================================

为什么用 char-bigram：中文无空格，纯 BM25 需要分词（jieba 未安装）。char-bigram
对「专名/数字/条款号」这类精确词召回很好，且零依赖、可解释，与设计 §5「关键词
BM25（标题 > 条款 > 正文）」的替代口径一致（W32 再换 jieba + 业务词典）。

公式：score(D,Q) = Σ IDF(qi) · f(qi,D)·(k1+1) / (f(qi,D) + k1·(1-b+b·|D|/avgdl))
     IDF(qi) = ln((N - df + 0.5)/(df + 0.5) + 1)
     k1=1.5、b=0.75（设计 §4 参数）
"""

from __future__ import annotations

import math
import re
from collections import defaultdict
from typing import Iterable

_WS_RE = re.compile(r"\s+")
# 数字（含小数/千分位）/ 英文词：单独作为 token，避免 bigram 把 "0.38" 切碎后失配
_ALNUM_RE = re.compile(r"[a-z0-9]+(?:\.[0-9]+)?")


def tokenize(text: str) -> list[str]:
    """文本 → BM25 词序列（char-bigram + 数字/英文词）。

    输入：任意文本
    输出：词列表；空文本返回 []
    """
    raw = text or ""
    compact = _WS_RE.sub("", raw).lower()
    tokens: list[str] = []
    if compact:
        if len(compact) == 1:
            tokens.append(compact)
        else:
            tokens.extend(compact[i : i + 2] for i in range(len(compact) - 1))
    tokens.extend(_ALNUM_RE.findall(raw.lower()))
    return tokens


class BM25Index:
    """BM25 倒排索引。

    输入：
        k1 : 词频饱和系数（默认 1.5）
        b  : 文档长度归一化（默认 0.75）
    """

    def __init__(self, k1: float = 1.5, b: float = 0.75) -> None:
        self.k1 = k1
        self.b = b
        self._docs: list[tuple[str, str, str, str, str]] = []  # (chunk_id, text, category, status, project)
        self._tf: list[dict[str, int]] = []
        self._len: list[int] = []
        self._df: defaultdict[str, int] = defaultdict(int)
        self._avg_len: float = 0.0
        self._built = False

    @property
    def size(self) -> int:
        """索引中的文档数。"""
        return len(self._docs)

    def build(self, records: Iterable[tuple]) -> None:
        """建索引。

        输入：records = (chunk_id, text, category, status[, project]) 可迭代
              （W30 的 4 元组保持兼容；W31 可传第 5 项 project_id 支持项目过滤）
        输出：无
        """
        self._docs = []
        for record in records:
            values = list(record)
            if len(values) == 4:
                values.append("")
            self._docs.append((str(values[0]), str(values[1]), str(values[2]), str(values[3]), str(values[4])))
        self._tf = []
        self._len = []
        self._df = defaultdict(int)
        total = 0
        for _cid, text, _cat, _status, _project in self._docs:
            tokens = tokenize(text)
            tf: dict[str, int] = {}
            for token in tokens:
                tf[token] = tf.get(token, 0) + 1
            self._tf.append(tf)
            self._len.append(len(tokens))
            total += len(tokens)
            for token in tf:
                self._df[token] += 1
        self._avg_len = total / len(self._docs) if self._docs else 0.0
        self._built = True

    def _idf(self, term: str) -> float:
        n = len(self._docs)
        df = self._df.get(term, 0)
        return math.log((n - df + 0.5) / (df + 0.5) + 1.0)

    def _score(self, query_terms: set[str], doc_idx: int) -> float:
        tf = self._tf[doc_idx]
        norm = 1.0 - self.b + self.b * (self._len[doc_idx] / self._avg_len if self._avg_len else 1.0)
        score = 0.0
        for term in query_terms:
            freq = tf.get(term, 0)
            if freq == 0:
                continue
            score += self._idf(term) * (freq * (self.k1 + 1.0)) / (freq + self.k1 * norm)
        return score

    @staticmethod
    def _match(value: str, allowed: str | list[str] | tuple[str, ...] | None) -> bool:
        """过滤辅助：None/"all" 全部通过；str 单值；序列白名单。"""
        if not allowed or allowed == "all":
            return True
        if isinstance(allowed, str):
            return value == allowed
        return value in allowed

    @staticmethod
    def _match_project(value: str, allowed: str | list[str] | tuple[str, ...] | None) -> bool:
        """项目过滤（W32）：口径同向量库 `project_id IN (项目…, 'ALL')`。

        兼容：**空 project_id 视为平台级知识**（W30 的 4 元组记录没有项目字段，
        等价于平台级、全项目可见）；真实索引每条都带 project_id，不触发该分支。
        """
        if not allowed or allowed == "all":
            return True
        if not (value or "").strip():
            return True
        return BM25Index._match(value, allowed)

    def search(
        self,
        query: str,
        top_k: int = 5,
        category: str | list[str] | None = None,
        status: str | list[str] | None = None,
        project: str | list[str] | None = None,
    ) -> list[tuple[str, float]]:
        """检索。

        输入：
            query    : 查询文本
            top_k    : 返回条数
            category : 分类过滤
            status   : 状态过滤（PoC 传 ("published",)）
            project  : 项目过滤（W31；None/"all" 不过滤，序列白名单）
        输出：[(chunk_id, score)]，按分数降序；分数为 0 的不返回。
        """
        if not self._built:
            return []
        query_terms = set(tokenize(query))
        if not query_terms or not self._docs:
            return []
        scored: list[tuple[str, float]] = []
        for idx, (cid, _text, cat, st, proj) in enumerate(self._docs):
            if not self._match(cat, category) or not self._match(st, status) or not self._match_project(proj, project):
                continue
            score = self._score(query_terms, idx)
            if score > 0:
                scored.append((cid, score))
        scored.sort(key=lambda item: -item[1])
        return scored[:top_k]
