"""检索作用域（W32）：项目隔离 + 生效期过滤
=============================================

W31 实测问题（`tests/rag/results/rag-poc-w31.json`）：
    - 跨项目干扰命中率 **93%**（29 条多项目题，同主题其它项目文档挤进 top5）；
    - 过期知识泄漏 0%（靠 `expire_scan` 把 `effectiveTo < today` 标 `status=expired`），
      但**召回层没有生效期兜底**：库里若残留 published+已过期条目仍会被召回。

W32 口径（设计 §4.5 项目作用域 + §5 检索前置过滤）：
    1. **强制项目作用域**：检索入口必须给出 `project_id`；
       未给出 → 默认平台级 `ALL` 并**打印 WARNING 日志**（不静默全库召回）；
       过滤值 = `project_id IN (所选项目…, 'ALL')`（`ALL` = 平台级知识，全项目可见）；
       需要做「过滤前/后」对照时才允许显式 `UNSCOPED = "*"`（会打 WARNING，仅评测/排障用）。
    2. **生效期兜底**：`effective_from <= today <= effective_to`（空 = 不限），
       且 `archived=false`；向量库 where 只过滤 status，日期这里做二次兜底
       （Chroma 的 where 对字符串日期比较能力有限，统一在应用层判定）。

本模块只做「口径」，不碰存储；`HybridRetriever` / `Retriever` / `service` 共用。
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from datetime import date
from typing import Sequence

from .config import Settings

#: 显式「不过滤项目」哨兵（仅评测对照/排障；会打 WARNING，禁止在生产请求里使用）
UNSCOPED = "*"

logger = logging.getLogger("rag.scope")


class DedupedLogger:
    """同一检索实例内同类 WARNING 只打印一次（评测 258 题/长跑服务不刷屏）。

    只代理 `warning(msg, *args)`；语义与实际日志一致（写入 `rag.scope` logger，
    `assertLogs` 可捕获），仅做**进程内去重**，不影响判定口径。
    """

    def __init__(self, target: logging.Logger | None = None) -> None:
        self.target = target or logger
        self._seen: set[str] = set()

    def warning(self, msg: str, *args: object) -> None:
        key = msg % args if args else msg
        if key in self._seen:
            return
        self._seen.add(key)
        self.target.warning(msg, *args)


def is_unscoped(project_id: str | Sequence[str] | None) -> bool:
    """是否为显式「不过滤项目」哨兵（`UNSCOPED` / 空串 之外的 None 不算）。"""
    return isinstance(project_id, str) and project_id.strip() == UNSCOPED


def normalize_projects(project_id: str | Sequence[str] | None) -> list[str]:
    """项目参数 → 去空去重列表（保持传入顺序）。"""
    if project_id is None:
        return []
    if isinstance(project_id, str):
        values = [project_id.strip()]
    else:
        values = [str(item).strip() for item in project_id]
    return [value for value in dict.fromkeys(values) if value]


def resolve_project_scope(
    project_id: str | Sequence[str] | None,
    settings: Settings,
    *,
    log: logging.Logger | None = None,
) -> tuple[str, ...]:
    """项目参数 → 向量库/BM25 的过滤值列表。

    输入：
        project_id : 请求项目（str / 列表）；None = 调用方没给；``UNSCOPED`` = 显式不过滤
        settings   : 配置（`global_project_id` / `default_project_id`）
        log        : 日志器（测试可注入；缺省 `rag.scope`）
    输出：
        - `()`        → **不过滤**（仅当显式 `UNSCOPED`；已打 WARNING）；
        - `("P-JZY", "ALL")` → 过滤 `project_id IN (...)`（平台级知识并集）。

    日志口径：
        - 未传 project_id → WARNING `project_scope_defaulted`（默认平台级 ALL）；
        - 显式 UNSCOPED → WARNING `project_scope_unscoped`（仅评测/排障）。
    """
    out = log or logger
    if is_unscoped(project_id):
        out.warning("project_scope_unscoped：显式关闭项目过滤（仅限评测/排障，生产请求禁止）")
        return ()
    values = normalize_projects(project_id)
    if not values:
        fallback = (settings.default_project_id or "").strip() or settings.global_project_id
        values = [fallback]
        out.warning(
            "project_scope_defaulted：未传 project_id → 使用 %s（平台级/默认项目），"
            "显式日志以避免静默全库召回",
            fallback,
        )
    global_id = (settings.global_project_id or "ALL").strip() or "ALL"
    if global_id not in values:
        values.append(global_id)
    return tuple(dict.fromkeys(values))


def scope_values(scope: tuple[str, ...]) -> list[str]:
    """过滤值 → 列表（空 = 不过滤）。"""
    return list(scope or ())


def within_effective_period(
    effective_from: str | None,
    effective_to: str | None,
    today: date | None = None,
) -> bool:
    """是否在生效期内（`YYYY-MM-DD` 字符串按 ISO 字典序可直接比较；空 = 不限）。

    输入：
        effective_from : 生效起始（空 = 无下界）
        effective_to   : 生效截止（空 = 无上界；含当天）
        today          : 判定基准日（缺省本地今天；测试可注入）
    输出：bool（格式非法的日期一律视为「无该边界」，不因脏数据把知识整体屏蔽）
    """
    today_iso = (today or date.today()).isoformat()
    start = (effective_from or "").strip()
    end = (effective_to or "").strip()
    if _looks_like_date(start) and start > today_iso:
        return False
    if _looks_like_date(end) and end < today_iso:
        return False
    return True


def _looks_like_date(value: str) -> bool:
    """粗略校验 `YYYY-MM-DD`（长度 10 且可被 date 解析）。"""
    if len(value) != 10 or value[4] != "-" or value[7] != "-":
        return False
    try:
        date.fromisoformat(value)
        return True
    except ValueError:
        return False


def is_recallable(chunk, today: date | None = None) -> bool:  # noqa: ANN001 - RetrievedChunk（避免循环导入）
    """召回兜底：未归档 + 在生效期内（status 过滤由向量库 where 完成）。"""
    if getattr(chunk, "archived", False):
        return False
    return within_effective_period(
        getattr(chunk, "effective_from", ""),
        getattr(chunk, "effective_to", ""),
        today=today,
    )


@dataclass
class ScopeInfo:
    """一次检索的作用域快照（写进结果/日志/评测 JSON，便于审计）。"""

    project_id: str = ""
    values: tuple[str, ...] = ()

    @property
    def unscoped(self) -> bool:
        """无过滤值 ⇔ 显式 UNSCOPED（不过滤项目）。"""
        return not self.values

    @property
    def label(self) -> str:
        """审计标签：无过滤值 ⇔ 不过滤（UNSCOPED）。"""
        return ",".join(self.values) if self.values else "UNSCOPED"

    def to_dict(self) -> dict[str, object]:
        return {
            "project_id": self.project_id,
            "values": list(self.values),
            "unscoped": self.unscoped,
            "label": self.label,
        }


def describe(scope: tuple[str, ...], project_id: str | Sequence[str] | None = None) -> ScopeInfo:
    """构造作用域快照。"""
    values = tuple(scope or ())
    raw = project_id if isinstance(project_id, str) else (",".join(project_id) if project_id else "")
    return ScopeInfo(project_id=str(raw or ""), values=values)


__all__ = [
    "UNSCOPED",
    "DedupedLogger",
    "ScopeInfo",
    "describe",
    "is_recallable",
    "is_unscoped",
    "normalize_projects",
    "resolve_project_scope",
    "scope_values",
    "within_effective_period",
]
