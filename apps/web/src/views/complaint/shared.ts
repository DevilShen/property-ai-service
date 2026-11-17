import {
  COMPLAINT_LEVEL_LABELS,
  COMPLAINT_STATUS_LABELS,
  formatIsoMinute,
} from '@/api'
import type {
  Complaint,
  ComplaintAuditEntry,
  ComplaintLevel,
  ComplaintStatus,
  ComplaintTransitionAction,
} from '@/api'

/**
 * P3 W12 投诉前端共享口径（P3-021~023，契约 v1.1.0 §12.3）
 * ===========================================================================
 * 只做「展示派生」：枚举、中文文案、SLA 分钟数全部来自 `@/api`（`packages/shared` 单一事实源），
 * 本文件**不新写一套枚举**，只补前端需要的：
 * 1) 视觉 tone / 短文案 / 图标语义（由 shared 文案派生）；
 * 2) SLA 倒计时与人话（`slaDueAt` 由服务端给出，前端只做时间差格式化，不改口径）；
 * 3) audit[] → 人话时间线（staff 视角）与 owner 可见进度（**不含内部升级人/审计细节**）；
 * 4) 脱敏：房号在列表中按「楼栋-单元-房间后 2 位掩码」展示（`3-2-18**`），
 *    完整房号只在 staff 详情头部展示（业务需要）；owner 侧展示本户房号。
 *
 * 红线（AGENTS.md）：
 * - 投诉处置必须人工：前端只推进状态机与留痕，不自动回复、不自动结案；
 * - 不展示手机号/身份证；`contactTail4` 只提交后四位、不回显完整号码；
 * - 不编造数据：SLA、统计、工单号全部来自服务端响应。
 */

/** 投诉分类字典（OpenAPI `Complaint.category` 描述示例；P3 不锁死枚举，未知 code 原样展示） */
export const COMPLAINT_CATEGORY_LABELS: Readonly<Record<string, string>> = {
  noise: '噪音扰民',
  parking: '停车管理',
  fee: '费用争议',
  attitude: '服务态度',
  public_area: '公共区域',
  other: '其它',
}

/** 分类 code → 人话（未知 code 原样返回，不编造含义） */
export function categoryLabel(code?: string | null): string {
  const key = (code ?? '').trim()
  if (!key) return '未分类'
  return COMPLAINT_CATEGORY_LABELS[key] ?? key
}

/** 分类下拉选项（固定演示字典 + 未知值不追加，避免把后端字典写成前端契约） */
export const COMPLAINT_CATEGORY_OPTIONS: readonly { value: string; label: string }[] = Object.entries(
  COMPLAINT_CATEGORY_LABELS,
).map(([value, label]) => ({ value, label }))

/** 状态 → WTag tone（仅视觉，不改语义） */
export const COMPLAINT_STATUS_TONES: Readonly<Record<ComplaintStatus, 'g' | 'b' | 'a' | 'r' | 'v' | 'n'>> = {
  submitted: 'n',
  accepted: 'b',
  processing: 'v',
  escalated: 'a',
  closed: 'g',
  reopened: 'r',
}

/** 级别 → WTag tone（L1 最紧急） */
export const COMPLAINT_LEVEL_TONES: Readonly<Record<ComplaintLevel, 'g' | 'b' | 'a' | 'r' | 'v' | 'n'>> = {
  L1: 'r',
  L2: 'a',
  L3: 'b',
}

/** 级别短文案（由 shared 长文案「一级（立即接管…）」派生，不另写一套语义） */
export function levelLabelShort(level: ComplaintLevel): string {
  const long = COMPLAINT_LEVEL_LABELS[level] ?? level
  return long.split('（')[0] ?? long
}

/** 流转动作 → 人话按钮文案（`ComplaintTransitionAction` 全集） */
export const COMPLAINT_ACTION_LABELS: Readonly<Record<ComplaintTransitionAction, string>> = {
  accept: '受理',
  first_response: '首次响应',
  escalate: '升级主管',
  close: '结案',
  reopen: '重开',
}

