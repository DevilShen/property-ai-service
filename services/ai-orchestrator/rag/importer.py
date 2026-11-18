"""知识导入编排层（P0/P1，task-85）：解析 → 归一化 → 脱敏 → 切分 → 去重 / 索引 / 撤销
============================================================================================

职责（设计 `docs/design/知识导入方案.md` §3 / §5.2）：
    业务人员在页面「粘贴文本」或「上传 md/txt/docx/pdf」后，Java BFF 调用本模块暴露的内部端点：
        preview        ：解析 + 归一化 + PII 脱敏 + 结构感知切分 + 去重提示 → 预览（不入库）
        index_knowledge：单知识 upsert（写入 Chroma + 元数据；同 knowledgeId+checksum 幂等跳过）
        remove_knowledge：撤销（删除该知识全部 chunk）
    Java 只做鉴权/项目隔离/配额/任务状态机/审计，**解析与切分能力集中在 Python 编排层**。

分步口径：
    1. **解析**（不可信输入，先校验）
        - `md` / `txt`：直读文本（bytes 走 utf-8-sig → gb18030 回退；含 NUL 视为二进制拒绝）；
        - `docx`：`python-docx`，按文档顺序遍历「段落 + 表格」；标题样式（Heading N/标题 N）
          转 Markdown `#`×N 保留层级；表格转 Markdown 行组（表头 + `---` 分隔 + 数据行）；
        - `pdf`：`pypdf` 逐页提取文本；空文本页告警；整篇无文本（扫描件）→ `ImportParseError`；
          加密 PDF 先尝试空口令，仍失败 → `ImportParseError(reason="pdf_encrypted")`。
    2. **归一化**：全角字母/数字→半角（**保留中文全角标点**）、统一换行、去控制字符/零宽字符、
       清 `第N页 / Page N of M / N/M` 页码行、按「每页首/末行重复出现」清页眉页脚、
       压缩连续空白与空行；Markdown 标题行原样保留（结构供 chunker 使用）。
    3. **PII**：复用 `screener.screen`（手机号/身份证/邮箱 → 掩码），只返回**命中计数** `piiMaskedCount`。
    4. **切分**：复用 `chunker.chunk_text`（500/80/30），返回 `[{seq, sectionPath, text, charCount}]`。
    5. **去重**：归一化脱敏文本 `sha256`；与现有索引（`store.all_chunks()`）做精确命中
       （知识级 `content_sha256`/`doc_checksum` 相等、或 chunk 级 `source_hash` 相等）→ `similarity=1.0`；
       可选近似：新文本首块 embedding 与向量库 top5 余弦 ≥ 0.95 → 重复提示（失败只告警，不阻断预览）。
    6. **安全限制**：单文件 ≤10MB、字符 ≤200000、解析超时 30s、扩展名白名单 txt/md/docx/pdf。

错误映射（由 `rag/service.py` 转 HTTP）：
    BadRequestError     → 400 `bad_request`
    PayloadTooLargeError→ 413 `payload_too_large`
    ImportParseError    → 422 `parse_failed`（带 `reason` 机器可读原因）
"""

from __future__ import annotations

import base64
import binascii
import hashlib
import io
import math
import re
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from concurrent.futures import TimeoutError as FuturesTimeoutError
from dataclasses import dataclass, field
from datetime import date, datetime, timezone
from pathlib import Path
from typing import Any, Callable

from .chunker import chunk_text, is_heading
from .config import Settings
from .screener import screen
from .store import BaseStore, RetrievedChunk, create_store, source_hash

# --------------------------------------------------------------------------- 限制常量

MAX_FILE_BYTES = 10 * 1024 * 1024  # 单文件 ≤10MB（设计 §4.3）
MAX_CHARS = 200_000  # 字符上限（粘贴文本 / 解析后正文）
MAX_PREVIEW_CHARS = 200_000  # 预览返回 contentMasked 上限（Java 存档用）
PARSE_TIMEOUT_S = 30.0  # 单文件解析超时（设计 §6）
APPROX_DUP_THRESHOLD = 0.95  # 近似重复：余弦 ≥0.95
APPROX_DUP_TOP_K = 5  # 近似重复：取向量库 top5
MAX_DUPLICATES = 5  # 预览最多返回 5 条重复提示

ALLOWED_FILE_TYPES = ("txt", "md", "docx", "pdf")
_EXT_ALIASES = {"txt": "txt", "md": "md", "markdown": "md", "docx": "docx", "pdf": "pdf"}
_ALLOWED_EXTENSIONS = frozenset(_EXT_ALIASES)

# --------------------------------------------------------------------------- 异常


class ImporterError(Exception):
    """导入编排层可读错误基类（service 层映射为统一错误信封）。"""

    status: int = 400
    code: str = "bad_request"

    def __init__(self, message: str, *, reason: str = "") -> None:
        super().__init__(message)
        self.message = message
        self.reason = reason or ""

    def to_error(self) -> dict[str, Any]:
        """→ `{code, message, reason?}`（reason 为机器可读原因，供 Java 记 job.error）。"""
        error: dict[str, Any] = {"code": self.code, "message": self.message}
        if self.reason:
            error["reason"] = self.reason
        return error


