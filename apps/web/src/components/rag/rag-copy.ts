import type { RagHealth, RagMode, RagQueryResult, RagRefusalReason } from '@/api'
import { RAG_REFUSAL_REASONS } from '@/api'

/**
 * RAG 前端口径（P6 W33 / 契约 v1.4.0 §11 / task-81）
 * ===========================================================================
 * 纯函数 + 文案常量：**只做「服务端事实 → 人话」的映射，不改写契约语义**。
 * 红线（AGENTS.md NEVER #11 + 设计 `知识库RAG方案.md` §6/§11）：
 * 1) `mode` 必须如实展示：`generative` 才算生成答案；`retrieval_only` = 仅原文片段 + `[n]`；
 *    `rules` = 编排层不可用时的规则检索兜底。**不得把后两者说成「AI 生成」**；
 * 2) 拒答（`answerable=false`）不是错误：按 `refusalReason` 给人话原因 + 转人工，**不编造答案**；
 * 3) `[n]` 编号只做高亮/定位（编号来自服务端，前端不改号、不补号、不重排）；
 * 4) `degraded` 一律展示服务端 `reason`（它已是中文人话），不自行编造降级原因。
 */

/** 示例问题（演示用；知识库标题类命中最好，W33 实测口径） */
export const RAG_PRESET_QUESTIONS: readonly string[] = [
  '物业费单价',
  '缴费期限',
  '公摊电费',
  '报修流程',
]

type Tone = 'g' | 'b' | 'a' | 'r' | 'v' | 'n'

/** 模式 → 徽标文案（与 OpenAPI `RagMode` 一一对应，不新造枚举） */
export const RAG_MODE_LABELS: Readonly<Record<RagMode, string>> = {
  generative: '生成模式',
  retrieval_only: '仅检索模式',
  rules: '规则检索兜底',
}

/** 模式 → 徽标色（generative 绿 / 仅检索 蓝 / 规则兜底 琥珀 = 越靠人工越暖色） */
export const RAG_MODE_TONES: Readonly<Record<RagMode, Tone>> = {
  generative: 'g',
  retrieval_only: 'b',
  rules: 'a',
}

/** 模式 → 一句人话解释（页面徽标旁展示，避免把降级链路误读成生成） */
export const RAG_MODE_NOTES: Readonly<Record<RagMode, string>> = {
  generative: 'LLM 生成 + 引用校验（[n] 必须落在来源编号内）',
  retrieval_only: '未调用生成模型：返回知识原文片段 + [n] 编号，不是生成答案',
  rules: '编排层不可用，已降级为规则检索（沿用 /knowledge/search 口径，仅原文片段）',
}

/** 模式文案（未知值原样展示，不编造含义） */
export function ragModeLabel(mode?: RagMode | string | null): string {
  if (!mode) return '未知模式'
  return RAG_MODE_LABELS[mode as RagMode] ?? String(mode)
}

/** 模式色（未知值用中性色） */
export function ragModeTone(mode?: RagMode | string | null): Tone {
  return RAG_MODE_TONES[mode as RagMode] ?? 'n'
}

/** 模式解释（未知值为空串，页面不硬编解释） */
export function ragModeNote(mode?: RagMode | string | null): string {
  return RAG_MODE_NOTES[mode as RagMode] ?? ''
}

/* ------------------------------- 拒答人话 ------------------------------- */

export interface RefusalCopy {
  /** 一句话原因（页面标题） */
  title: string
  /** 为什么会拒答（口径说明） */
  detail: string
  /** 下一步给用户的建议 */
  next: string
}

/**
 * 拒答原因 → 人话（OpenAPI `RagRefusalReason` 四值全覆盖）。
 * 阈值/模型配置类细节不给具体数字：数值由服务端配置，前端硬编码会漂移。
 */
export const RAG_REFUSAL_COPY: Readonly<Record<RagRefusalReason, RefusalCopy>> = {
  no_evidence: {
    title: '知识库没有收录相关内容',
    detail: '按知识秒查红线：无出处不得作答，所以本次不生成答案（不猜测、不编造）。',
    next: '换个问法（带条款名/位置）再试，或点击「转人工」由客服跟进；该问题会进入知识运营的缺口池。',
  },
  low_confidence: {
    title: '命中依据不足（相关度低于服务端阈值）',
    detail: '检索到的最相关条款相关度低于配置阈值，为避免答错，本次不生成答案。',
    next: '补充关键词后重试（如「公摊电费怎么算」），或转人工确认。',
  },
  citation_invalid: {
    title: '答案引用校验未通过',
    detail: '生成答案里的 [n] 引用与来源编号不匹配（越界/缺失），按引用红线已丢弃该答案。',
    next: '请重试；若持续出现，请转人工并反馈知识运营核查索引。',
  },
  out_of_scope: {
    title: '超出可答范围',
    detail: '问题超出本项目知识范围或你的数据范围（越权/跨项目），按红线不对外作答。',
    next: '确认问的是本项目知识；需要跨项目信息请转人工处理。',
  },
}

/** 拒答原因 → 人话（未知/缺省给通用口径，不编造具体原因） */
export function refusalCopy(reason?: RagRefusalReason | string | null): RefusalCopy {
  const known = reason ? RAG_REFUSAL_COPY[reason as RagRefusalReason] : undefined
  if (known) return known
  return {
    title: '本次未给出答案',
    detail: '服务端判定为不可答，页面不生成答案（不编造）。',
    next: '换个问法重试，或点击「转人工」由客服跟进。',
  }
}

/** 是否为契约内的拒答原因（页面据此标注「原因码」；未知值原样展示） */
export function isKnownRefusalReason(reason?: string | null): reason is RagRefusalReason {
  return Boolean(reason) && (RAG_REFUSAL_REASONS as readonly string[]).includes(String(reason))
}