/** 动作对应所需能力（与 Java `PermissionMatrix.complaintTransitionCapability` + 契约 README §7.2 同口径） */
export const COMPLAINT_ACTION_CAPABILITIES: Readonly<Record<ComplaintTransitionAction, string>> = {
  accept: 'complaint.handle',
  first_response: 'complaint.handle',
  escalate: 'complaint.escalate',
  close: 'complaint.handle',
  reopen: 'complaint.read',
}

/** audit 动作 code → 人话（staff 时间线；未知 code 原样展示，不编造） */
export const COMPLAINT_AUDIT_LABELS: Readonly<Record<string, string>> = {
  submitted: '业主提交',
  accept: '受理',
  first_response: '首次响应',
  escalate: '升级主管',
  close: '结案',
  reopen: '重开',
  note: '备注',
}

export function auditActionLabel(action: string): string {
  return COMPLAINT_AUDIT_LABELS[action] ?? action
}

/**
 * 状态机「当前状态可用的动作」——仅用于**前端入口显隐与提示**；
 * 真正的判定在服务端（非法迁移 409 `E-3002` + `details.allowed`），前端隐藏不是安全边界。
 * 口径与契约 `complaintTransition` 表格一致。
 */
export const COMPLAINT_ALLOWED_ACTIONS: Readonly<Record<ComplaintStatus, readonly ComplaintTransitionAction[]>> = {
  submitted: ['accept', 'close'],
  accepted: ['first_response', 'escalate', 'close'],
  processing: ['first_response', 'escalate', 'close'],
  escalated: ['first_response', 'close'],
  closed: ['reopen'],
  reopened: ['accept', 'first_response', 'close'],
}

/** 当前状态下按「能力 + 状态」可用的动作（顺序固定：受理 → 首响 → 升级 → 结案 → 重开） */
export function availableActions(
  status: ComplaintStatus,
  hasCapability: (capability: string) => boolean,
): ComplaintTransitionAction[] {
  const allowed = COMPLAINT_ALLOWED_ACTIONS[status] ?? []
  return (['accept', 'first_response', 'escalate', 'close', 'reopen'] as const).filter(
    (action) => allowed.includes(action) && hasCapability(COMPLAINT_ACTION_CAPABILITIES[action]),
  )
}

/** 服务端 409 `details.allowed` → 人话动作列表（未知 code 原样展示） */
export function allowedActionLabels(allowed: readonly string[]): string[] {
  return allowed.map((action) => COMPLAINT_ACTION_LABELS[action as ComplaintTransitionAction] ?? action)
}

/* ------------------------------------------------------------------ 时间格式 */

const pad = (value: number): string => String(value).padStart(2, '0')

/** 毫秒差 → `HH:MM:SS`（≥24h 前缀 `N 天`）；负数取绝对值 */
export function formatCountdown(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000))
  const days = Math.floor(total / 86_400)
  const hours = Math.floor((total % 86_400) / 3_600)
  const minutes = Math.floor((total % 3_600) / 60)
  const seconds = total % 60
  const clock = `${pad(hours)}:${pad(minutes)}:${pad(seconds)}`
  return days > 0 ? `${days} 天 ${clock}` : clock
}

/** 毫秒差 → `N 分钟` / `N 小时 M 分`（首响用时口径） */
export function formatMinutes(ms: number): string {
  const total = Math.max(0, Math.round(ms / 60_000))
  if (total < 60) return `${total} 分钟`
  const hours = Math.floor(total / 60)
  const minutes = total % 60
  return minutes === 0 ? `${hours} 小时` : `${hours} 小时 ${minutes} 分`
}

function timeOf(iso?: string | null): number | null {
  if (!iso) return null
  const value = new Date(iso).getTime()
  return Number.isFinite(value) ? value : null
}

/* --------------------------------------------------------------------- SLA */

export interface ComplaintSlaView {
  /** pending：未首响且未超时；responded：已首响；breached：超时（未首响已过期 / 首响晚于截止） */
  kind: 'pending' | 'responded' | 'breached'
  /** 列表/详情展示文案（倒计时或首响用时） */
  text: string
  /** 是否命中 SLA 超时（红色「已超时」口径） */
  breached: boolean
  /** 倒计时毫秒（仅 pending 有意义；其它状态为 0） */
  remainingMs: number
  /** WTag tone */
  tone: 'g' | 'b' | 'a' | 'r'
  /** 明细：截止时间人话 */
  dueText: string
}