class BadRequestError(ImporterError):
    """请求参数非法（缺字段/扩展名非白名单/base64 解码失败）→ 400。"""

    status = 400
    code = "bad_request"


class PayloadTooLargeError(ImporterError):
    """文件 >10MB 或正文 >200000 字符 → 413。"""

    status = 413
    code = "payload_too_large"


class ImportParseError(ImporterError):
    """解析失败（损坏/加密/扫描件/空文本/超时）→ 422。"""

    status = 422
    code = "parse_failed"


# --------------------------------------------------------------------------- 归一化正则

_WS_RE = re.compile(r"[ \t\u3000]+")
_MULTI_NL_RE = re.compile(r"\n{3,}")
# 页码行：第 1 页 / 第 1 页 共 3 页 / 共 3 页 / Page 1 / Page 1 of 3 / 1/3 / - 1 -
_PAGE_NO_RE = re.compile(
    r"^\s*(?:第\s*\d+\s*页(?:\s*[,，/／]?\s*(?:共\s*)?\d+\s*页)?"
    r"|共\s*\d+\s*页"
    r"|page\s*\d+(?:\s*(?:of|/)\s*\d+)?"
    r"|\d{1,4}\s*/\s*\d{1,4}"
    r"|-{1,3}\s*\d{1,4}\s*-{1,3})\s*$",
    re.IGNORECASE,
)
# 控制字符（保留 \n \t）+ 零宽/BOM
_CTRL_RE = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\u200b-\u200f\u202a-\u202e\ufeff]")
_MD_HEADING_RE = re.compile(r"^(#{1,6})\s+(.+)$")


def _now_iso() -> str:
    """本地时区 ISO 时间（与 indexer 审计口径一致）。"""
    return datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")


# --------------------------------------------------------------------------- 解析结果


@dataclass
class ParsedDocument:
    """解析结果（含分页信息，供页眉页脚清洗）。"""

    text: str
    file_type: str = ""
    pages: list[str] = field(default_factory=list)
    warnings: list[dict[str, str]] = field(default_factory=list)


# --------------------------------------------------------------------------- 请求校验


def resolve_file_type(payload: dict[str, Any]) -> str:
    """body → 规范化 fileType（txt|md|docx|pdf）；非白名单扩展名/类型 → BadRequestError。"""
    raw = str(payload.get("fileType") or payload.get("file_type") or "").strip().lower().lstrip(".")
    file_name = str(payload.get("fileName") or payload.get("file_name") or "").strip()
    ext = ""
    if file_name:
        suffix = Path(file_name).suffix.lower().lstrip(".")
        if suffix:
            if suffix not in _ALLOWED_EXTENSIONS:
                raise BadRequestError(
                    f"不支持的文件扩展名 .{suffix}（白名单 txt/md/docx/pdf）",
                    reason="unsupported_extension",
                )
            ext = _EXT_ALIASES.get(suffix, suffix)
    if raw == "markdown":
        raw = "md"
    if raw and raw not in ALLOWED_FILE_TYPES:
        raise BadRequestError(
            f"不支持的文件类型 {raw!r}（白名单 txt|md|docx|pdf）",
            reason="unsupported_file_type",
        )
    if ext and raw and ext != raw:
        raise BadRequestError(
            f"fileType（{raw}）与文件扩展名（.{ext}）不一致", reason="file_type_mismatch"
        )
    final = raw or ext
    if not final:
        raise BadRequestError("fileType 必填（txt|md|docx|pdf）", reason="missing_file_type")
    return final


def decode_content_base64(content_base64: Any) -> bytes:
    """contentBase64 → bytes；解码失败 400，空内容 400，超 10MB 413。"""
    raw = str(content_base64 or "").strip()
    if not raw:
        raise BadRequestError("contentBase64 为空", reason="empty_content_base64")
    if raw.startswith("data:") and "," in raw:  # 兼容 Data URL 前缀
        raw = raw.split(",", 1)[1]
    try:
        data = base64.b64decode(raw, validate=True)
    except (binascii.Error, ValueError) as exc:
        raise BadRequestError(f"contentBase64 解码失败：{exc}", reason="invalid_base64") from None
    if not data:
        raise BadRequestError("contentBase64 解码后为空", reason="empty_file")
    if len(data) > MAX_FILE_BYTES:
        raise PayloadTooLargeError(
            f"文件超限：{len(data)} 字节 > {MAX_FILE_BYTES} 字节（10MB）",
            reason="file_too_large",
        )
    return data


# --------------------------------------------------------------------------- 解析：txt / md


def _decode_text_bytes(data: bytes) -> tuple[str, str]:
    """文本字节 → (文本, 实际编码)；utf-8-sig → gb18030 → utf-8(replace)。"""
    for encoding in ("utf-8-sig", "gb18030"):
        try:
            return data.decode(encoding), encoding
        except UnicodeDecodeError:
            continue
    return data.decode("utf-8", errors="replace"), "utf-8(replace)"


def parse_text_bytes(data: bytes, file_type: str = "txt") -> ParsedDocument:
    """txt/md 字节 → ParsedDocument；含 NUL 视为二进制拒绝（不可信输入）。"""
    if b"\x00" in data:
        raise BadRequestError(
            "疑似二进制文件（含 NUL 字节），拒绝按文本解析；请确认文件类型",
            reason="binary_content",
        )
    text, encoding = _decode_text_bytes(data)
    warnings: list[dict[str, str]] = []
    if encoding != "utf-8-sig" and encoding != "utf-8":
        warnings.append(
            {"code": "text_encoding_fallback", "message": f"文本非 UTF-8，已按 {encoding} 解码"}
        )
    return ParsedDocument(text=text, file_type=file_type, warnings=warnings)


