/**
 * 管理端动作弹层公共类型（P1-033）
 * 六个管理端视图（费用/映射/编排/回溯/知识/看板）共用，避免各页重复定义。
 */

/* ------------------------------- 筛选 ------------------------------- */

export type FilterFieldType = 'select' | 'multi' | 'range'

export interface FilterOption {
  label: string
  value: string
  /** 可选：右侧计数文案（例如「15—30 天 · 84 户」） */
  hint?: string
}

export interface FilterField {
  key: string
  label: string
  type: FilterFieldType
  /** select / multi 的候选值 */
  options?: FilterOption[]
  /** select 的「不限」文案，默认「全部」 */
  emptyLabel?: string
  /** range 的最小 / 最大 / 步长 / 单位 */
  min?: number
  max?: number
  step?: number
  unit?: string
  hint?: string
}

/** select → string；multi → string[]；range → [min, max] */
export type FilterValue = string | string[] | [number, number]
export type FilterValues = Record<string, FilterValue>

/* ------------------------------- 导出 ------------------------------- */

export interface CsvColumn {
  key: string
  title: string
}

export type CsvRow = Record<string, unknown>

export interface DownloadCsvOptions {
  /** 文件名主体：`<baseName>-20261114.csv` */
  baseName: string
  columns: CsvColumn[]
  rows: CsvRow[]
  /** 覆盖日期（默认今天），仅测试/回放用 */
  date?: Date
  /** 直接指定完整文件名（优先级高于 baseName/date） */
  filename?: string
}

export interface DownloadCsvResult {
  filename: string
  rowCount: number
  content: string
}

/* ------------------------------- 试跑 ------------------------------- */

export interface TrialDiff {
  title: string
  detail: string
  expected?: string
  actual?: string
  level?: 'high' | 'mid'
}

export interface TrialRunPayload {
  passed: number
  total: number
  diffCount: number
}

/* ------------------------------- 降级 ------------------------------- */

export interface DegradeOption {
  key: string
  label: string
  description: string
  tone?: 'g' | 'b' | 'a'
}

export interface DegradePayload {
  strategy: string
  strategyLabel: string
  note: string
  receiptCode: string
}

/* ------------------------------- 知识 ------------------------------- */

export interface KnowledgeDraft {
  title: string
  category: string
  version: string
  period: string
  owner: string
  content: string
}

/* ------------------------------- 催缴 ------------------------------- */

export interface CollectionTaskPayload {
  tiers: string[]
  channels: string[]
  sendAt: string
  script: string
}

export interface CollectionChannel {
  key: string
  label: string
  /** 红线渠道（如电话外呼）不可勾选 */
  disabled?: boolean
  note?: string
}
