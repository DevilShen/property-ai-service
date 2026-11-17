import type {
  CapabilityStatus,
  ConnectorHealth,
  ConnectorStatus,
  DegradedFallback,
  MappingDiffKind,
  MappingVersionStatus,
  ReconDiffKind,
  ReconRunStatus,
  ReconScope,
} from '@/api'

/**
 * ERP 适配中心（P2 W7）展示口径
 * ---------------------------------------------------------------------------
 * 只做「契约枚举 → 中文标签 / 视觉 tone」的映射，不复制契约里的业务类型：
 * - 枚举来源：`@/api`（re-export 自 `packages/shared` §11.8，与 OpenAPI v0.3.2 一致）；
 * - 本文件不含任何业务数字，页面上的数字一律来自 API 响应（禁止在页面硬编码）；
 * - tone 与 `WTag` 色板一致：g=品牌绿 / b=蓝 / a=琥珀 / r=红 / v=AI 紫 / n=中性。
 */
export type TagTone = 'g' | 'b' | 'a' | 'r' | 'v' | 'n'

export interface EnumMeta {
  label: string
  tone: TagTone
}

/** 连接器生命周期状态（架构 §4.1：注册草稿/灰度/已发布/已退役） */
export const CONNECTOR_STATUS_META: Record<ConnectorStatus, EnumMeta> = {
  draft: { label: '注册草稿', tone: 'n' },
  gray: { label: '灰度中', tone: 'a' },
  published: { label: '已发布', tone: 'g' },
  retired: { label: '已退役', tone: 'n' },
}

/**
 * 连接器健康度（架构 §4.3 四态；UI 按三态呈现：健康 / 降级 / 离线，`unknown` 显示未探测）。
 * `error` 即任务口径的 offline（离线/异常），这里同时给出契约原值与三态标签，避免语义漂移。
 */
export const CONNECTOR_HEALTH_META: Record<
  ConnectorHealth,
  EnumMeta & { healthTone: 'healthy' | 'degraded' | 'offline' | 'unknown'; hint: string }
> = {
  healthy: {
    label: '健康',
    tone: 'g',
    healthTone: 'healthy',
    hint: '最近健康探测通过（contract: healthy）',
  },
  degraded: {
    label: '降级',
    tone: 'a',
    healthTone: 'degraded',
    hint: '已降级：入口保留 + 替代通道（contract: degraded）',
  },
  error: {
    label: '离线/异常',
    tone: 'r',
    healthTone: 'offline',
    hint: '不可用：按离线处理，入口隐藏或转人工（contract: error）',
  },
  unknown: {
    label: '未探测',
    tone: 'n',
    healthTone: 'unknown',
    hint: '尚无探测结果，能力计数按注册声明展示（contract: unknown）',
  },
}

/** 单能力三态（架构 §4.3/§6：支持 / 降级 / 不支持） */
export const CAPABILITY_STATE_META: Record<CapabilityStatus, EnumMeta & { entry: string; hint: string }> = {
  supported: {
    label: '支持',
    tone: 'g',
    entry: '入口可用',
    hint: '探测通过：入口可用，按标准契约承诺',
  },
  degraded: {
    label: '降级',
    tone: 'a',
    entry: '入口降级保留',
    hint: '入口保留但能力受限，必须展示降级路径与替代通道',
  },
  unsupported: {
    label: '不支持',
    tone: 'n',
    entry: '入口已隐藏',
    hint: '入口隐藏、话术不承诺，走替代通道或转人工',
  },
}

/** 映射版本状态机（草稿 → 试跑通过 → 已发布 → 已回滚） */
export const MAPPING_STATUS_META: Record<MappingVersionStatus, EnumMeta> = {
  draft: { label: '草稿', tone: 'n' },
  trial_passed: { label: '试跑通过', tone: 'b' },
  published: { label: '已发布', tone: 'g' },
  rolled_back: { label: '已回滚', tone: 'a' },
}

/** 映射版本状态 → 允许的动作（与 BFF `MappingService.allowedActions` / 409 details.allowed 同口径） */
export const MAPPING_ALLOWED_ACTIONS: Record<MappingVersionStatus, readonly string[]> = {
  draft: ['trial'],
  trial_passed: ['publish', 'trial'],
  published: ['rollback', 'trial'],
  rolled_back: ['trial'],
}

/** 配置动作码 → 中文（错误 409 的 `details.allowed` 人话提示） */
export const MAPPING_ACTION_LABEL: Record<string, string> = {
  trial: '试跑',
  publish: '发布',
  rollback: '回滚',
}

export const MAPPING_DIFF_KIND_LABEL: Record<MappingDiffKind, string> = {
  missing: '字段缺失',
  type_mismatch: '类型不一致',
  required_missing: '必填缺失',
}

/** 降级替代通道（与信封 `DegradedInfo.fallback` 同码） */
export const DEGRADED_FALLBACK_LABEL: Record<DegradedFallback, string> = {
  alt_connector: '备用连接器',
  cache: '缓存兜底',
  mock: '演示数据',
  human: '转人工',
  partial: '部分返回',
  none: '无替代路径',
}

/** 对账范围（房档 / 账单 / 工单） */
export const RECON_SCOPE_META: Record<ReconScope, string> = {
  room: '房档',
  bill: '账单',
  ticket: '工单',
}

export const RECON_STATUS_META: Record<ReconRunStatus, EnumMeta> = {
  queued: { label: '排队中', tone: 'n' },
  running: { label: '执行中', tone: 'b' },
  completed: { label: '已完成', tone: 'g' },
  failed: { label: '失败', tone: 'r' },
}

export const RECON_DIFF_KIND_LABEL: Record<ReconDiffKind, string> = {
  missing: '缺失',
  field_drift: '字段漂移',
  amount_mismatch: '金额不一致',
  status_mismatch: '状态不一致',
}

/** 比例展示（0.01 → `1.00%`；空值不造数） */
export function formatRate(rate?: number | null): string {
  if (rate === null || rate === undefined || Number.isNaN(rate)) return '—'
  return `${(rate * 100).toFixed(2)}%`
}

/** ISO 时间 → `YYYY-MM-DD HH:mm`（解析失败原样返回） */
export function formatDateTime(iso?: string | null): string {
  if (!iso) return '—'
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return iso
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

/** ISO 时间 → `HH:mm` */
export function formatClock(iso?: string | null): string {
  if (!iso) return '—'
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return iso
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${pad(date.getHours())}:${pad(date.getMinutes())}`
}

/** 能力状态码 → 中文（含探测前未知态，用于「变化」对比展示） */
export function stateLabelOf(state?: CapabilityStatus | 'unknown'): string {
  if (!state) return '未知'
  if (state === 'unknown') return '未知'
  return CAPABILITY_STATE_META[state].label
}
