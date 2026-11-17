"""结构感知中文切分（设计 §3 / §4：500 / overlap 80 / min 30）
================================================================

职责：把一条知识（title + content）切成可检索的 chunk，保留 `sectionPath` /
`chunkSeq`，供入库与引用回显。

W31 升级（结构感知，由粗到细）：
    1. **块识别**（`parse_blocks`）：按行识别 6 类结构块
         - `heading`   ：Markdown `#`/`##`…（按级别维护 `sectionPath` 层级栈）、
                         `第X条/章`、`一、`、`1.`、`【…】`（同级标题，覆盖当前层级）；
         - `code`      ：``` / ~~~ 围栏代码块，**整块保留**（内部空白/换行不归一，
                         不按句号切分，避免把代码/JSON 切碎）；
         - `table`     ：连续 `| … |` 行 → 表格；按「表头 + N 行」的**行组**切分，
                         每组重复表头（列语义不丢）；
         - `qa`        ：`问：…/答：…` 或 `Q:/A:` 问答对，问答**成对不拆**；
         - `paragraph` ：普通正文（逐行成段，保持 W30 行为）；
         - `blank`     ：空行（仅分隔，不入 chunk）。
    2. **段落超长**：按句末标点（。；！？!?;）切句再打包；单句仍超长 → 按 size
       硬切（带 overlap）。
    3. **打包**：贪心装块到 `CHUNK_SIZE`；相邻同类型块用**上一块尾部
       `CHUNK_OVERLAP` 字符**做重叠；**跨结构边界（正文↔代码/表格/问答、标题换节）
       不做字符重叠**，避免把上一节的尾巴粘进代码/新章节。
    4. **尾块**短于 `CHUNK_MIN_LENGTH` 且与前一 chunk 同类型 → 并入前一块。

注意：重叠在字符级（中文无空格），与设计「中文字符计」口径一致；单块最大长度
≈ CHUNK_SIZE + CHUNK_OVERLAP（重叠回填所致），由测试固定该边界。
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Any, Iterable

# 句末标点：中文句号/分号/问叹号 + 英文对应 + 换行
_SENTENCE_SPLIT_RE = re.compile(r"(?<=[。；！？!?;])|\n+")
# 结构标题：第X条 / 第X章 / 一、二、 / 1. 2、 / 【…】/ 以 # 开头的 markdown 标题
_HEADING_RE = re.compile(
    r"^\s*(?:第\s*[一二三四五六七八九十百千0-9]+\s*[条款章节项]"
    r"|[一二三四五六七八九十]+、"
    r"|\d+[.、)）]\s*"
    r"|【[^】]{1,20}】"
    r"|#{1,6}\s+"
    r")"
)
_MD_HEADING_RE = re.compile(r"^(#{1,6})\s+(.+)$")
_WS_RE = re.compile(r"[ \t\u3000]+")
_MULTI_NL_RE = re.compile(r"\n{3,}")
# 代码围栏 / 表格行 / 问答对
_FENCE_RE = re.compile(r"^\s*(```|~~~)")
_TABLE_ROW_RE = re.compile(r"^\s*\|.*\|\s*$")
_TABLE_DIVIDER_RE = re.compile(r"^\s*\|(?:\s*:?-{2,}:?\s*\|)+\s*$")
_QA_Q_RE = re.compile(r"^\s*(?:问|Q|q)\s*[:：]\s*")
_QA_A_RE = re.compile(r"^\s*(?:答|A|a)\s*[:：]\s*")

# 结构块类型
KIND_HEADING = "heading"
KIND_PARAGRAPH = "paragraph"
KIND_TABLE = "table"
KIND_CODE = "code"
KIND_QA = "qa"
# 跨这些类型边界不做字符重叠（避免污染代码/表格）
_HARD_KINDS = frozenset({KIND_CODE, KIND_TABLE})
# 尾块合并允许的「同类族」（正文/标题/问答可互相合并，代码/表格各自独立）
_PROSE_KINDS = frozenset({KIND_PARAGRAPH, KIND_HEADING, KIND_QA})


@dataclass
class Block:
    """结构块（切分中间表示）。

    字段：
        kind    : heading | paragraph | table | code | qa
        text    : 块正文（散文已归一空白；代码保留原始换行）
        section : 该块所属 sectionPath
        level   : 标题级别（仅 heading 有意义；非 Markdown 标题记 1）
    """

    kind: str
    text: str
    section: str = ""
    level: int = 0


@dataclass
class Chunk:
    """切分结果。

    字段：
        text         : chunk 正文（散文归一空白；代码块保留换行）
        section_path : 结构路径（标题层级拼接，缺省用知识标题）
        chunk_seq    : 该知识内的块序号（0 起，连续）
        block_type   : 该 chunk 主导结构类型（paragraph/heading/table/code/qa）
    """

    text: str
    section_path: str = ""
    chunk_seq: int = 0
    block_type: str = KIND_PARAGRAPH


@dataclass
class ChunkSet:
    """一条知识的切分结果集。"""

    title: str
    chunks: list[Chunk] = field(default_factory=list)


def normalize_text(text: str) -> str:
    """空白归一：统一换行、去行尾空格、压缩连续空格/空行（不删正文）。"""
    text = (text or "").replace("\r\n", "\n").replace("\r", "\n")
    text = _WS_RE.sub(" ", text)
    text = "\n".join(line.strip() for line in text.split("\n"))
    text = _MULTI_NL_RE.sub("\n\n", text)
    return text.strip()


def is_heading(line: str) -> bool:
    """判断一行是否为结构标题（长度 ≤ 40 且匹配标题前缀）。"""
    line = (line or "").strip()
    return bool(line) and len(line) <= 40 and bool(_HEADING_RE.match(line))


def _heading_level(line: str) -> int:
    """标题级别：Markdown `#` 按个数；其余结构标题固定 1 级（覆盖当前层级）。"""
    match = _MD_HEADING_RE.match((line or "").strip())
    if match:
        return len(match.group(1))
    return 1


def _push_section(stack: list[str], line: str) -> str:
    """把标题压入 sectionPath 层级栈，返回拼接后的 sectionPath。

    - Markdown 标题按 `#` 数量确定层级：`##` 追加到 `#` 下，`#` 清空同级之后；
      sectionPath 中**去掉 `#` 标记**（只保留标题文字）；
    - 非 Markdown 结构标题（第X条/一、/1./【…】）视为 1 级，覆盖整个栈。
    """
    raw = (line or "").strip()
    heading = re.sub(r"^#{1,6}\s*", "", raw).strip() or raw
    level = max(1, _heading_level(raw))
    if level <= 1:
        stack[:] = [heading]
    else:
        stack[:] = stack[: level - 1] + [heading]
    return " > ".join(stack)


def split_paragraphs(text: str) -> list[tuple[str, str]]:
    """把正文切成 (段落, sectionPath) 列表；标题行会更新当前 sectionPath。

    保留 W30 签名（单测/调用方兼容）：段落粒度 = 行；表格/代码/问答块按其文本行
    展开（不合并），因此这里只用于「段落级」视图。
    """
    paragraphs: list[tuple[str, str]] = []
    for block in parse_blocks(text):
        if block.kind == KIND_HEADING:
            paragraphs.append((block.text, block.section))
        elif block.kind in (KIND_PARAGRAPH, KIND_QA):
            paragraphs.append((block.text, block.section))
        else:  # 表格 / 代码：按行展开，保持「行即段落」的旧视图
            for line in block.text.split("\n"):
                if line.strip():
                    paragraphs.append((line.strip(), block.section))
    return paragraphs


def _split_long_paragraph(para: str, size: int, overlap: int) -> list[str]:
    """超长段落 → 按句打包；单句仍超长时按 size 硬切（带 overlap）。"""
    sentences = [s.strip() for s in _SENTENCE_SPLIT_RE.split(para) if s and s.strip()]
    if not sentences:
        sentences = [para]
    pieces: list[str] = []
    buf = ""
    for sent in sentences:
        if len(sent) > size:
            if buf:
                pieces.append(buf)
                buf = ""
            step = max(size - overlap, 1)
            for i in range(0, len(sent), step):
                piece = sent[i : i + size]
                if piece:
                    pieces.append(piece)
                if i + size >= len(sent):
                    break
            continue
        if buf and len(buf) + len(sent) > size:
            pieces.append(buf)
            buf = sent
        else:
            buf += sent
    if buf:
        pieces.append(buf)
    return pieces


def _split_code(block: str, size: int) -> list[str]:
    """代码块按**行**切分（不按标点），保证语法单元不被截断在句中。"""
    if len(block) <= size:
        return [block]
    pieces: list[str] = []
    buf: list[str] = []
    buf_len = 0
    for line in block.split("\n"):
        add = len(line) + (1 if buf else 0)
        if buf and buf_len + add > size:
            pieces.append("\n".join(buf))
            buf, buf_len = [], 0
            add = len(line)
        buf.append(line)
        buf_len += add
    if buf:
        pieces.append("\n".join(buf))
    return [piece for piece in pieces if piece.strip()]


def _split_table(rows: list[str], size: int) -> list[str]:
    """表格按「表头 + 若干数据行」切行组，每组重复表头（列语义不丢）。"""
    if len(rows) <= 1:
        return ["\n".join(rows)] if rows else []
    header = rows[0]
    body = [row for row in rows[1:] if not _TABLE_DIVIDER_RE.match(row)]
    divider = [row for row in rows[1:] if _TABLE_DIVIDER_RE.match(row)]
    # 每组行数：保证「表头 + 行组」长度 ≤ size；至少 1 行，避免死循环
    header_len = len(header) + (len(divider[0]) + 1 if divider else 0)
    budget = max(size - header_len - 1, 60)
    groups: list[str] = []
    current: list[str] = []
    current_len = 0
    for row in body:
        add = len(row) + (1 if current else 0)
        if current and current_len + add > budget:
            groups.append("\n".join([header, *divider, *current]))
            current, current_len = [], 0
            add = len(row)
        current.append(row)
        current_len += add
    if current:
        groups.append("\n".join([header, *divider, *current]))
    return groups or ["\n".join(rows)]


def parse_blocks(text: str) -> list[Block]:
    """把正文解析成结构块列表（heading/paragraph/table/code/qa）。

    输入：任意正文（内部按行扫描；代码块保留原始换行，散文块做空白归一）
    输出：list[Block]（顺序与原文一致；空行丢弃）
    """
    raw = (text or "").replace("\r\n", "\n").replace("\r", "\n")
    lines = raw.split("\n")
    blocks: list[Block] = []
    stack: list[str] = []
    index = 0
    total = len(lines)

    def section() -> str:
        return " > ".join(stack)

    while index < total:
        line = lines[index]
        stripped = line.strip()
        if not stripped:
            index += 1
            continue

        # 1) 围栏代码块：整块保留（内部空白不归一、不切句）
        fence = _FENCE_RE.match(line)
        if fence:
            marker = fence.group(1)
            code_lines = [line.rstrip()]
            index += 1
            while index < total and not lines[index].strip().startswith(marker):
                code_lines.append(lines[index].rstrip())
                index += 1
            if index < total:  # 收尾围栏
                code_lines.append(lines[index].rstrip())
                index += 1
            blocks.append(Block(kind=KIND_CODE, text="\n".join(code_lines).strip("\n"), section=section()))
            continue

        # 2) 表格：连续 | … | 行成表
        if _TABLE_ROW_RE.match(line):
            rows = []
            while index < total and _TABLE_ROW_RE.match(lines[index]):
                rows.append(lines[index].strip())
                index += 1
            blocks.append(Block(kind=KIND_TABLE, text="\n".join(rows), section=section()))
            continue

        # 3) 问答对：问/答相邻（成对不拆）
        if _QA_Q_RE.match(line):
            question = stripped
            answer = ""
            index += 1
            if index < total and _QA_A_RE.match(lines[index]):
                answer = lines[index].strip()
                index += 1
            text_qa = f"{normalize_text(question)}\n{normalize_text(answer)}".strip()
            blocks.append(Block(kind=KIND_QA, text=text_qa, section=section()))
            continue

        # 4) 标题：更新 sectionPath 层级栈，标题本身也入 chunk（便于标题召回）
        if is_heading(stripped):
            path = _push_section(stack, stripped)
            blocks.append(Block(kind=KIND_HEADING, text=stripped, section=path, level=_heading_level(stripped)))
            index += 1
            continue

        # 5) 普通段落（逐行成段，保持 W30 行为）
        blocks.append(Block(kind=KIND_PARAGRAPH, text=normalize_text(line), section=section()))
        index += 1
    return blocks


def _blocks_to_segments(
    blocks: list[Block], size: int, overlap: int, base_section: str
) -> list[tuple[str, str, str]]:
    """结构块 → (segment, sectionPath, kind) 列表（超长块先按结构拆）。"""
    segments: list[tuple[str, str, str]] = []
    for block in blocks:
        sect = block.section or base_section
        text = block.text.strip()
        if not text:
            continue
        if block.kind == KIND_CODE:
            # 只有围栏、无代码正文的空代码块 → 不产生 chunk
            if not text.replace("```", "").replace("~~~", "").strip():
                continue
            for piece in _split_code(text, size):
                segments.append((piece, sect, KIND_CODE))
        elif block.kind == KIND_TABLE:
            for piece in _split_table(text.split("\n"), size):
                segments.append((piece, sect, KIND_TABLE))
        elif block.kind == KIND_QA:
            if len(text) <= size:
                segments.append((text, sect, KIND_QA))
            else:
                for piece in _split_long_paragraph(text, size, overlap):
                    segments.append((piece, sect, KIND_QA))
        else:  # paragraph / heading
            kind = block.kind
            if len(text) <= size:
                segments.append((text, sect, kind))
            else:
                for piece in _split_long_paragraph(text, size, overlap):
                    segments.append((piece, sect, kind))
    return segments


def chunk_text(
    text: str,
    size: int = 500,
    overlap: int = 80,
    min_length: int = 30,
    section_path: str = "",
) -> list[Chunk]:
    """对单条正文做结构感知切分。

    输入：
        text         : 正文
        size         : 目标块长（字符）
        overlap      : 相邻块重叠字符数（同类型结构块之间生效）
        min_length   : 尾块最短长度；短于此值并入前一同类型块
        section_path : 缺省 sectionPath（通常是知识标题）
    输出：list[Chunk]（chunk_seq 连续、无空块）

    异常：size <= 0 抛 ValueError；overlap 会被夹到 [0, size-1]。
    """
    if size <= 0:
        raise ValueError("chunk size 必须为正整数")
    overlap = max(0, min(int(overlap), size - 1))

    blocks = parse_blocks(text)
    if not blocks:
        return []
    segments = _blocks_to_segments(blocks, size, overlap, section_path)
    if not segments:
        return []

    # 贪心打包：结构边界不重叠，同类型按字符 overlap 回填
    raw_chunks: list[tuple[str, str, str]] = []  # (body, section, kind)
    buf: list[str] = []
    buf_len = 0
    buf_section = section_path
    buf_kind = KIND_PARAGRAPH
    for seg, sect, kind in segments:
        flush = False
        if buf:
            if buf_kind in _HARD_KINDS and kind != buf_kind:
                # 表格/代码已收尾：不把后续散文拼进同一块（保持结构块干净）
                flush = True
            elif kind in _HARD_KINDS and buf_kind != kind and buf_len >= size * 0.5:
                # 散文占了大半块：先收口，再单独放表格/代码（避免超长）
                flush = True
            elif kind == KIND_HEADING and buf_len >= size * 0.6:
                # 新标题换节：当前块够大就先收口，避免跨节混块
                flush = True
            elif buf_len + 1 + len(seg) > size:
                flush = True
        if flush:
            body = "\n".join(buf)
            raw_chunks.append((body, buf_section, buf_kind))
            same_kind = kind == buf_kind
            tail = ""
            if overlap > 0 and same_kind:
                tail = body[-overlap:] if len(body) > overlap else body
            buf = [tail] if tail else []
            buf_len = len(tail)
            buf_section = sect or section_path
        elif not buf:
            buf_section = sect or section_path
        buf_kind = kind
        sep = 1 if buf else 0
        buf.append(seg)
        buf_len += sep + len(seg)
    if buf:
        body = "\n".join(buf)
        if body.strip():
            raw_chunks.append((body.strip(), buf_section, buf_kind))

    # 尾块过短 → 并入前一块（仅同「散文族」；代码/表格不合并）
    merged: list[tuple[str, str, str]] = [(body.strip(), sect, kind) for body, sect, kind in raw_chunks if body.strip()]
    i = 1
    while i < len(merged) and min_length > 0:
        body, sect, kind = merged[i]
        prev_body, prev_sect, prev_kind = merged[i - 1]
        if len(body) < min_length and ((kind in _PROSE_KINDS) == (prev_kind in _PROSE_KINDS)):
            merged[i - 1] = (prev_body + "\n" + body, prev_sect, prev_kind)
            del merged[i]
        else:
            i += 1
    # 只有一块且短于 min_length → 保留（不丢内容）
    return [
        Chunk(text=body, section_path=sect or section_path, chunk_seq=idx, block_type=kind)
        for idx, (body, sect, kind) in enumerate(merged)
    ]


def chunk_knowledge(
    knowledge: dict[str, Any],
    size: int = 500,
    overlap: int = 80,
    min_length: int = 30,
) -> list[dict[str, Any]]:
    """把一条知识 dict 切成带元数据的 chunk 列表（供 ingest 入库）。

    输入：knowledge（Java `GET /api/v1/knowledge` 的 item 或 corpus 文档：
                knowledgeId/title/content/.../projectId/serviceScope）
    输出：[{chunk_id, knowledge_id, text, embed_text, section_path, chunk_seq,
            block_type, source_hash, project_id, ...}]

    chunk_id 规则：`{knowledgeId}#{chunkSeq}`；`embed_text` 前置「标题｜分类」
    （带具体项目时再拼「项目中文名」），提升语义/专名召回。
    """
    kid = str(knowledge.get("knowledgeId") or knowledge.get("knowledge_id") or "").strip()
    title = str(knowledge.get("title") or "").strip()
    content = str(knowledge.get("content") or "")
    category = str(knowledge.get("category") or "").strip()
    project_id = str(knowledge.get("projectId") or knowledge.get("project_id") or "").strip()
    service_scope = str(knowledge.get("serviceScope") or knowledge.get("service_scope") or "").strip()

    pieces = chunk_text(content, size=size, overlap=overlap, min_length=min_length, section_path=title)
    if not pieces:
        return []

    from .config import category_name, global_project_id, project_name
    from .store import source_hash  # 局部导入：避免 chunker ↔ store 的模块级循环

    cat_name = category_name(category)
    is_global = not project_id or project_id == global_project_id()
    proj_name = "" if is_global else project_name(project_id)

    out: list[dict[str, Any]] = []
    for piece in pieces:
        body = piece.text
        # 标题不在正文里时补一个前缀，保证单条知识也能靠标题被召回
        display = body if title and title in body else (f"{title}：{body}" if title else body)
        parts = [title, cat_name or category]
        if proj_name:
            parts.append(proj_name)
        embed_text = "｜".join(part for part in parts if part)
        embed_text = f"{embed_text}\n{display}" if embed_text else display
        out.append(
            {
                "chunk_id": f"{kid}#{piece.chunk_seq}",
                "knowledge_id": kid,
                "text": display,
                "embed_text": embed_text,
                "section_path": piece.section_path or title,
                "chunk_seq": piece.chunk_seq,
                "block_type": piece.block_type,
                "title": title,
                "category": category,
                "category_name": cat_name,
                "status": str(knowledge.get("status") or "").strip(),
                "version": str(knowledge.get("version") or "").strip(),
                "owner": str(knowledge.get("owner") or "").strip(),
                "source": source_of(knowledge),
                "effective_from": str(knowledge.get("effectiveFrom") or "").strip(),
                "effective_to": str(knowledge.get("effectiveTo") or "").strip(),
                "project_id": project_id or global_project_id(),
                "service_scope": service_scope or "平台",
                "source_hash": source_hash(display),
            }
        )
    return out


def source_of(knowledge: dict[str, Any]) -> str:
    """来源展示串：`source` 可为 {doc, clause} 结构（Java 契约实现），兼容纯字符串。"""
    src = knowledge.get("source")
    if isinstance(src, dict):
        parts = [str(src.get(key)).strip() for key in ("doc", "clause") if src.get(key)]
        if parts:
            return " ".join(parts)
    elif src:
        return str(src).strip()
    kid = str(knowledge.get("knowledgeId") or knowledge.get("knowledge_id") or "").strip()
    ver = str(knowledge.get("version") or "").strip()
    return f"{kid} {ver}".strip()


def dedupe_by_text(
    chunks: Iterable[dict[str, Any]], threshold: float = 0.9
) -> tuple[list[dict[str, Any]], int]:
    """按 chunk 文本去重（委托 screener.dedup_chunks，保持单一实现）。

    输出：(保留列表, 丢弃数量)
    """
    from .screener import dedup_chunks

    return dedup_chunks(list(chunks), threshold=threshold)