def parse_plain_text(text: Any, file_type: str = "txt") -> ParsedDocument:
    """直接粘贴的文本（粘贴入口 E1）→ ParsedDocument。"""
    return ParsedDocument(text=str(text or ""), file_type=file_type)


# --------------------------------------------------------------------------- 解析：docx


def _docx_blocks(document: Any) -> list[Any]:
    """按文档顺序返回 [Paragraph|Table]（段落与表格交错保持原文位置）。"""
    from docx.oxml.ns import qn
    from docx.table import Table
    from docx.text.paragraph import Paragraph

    blocks: list[Any] = []
    body = document.element.body
    for child in body.iterchildren():
        if child.tag == qn("w:p"):
            blocks.append(Paragraph(child, document))
        elif child.tag == qn("w:tbl"):
            blocks.append(Table(child, document))
    return blocks


def _docx_heading_level(paragraph: Any) -> int:
    """段落标题级别：Heading N / 标题 N → N（1—6）；非标题 → 0。"""
    style = getattr(paragraph, "style", None)
    name = str(getattr(style, "name", "") or "").strip()
    if not name:
        return 0
    if name.lower().startswith("heading") or name.startswith("标题"):
        match = re.search(r"(\d+)", name)
        level = int(match.group(1)) if match else 1
        return max(1, min(level, 6))
    return 0


def _docx_cell_text(cell: Any) -> str:
    """表格单元格 → 单行文本（换行压空格、转义 `|`，避免破坏 Markdown 表格）。"""
    text = " ".join(str(getattr(cell, "text", "") or "").split())
    return text.replace("|", "\\|")


def _docx_table_markdown(table: Any) -> list[str]:
    """表格 → Markdown 行组（首行表头 + `---` 分隔 + 数据行）。"""
    rows: list[list[str]] = []
    for row in table.rows:
        cells = [_docx_cell_text(cell) for cell in row.cells]
        if not any(cells):
            continue
        rows.append(cells)
    if not rows:
        return []
    width = max(len(row) for row in rows)
    header = rows[0] + [""] * (width - len(rows[0]))
    lines = ["| " + " | ".join(header) + " |", "| " + " | ".join(["---"] * width) + " |"]
    for row in rows[1:]:
        padded = row + [""] * (width - len(row))
        lines.append("| " + " | ".join(padded) + " |")
    return lines


def parse_docx_bytes(data: bytes) -> ParsedDocument:
    """docx 字节 → ParsedDocument（段落 + 表格成 Markdown 行组、保留标题层级）。"""
    if not data.startswith(b"PK"):
        raise ImportParseError(
            "docx 解析失败：文件不是有效的 Office Open XML（ZIP）格式（可能已损坏或为 .doc 旧格式）",
            reason="docx_bad_magic",
        )
    try:
        from docx import Document
    except Exception as exc:  # pragma: no cover - 依赖缺失（环境相关）
        raise ImportParseError(
            "docx 解析不可用：缺少 python-docx 依赖（pip install python-docx）",
            reason="docx_dependency_missing",
        ) from exc
    try:
        document = Document(io.BytesIO(data))
        blocks = _docx_blocks(document)
    except Exception as exc:
        raise ImportParseError(
            f"docx 解析失败：{type(exc).__name__}: {str(exc)[:200]}", reason="docx_parse_error"
        ) from exc

    warnings: list[dict[str, str]] = []
    lines: list[str] = []
    tables = 0
    headings = 0
    for block in blocks:
        if hasattr(block, "rows") and hasattr(block, "columns"):  # Table
            tables += 1
            lines.extend(_docx_table_markdown(block))
            continue
        text = " ".join(str(getattr(block, "text", "") or "").split())
        if not text:
            continue
        level = _docx_heading_level(block)
        if level:
            headings += 1
            lines.append(f"{'#' * level} {text}")
        else:
            lines.append(text)
    if tables:
        warnings.append(
            {"code": "docx_tables_extracted", "message": f"docx 含 {tables} 个表格，已按 Markdown 行组提取"}
        )
    if not lines:
        raise ImportParseError(
            "docx 无有效文本（可能为空文档或内容全在图片/文本框中）", reason="docx_empty_text"
        )
    return ParsedDocument(
        text="\n".join(lines), file_type="docx", warnings=warnings
    )


# --------------------------------------------------------------------------- 解析：pdf