/**
 * 首响 SLA 视图（**只用服务端给的 `slaDueAt` / `firstResponseAt` 做时间差**）：
 * - 已首响：`已首响 · 用时 N 分钟`；晚于 `slaDueAt` 标红「首响超时」；
 * - 未首响：`剩 HH:MM:SS`（<30 分钟转 amber）；已过期 → 红色「已超时 HH:MM:SS」。
 */
export function complaintSlaView(complaint: Complaint, nowMs: number): ComplaintSlaView {
  const due = timeOf(complaint.slaDueAt)
  const first = timeOf(complaint.firstResponseAt)
  const created = timeOf(complaint.createdAt)
  const dueText = complaint.slaDueAt ? `首响 SLA 截止 ${formatIsoMinute(complaint.slaDueAt)}` : '首响 SLA 未返回'

  if (first !== null) {
    const usedMs = created !== null ? first - created : null
    const breached = due !== null && first > due
    return {
      kind: breached ? 'breached' : 'responded',
      text: breached
        ? `首响超时${usedMs !== null ? ` · 用时 ${formatMinutes(usedMs)}` : ''}`
        : `已首响${usedMs !== null ? ` · 用时 ${formatMinutes(usedMs)}` : ''}`,
      breached,
      remainingMs: 0,
      tone: breached ? 'r' : 'g',
      dueText,
    }
  }

  if (due === null) {
    return { kind: 'pending', text: 'SLA 未返回', breached: false, remainingMs: 0, tone: 'a', dueText }
  }

  const remaining = due - nowMs
  if (remaining > 0) {
    return {
      kind: 'pending',
      text: `剩 ${formatCountdown(remaining)}`,
      breached: false,
      remainingMs: remaining,
      tone: remaining <= 30 * 60_000 ? 'a' : 'b',
      dueText,
    }
  }

  return {
    kind: 'breached',
    text: `已超时 ${formatCountdown(-remaining)}`,
    breached: true,
    remainingMs: remaining,
    tone: 'r',
    dueText,
  }
}

/* ------------------------------------------------------------- staff 时间线 */

export interface ComplaintAuditItem {
  at: string
  action: string
  title: string
  note: string
}

/** staff 视角 audit[]（人话动作 + 操作人岗位名 + 备注；服务端已对 owner 姓名/手机号脱敏） */
export function complaintAuditTimeline(complaint: Complaint): ComplaintAuditItem[] {
  return (complaint.audit ?? []).map((entry: ComplaintAuditEntry) => ({
    at: formatIsoMinute(entry.at),
    action: entry.action,
    title: `${auditActionLabel(entry.action)} · ${entry.who || '系统'}`,
    note: entry.note?.trim() ?? '',
  }))
}

/* -------------------------------------------------------- owner 可见进度口径 */

/**
 * owner 可见状态文案（**业主侧只展示业主可见文案**）：
 * - 直接复用 shared `COMPLAINT_STATUS_LABELS`；
 * - `escalated` 在业主侧统一表述为「处理中（加急）」：不暴露内部升级层级/升级人（README §7.2 隐私口径）。
 */
export const OWNER_STATUS_LABELS: Readonly<Record<ComplaintStatus, string>> = {
  submitted: COMPLAINT_STATUS_LABELS.submitted,
  accepted: COMPLAINT_STATUS_LABELS.accepted,
  processing: COMPLAINT_STATUS_LABELS.processing,
  escalated: '处理中（加急）',
  closed: COMPLAINT_STATUS_LABELS.closed,
  reopened: COMPLAINT_STATUS_LABELS.reopened,
}

