"""筛选与脱敏模块（设计 §3 / §4 / §9）
=====================================

职责（索引前必过）：
    1. **脱敏**：手机号 / 身份证号 / 邮箱（可选）→ 掩码，保留可读性；
    2. **敏感词**：命中替换为 MASK_TOKEN；
    3. **清洗**：去页眉页脚/页码行、空白归一；
    4. **去重**：chunk 间 Jaccard（char-bigram）≥ 0.9 视为重复丢弃；
    5. **自检**：`contains_pii()` 供测试/上线前校验「仓库与索引无明文 PII」。

不可退让：知识原文可能含真实手机号（P1 用 20 户示例数据也视为准真实），
脱敏必须在**入库前**完成，日志不得输出正文。
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field

# 手机号：中国大陆 11 位，前后不接数字
_PHONE_RE = re.compile(r"(?<!\d)1[3-9]\d{9}(?!\d)")
# 身份证：18 位（末位可为 X）或 15 位
_ID18_RE = re.compile(r"(?<!\d)\d{17}[\dXx](?!\d)")
_ID15_RE = re.compile(r"(?<!\d)\d{15}(?!\d)")
# 邮箱（可选脱敏，PoC 默认开）
_EMAIL_RE = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")
# 页眉页脚 / 页码 / 水印类整行
_FOOTER_LINE_RE = re.compile(
    r"^\s*(?:第\s*\d+\s*页(?:\s*[/／]\s*(?:共\s*)?\d+\s*页)?|共\s*\d+\s*页|page\s*\d+(?:\s*of\s*\d+)?|-\s*\d+\s*-)\s*$",
    re.IGNORECASE,
)
_WS_RE = re.compile(r"[ \t\u3000]+")
_MULTI_NL_RE = re.compile(r"\n{3,}")


def mask_phone(text: str) -> str:
    """手机号掩码：138****5678 → 138****5678。"""
    return _PHONE_RE.sub(lambda m: f"{m.group(0)[:3]}****{m.group(0)[-4:]}", text or "")


def mask_id_card(text: str) -> str:
    """身份证掩码：110101********1234 → 110101********1234（18 位）。"""
    def _m18(m: re.Match[str]) -> str:
        s = m.group(0)
        return f"{s[:6]}********{s[-4:]}"

    def _m15(m: re.Match[str]) -> str:
        s = m.group(0)
        return f"{s[:6]}*****{s[-4:]}"

    text = _ID18_RE.sub(_m18, text or "")
    return _ID15_RE.sub(_m15, text)


def mask_email(text: str) -> str:
    """邮箱掩码：a.b@x.com → a***@x.com。"""
    def _m(m: re.Match[str]) -> str:
        local, _, domain = m.group(0).partition("@")
        keep = local[:1] if local else ""
        return f"{keep}***@{domain}"

    return _EMAIL_RE.sub(_m, text or "")


def strip_headers_footers(text: str) -> str:
    """去掉页眉页脚/页码整行（不误删正文中的「第X页」引用句）。"""
    lines = [line for line in (text or "").split("\n") if not _FOOTER_LINE_RE.match(line)]
    return "\n".join(lines)


def normalize_whitespace(text: str) -> str:
    """空白归一：统一换行、压缩行内连续空格、连续空行压成一行空行。"""
    text = (text or "").replace("\r\n", "\n").replace("\r", "\n")
    text = _WS_RE.sub(" ", text)
    text = "\n".join(line.strip() for line in text.split("\n"))
    text = _MULTI_NL_RE.sub("\n\n", text)
    return text.strip()


@dataclass
class ScreenResult:
    """筛选结果：脱敏后的文本 + 命中统计（只记计数，不记原文）。"""

    text: str
    masked_phone: int = 0
    masked_id: int = 0
    masked_email: int = 0
    masked_sensitive: int = 0
    dropped_lines: int = 0

    @property
    def masked_total(self) -> int:
        return self.masked_phone + self.masked_id + self.masked_email + self.masked_sensitive

    def to_dict(self) -> dict[str, int]:
        return {
            "masked_phone": self.masked_phone,
            "masked_id": self.masked_id,
            "masked_email": self.masked_email,
            "masked_sensitive": self.masked_sensitive,
            "dropped_lines": self.dropped_lines,
            "masked_total": self.masked_total,
        }


def screen(
    text: str,
    sensitive_words: tuple[str, ...] | list[str] = (),
    mask_token: str = "***",
    mask_email_enabled: bool = True,
) -> ScreenResult:
    """对一段文本做完整脱敏 + 清洗。

    输入：
        text              : 原文
        sensitive_words   : 敏感词表（命中替换为 mask_token）
        mask_token        : 掩码占位符
        mask_email_enabled: 是否脱敏邮箱
    输出：ScreenResult（text 已清洗；计数只记次数）。
    """
    raw = text or ""
    masked_email = 0
    if mask_email_enabled:
        masked_email = len(_EMAIL_RE.findall(raw))
        raw = mask_email(raw)

    masked_phone = len(_PHONE_RE.findall(raw))
    raw = mask_phone(raw)

    masked_id = len(_ID18_RE.findall(raw)) + len(_ID15_RE.findall(raw))
    raw = mask_id_card(raw)

    masked_sensitive = 0
    for word in sensitive_words or ():
        w = (word or "").strip()
        if not w:
            continue
        masked_sensitive += raw.count(w)
        raw = raw.replace(w, mask_token)

    before_lines = len(raw.split("\n"))
    raw = strip_headers_footers(raw)
    dropped = before_lines - len(raw.split("\n"))
    raw = normalize_whitespace(raw)

    return ScreenResult(
        text=raw,
        masked_phone=masked_phone,
        masked_id=masked_id,
        masked_email=masked_email,
        masked_sensitive=masked_sensitive,
        dropped_lines=max(dropped, 0),
    )


def contains_pii(text: str) -> bool:
    """快速自检：文本是否仍含明文手机号/身份证/邮箱（True = 有风险）。"""
    text = text or ""
    return bool(_PHONE_RE.search(text) or _ID18_RE.search(text) or _ID15_RE.search(text) or _EMAIL_RE.search(text))


def char_ngrams(text: str, n: int = 2) -> set[str]:
    """字符 n-gram 集合（去空白、小写），用于 Jaccard 相似度。"""
    compact = re.sub(r"\s+", "", (text or "").lower())
    if len(compact) < n:
        return {compact} if compact else set()
    return {compact[i : i + n] for i in range(len(compact) - n + 1)}


def jaccard_similarity(a: str, b: str, n: int = 2) -> float:
    """两段文本的字符 n-gram Jaccard 相似度（0—1）。"""
    ga, gb = char_ngrams(a, n), char_ngrams(b, n)
    if not ga and not gb:
        return 1.0
    if not ga or not gb:
        return 0.0
    return len(ga & gb) / len(ga | gb)


def dedup_chunks(
    chunks: list[dict],
    threshold: float = 0.9,
    text_key: str = "text",
) -> tuple[list[dict], int]:
    """按文本 Jaccard 去重（保留首次出现）。

    输入：
        chunks    : chunk dict 列表（需含 text_key 字段）
        threshold : 相似度阈值（≥ 视为重复）
        text_key  : 文本字段名
    输出：(保留的 chunk 列表, 丢弃数量)
    """
    kept: list[dict] = []
    for chunk in chunks:
        text = str(chunk.get(text_key) or "")
        if any(jaccard_similarity(text, str(prev.get(text_key) or "")) >= threshold for prev in kept):
            continue
        kept.append(chunk)
    return kept, len(chunks) - len(kept)