def parse_pdf_bytes(data: bytes) -> ParsedDocument:
    """pdf 字节 → ParsedDocument（逐页提取；空页告警；加密/扫描件 → ImportParseError）。"""
    if not data.lstrip()[:5] == b"%PDF-":
        raise ImportParseError(
            "PDF 解析失败：文件缺少 %PDF 头（可能不是 PDF 或已损坏）", reason="pdf_bad_magic"
        )
    try:
        from pypdf import PdfReader
    except Exception as exc:  # pragma: no cover - 依赖缺失（环境相关）
        raise ImportParseError(
            "PDF 解析不可用：缺少 pypdf 依赖（pip install pypdf）", reason="pdf_dependency_missing"
        ) from exc
    try:
        reader = PdfReader(io.BytesIO(data))
    except Exception as exc:
        raise ImportParseError(
            f"PDF 解析失败：{type(exc).__name__}: {str(exc)[:200]}", reason="pdf_parse_error"
        ) from exc
    if reader.is_encrypted:
        try:
            opened = bool(reader.decrypt(""))
        except Exception:  # noqa: BLE001 - 加密格式异常统一按加密处理
            opened = False
        if not opened or reader.is_encrypted:
            raise ImportParseError(
                "PDF 已加密（需要打开密码），无法解析；请提供未加密 PDF 或先解密后重新上传",
                reason="pdf_encrypted",
            )
    pages: list[str] = []
    empty_pages: list[int] = []
    try:
        for index in range(len(reader.pages)):
            page_text = reader.pages[index].extract_text() or ""
            if not page_text.strip():
                empty_pages.append(index + 1)
            pages.append(page_text)
    except ImportParseError:
        raise
    except Exception as exc:
        raise ImportParseError(
            f"PDF 解析失败：{type(exc).__name__}: {str(exc)[:200]}", reason="pdf_parse_error"
        ) from exc
    warnings: list[dict[str, str]] = []
    if empty_pages:
        shown = ",".join(str(number) for number in empty_pages[:20])
        suffix = " 等" if len(empty_pages) > 20 else ""
        warnings.append(
            {
                "code": "pdf_empty_page",
                "message": f"第 {shown}{suffix} 页无可提取文本（可能为扫描件/图片页），已跳过",
            }
        )
    if not any(page.strip() for page in pages):
        raise ImportParseError(
            "PDF 无可提取文本（可能为扫描件/图片型 PDF）；请先 OCR，或改传 docx/md 文本",
            reason="pdf_empty_text",
        )
    return ParsedDocument(text="\n\n".join(pages), file_type="pdf", pages=pages, warnings=warnings)


def parse_source(payload: dict[str, Any], file_type: str, *, timeout: float = PARSE_TIMEOUT_S) -> ParsedDocument:
    """按 fileType 从 text/contentBase64 解析（解析步骤带 30s 超时保护）。"""
    text = payload.get("text")
    has_text = isinstance(text, str)
    content_base64 = payload.get("contentBase64", payload.get("content_base64"))
    has_base64 = isinstance(content_base64, str) and content_base64.strip() != ""

    if file_type in ("txt", "md"):
        if has_text:
            # 显式传了 text（哪怕全空白）→ 交给归一化判空并报 422，区分「没传内容」与「内容为空」
            return parse_plain_text(text, file_type)
        if not has_base64:
            raise BadRequestError(
                "缺少内容：text 与 contentBase64 至少提供一个", reason="missing_content"
            )
        data = decode_content_base64(content_base64)
        return _run_with_timeout(parse_text_bytes, data, file_type, timeout=timeout)

    if has_text:
        raise BadRequestError(
            f"{file_type} 必须通过 contentBase64 上传二进制内容（不支持 text 直传）",
            reason="binary_requires_base64",
        )
    if not has_base64:
        raise BadRequestError("缺少内容：docx/pdf 必须提供 contentBase64", reason="missing_content")
    data = decode_content_base64(content_base64)
    if file_type == "docx":
        return _run_with_timeout(parse_docx_bytes, data, timeout=timeout)
    return _run_with_timeout(parse_pdf_bytes, data, timeout=timeout)


def _run_with_timeout(fn: Callable[..., ParsedDocument], *args: Any, timeout: float) -> ParsedDocument:
    """在单线程池中执行解析，超时 → ImportParseError（线程无法强杀，属已知简化）。"""
    executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="rag-import-parse")
    try:
        future = executor.submit(fn, *args)
        try:
            return future.result(timeout=max(0.05, float(timeout)))
        except FuturesTimeoutError:
            future.cancel()
            raise ImportParseError(
                f"文档解析超时（>{timeout:.0f}s），请拆分文件或压缩后重试", reason="parse_timeout"
            ) from None
        except ImporterError:
            raise
        except Exception as exc:  # noqa: BLE001 - 解析异常统一转可读 422
            raise ImportParseError(
                f"文档解析失败：{type(exc).__name__}: {str(exc)[:200]}", reason="parse_error"
            ) from exc
    finally:
        executor.shutdown(wait=False)


# --------------------------------------------------------------------------- 归一化


def to_halfwidth(text: str) -> str:
    """全角字母/数字→半角、全角空格→半角（**保留中文全角标点**，不做 NFKC 全局替换）。"""
    out: list[str] = []
    for char in text or "":
        code = ord(char)
        if (
            0xFF10 <= code <= 0xFF19  # ０-９
            or 0xFF21 <= code <= 0xFF3A  # Ａ-Ｚ
            or 0xFF41 <= code <= 0xFF5A  # ａ-ｚ
        ):
            out.append(chr(code - 0xFEE0))
        elif char == "\u3000":
            out.append(" ")
        else:
            out.append(char)
    return "".join(out)


