import {
  KNOWLEDGE_CATEGORY_LABELS,
  KNOWLEDGE_STATUSES,
  formatIsoMinute,
} from '@/api'
import type {
  KnowledgeCategory,
  KnowledgeChangeStatus,
  KnowledgeItemStatus,
  KnowledgeOwnerStat,
  KnowledgeVersion,
  ReviewDecision,
} from '@/api'

/**
 * P4 W15 知识运营中心前端共享口径（P4-007/008，契约 v1.2.0 §13）
 * ===========================================================================
 * 只做「展示派生」，**不新写枚举**：
 * - 状态/分类/变更状态/审核决定的取值集合全部来自 `@/api`（`packages/shared` §13 单一事实源）；
 * - 本文件只补前端需要的中文短文案、WTag tone、生效期展示与「是否可对外作答」的**只读判定**；
 * - 回执缓存（`localStorage`）只存**服务端 201 响应**（changeId/status/时间），用于 it/admin 找到待审
 *   变更单的入口；它**不是**业务事实源，服务端仍按 changeId 校验，409/403 一律以服务端为准。
 *
 * 红线（AGENTS.md「不编造知识」）：
 * - 过期（expired）/草稿（draft）/待审（pending_review）/驳回（rejected）**不得对外作答**，
 *   页面必须标注；检索结果只来自服务端（`GET /knowledge/search` 只回 published + 生效期）；
 * - 版本引用必须带版本号，不允许前端补写版本或补全正文。
 */

/** 知识条目状态中文短文案（`KnowledgeItemStatus` 全集，与 shared 枚举一一对应） */
export const KNOWLEDGE_STATUS_LABELS: Readonly<Record<KnowledgeItemStatus, string>> = {
  published: '生效中',
  expired: '已过期',
  draft: '草稿',
  pending_review: '待审核',
  rejected: '审核驳回',
}

/** 状态 → WTag tone（仅视觉，不改语义） */
export const KNOWLEDGE_STATUS_TONES: Readonly<Record<KnowledgeItemStatus, 'g' | 'b' | 'a' | 'r' | 'v' | 'n'>> = {
  published: 'g',
  expired: 'r',
  draft: 'n',
  pending_review: 'a',
  rejected: 'r',
}

/** 状态下拉选项（顺序 = shared `KNOWLEDGE_STATUSES`，禁止前端另排一套） */
export const KNOWLEDGE_STATUS_OPTIONS: readonly { value: KnowledgeItemStatus; label: string }[] =
  KNOWLEDGE_STATUSES.map((status) => ({ value: status, label: KNOWLEDGE_STATUS_LABELS[status] }))

/** 分类下拉选项（11 类，文案来自 shared `KNOWLEDGE_CATEGORY_LABELS`） */
export const KNOWLEDGE_CATEGORY_OPTIONS: readonly { value: KnowledgeCategory; label: string }[] = (
  Object.keys(KNOWLEDGE_CATEGORY_LABELS) as KnowledgeCategory[]
).map((category) => ({ value: category, label: KNOWLEDGE_CATEGORY_LABELS[category] }))

/** 分类 code → 人话（未知 code 原样返回，不编造含义） */
export function categoryLabel(code?: string | null): string {
  const key = (code ?? '').trim()
  if (!key) return '未分类'
  return KNOWLEDGE_CATEGORY_LABELS[key as KnowledgeCategory] ?? key
}

/** 变更单状态中文文案（`KnowledgeChangeStatus` 全集：pending_review → approved | rejected） */
export const KNOWLEDGE_CHANGE_STATUS_LABELS: Readonly<Record<KnowledgeChangeStatus, string>> = {
  pending_review: '待审核（未生效）',
  approved: '已通过（已生效）',
  rejected: '已驳回（未生效）',
}

/** 审核决定中文文案（`ReviewDecision` 全集） */
export const REVIEW_DECISION_LABELS: Readonly<Record<ReviewDecision, string>> = {
  approved: '通过并生效',
  rejected: '驳回',
}

/** 生效期/状态相关的最小字段（mock 基线 `effectiveTo` 可能为 null，读接口会省略该字段） */
export interface KnowledgePeriodLike {
  status?: KnowledgeItemStatus
  effectiveFrom?: string | null
  effectiveTo?: string | null
}

/** 生效期展示：「2026-11-01 起 · 2027-10-31 止 / 长期有效」 */
export function periodText(item: KnowledgePeriodLike): string {
  const from = (item.effectiveFrom ?? '').trim()
  const to = (item.effectiveTo ?? '').trim()
  const fromText = from || '未标注生效日'
  return `${fromText} 起 · ${to ? `${to} 止` : '长期有效'}`
}