/** owner 可见的处理说明：只取首次响应 / 结案 / 重开的业主可见备注，**不含升级原因与操作人** */
export function ownerResolutionText(complaint: Complaint): string {
  const ownerVisible = new Set(['first_response', 'close'])
  const entries = (complaint.audit ?? []).filter(
    (entry) => ownerVisible.has(entry.action) && Boolean(entry.note?.trim()),
  )
  const close = [...entries].reverse().find((entry) => entry.action === 'close')
  if (close?.note?.trim()) return close.note.trim()
  const first = entries.find((entry) => entry.action === 'first_response')
  return first?.note?.trim() ?? ''
}

export interface OwnerProgressView {
  statusLabel: string
  statusTone: 'g' | 'b' | 'a' | 'r' | 'v' | 'n'
  /** 首响时间人话（未首响时给「物业尚未首次响应」） */
  firstResponseText: string
  /** SLA 说明：已首响给用时；未首响给截止/超时 */
  slaText: string
  slaBreached: boolean
  /** 处理说明（业主可见备注；无则空串） */
  resolution: string
  /** 业主可见进度节点（不含 escalate / 操作人 / 内部审计细节） */
  timeline: { at: string; label: string }[]
}

/**
 * owner 可见进度（契约 README §7.2：业主侧只展示「状态 / 首响时间 / 处理说明」）。
 * 明确**不展示**：升级人 `escalatedTo`、audit 里的 staff 姓名、内部备注（escalate 原因）。
 */
export function ownerProgressView(complaint: Complaint, nowMs: number): OwnerProgressView {
  const sla = complaintSlaView(complaint, nowMs)
  const ownerVisibleActions = new Set(['submitted', 'accept', 'first_response', 'close', 'reopen'])
  return {
    statusLabel: OWNER_STATUS_LABELS[complaint.status] ?? complaint.status,
    statusTone: COMPLAINT_STATUS_TONES[complaint.status] ?? 'n',
    firstResponseText: complaint.firstResponseAt
      ? `物业已于 ${formatIsoMinute(complaint.firstResponseAt)} 首次响应`
      : '物业尚未首次响应（已进入受理队列）',
    slaText:
      sla.kind === 'pending'
        ? `承诺首响剩余 ${sla.text.replace(/^剩\s*/, '')}`
        : sla.kind === 'responded'
          ? sla.text
          : '首响已超时：物业将按加急流程跟进',
    slaBreached: sla.breached,
    resolution: ownerResolutionText(complaint),
    timeline: (complaint.audit ?? [])
      .filter((entry) => ownerVisibleActions.has(entry.action))
      .map((entry) => ({
        at: formatIsoMinute(entry.at),
        label: entry.action === 'submitted' ? '投诉已提交' : entry.action === 'accept' ? '物业已受理' : entry.action === 'first_response' ? '物业已首次响应' : entry.action === 'close' ? '投诉已处理完成' : '投诉已重新打开',
      })),
  }
}

/* -------------------------------------------------------------------- 脱敏 */

/**
 * 房号列表脱敏：`3-2-1802` → `3-2-18**`（保留楼栋/单元，房间号只留前段）。
 * 说明：列表用于排队浏览，按交付口径「房号(掩码)」展示；staff 详情页头部展示完整房号以支撑上门处置。
 */
export function maskRoomCode(roomCode?: string | null): string {
  const value = (roomCode ?? '').trim()
  if (!value) return '—'
  const parts = value.split('-')
  if (parts.length === 3 && parts[2]) {
    const room = parts[2]
    const keep = Math.max(1, room.length - 2)
    return `${parts[0]}-${parts[1]}-${room.slice(0, keep)}${'*'.repeat(room.length - keep)}`
  }
  if (value.length <= 2) return value
  return `${value.slice(0, value.length - 2)}**`
}

/**
 * 演示账号 `owner-<roomCode>` → 本户房号（与 Java `ComplaintService#ownerRoomOfActorId` 同约定）。
 * 契约 `Actor` 无 householdId 字段（冻结），P3 用演示账号约定解析；P5 接真实身份后由 SSO 声明注入。
 */
export function ownerRoomOfActorId(actorId?: string | null): string {
  const id = (actorId ?? '').trim()
  if (!id.startsWith('owner-')) return ''
  const room = id.slice('owner-'.length).trim()
  return /^\d{1,2}-\d{1,2}-\d{3,4}$/.test(room) ? room : ''
}