def _clean_lines(raw: str) -> list[str]:
    """单页/整篇 → 归一化行列表（去控制字符、空白归一、去页码行）。"""
    text = _CTRL_RE.sub("", to_halfwidth(raw or "").replace("\r\n", "\n").replace("\r", "\n"))
    lines: list[str] = []
    for line in text.split("\n"):
        line = _WS_RE.sub(" ", line).strip()
        if _PAGE_NO_RE.match(line):
            continue
        lines.append(line)
    return lines


def _strip_running_edge_lines(pages: list[list[str]]) -> tuple[list[list[str]], int]:
    """按页清洗页眉页脚：某行在 ≥60% 页面的首/末行重复出现 → 视为页眉/页脚删除。"""
    if len(pages) < 2:
        return pages, 0
    first_counts: Counter[str] = Counter()
    last_counts: Counter[str] = Counter()
    for lines in pages:
        non_empty = [line for line in lines if line]
        if len(non_empty) >= 2:
            first_counts[non_empty[0]] += 1
            last_counts[non_empty[-1]] += 1
    threshold = max(2, math.ceil(len(pages) * 0.6))
    headers = {line for line, count in first_counts.items() if count >= threshold}
    footers = {line for line, count in last_counts.items() if count >= threshold}
    if not headers and not footers:
        return pages, 0
    cleaned: list[list[str]] = []
    dropped = 0
    for lines in pages:
        non_empty_indexes = [index for index, line in enumerate(lines) if line]
        drop: set[int] = set()
        if len(non_empty_indexes) >= 2:
            first_index, last_index = non_empty_indexes[0], non_empty_indexes[-1]
            if lines[first_index] in headers:
                drop.add(first_index)
            if lines[last_index] in footers and last_index not in drop:
                drop.add(last_index)
        dropped += len(drop)
        cleaned.append([line for index, line in enumerate(lines) if index not in drop])
    return cleaned, dropped


def normalize_document(
    text: str, *, pages: list[str] | None = None
) -> tuple[str, list[dict[str, str]]]:
    """正文归一化 → (归一化文本, 告警列表)。

    输入：
        text  : 原始正文（docx/txt/md；pdf 时可为各页拼接结果）
        pages : 分页原文（pdf 用；提供时按页清洗页眉页脚，并保留页间空行）
    输出：(文本, warnings)；warnings 码：`page_number_removed` / `header_footer_removed`
    """
    warnings: list[dict[str, str]] = []
    if pages:
        page_lines = [_clean_lines(page) for page in pages]
        page_lines, dropped = _strip_running_edge_lines(page_lines)
        if dropped:
            warnings.append(
                {"code": "header_footer_removed", "message": f"已清洗页眉页脚重复行 {dropped} 行"}
            )
        lines: list[str] = []
        for page in page_lines:
            if not any(page):
                continue
            if lines:
                lines.append("")
            lines.extend(page)
    else:
        lines = _clean_lines(text)
    # 统计页码行（_clean_lines 已丢弃，这里只报告数量，供 Java 提示）
    page_no_count = sum(
        1
        for raw in (pages if pages else [text or ""])
        for line in _CTRL_RE.sub("", to_halfwidth(raw)).replace("\r\n", "\n").split("\n")
        if _PAGE_NO_RE.match(_WS_RE.sub(" ", line).strip())
    )
    if page_no_count:
        warnings.append(
            {"code": "page_number_removed", "message": f"已清洗页码行 {page_no_count} 行"}
        )
    normalized = "\n".join(lines)
    normalized = _MULTI_NL_RE.sub("\n\n", normalized).strip()
    return normalized, warnings


# --------------------------------------------------------------------------- 标题建议


def suggest_title(
    payload: dict[str, Any], file_name: str, masked_text: str, *, today: str | None = None
) -> str:
    """标题建议：显式 title > 文件名去扩展名 > 正文首个 heading > 「未命名知识-<日期>」。"""
    explicit = str(payload.get("title") or "").strip()
    if explicit:
        return explicit[:120]
    if file_name:
        stem = Path(file_name).stem.strip()
        if stem:
            return stem[:120]
    for line in (masked_text or "").split("\n")[:20]:
        candidate = line.strip()
        if not candidate:
            continue
        match = _MD_HEADING_RE.match(candidate)
        if match:
            return match.group(2).strip()[:120]
        if is_heading(candidate):
            return candidate[:120]
    return f"未命名知识-{today or date.today().isoformat()}"


# --------------------------------------------------------------------------- 去重


def content_text_sha256(masked_text: str) -> str:
    """归一化脱敏文本的 sha256（知识级精确去重键 / index 的 content_sha256 元数据）。"""
    return hashlib.sha256((masked_text or "").encode("utf-8")).hexdigest()


def _duplicate_row(chunk: RetrievedChunk, similarity: float) -> dict[str, Any]:
    return {
        "knowledgeId": str(chunk.knowledge_id or ""),
        "title": str(chunk.title or ""),
        "similarity": round(float(similarity), 4),
    }