/* ------------------------------ 降级 / 重排 ------------------------------ */

/**
 * 降级人话：**优先展示服务端 `degraded.reason`**（编排层给的中文原因，如「编排层响应超时，已降级为规则检索」）；
 * 只在服务端没给 reason 时用通用兜底。`fallback=human` 时补一句「已转人工兜底」。
 */
export function ragDegradedText(result?: Pick<RagQueryResult, 'degraded'> | null): string {
  const degraded = result?.degraded
  if (!degraded) return ''
  const reason = (degraded.reason ?? '').trim()
  const base = reason || '编排层返回降级标记'
  const fallback = degraded.fallback === 'human' ? '；链路已转人工兜底' : ''
  const retry = degraded.retryable ? '（可稍后重试）' : ''
  return `${base}${fallback}${retry}`
}

/** 重排人话：`reranked=false` = 原序透传（不等于「没有排序」，只是没走 LLM 重排） */
export function ragRerankedText(reranked: boolean): string {
  return reranked ? '已重排（LLM-as-rerank）' : '未重排（检索原序透传）'
}

/** 相关度/置信度 → 百分比展示（服务端 0—1，前端只格式化，不参与排序/判定） */
export function scorePercent(score?: number | null): string {
  if (typeof score !== 'number' || Number.isNaN(score)) return '—'
  return `${Math.round(score * 100)}%`
}

/* --------------------------- 答案 [n] 引用切分 --------------------------- */

export type AnswerSegment =
  | { kind: 'text'; text: string }
  | { kind: 'cite'; text: string; index: number }

/**
 * 把答案文本按 `[n]` 切成「文本 / 引用」段，用于高亮与点击定位来源卡片。
 * - 只识别 1—2 位数字编号（契约 `RagSource.index` 从 1 起）；
 * - **不改写、不重排、不补号**：没有 `[n]` 就原样一段文本；编号是否越界由服务端校验。
 */
export function splitAnswerCitations(answer: string): AnswerSegment[] {
  const text = answer ?? ''
  if (!text) return []
  const segments: AnswerSegment[] = []
  const pattern = /\[(\d{1,2})\]/g
  let cursor = 0
  let match: RegExpExecArray | null
  while ((match = pattern.exec(text)) !== null) {
    const start = match.index
    if (start > cursor) segments.push({ kind: 'text', text: text.slice(cursor, start) })
    segments.push({ kind: 'cite', text: match[0], index: Number(match[1]) })
    cursor = start + match[0].length
  }
  if (cursor < text.length) segments.push({ kind: 'text', text: text.slice(cursor) })
  return segments
}

/** 答案中出现的引用编号（升序去重；用于「来源卡片高亮」与 e2e 对拍） */
export function citedIndexes(segments: readonly AnswerSegment[]): number[] {
  const set = new Set<number>()
  for (const segment of segments) if (segment.kind === 'cite') set.add(segment.index)
  return [...set].sort((a, b) => a - b)
}

/* ------------------------------- 健康口径 ------------------------------- */

const HEALTH_STATUS_LABELS: Readonly<Record<string, string>> = {
  ok: '正常',
  degraded: '降级',
  down: '不可用',
  // 服务端当前可能给 `off`（重排器关闭）；契约 `RagComponent.status` 枚举只有 ok/degraded/down，
  // 这里做容错展示（原值仍在 data-status 里，不在前端改写契约）。
  off: '已关闭',
}

const HEALTH_STATUS_TONES: Readonly<Record<string, Tone>> = {
  ok: 'g',
  degraded: 'a',
  down: 'r',
  off: 'n',
}

/** 组件状态文案（未知值原样展示，不编造） */
export function healthStatusLabel(status?: string | null): string {
  if (!status) return '未知'
  return HEALTH_STATUS_LABELS[status] ?? String(status)
}

/** 组件状态色 */
export function healthStatusTone(status?: string | null): Tone {
  return HEALTH_STATUS_TONES[String(status ?? '')] ?? 'n'
}

/** 组件名 → 中文（编排层/生成/向量化/向量库/重排；未知值原样） */
export function healthComponentLabel(name?: string | null): string {
  const labels: Readonly<Record<string, string>> = {
    orchestrator: '编排层',
    llm: '生成模型（LLM）',
    embedding: '向量化（Embedding）',
    vectorStore: '向量库（VectorStore）',
    reranker: '重排器（Reranker）',
  }
  if (!name) return '组件'
  return labels[name] ?? String(name)
}

/** 汇总状态 → 人话（ok/degraded/down；未知值给中性说明） */
export function healthSummary(health?: RagHealth | null): string {
  if (!health) return ''
  if (health.status === 'ok') return 'RAG 链路正常：编排层、生成、向量化、向量库全部可用。'
  if (health.status === 'down') {
    return 'RAG 链路不可用：编排层或向量库不可达，问答会走规则检索兜底或直接转人工。'
  }
  if (health.status === 'degraded') {
    const bad = (health.components ?? [])
      .filter((item) => item.status !== 'ok')
      .map((item) => healthComponentLabel(item.name))
    const names = bad.length > 0 ? bad.join('、') : '部分组件'
    return `RAG 链路降级运行：${names} 处于降级/关闭状态，问答会退化为仅检索或规则模式（非生成答案）。`
  }
  return `RAG 链路状态「${health.status}」（服务端原值展示）。`
}

/** 生成时间展示（只格式化，不做时区换算） */
export function healthGeneratedAt(health?: RagHealth | null): string {
  const iso = health?.generatedAt
  if (!iso) return '—'
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return iso
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}
