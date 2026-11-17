import { errorCodeCopy } from '@/content'
import type { DegradedFallback, DegradedInfo, ErrorCode } from './contract'

/**
 * 错误码 / 降级 → 人话（P2 薄封装）
 * ---------------------------------------------------------------------------
 * 1) 错误码人话复用 `src/content/action-copy.ts` 的 `ERROR_CODE_COPY`（17 类唯一映射，
 *    content 为只读复用，本文件**不改其内部实现**，只做页面需要的便捷取用）；
 * 2) `degraded` 文案是 P2 新增口径（content 的降级句式面向 P1 通用场景），这里按
 *    `DegradedFallback` 替代路径给「发生了什么 + 下一步 + 写操作是否暂停」，不编造业务数字；
 * 3) 页面禁止直接展示内部错误码与上游报文，一律经由本文件转人话。
 */

/** 契约错误码集合（运行期判定；与 shared `ERROR_CODES` 同源，不新造字符串） */
const KNOWN_CODES = new Set<string>([
  'E-1001',
  'E-1002',
  'E-1003',
  'E-2001',
  'E-2002',
  'E-2003',
  'E-3001',
  'E-3002',
  'E-3003',
  'E-4001',
  'E-4002',
  'E-4003',
  'E-5001',
  'E-5002',
  'E-6001',
  'E-6002',
  'E-9001',
])

/** 是否 17 类契约错误码（网络/HTTP_xxx 等非契约码不展示给用户） */
export function isErrorCode(code: string): code is ErrorCode {
  return KNOWN_CODES.has(code)
}

/** 错误码 → 面向用户的人话（不含内部码/金额/工单号）；非契约码回落 E-9001 口径 */
export function errorText(code: string): string {
  return errorCodeCopy(code).userMessage
}

/** 错误码 → 下一步动作（转人工 / 重试 / 补齐信息） */
export function errorNextStep(code: string): string {
  return errorCodeCopy(code).nextStep
}

/** 错误码 → 建议 Toast 语气 */
export function errorTone(code: string): 'error' | 'warning' {
  return errorCodeCopy(code).tone
}

/** 降级是否出现（`degraded` 未出现即未降级） */
export function isDegraded(degraded?: DegradedInfo | null): degraded is DegradedInfo {
  return Boolean(degraded)
}

const FALLBACK_TEXT: Readonly<Record<DegradedFallback, string>> = {
  alt_connector: '已切换备用连接器，数据可能略有延迟',
  cache: '数据来自缓存快照，仅供参考',
  mock: '当前为演示数据（mock ERP），非真实 ERP 结果',
  human: '上游系统暂不可用，已转人工处理',
  partial: '只返回已确认部分，其余字段缺失',
  none: '上游系统暂不可用，本次未返回数据',
}

/**
 * 降级标记 → 人话（一条，可直接放降级条）。
 * 例：`已降级：数据来自缓存快照，仅供参考；账单类写操作已暂停，需要即时结果请转人工。`
 */
export function degradedText(degraded?: DegradedInfo | null): string {
  if (!degraded) return ''
  const fallback = FALLBACK_TEXT[degraded.fallback] ?? '上游系统暂不可用'
  const paused = degraded.fallback === 'human' ? '写操作已暂停，' : ''
  const retry = degraded.retryable ? '稍后可重试。' : '需要即时结果请转人工。'
  const at = degraded.dataAsOf ? `（数据时点 ${formatIsoMinute(degraded.dataAsOf)}）` : ''
  return `已降级：${fallback}${at}；${paused}${retry}`
}

/** `mock: true` 时的统一角标文案（不得把演示数据说成真实 ERP） */
export const mockBadgeText = '演示数据（mock ERP）'

/** ISO 时间 → `MM-DD HH:mm`（展示口径；解析失败原样返回） */
export function formatIsoMinute(iso?: string | null): string {
  if (!iso) return ''
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return iso
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

/** ISO 时间 → `HH:mm`（时间轴节点用） */
export function formatIsoTime(iso?: string | null): string {
  if (!iso) return ''
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return iso
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${pad(date.getHours())}:${pad(date.getMinutes())}`
}

/** 金额（元，定点两位）；由 ERP 给出，前端只格式化不自算 */
export function formatMoney(amount?: number | null): string {
  if (amount === null || amount === undefined || Number.isNaN(amount)) return ''
  return amount.toFixed(2)
}