def find_duplicates(
    *,
    chunks: list[dict[str, Any]],
    title: str,
    text_sha256: str,
    store: BaseStore | None,
    embedder: Any = None,
    approx: bool = True,
) -> tuple[list[dict[str, Any]], list[dict[str, str]]]:
    """与现有索引做重复检测 → (duplicates, warnings)。

    精确：知识级 `content_sha256`/`doc_checksum` 等于本次文本 sha256；
         chunk 级 `source_hash` 命中文档块（含「标题：正文」前缀变体）。
    近似：新文本首块 embedding → 向量库 top5，余弦 ≥0.95 视为近似重复；
         失败只告警（`dedupe_approx_skipped`），不阻断预览。
    """
    warnings: list[dict[str, str]] = []
    if store is None:
        return [], warnings
    try:
        existing = store.all_chunks()
    except Exception as exc:  # noqa: BLE001 - 去重失败不阻断预览
        warnings.append(
            {
                "code": "dedupe_skipped",
                "message": f"重复检测跳过（索引不可读：{type(exc).__name__}）",
            }
        )
        return [], warnings
    if not existing:
        return [], warnings

    found: dict[str, dict[str, Any]] = {}

    def _add(chunk: RetrievedChunk, similarity: float) -> None:
        key = str(chunk.knowledge_id or chunk.chunk_id or "")
        if not key:
            return
        current = found.get(key)
        if current is None or similarity > current["similarity"]:
            found[key] = _duplicate_row(chunk, similarity)

    # 1) 知识级精确：content_sha256 / doc_checksum 相等 → similarity=1.0
    for chunk in existing:
        if text_sha256 and text_sha256 in {chunk.content_sha256, chunk.doc_checksum}:
            _add(chunk, 1.0)

    # 2) chunk 级精确：source_hash 命中（含「标题：正文」前缀变体）
    by_hash: dict[str, RetrievedChunk] = {}
    for chunk in existing:
        if chunk.source_hash:
            by_hash.setdefault(chunk.source_hash, chunk)
    for item in chunks:
        text = str(item.get("text") or "")
        if not text:
            continue
        variants = [text]
        if title:
            variants.append(f"{title}：{text}")
        for variant in variants:
            hit = by_hash.get(source_hash(variant))
            if hit is not None:
                _add(hit, 1.0)
                break

    # 3) 近似：首块 embedding → top5 余弦 ≥0.95（失败只告警）
    if approx and embedder is not None and chunks:
        try:
            vector = embedder.embed_query(str(chunks[0].get("text") or ""))
            result = store.query(vector, top_k=APPROX_DUP_TOP_K)
            for chunk in result.chunks:
                if float(chunk.score) >= APPROX_DUP_THRESHOLD:
                    _add(chunk, float(chunk.score))
        except Exception as exc:  # noqa: BLE001 - 近似去重失败不阻断
            warnings.append(
                {
                    "code": "dedupe_approx_skipped",
                    "message": f"近似重复检测跳过（embedding/向量查询不可用：{type(exc).__name__}）",
                }
            )
    return list(found.values())[:MAX_DUPLICATES], warnings


# --------------------------------------------------------------------------- 预览


def preview(
    payload: dict[str, Any],
    *,
    settings: Settings,
    store: BaseStore | None = None,
    embedder: Any = None,
    approx_dedupe: bool = True,
    parse_timeout: float = PARSE_TIMEOUT_S,
    max_chars: int | None = None,
    content_max_chars: int | None = None,
) -> dict[str, Any]:
    """解析 + 归一化 + 脱敏 + 切分 + 去重 → 预览数据（不写入向量库）。

    输入：
        payload        : `{fileName?, fileType, text?, contentBase64?, projectId, title?}`
        settings       : 配置（切分参数/敏感词/掩码 token）
        store          : 向量库（去重用；None → 跳过去重）
        embedder       : 向量器（近似去重；None → 跳过近似）
        approx_dedupe  : 是否做近似去重（embedding 失败仅告警）
        parse_timeout  : 解析超时（秒）
        max_chars      : 正文字符上限（超限 → 413）
        content_max_chars: contentMasked 返回上限（超出截断并告警）
    输出：`{suggestedTitle, charCount, piiMaskedCount, chunks, totalChunks, warnings,
           duplicates, contentMasked}`
    异常：BadRequestError(400) / PayloadTooLargeError(413) / ImportParseError(422)
    """
    file_type = resolve_file_type(payload)
    file_name = str(payload.get("fileName") or payload.get("file_name") or "").strip()
    char_limit = MAX_CHARS if max_chars is None else int(max_chars)
    content_limit = MAX_PREVIEW_CHARS if content_max_chars is None else int(content_max_chars)
    parsed = parse_source(payload, file_type, timeout=parse_timeout)
    normalized, norm_warnings = normalize_document(parsed.text, pages=parsed.pages or None)
    if not normalized.strip():
        raise ImportParseError(
            "文档无有效文本（空文件/扫描件/加密文档），请确认内容后重试", reason="empty_text"
        )
    char_count = len(normalized)
    if char_count > char_limit:
        raise PayloadTooLargeError(
            f"正文超限：{char_count} 字符 > {char_limit} 字符上限", reason="content_too_large"
        )

    screened = screen(
        normalized,
        sensitive_words=settings.sensitive_words,
        mask_token=settings.mask_token,
        mask_email_enabled=True,
    )
    masked_text = screened.text
    pii_masked_count = screened.masked_phone + screened.masked_id + screened.masked_email

    warnings: list[dict[str, str]] = list(parsed.warnings) + list(norm_warnings)
    if pii_masked_count:
        warnings.append(
            {
                "code": "pii_masked",
                "message": f"已自动脱敏 {pii_masked_count} 处个人信息（手机号/身份证/邮箱）",
            }
        )
    if screened.dropped_lines:
        warnings.append(
            {
                "code": "header_footer_removed",
                "message": f"已清洗页眉页脚/页码行 {screened.dropped_lines} 行",
            }
        )

    title = suggest_title(payload, file_name, masked_text)
    pieces = chunk_text(
        masked_text,
        size=settings.chunk_size,
        overlap=settings.chunk_overlap,
        min_length=settings.chunk_min_length,
        section_path=title,
    )
    if not pieces:
        raise ImportParseError("正文切分后无有效内容（可能全为空白/标点）", reason="empty_chunks")
    chunks = [
        {
            # seq 从 1 起连续且稳定（对齐 OpenAPI KnowledgeImportPreviewChunk / commit.chunks 勾选）
            "seq": int(piece.chunk_seq) + 1,
            "sectionPath": piece.section_path or title,
            "text": piece.text,
            "charCount": len(piece.text),
        }
        for piece in pieces
    ]

    content_masked = masked_text
    if len(content_masked) > content_limit:
        content_masked = content_masked[:content_limit]
        warnings.append(
            {
                "code": "truncated",  # 对齐 OpenAPI KnowledgeImportWarning 口径
                "message": f"contentMasked 超过 {content_limit} 字符，已截断（全文以 charCount 为准）",
            }
        )

    text_sha256 = content_text_sha256(masked_text)
    duplicates, dedupe_warnings = find_duplicates(
        chunks=chunks,
        title=title,
        text_sha256=text_sha256,
        store=store,
        embedder=embedder,
        approx=approx_dedupe,
    )
    warnings.extend(dedupe_warnings)
    if duplicates:
        warnings.append(
            {
                "code": "duplicate_suspected",  # 对齐 OpenAPI KnowledgeImportWarning 口径
                "message": f"检测到 {len(duplicates)} 条疑似重复知识（默认跳过，可强制新建）",
            }
        )

    return {
        "suggestedTitle": title,
        "charCount": char_count,
        "piiMaskedCount": pii_masked_count,
        "chunks": chunks,
        "totalChunks": len(chunks),
        "warnings": warnings,
        "duplicates": duplicates,
        "contentMasked": content_masked,
    }