/** 业务时区「今天」（`YYYY-MM-DD`，与 Java `TicketTimes.BUSINESS_ZONE` 展示口径一致，不做时区换算） */
export function todayIso(): string {
  const now = new Date()
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`
}

/** `YYYY-MM-DD` 加 N 年（变更表单「原知识无失效日」时预填用，用户可改；不改变任何服务端口径） */
export function addYears(iso: string, years: number): string {
  const parsed = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso.trim())
  if (!parsed) return ''
  const year = Number(parsed[1]) + years
  return `${year}-${parsed[2]}-${parsed[3]}`
}

/**
 * 「是否可对外作答」只读判定（**展示用**，安全边界在服务端 `KnowledgeService.searchable`）：
 * 仅 `published` 且在生效期内可作答；过期/草稿/待审/驳回/未到生效日一律不可对外作答。
 */
export function answerabilityOf(item: KnowledgePeriodLike): {
  answerable: boolean
  note: string
  tone: 'g' | 'a' | 'r' | 'n'
} {
  const status = item.status ?? 'draft'
  const today = todayIso()
  const from = (item.effectiveFrom ?? '').trim()
  const to = (item.effectiveTo ?? '').trim()
  if (status === 'expired') {
    return { answerable: false, note: '已过期 · 不可对外作答（检索不返回，仅运营视图可见）', tone: 'r' }
  }
  if (status !== 'published') {
    return {
      answerable: false,
      note: `${KNOWLEDGE_STATUS_LABELS[status]} · 未生效，不可对外作答（仅运营视图可见）`,
      tone: 'a',
    }
  }
  if (from && today < from) {
    return { answerable: false, note: `未到生效日（${from} 起）· 检索不返回`, tone: 'a' }
  }
  if (to && today > to) {
    return { answerable: false, note: `已过失效日（${to}）· 不可对外作答，待责任人复核`, tone: 'r' }
  }
  return { answerable: true, note: '生效中 · 可对外作答（引用必须带版本）', tone: 'g' }
}

/** 版本历史表格行（已转成展示文案；页面不需要再做类型断言） */
export interface KnowledgeVersionRow {
  version: string
  statusLabel: string
  statusTone: 'g' | 'b' | 'a' | 'r' | 'v' | 'n'
  effectiveFrom: string
  submittedAtText: string
  submittedBy: string
  changeText: string
  noteText: string
}

/** 版本历史 → 表格行（提交时间升序，服务端已排序；前端只补中文状态文案） */
export function versionRows(versions: readonly KnowledgeVersion[]): Record<string, unknown>[] {
  return versions.map((version) => {
    const row: KnowledgeVersionRow = {
      version: version.version,
      statusLabel: KNOWLEDGE_STATUS_LABELS[version.status] ?? version.status,
      statusTone: KNOWLEDGE_STATUS_TONES[version.status] ?? 'n',
      effectiveFrom: version.effectiveFrom || '—',
      submittedAtText: formatIsoMinute(version.submittedAt) || '—',
      submittedBy: version.submittedBy || '—',
      changeText: version.changeId || '基线版本',
      noteText: version.reviewNote || '—',
    }
    return row as unknown as Record<string, unknown>
  })
}

/** 责任人分布 → 表格行（数字全部来自服务端 `/knowledge/stats`） */
export function ownerRows(owners: readonly KnowledgeOwnerStat[]): Record<string, unknown>[] {
  return owners.map((owner) => ({ ...owner }))
}

/* ============================================================================
 * 变更回执缓存（本机 localStorage）
 * ----------------------------------------------------------------------------
 * 契约没有「变更单列表」端点：`POST /change` 返回的 `changeId` 是 it/admin 审核的唯一入口。
 * 因此把**服务端 201 响应**按知识缓存到本机，用于：
 * 1) staff 提交后本页立刻显示「待审核」态；
 * 2) it/admin 在同一浏览器切换演示账号后能找到待审变更单并审核。
 * 服务端仍是唯一事实源（409 invalid_transition / 403 self_review_forbidden 以服务端为准）。
 * ========================================================================== */

export const KNOWLEDGE_RECEIPT_STORAGE_KEY = 'property.knowledge.receipts'

/** 变更回执（= 服务端 `KnowledgeChange` + 展示用的知识标题） */
export interface KnowledgeChangeReceipt {
  changeId: string
  knowledgeId: string
  knowledgeTitle: string
  status: KnowledgeChangeStatus
  submittedBy: string
  submittedAt: string
  reason?: string
}

function readAllReceipts(): KnowledgeChangeReceipt[] {
  if (typeof localStorage === 'undefined') return []
  try {
    const raw = localStorage.getItem(KNOWLEDGE_RECEIPT_STORAGE_KEY)
    if (!raw) return []
    const parsed = JSON.parse(raw) as unknown
    if (!Array.isArray(parsed)) return []
    return parsed.filter((item): item is KnowledgeChangeReceipt => {
      const receipt = item as Partial<KnowledgeChangeReceipt>
      return typeof receipt.changeId === 'string' && typeof receipt.knowledgeId === 'string'
    })
  } catch {
    return []
  }
}

function writeAllReceipts(receipts: readonly KnowledgeChangeReceipt[]): void {
  if (typeof localStorage === 'undefined') return
  try {
    localStorage.setItem(KNOWLEDGE_RECEIPT_STORAGE_KEY, JSON.stringify(receipts.slice(0, 30)))
  } catch {
    // 隐私模式/配额满：忽略（回执只是审核入口便利，不影响服务端事实）
  }
}

/** 读取某知识的待审核回执（新 → 旧） */
export function pendingReceiptsOf(knowledgeId: string): KnowledgeChangeReceipt[] {
  return readAllReceipts().filter(
    (receipt) => receipt.knowledgeId === knowledgeId && receipt.status === 'pending_review',
  )
}

/** 读取全部待审核回执（新 → 旧；it/admin「待审变更」面板用） */
export function allPendingReceipts(): KnowledgeChangeReceipt[] {
  return readAllReceipts().filter((receipt) => receipt.status === 'pending_review')
}

/** 写入/覆盖一条回执（同一 changeId 覆盖，避免重复） */
export function saveReceipt(receipt: KnowledgeChangeReceipt): void {
  const rest = readAllReceipts().filter((item) => item.changeId !== receipt.changeId)
  writeAllReceipts([receipt, ...rest])
}

/** 审核完成（approved/rejected 终态）后移除回执，避免「已处理还显示待审」 */
export function removeReceipt(changeId: string): void {
  writeAllReceipts(readAllReceipts().filter((item) => item.changeId !== changeId))
}