# --------------------------------------------------------------------------- 索引 / 撤销


def _screened_content_sha256(content: str, settings: Settings) -> str:
    """索引侧内容 sha256（与 preview 的 text_sha256 同口径：归一化 → 脱敏 → sha256）。"""
    normalized, _ = normalize_document(content)
    screened = screen(
        normalized,
        sensitive_words=settings.sensitive_words,
        mask_token=settings.mask_token,
        mask_email_enabled=True,
    )
    return content_text_sha256(screened.text)


def _existing_records(store: BaseStore, knowledge_id: str) -> list[dict[str, Any]]:
    try:
        records = store.iter_records()
    except Exception:  # noqa: BLE001 - 幂等检查失败按「不存在」处理（宁重写不丢索引）
        return []
    return [
        record
        for record in records
        if str((record.get("metadata") or {}).get("knowledge_id") or "") == knowledge_id
    ]


def index_knowledge(
    payload: dict[str, Any],
    *,
    settings: Settings,
    store: BaseStore | None = None,
    embedder: Any = None,
    print_fn: Callable[[str], None] | None = None,
) -> dict[str, Any]:
    """单知识 upsert（复用 indexer 的切分/脱敏/向量化管道；写入 Chroma/内存库 + 元数据）。

    幂等：同 `knowledgeId + checksum` 已存在 → `skipped=True` 不重复写（chunk id 天然稳定）。

    输入：`{knowledgeId, title, projectId, category, owner, version, effectiveFrom?, effectiveTo?,
            status, content, sourceDocId?, checksum}`
    输出：`{knowledgeId, chunks, indexedAt, skipped}`
    异常：BadRequestError(400) / PayloadTooLargeError(413) / ImportParseError(422)
    """
    from .indexer import audit_append, embed_chunks, prepare_chunks
    from .embedder import Embedder

    knowledge_id = str(payload.get("knowledgeId") or payload.get("knowledge_id") or "").strip()
    if not knowledge_id:
        raise BadRequestError("knowledgeId 必填", reason="missing_knowledge_id")
    title = str(payload.get("title") or "").strip()
    content = str(payload.get("content") or "")
    if not content.strip():
        raise BadRequestError("content 必填且非空", reason="missing_content")
    if len(content) > MAX_CHARS:
        raise PayloadTooLargeError(
            f"正文超限：{len(content)} 字符 > {MAX_CHARS} 字符上限", reason="content_too_large"
        )

    project_id = str(payload.get("projectId") or payload.get("project_id") or "").strip()
    category = str(payload.get("category") or "").strip()
    owner = str(payload.get("owner") or "").strip()
    version = str(payload.get("version") or "").strip()
    status = str(payload.get("status") or "").strip() or "published"
    effective_from = str(payload.get("effectiveFrom") or payload.get("effective_from") or "").strip()
    effective_to = str(payload.get("effectiveTo") or payload.get("effective_to") or "").strip()
    source_doc_id = str(payload.get("sourceDocId") or payload.get("source_doc_id") or "").strip()
    checksum = str(payload.get("checksum") or "").strip() or hashlib.sha256(
        content.encode("utf-8")
    ).hexdigest()

    store = store or create_store(settings)
    embedder = embedder or Embedder(settings)
    print_fn = print_fn or (lambda message: None)

    existing = _existing_records(store, knowledge_id)
    if existing:
        stored_checksums = {
            str((record.get("metadata") or {}).get("doc_checksum") or "") for record in existing
        }
        if checksum in stored_checksums:
            indexed_at = str((existing[0].get("metadata") or {}).get("indexed_at") or "")
            print_fn(
                f"[importer] index {knowledge_id}：checksum 未变化 → 幂等跳过（{len(existing)} 块）"
            )
            return {
                "knowledgeId": knowledge_id,
                "chunks": len(existing),
                "indexedAt": indexed_at or _now_iso(),
                "skipped": True,
            }

    item: dict[str, Any] = {
        "knowledgeId": knowledge_id,
        "title": title,
        "content": content,
        "projectId": project_id or settings.global_project_id,
        "category": category,
        "owner": owner,
        "version": version,
        "status": status,
        "effectiveFrom": effective_from,
        "effectiveTo": effective_to,
        "sourceDocId": source_doc_id,
        "source": source_doc_id or f"{knowledge_id} {version}".strip(),
    }
    chunks, _stats = prepare_chunks([item], settings)
    if not chunks:
        raise ImportParseError(
            "正文切分后无有效内容（可能全为空白/标点）", reason="empty_chunks"
        )
    indexed_at = _now_iso()
    content_sha256 = _screened_content_sha256(content, settings)
    for chunk in chunks:
        chunk["doc_checksum"] = checksum
        chunk["content_sha256"] = content_sha256
        chunk["source_doc_id"] = source_doc_id
        chunk["indexed_at"] = indexed_at
    vectors, embed_stats = embed_chunks(settings, chunks, embedder=embedder, print_fn=print_fn)
    removed = store.delete_by_knowledge(knowledge_id)
    written = store.add_chunks(chunks, vectors)
    result = {
        "knowledgeId": knowledge_id,
        "chunks": int(written),
        "indexedAt": indexed_at,
        "skipped": False,
    }
    audit_append(
        settings,
        "index",
        knowledge_id=knowledge_id,
        chunks=written,
        chunks_removed=removed,
        checksum=checksum,
        source_doc_id=source_doc_id,
        status=status,
        project_id=item["projectId"],
        store_backend=store.backend,
        embed=embed_stats,
    )
    print_fn(
        f"[importer] index {knowledge_id}：写入 {written} 块（删除旧块 {removed}），"
        f"backend={store.backend} status={status}"
    )
    return result


def remove_knowledge(
    payload: dict[str, Any],
    *,
    settings: Settings,
    store: BaseStore | None = None,
    print_fn: Callable[[str], None] | None = None,
) -> dict[str, Any]:
    """撤销一条知识的全部 chunk → `{removed}`（复用 indexer.remove_knowledge，含审计）。"""
    from .indexer import remove_knowledge as _remove

    knowledge_id = str(payload.get("knowledgeId") or payload.get("knowledge_id") or "").strip()
    if not knowledge_id:
        raise BadRequestError("knowledgeId 必填", reason="missing_knowledge_id")
    store = store or create_store(settings)
    result = _remove(settings, knowledge_id, store=store, print_fn=print_fn or (lambda m: None))
    return {"removed": int(result.get("chunks_removed") or 0)}


# --------------------------------------------------------------------------- 健康


def _module_available(name: str) -> bool:
    """依赖是否可导入（不真正 import，避免副作用/告警）。"""
    import importlib.util

    try:
        return importlib.util.find_spec(name) is not None
    except (ImportError, ValueError):  # pragma: no cover - 环境异常
        return False


def health() -> dict[str, Any]:
    """导入能力快照（health 组件）。

    `available` 表示**文本入口（粘贴/md/txt）**可用（始终为 True）；
    `docx`/`pdf` 是各格式解析依赖（python-docx / pypdf）是否可用的真实能力位，
    Java 应据此对相应格式上传做降级提示（不要用 available 判断粘贴入口）。
    """
    docx_ok = _module_available("docx")
    pdf_ok = _module_available("pypdf")
    return {"available": True, "docx": docx_ok, "pdf": pdf_ok}


__all__ = [
    "ALLOWED_FILE_TYPES",
    "APPROX_DUP_THRESHOLD",
    "BadRequestError",
    "ImportParseError",
    "ImporterError",
    "MAX_CHARS",
    "MAX_FILE_BYTES",
    "MAX_PREVIEW_CHARS",
    "PARSE_TIMEOUT_S",
    "ParsedDocument",
    "PayloadTooLargeError",
    "content_text_sha256",
    "decode_content_base64",
    "find_duplicates",
    "health",
    "index_knowledge",
    "normalize_document",
    "parse_docx_bytes",
    "parse_pdf_bytes",
    "parse_plain_text",
    "parse_source",
    "parse_text_bytes",
    "preview",
    "remove_knowledge",
    "resolve_file_type",
    "suggest_title",
    "to_halfwidth",
]
