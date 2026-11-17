<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { WButton, WCard, WEmpty, WLoading, WModal, WPageHeader, WTable, WTag, useToast } from '@/components'
import PermissionDenied from '@/components/feedback/PermissionDenied.vue'
import {
  CollectionTaskModal,
  ExportModal,
  FilterModal,
  type CollectionTaskPayload,
  type CsvColumn,
  type FilterField,
  type FilterValues,
} from '@/components/admin-actions'
import { SCREENS } from '@/app/screens'
import { ROLE_LABELS } from '@/app/roles'
import { capabilityLabel } from '@/auth/capabilities'
import { useAuthStore } from '@/stores/auth'
import { demoNote, emptyCopy } from '@/content'
import StateDemo from '@/content/StateDemo.vue'
import type { TableColumn } from '@/types'
import {
  ARREARS_BUCKETS,
  ARREARS_BUCKET_LABELS,
  api,
  ApiError,
  degradedText,
  errorNextStep,
  errorText,
  forbiddenDetailsOf,
  formatIsoMinute,
  formatMoney,
  mockBadgeText,
} from '@/api'
import type {
  ActorRole,
  ArrearsBucket,
  ArrearsItem,
  ArrearsListResult,
  BillExplanation,
  BillExplanationRef,
  BillInfo,
  BillListResult,
  BillStatus,
  DegradedInfo,
} from '@/api'
import DunningWorkbench from './DunningWorkbench.vue'
import FeeBoard from './FeeBoard.vue'

/**
 * 06 费用与催缴（P1-018 布局骨架 + P3 W10 真实数据）
 * ===========================================================================
 * 数据口径（契约 v1.1.0 §7.2 / §12；AGENTS.md §3 统一契约）：
 * - **金额全部来自 API**：`total/outstanding/lateFee/amount` 由收费系统返回，前端只格式化，
 *   不做任何加总/重算；汇总（户数/金额）取 `/fees/arrears` 的 `summary`，不是前端求和。
 * - **欠费分层**：`GET /api/v1/fees/arrears`（D1/D7/D15/D30 + `summary.buckets` + 分页）；
 *   owner 视角仅本户（服务端强制过滤），页面不显示分层汇总。
 * - **账单列表**：`GET /api/v1/bills`（列表模式 `period`/`status` + 分页；带 `roomCode` 时走单账单模式）。
 * - **账单明细**：`GET /api/v1/bills?roomCode=&period=`（items 拆项 + `lateFee`/`overdueDays`/`arrearsDays`）。
 * - **费用解释**：`GET /api/v1/fees/explain`（`formula` + `breakdown` + `diffVsPrev` + `knowledgeRefs`）；
 *   每条解释可点开「知识出处」，`mock:true` 标注「演示数据（mock ERP）」，无出处不编造。
 * - **权限**：steward/supervisor 可见管理视图；it/admin 调费用接口 → 403 `E-2001`，
 *   页面渲染既有 `PermissionDenied` 无权限态且**不渲染任何金额**。
 *
 * 保留 P1 视觉骨架与既有钩子：`data-page="billing"`、`billing-export-open`、`billing-task-open`、
 * `billing-stat-note`、`billing-filter-open`、`billing-filter-summary`、`billing-task-open-side`。
 * 缺数据时展示空态/错误态/降级态，**不拿 mock 金额充数**。
 */
const screen = SCREENS.billing
const toast = useToast()
const auth = useAuthStore()

/* ------------------------------------------------------------------ 常量 */

/** 列表页大小（服务端分页：`page`/`pageSize` 由契约定义，前端只传参不改分页语义） */
const PAGE_SIZE = 10
/** P3 费用数据由 mock-erp 演示替身提供：台账没有 `mock` 字段，这里显式标注来源，不冒充真实 ERP */
const ARREARS_SOURCE_NOTE = '欠费台账来自 mock-erp 演示替身（/api/v1/fees/arrears），金额以收费系统返回为准'
/** 账单状态中文（展示文案；枚举值本身来自 shared `BillStatus`，不复制契约类型） */
const BILL_STATUS_LABELS: Readonly<Record<BillStatus, string>> = {
  unpaid: '未缴',
  partial: '部分缴',
  paid: '已缴清',
  overdue: '逾期',
  void: '作废',
}
const BILL_STATUSES: readonly BillStatus[] = ['unpaid', 'partial', 'paid', 'overdue', 'void']
/** 分层色调（D1 最轻 → D30 最重） */
const BUCKET_TONE: Readonly<Record<ArrearsBucket, 'n' | 'b' | 'a' | 'r'>> = {
  D1: 'n',
  D7: 'b',
  D15: 'a',
  D30: 'r',
}
const CHANNEL_LABEL: Readonly<Record<string, string>> = { wecom: '企微私信', sms: '短信', call: '电话外呼' }
/** 空态文案统一来自 `src/content`（P1-036），页面不另写一套 */
const billingEmpty = emptyCopy('billing')

/** 当前自然月（`YYYY-MM`）：作为账单列表默认账期，可手改 */
function currentPeriod(): string {
  const now = new Date()
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`
}

/** 金额展示（ERP 定点两位；无值不编造，显示「—」） */
function money(amount?: number | null): string {
  const text = formatMoney(amount)
  return text ? `¥${text}` : '—'
}

function codeOf(error: unknown): string {
  return error instanceof ApiError ? error.code : 'E-9001'
}

function degradedOf(error: unknown): DegradedInfo | undefined {
  return error instanceof ApiError ? error.degraded : undefined
}

/** 表格行 → 强类型（WTable 行是 `Record<string, unknown>`，模板里不做 `as` 断言） */
function arrearsOf(row: unknown): ArrearsItem {
  return row as ArrearsItem
}

function billOf(row: unknown): BillInfo {
  return row as BillInfo
}

/** 账单状态 → 中文 + 色调（枚举值来自 shared `BillStatus`，这里只做展示映射） */
function billStatusText(bill: BillInfo): string {
  return bill.status ? BILL_STATUS_LABELS[bill.status] : '—'
}

function billStatusTone(bill: BillInfo): 'g' | 'a' | 'n' {
  if (bill.status === 'paid') return 'g'
  if (bill.status === 'void') return 'n'
  return 'a'
}

/* ------------------------------------------------------------- 表头（P1 骨架） */

const arrearsColumns: TableColumn[] = [
  { key: 'owner', title: '业主 / 房号', width: '150px' },
  { key: 'period', title: '账期', width: '82px' },
  { key: 'total', title: '应缴', width: '88px', align: 'right' },
  { key: 'outstanding', title: '欠费', width: '92px', align: 'right' },
  { key: 'overdue', title: '逾期天数', width: '80px', align: 'right' },
  { key: 'bucket', title: '逾期分层', width: '110px' },
  { key: 'lateFee', title: '滞纳金', width: '84px', align: 'right' },
  { key: 'action', title: '操作', width: '64px' },
]

const billColumns: TableColumn[] = [
  { key: 'room', title: '房号', width: '120px' },
  { key: 'period', title: '账期', width: '82px' },
  { key: 'total', title: '应缴', width: '88px', align: 'right' },
  { key: 'outstanding', title: '欠费', width: '92px', align: 'right' },
  { key: 'overdue', title: '逾期天数', width: '80px', align: 'right' },
  { key: 'arrearsDays', title: '连续欠费', width: '86px', align: 'right' },
  { key: 'lateFee', title: '滞纳金', width: '84px', align: 'right' },
  { key: 'status', title: '状态', width: '80px' },
  { key: 'action', title: '操作', width: '64px' },
]

/* ------------------------------------------------------- 403 无权限（整页） */

interface DeniedInfo {
  requiredCapability: string
  role: string
  traceId: string
}

const denied = ref<DeniedInfo | null>(null)
const switching = ref(false)
/** 可切换账号白名单：既能进 /billing、又具备 `bill.query` 的演示角色（owner 不可见本页） */
const deniedCandidates: readonly ActorRole[] = ['steward', 'supervisor']
const currentRoleLabel = computed(() => (auth.role ? ROLE_LABELS[auth.role] : '未登录'))
const deniedDetail = computed(() => {
  const info = denied.value
  if (!info) return ''
  return (
    `费用台账 / 账单解释需要能力 ${capabilityLabel(info.requiredCapability)}；当前演示角色「${currentRoleLabel.value}」不具备该能力（后端 403 E-2001）。` +
    '本页不读取、不渲染任何未授权金额（不编造数据）。可切换管家 / 主管演示账号后重新读取。'
  )
})

function deniedOf(error: unknown): DeniedInfo {
  const forbidden = forbiddenDetailsOf(error)
  return {
    requiredCapability: forbidden?.requiredCapability || 'bill.query',
    role: forbidden?.role || (auth.role ?? ''),
    traceId: error instanceof ApiError ? error.traceId : '',
  }
}

/** 无权限态「重新校验一次」：先给可见反馈，再按当前身份重取（仍是 403 时保持无权限态） */
function retryAfterDenied(): void {
  toast.warning(
    `无权限：需要 ${capabilityLabel('bill.query')} 能力，当前演示角色「${currentRoleLabel.value}」不具备；已重新校验一次`,
  )
  denied.value = null
  void loadArrears()
}

/** 从无权限态切换演示账号：换演示 JWT（`auth.role` 变化由 watch 触发按新身份重取） */
async function switchAccount(role: ActorRole): Promise<void> {
  if (switching.value) return
  switching.value = true
  try {
    const ok = await auth.loginAsRole(role)
    if (!ok) {
      toast.error(`演示账号切换失败：${auth.failure?.message ?? '登录接口不可用'}`)
      return
    }
    toast.info(`已切换演示账号：${ROLE_LABELS[role]}（demo，非真实鉴权），正在重新读取费用数据…`)
  } finally {
    switching.value = false
  }
}

/* --------------------------------------------------------- 主视图：页签 */

type BillingTab = 'arrears' | 'bills'
const tab = ref<BillingTab>('arrears')
/** owner 视角：仅本户（服务端强制），不展示分层汇总 */
const ownerScope = computed(() => auth.role === 'owner')

function switchTab(next: BillingTab): void {
  tab.value = next
  if (next === 'bills' && !bills.value && !billsLoading.value) void loadBills()
  if (next === 'arrears' && !arrears.value && !arrearsLoading.value) void loadArrears()
}

/* --------------------------------------- 页面级视图（台账 / 催缴工作台 / 费用看板） */

/**
 * P3 W11：`/billing` 拆成三个页面级视图（路由不变，保留 P1 骨架与既有 data-testid）：
 * - `ledger` 费用台账（W10 既有：四层卡 + 台账 / 账单列表 + 解释卡）；
 * - `dunning` 催缴工作台（任务/话术/人工确认发送/频次红线/关闭，P3-016/018）；
 * - `board` 费用看板（欠费分层 + 按账期趋势 + 催缴漏斗，P3-017）。
 * 催缴入口按 `dunning.task` 能力门控（owner/it/admin 不显示，真正边界仍是 Java 403）。
 */
type BillingView = 'ledger' | 'dunning' | 'board'
const view = ref<BillingView>('ledger')
const canDunning = computed(() => auth.hasCapability('dunning.task'))

function switchView(next: BillingView): void {
  if (next === 'dunning' && !canDunning.value) return
  view.value = next
}

/* ------------------------------------------------ 欠费台账（/fees/arrears） */

const arrears = ref<ArrearsListResult | null>(null)
const arrearsLoading = ref(false)
const arrearsError = ref('')
const arrearsDegraded = ref<DegradedInfo | undefined>(undefined)
const bucketFilter = ref<'' | ArrearsBucket>('')
const keyword = ref('')
const arrearsPage = ref(1)

const arrearsItems = computed<ArrearsItem[]>(() => arrears.value?.items ?? [])
const filteredArrears = computed<ArrearsItem[]>(() => {
  const text = keyword.value.trim()
  return text
    ? arrearsItems.value.filter((item) => `${item.roomCode}${item.ownerNameMasked}`.includes(text))
    : arrearsItems.value
})
const arrearsRows = computed<Record<string, unknown>[]>(() =>
  filteredArrears.value.map((item) => ({ ...item } as unknown as Record<string, unknown>)),
)
const arrearsPageCount = computed(() => Math.max(1, Math.ceil((arrears.value?.total ?? 0) / PAGE_SIZE)))
const summaryRooms = computed(() => arrears.value?.summary.totalRooms ?? 0)
const summaryAmount = computed(() => arrears.value?.summary.totalAmount ?? 0)
const statNote = computed(
  () =>
    `当前筛选 ${arrears.value?.total ?? 0} 户 · 欠费合计 ${arrears.value ? money(summaryAmount.value) : '—'}（API 汇总，非前端求和）`,
)

interface BucketCard {
  bucket: ArrearsBucket
  rooms: number | null
  amount: number | null
}

/** 四层卡数据：`summary.buckets`（顺序固定 D1→D30）；未加载时显示「—」，不填 mock 数字 */
const bucketCards = computed<BucketCard[]>(() => {
  const stats = arrears.value?.summary.buckets ?? []
  return ARREARS_BUCKETS.map((bucket) => {
    const stat = stats.find((item) => item.bucket === bucket)
    return { bucket, rooms: stat ? stat.rooms : null, amount: stat ? stat.amount : null }
  })
})

const activeFilterChips = computed<string[]>(() => {
  const chips: string[] = []
  if (bucketFilter.value) chips.push(`逾期分层 ${ARREARS_BUCKET_LABELS[bucketFilter.value]}`)
  if (keyword.value.trim()) chips.push(`关键词 ${keyword.value.trim()}`)
  return chips
})

const filterSummaryText = computed(() => {
  if (tab.value === 'bills') {
    const parts = [`账期 ${billPeriod.value || '全部'}`]
    if (billStatus.value) parts.push(`状态 ${BILL_STATUS_LABELS[billStatus.value]}`)
    if (billRoom.value.trim()) parts.push(`房号 ${billRoom.value.trim()}`)
    return `账单列表 · ${parts.join(' · ')} · 共 ${bills.value?.total ?? 0} 条`
  }
  return activeFilterChips.value.length
    ? `已筛选：${activeFilterChips.value.join(' · ')} · 共 ${arrears.value?.total ?? 0} 户`
    : `筛选：全部分层 · 共 ${arrears.value?.total ?? 0} 户`
})

async function loadArrears(): Promise<void> {
  if (denied.value) return
  arrearsLoading.value = true
  arrearsError.value = ''
  try {
    const { data, degraded } = await api.fee.arrears({
      bucket: bucketFilter.value || undefined,
      page: arrearsPage.value,
      pageSize: PAGE_SIZE,
    })
    arrears.value = data
    arrearsDegraded.value = degraded
    ensureSelection(data.items)
  } catch (error) {
    arrears.value = null
    if (forbiddenDetailsOf(error)) {
      denied.value = deniedOf(error)
      return
    }
    arrearsError.value = `${errorText(codeOf(error))}；${errorNextStep(codeOf(error))}`
    arrearsDegraded.value = degradedOf(error)
  } finally {
    arrearsLoading.value = false
  }
}

function pickBucket(next: '' | ArrearsBucket): void {
  bucketFilter.value = next
  arrearsPage.value = 1
  void loadArrears()
}

function pickBucketCard(bucket: ArrearsBucket): void {
  if (ownerScope.value) return
  pickBucket(bucketFilter.value === bucket ? '' : bucket)
}

function goArrearsPage(page: number): void {
  const next = Math.min(Math.max(page, 1), arrearsPageCount.value)
  if (next === arrearsPage.value) return
  arrearsPage.value = next
  void loadArrears()
}

/* ------------------------------------------------- 账单列表（/bills） */

const bills = ref<BillListResult | null>(null)
const billsLoading = ref(false)
const billsError = ref('')
const billsDegraded = ref<DegradedInfo | undefined>(undefined)
const billPeriod = ref(currentPeriod())
const billStatus = ref<'' | BillStatus>('')
const billRoom = ref('')
const billsPage = ref(1)
const billsPageCount = computed(() => Math.max(1, Math.ceil((bills.value?.total ?? 0) / PAGE_SIZE)))
const billRows = computed<Record<string, unknown>[]>(() =>
  (bills.value?.items ?? []).map((item) => ({ ...item } as unknown as Record<string, unknown>)),
)

async function loadBills(): Promise<void> {
  if (denied.value) return
  billsLoading.value = true
  billsError.value = ''
  const room = billRoom.value.trim()
  try {
    if (room) {
      // 房号 + 账期 → 单账单模式（契约 `GET /bills?roomCode=&period=`）
      const { data, degraded } = await api.bill.query({ roomCode: room, period: billPeriod.value })
      bills.value = { items: [data], page: 1, pageSize: 1, total: 1 }
      billsDegraded.value = degraded
      applyBillSelection(data)
    } else {
      const period = /^\d{4}-\d{2}$/.test(billPeriod.value) ? billPeriod.value : undefined
      const { data, degraded } = await api.bill.list({
        period,
        status: billStatus.value || undefined,
        page: billsPage.value,
        pageSize: PAGE_SIZE,
      })
      bills.value = data
      billsDegraded.value = degraded
      const first = data.items[0]
      if (first) applyBillSelection(first)
      else clearSelection()
    }
  } catch (error) {
    bills.value = null
    if (forbiddenDetailsOf(error)) {
      denied.value = deniedOf(error)
      return
    }
    billsError.value = `${errorText(codeOf(error))}；${errorNextStep(codeOf(error))}`
    billsDegraded.value = degradedOf(error)
  } finally {
    billsLoading.value = false
  }
}

function goBillsPage(page: number): void {
  const next = Math.min(Math.max(page, 1), billsPageCount.value)
  if (next === billsPage.value) return
  billsPage.value = next
  void loadBills()
}

/** 用户点「查询」：列表可能完全没变，仍要给可见回执（成功 Toast 带条数，失败错误 Toast） */
async function applyBillQuery(): Promise<void> {
  billsPage.value = 1
  await loadBills()
  if (denied.value) return
  if (billsError.value) {
    toast.error(`账单查询未完成：${billsError.value}`)
    return
  }
  const parts = [`账期 ${billPeriod.value || '全部'}`]
  if (billStatus.value) parts.push(`状态 ${BILL_STATUS_LABELS[billStatus.value]}`)
  if (billRoom.value.trim()) parts.push(`房号 ${billRoom.value.trim()}`)
  toast.success(`已查询账单 · ${parts.join(' · ')} · 共 ${bills.value?.total ?? 0} 条`)
}

function resetBillQuery(): void {
  billPeriod.value = currentPeriod()
  billStatus.value = ''
  billRoom.value = ''
  billsPage.value = 1
  void loadBills()
  toast.info('账单筛选已重置：回到当前账期全部账单')
}

/* ------------------------------- 明细 + 解释（/bills 单账单 + /fees/explain） */

const selectedKey = ref('')
const selectedRoom = ref('')
const selectedPeriod = ref('')
const detailBill = ref<BillInfo | null>(null)
const detailLoading = ref(false)
const detailError = ref('')
const detailDegraded = ref<DegradedInfo | undefined>(undefined)
const explain = ref<BillExplanation | null>(null)
const explainLoading = ref(false)
const explainError = ref('')
const explainForbidden = ref(false)
const explainDegraded = ref<DegradedInfo | undefined>(undefined)
const knowledgeOpen = ref(false)
const knowledgeRef = ref<BillExplanationRef | null>(null)
let detailSeq = 0
let explainSeq = 0

const detailStatusText = computed(() => (detailBill.value?.status ? BILL_STATUS_LABELS[detailBill.value.status] : ''))

const detailDataTime = computed(() => {
  const bill = detailBill.value
  if (!bill) return ''
  const freshness = bill.freshness
  if (freshness) {
    const source = freshness.hit ? `缓存命中 · TTL ${freshness.ttlSeconds}s` : freshness.source
    return `数据时间 ${formatIsoMinute(freshness.dataAsOf)}（${source}）`
  }
  return `数据时点 ${formatIsoMinute(bill.dataAsOf)}`
})

const explainDataTime = computed(() =>
  explain.value ? `数据时点 ${formatIsoMinute(explain.value.dataAsOf)}` : '',
)

function clearSelection(): void {
  selectedKey.value = ''
  selectedRoom.value = ''
  selectedPeriod.value = ''
  detailBill.value = null
  detailError.value = ''
  detailDegraded.value = undefined
  explain.value = null
  explainError.value = ''
  explainForbidden.value = false
  explainDegraded.value = undefined
}

function ensureSelection(items: ArrearsItem[]): void {
  if (!items.length) {
    clearSelection()
    return
  }
  if (items.some((item) => arrearsKeyOf(item) === selectedKey.value)) return
  applyArrearsSelection(items[0])
}

function arrearsKeyOf(item: ArrearsItem): string {
  return `${item.roomCode}@${item.period}`
}

/** 台账行是否为当前选中行（操作列 secondary / ghost 区分，点任意行都有可见变化） */
function isArrearsSelected(item: ArrearsItem): boolean {
  return arrearsKeyOf(item) === selectedKey.value
}

/** 内部选中（列表加载 / 筛选后默认首行）：不弹 Toast，避免自动加载变成刷屏 */
function applyArrearsSelection(item: ArrearsItem): void {
  selectedKey.value = arrearsKeyOf(item)
  selectedRoom.value = item.roomCode
  selectedPeriod.value = item.period
  detailBill.value = null
  detailError.value = ''
  detailDegraded.value = undefined
  void loadBillDetail(item.roomCode, item.period)
  void loadExplain(item.roomCode, item.period)
}

/**
 * 用户点「明细」（含已默认选中的首行）：P3 门禁要求点击必有可见反馈——
 * 选中态高亮 + Toast 回执（金额来自收费系统返回，前端不重算、不编造）。
 */
function selectArrearsItem(item: ArrearsItem): void {
  applyArrearsSelection(item)
  toast.info(
    `已载入明细：${item.roomCode} · ${item.period} · 应缴 ${money(item.total)} · 欠费 ${money(item.outstanding)}（收费系统口径）`,
  )
}

/** 账单行是否为当前选中行 */
function isBillSelected(bill: BillInfo): boolean {
  const room = bill.roomCode ?? ''
  return Boolean(room) && `${room}@${bill.period}` === selectedKey.value
}

/** 内部选中（账单列表 / 单账单加载后默认首行）：不弹 Toast */
function applyBillSelection(bill: BillInfo): void {
  const room = bill.roomCode ?? ''
  selectedKey.value = room ? `${room}@${bill.period}` : ''
  selectedRoom.value = room
  selectedPeriod.value = bill.period
  detailBill.value = bill
  detailLoading.value = false
  detailDegraded.value = billsDegraded.value
  detailError.value = room ? '' : '账单缺少房号，无法加载费用解释（不编造）'
  if (room) void loadExplain(room, bill.period)
  else {
    explain.value = null
    explainError.value = ''
    explainForbidden.value = false
  }
}

/** 用户点「明细」：选中态高亮 + Toast 回执（默认首行点击也有可见变化） */
function selectBill(bill: BillInfo): void {
  applyBillSelection(bill)
  const status = billStatusText(bill)
  toast.info(
    `已载入账单明细：${bill.roomCode || '—'} · ${bill.period} · 应缴 ${money(bill.total)}${status ? ` · ${status}` : ''}（收费系统口径）`,
  )
}

async function loadBillDetail(roomCode: string, period: string): Promise<void> {
  const seq = ++detailSeq
  detailLoading.value = true
  try {
    const { data, degraded } = await api.bill.query({ roomCode, period })
    if (seq !== detailSeq) return
    detailBill.value = data
    detailDegraded.value = degraded
  } catch (error) {
    if (seq !== detailSeq) return
    detailBill.value = null
    if (forbiddenDetailsOf(error)) {
      denied.value = deniedOf(error)
      return
    }
    detailError.value = `${errorText(codeOf(error))}；${errorNextStep(codeOf(error))}`
    detailDegraded.value = degradedOf(error)
  } finally {
    if (seq === detailSeq) detailLoading.value = false
  }
}

async function loadExplain(roomCode: string, period: string): Promise<void> {
  const seq = ++explainSeq
  explainLoading.value = true
  explainError.value = ''
  explainForbidden.value = false
  explain.value = null
  try {
    const { data, degraded } = await api.fee.explain({ roomCode, period })
    if (seq !== explainSeq) return
    explain.value = data
    explainDegraded.value = degraded
  } catch (error) {
    if (seq !== explainSeq) return
    explain.value = null
    explainForbidden.value = forbiddenDetailsOf(error)?.reason === 'out_of_scope'
    explainError.value = errorText(codeOf(error))
    explainDegraded.value = degradedOf(error)
  } finally {
    if (seq === explainSeq) explainLoading.value = false
  }
}

function openKnowledge(ref: BillExplanationRef): void {
  knowledgeRef.value = ref
  knowledgeOpen.value = true
}

/* ------------------------------------------------------------- 筛选弹层 */

const filterOpen = ref(false)
const filterFields = computed<FilterField[]>(() => [
  {
    key: 'tier',
    label: '逾期分层（服务端筛选）',
    type: 'select',
    emptyLabel: '全部分层',
    options: bucketCards.value.map((card) => ({
      label:
        card.rooms === null
          ? ARREARS_BUCKET_LABELS[card.bucket]
          : `${ARREARS_BUCKET_LABELS[card.bucket]} · ${card.rooms} 户`,
      value: card.bucket,
    })),
    hint: '户数来自 /fees/arrears 的 summary.buckets，不是前端统计',
  },
])
const filterValues = computed<FilterValues>(() => ({ tier: bucketFilter.value }))

function previewFilter(values: FilterValues): number {
  const tier = typeof values.tier === 'string' ? values.tier : ''
  if (!tier) return summaryRooms.value
  return bucketCards.value.find((card) => card.bucket === tier)?.rooms ?? 0
}

function applyFilters(values: FilterValues): void {
  const raw = typeof values.tier === 'string' ? values.tier : ''
  const tier = (ARREARS_BUCKETS as readonly string[]).includes(raw) ? (raw as ArrearsBucket) : ''
  bucketFilter.value = tier
  arrearsPage.value = 1
  void loadArrears()
  toast.success(`筛选已应用：${tier ? ARREARS_BUCKET_LABELS[tier] : '全部分层'}`)
}

function resetFilters(): void {
  bucketFilter.value = ''
  keyword.value = ''
  arrearsPage.value = 1
  void loadArrears()
  toast.info('筛选已重置：回到全部分层')
}

/* --------------------------------------------------------------- 导出 */

const exportOpen = ref(false)
const exported = ref(false)
const exportedNote = ref('')
const exportColumns: CsvColumn[] = [
  { key: 'roomCode', title: '房号' },
  { key: 'ownerNameMasked', title: '业主（掩码）' },
  { key: 'period', title: '账期' },
  { key: 'totalText', title: '应缴合计' },
  { key: 'outstandingText', title: '欠费金额' },
  { key: 'overdueDaysText', title: '逾期天数' },
  { key: 'bucketText', title: '逾期分层' },
  { key: 'lateFeeText', title: '滞纳金' },
]
const exportRows = computed<Record<string, unknown>[]>(() =>
  filteredArrears.value.map((item) => ({
    roomCode: item.roomCode,
    ownerNameMasked: item.ownerNameMasked,
    period: item.period,
    totalText: money(item.total),
    outstandingText: money(item.outstanding),
    overdueDaysText: `${item.overdueDays} 天`,
    bucketText: ARREARS_BUCKET_LABELS[item.bucket],
    lateFeeText: money(item.lateFee),
  })),
)
const exportSummary = computed(
  () =>
    `${filteredArrears.value.length} 户 · ${exportColumns.length} 个字段 · 当前页 ${arrearsPage.value}/${arrearsPageCount.value}` +
    (activeFilterChips.value.length ? ` · 已应用筛选（${activeFilterChips.value.join(' / ')}）` : ' · 未筛选（全部分层）'),
)

function onExported(payload: { filename: string; rowCount: number }): void {
  exported.value = true
  exportedNote.value = `${payload.rowCount} 行 · ${payload.filename}`
}

/* --------------------------------------------------------- 催缴助手（P1 保留） */

type ScriptTone = 'soft' | 'std' | 'firm'
const TONE_LABEL: Readonly<Record<ScriptTone, string>> = { soft: '温和', std: '标准', firm: '坚定' }
const TONES: { key: ScriptTone; label: string }[] = [
  { key: 'soft', label: '温和' },
  { key: 'std', label: '标准' },
  { key: 'firm', label: '坚定' },
]
const taskOpen = ref(false)
const scriptTone = ref<ScriptTone>('soft')
const channels = ref<Record<string, boolean>>({ wecom: true, sms: true, call: false })
const defaultChannels = computed(() => Object.entries(channels.value).filter(([, on]) => on).map(([key]) => key))
const selectedArrears = computed<ArrearsItem | null>(
  () => arrearsItems.value.find((item) => `${item.roomCode}@${item.period}` === selectedKey.value) ?? null,
)
const collectionSubject = computed(() => {
  const item = selectedArrears.value
  if (!item) return { name: '—', room: '—', tier: '—', period: '—', amount: '—' }
  return {
    name: item.ownerNameMasked,
    room: item.roomCode,
    tier: ARREARS_BUCKET_LABELS[item.bucket],
    period: item.period,
    amount: money(item.outstanding),
  }
})
const collectionTiers = computed(() =>
  bucketCards.value.map((card) => ({ label: ARREARS_BUCKET_LABELS[card.bucket], count: card.rooms ?? 0 })),
)

function toggleChannel(key: string): void {
  if (key === 'call') {
    toast.warning('电话外呼受红线限制：22:00—8:00 不外呼，单户每周 ≤2 次；需主管确认后开启（AI 不做语音外呼）')
    return
  }
  channels.value = { ...channels.value, [key]: !channels.value[key] }
}

/** 话术只拼装 API 返回的数字与解释原因，不给折扣承诺、不写减免 */
function scriptFor(tone: ScriptTone): string {
  const item = selectedArrears.value
  if (!item) return '从左侧欠费台账选择一行后生成话术（金额与解释均来自收费系统，不预填数字）。'
  const reason = explain.value?.diffVsPrev?.reason
  const head = `${item.ownerNameMasked}您好，${item.roomCode} ${item.period} 账单应缴 ${money(item.total)}，当前欠费 ${money(item.outstanding)}（逾期 ${item.overdueDays} 天，滞纳金 ${money(item.lateFee)} 由收费系统计算）。`
  const detail = reason ? `本期与上月差额说明：${reason}。` : ''
  if (tone === 'soft') return `${head}${detail}明细与知识出处见账单解释卡，您方便时在小程序点一下缴费就好，如已缴纳请忽略。`
  if (tone === 'std') return `${head}${detail}请在近期完成缴纳；如需逐项核对，我可以转管家为您解释收费依据。`
  return `${head}${detail}请在本周内完成缴纳；逾期将按合同与收费规则继续计收滞纳金（AI 不承诺减免，减免需人工审批）。`
}

function onCollectionCreated(payload: CollectionTaskPayload): void {
  const channelText = payload.channels.map((key) => CHANNEL_LABEL[key] ?? key).join(' + ')
  toast.success(
    `已生成催缴任务：${collectionSubject.value.name} · ${payload.tiers.join(' / ')} · 渠道 ${channelText} · ${payload.sendAt}` +
      demoNote('触达走 mock-channel，未真实发送'),
  )
}

/* ------------------------------------------------------- 生命周期 / 账号切换 */

onMounted(() => {
  void loadArrears()
})

watch(
  () => auth.role,
  () => {
    denied.value = null
    arrears.value = null
    bills.value = null
    arrearsError.value = ''
    billsError.value = ''
    bucketFilter.value = ''
    billsPage.value = 1
    arrearsPage.value = 1
    clearSelection()
    // 角色变化后：无 dunning.task 能力则回到台账视图（催缴工作台入口同时隐藏）
    if (!auth.hasCapability('dunning.task') && view.value === 'dunning') view.value = 'ledger'
    void loadArrears()
  },
)
</script>

<template>
  <div class="w-page billing-page" data-page="billing" data-testid="page-root">
    <WPageHeader :eyebrow="screen.eyebrow" :title="screen.title" :description="screen.description">
      <template #actions>
        <!-- P1 动作入口（data-testid 不变）：动作级权限占位在 CAPABILITIES 里，页面可见即可见；
             数据权限（403）只影响台账/金额区，渲染「无权限」态而不是隐藏动作入口 -->
        <WTag tone="g" plain>数据来自收费系统 · 前端不自算</WTag>
        <WButton size="sm" icon="download" data-testid="billing-export-open" @click="exportOpen = true">
          导出欠费台账
        </WButton>
        <WButton size="sm" variant="primary" icon="arrow-right" data-testid="billing-task-open" @click="taskOpen = true">
          生成催缴任务
        </WButton>
      </template>
    </WPageHeader>

    <!-- ① 无权限（403 E-2001）：不渲染任何金额 -->
    <PermissionDenied
      v-if="denied"
      :required-capability="denied.requiredCapability"
      :required-role="denied.role"
      :trace-id="denied.traceId"
      :detail="deniedDetail"
      :switching="switching"
      :candidate-roles="deniedCandidates"
      @switch="switchAccount"
    >
      <template #actions>
        <WButton size="sm" @click="retryAfterDenied">重新校验一次</WButton>
      </template>
    </PermissionDenied>

    <template v-else>
      <!-- P3 W11 页面级视图切换：费用台账（W10 既有）/ 催缴工作台 / 费用看板；默认台账，既有语义与 testid 不变 -->
      <div class="billing__views" role="tablist" aria-label="费用页面视图">
        <button
          type="button"
          role="tab"
          class="billing__view"
          :class="{ 'is-on': view === 'ledger' }"
          :aria-selected="view === 'ledger'"
          data-testid="billing-view-ledger"
          @click="switchView('ledger')"
        >
          费用台账
        </button>
        <button
          v-if="canDunning"
          type="button"
          role="tab"
          class="billing__view"
          :class="{ 'is-on': view === 'dunning' }"
          :aria-selected="view === 'dunning'"
          data-testid="billing-view-dunning"
          @click="switchView('dunning')"
        >
          催缴工作台
        </button>
        <button
          type="button"
          role="tab"
          class="billing__view"
          :class="{ 'is-on': view === 'board' }"
          :aria-selected="view === 'board'"
          data-testid="billing-view-board"
          @click="switchView('board')"
        >
          费用看板
        </button>
      </div>

      <DunningWorkbench v-if="view === 'dunning'" />
      <FeeBoard v-else-if="view === 'board'" />

      <template v-else>
      <!-- ② 欠费分层 D1/D7/D15/D30：户数/金额取 summary.buckets（owner 视角不显示汇总） -->
      <div v-if="!ownerScope" class="billing__stats">
        <button
          v-for="card in bucketCards"
          :key="card.bucket"
          type="button"
          class="billing__stat"
          :class="{ 'is-on': bucketFilter === card.bucket }"
          :aria-pressed="bucketFilter === card.bucket"
          :data-bucket="card.bucket"
          :data-rooms="card.rooms === null ? '' : card.rooms"
          :data-testid="`billing-bucket-${card.bucket}`"
          @click="pickBucketCard(card.bucket)"
        >
          <span class="billing__stat-lab">{{ ARREARS_BUCKET_LABELS[card.bucket] }}</span>
          <b class="billing__stat-val num">{{ card.rooms === null ? '—' : card.rooms }}<small>户</small></b>
          <span class="billing__stat-delta">欠费合计 {{ card.amount === null ? '—' : money(card.amount) }}</span>
        </button>
      </div>

      <div class="billing__metabar">
        <span data-testid="billing-stat-note">{{ statNote }}</span>
        <span class="billing__metabar-src">{{ ARREARS_SOURCE_NOTE }}</span>
        <WTag v-if="arrearsDegraded" tone="r" plain data-testid="billing-degraded-tag">已降级</WTag>
      </div>

      <p v-if="arrearsDegraded" class="billing__degradebar" data-testid="billing-degraded">
        {{ degradedText(arrearsDegraded) }}
      </p>

      <p v-if="billsDegraded && tab === 'bills'" class="billing__degradebar" data-testid="billing-bills-degraded">
        {{ degradedText(billsDegraded) }}
      </p>

      <div class="billing__grid">
        <div class="billing__main">
          <WCard class="billing__ledger" :padded="false">
            <template #head>
              <span class="billing__card-title">费用清单</span>
              <span class="billing__card-sub">欠费台账按逾期分层（服务端分页）；账单列表按账期 / 状态筛选</span>
            </template>
            <template #extra>
              <WTag tone="n" plain>{{ tab === 'arrears' ? `共 ${arrears?.total ?? 0} 户` : `共 ${bills?.total ?? 0} 条` }}</WTag>
            </template>

            <div class="billing__tabs" role="tablist" aria-label="费用视图">
              <button
                type="button"
                role="tab"
                class="billing__tab"
                :class="{ 'is-on': tab === 'arrears' }"
                :aria-selected="tab === 'arrears'"
                data-testid="billing-tab-arrears"
                @click="switchTab('arrears')"
              >
                欠费台账 · 分层
              </button>
              <button
                type="button"
                role="tab"
                class="billing__tab"
                :class="{ 'is-on': tab === 'bills' }"
                :aria-selected="tab === 'bills'"
                data-testid="billing-tab-bills"
                @click="switchTab('bills')"
              >
                账单列表
              </button>
            </div>

            <!-- 欠费台账工具条 -->
            <div v-if="tab === 'arrears'" class="billing__toolbar">
              <div class="billing__segs" role="group" aria-label="逾期分层">
                <button
                  type="button"
                  class="billing__seg"
                  :class="{ 'is-on': bucketFilter === '' }"
                  :aria-pressed="bucketFilter === ''"
                  data-testid="billing-bucket-all"
                  @click="pickBucket('')"
                >
                  全部 {{ summaryRooms }}
                </button>
                <button
                  v-for="card in bucketCards"
                  :key="card.bucket"
                  type="button"
                  class="billing__seg"
                  :class="{ 'is-on': bucketFilter === card.bucket }"
                  :aria-pressed="bucketFilter === card.bucket"
                  :data-testid="`billing-seg-${card.bucket}`"
                  @click="pickBucket(card.bucket)"
                >
                  {{ card.bucket }} {{ card.rooms === null ? '—' : card.rooms }}
                </button>
              </div>
              <input
                v-model="keyword"
                class="billing__search"
                type="search"
                placeholder="搜房号 / 业主掩码（当前页）"
                data-testid="billing-keyword"
              />
              <WButton size="sm" icon="filter" data-testid="billing-filter-open" @click="filterOpen = true">筛选</WButton>
            </div>

            <!-- 账单列表工具条 -->
            <div v-else class="billing__toolbar">
              <input
                v-model="billPeriod"
                class="billing__input num"
                type="text"
                inputmode="numeric"
                placeholder="账期 YYYY-MM"
                aria-label="账期"
                data-testid="billing-bills-period"
              />
              <select v-model="billStatus" class="billing__input" aria-label="账单状态" data-testid="billing-bills-status">
                <option value="">全部状态</option>
                <option v-for="status in BILL_STATUSES" :key="status" :value="status">
                  {{ BILL_STATUS_LABELS[status] }}
                </option>
              </select>
              <input
                v-model="billRoom"
                class="billing__input"
                type="text"
                placeholder="房号（单账单模式）"
                aria-label="房号"
                data-testid="billing-bills-room"
              />
              <WButton size="sm" variant="primary" data-testid="billing-bills-query" @click="applyBillQuery">查询</WButton>
              <WButton size="sm" variant="ghost" data-testid="billing-bills-reset" @click="resetBillQuery">重置</WButton>
            </div>

            <div class="billing__filtersummary" data-testid="billing-filter-summary">
              <span>{{ filterSummaryText }}</span>
              <button v-if="activeFilterChips.length" type="button" class="billing__filterclear" @click="resetFilters">
                清空筛选
              </button>
            </div>

            <!-- 欠费台账表（金额/欠费/逾期天数/滞纳金全部来自 API） -->
            <template v-if="tab === 'arrears'">
              <div v-if="arrearsError" class="billing__state" data-testid="billing-arrears-error">
                <WEmpty icon="wallet" title="欠费台账暂时无法加载" :description="arrearsError">
                  <WButton size="sm" @click="loadArrears">重新加载</WButton>
                </WEmpty>
              </div>
              <WTable
                v-else
                :columns="arrearsColumns"
                :rows="arrearsRows"
                dense
                :loading="arrearsLoading"
                empty-text="该筛选下没有欠费记录；调整分层或清空关键词后重试。"
                data-testid="billing-arrears-table"
              >
                <template #empty>
                  <WEmpty
                    :icon="billingEmpty.icon"
                    :title="billingEmpty.title"
                    :description="billingEmpty.description"
                    compact
                  />
                </template>
                <template #owner="{ row }">
                  <div class="billing__brow">
                    <b>{{ arrearsOf(row).ownerNameMasked }}</b>
                    <span>{{ arrearsOf(row).roomCode }} · 业主本人</span>
                  </div>
                </template>
                <template #period="{ row }">
                  <span class="num">{{ arrearsOf(row).period }}</span>
                </template>
                <template #total="{ row }">
                  <span class="num">{{ money(arrearsOf(row).total) }}</span>
                </template>
                <template #outstanding="{ row }">
                  <span class="num billing__amount">{{ money(arrearsOf(row).outstanding) }}</span>
                </template>
                <template #overdue="{ row }">
                  <span class="num">{{ arrearsOf(row).overdueDays }} 天</span>
                </template>
                <template #bucket="{ row }">
                  <WTag :tone="BUCKET_TONE[arrearsOf(row).bucket]">
                    {{ ARREARS_BUCKET_LABELS[arrearsOf(row).bucket] }}
                  </WTag>
                </template>
                <template #lateFee="{ row }">
                  <span class="num">{{ money(arrearsOf(row).lateFee) }}</span>
                </template>
                <template #action="{ row }">
                  <WButton
                    size="sm"
                    :variant="isArrearsSelected(arrearsOf(row)) ? 'secondary' : 'ghost'"
                    data-testid="billing-row-detail"
                    @click="selectArrearsItem(arrearsOf(row))"
                  >
                    明细
                  </WButton>
                </template>
                <template #footer>
                  <div class="billing__foot">
                    <span v-if="exported" data-testid="billing-exported-note">已导出 {{ exportedNote }}</span>
                    <span v-else>导出为 CSV（真实下载），字段含房号 / 账期 / 应缴 / 欠费 / 逾期天数 / 分层 / 滞纳金。</span>
                    <span class="billing__foot-note">只读视图：AI 不碰钱，不承诺减免或免滞纳金。</span>
                  </div>
                </template>
              </WTable>
              <div class="billing__pager">
                <WButton size="sm" variant="ghost" :disabled="arrearsPage <= 1" data-testid="billing-prev" @click="goArrearsPage(arrearsPage - 1)">
                  上一页
                </WButton>
                <span class="num" data-testid="billing-pager-info">
                  第 {{ arrearsPage }} / {{ arrearsPageCount }} 页 · 共 {{ arrears?.total ?? 0 }} 户
                </span>
                <WButton
                  size="sm"
                  variant="ghost"
                  :disabled="arrearsPage >= arrearsPageCount"
                  data-testid="billing-next"
                  @click="goArrearsPage(arrearsPage + 1)"
                >
                  下一页
                </WButton>
              </div>
            </template>

            <!-- 账单列表表 -->
            <template v-else>
              <div v-if="billsError" class="billing__state" data-testid="billing-bills-error">
                <WEmpty icon="wallet" title="账单列表暂时无法加载" :description="billsError">
                  <WButton size="sm" @click="loadBills">重新加载</WButton>
                </WEmpty>
              </div>
              <WTable
                v-else
                :columns="billColumns"
                :rows="billRows"
                dense
                :loading="billsLoading"
                empty-text="该账期/状态下没有账单；可切换账期或清空房号后重试。"
                data-testid="billing-bills-table"
              >
                <template #empty>
                  <WEmpty
                    :icon="billingEmpty.icon"
                    title="该账期/状态下没有账单"
                    description="切换账期或状态，或清空房号后重试；账单金额只在接口返回后展示。"
                    compact
                  />
                </template>
                <template #room="{ row }">
                  <div class="billing__brow">
                    <b>{{ billOf(row).roomCode || '—' }}</b>
                    <span>账单行 · 金额来自收费系统</span>
                  </div>
                </template>
                <template #period="{ row }">
                  <span class="num">{{ billOf(row).period }}</span>
                </template>
                <template #total="{ row }">
                  <span class="num">{{ money(billOf(row).total) }}</span>
                </template>
                <template #outstanding="{ row }">
                  <span class="num billing__amount">{{ money(billOf(row).outstanding) }}</span>
                </template>
                <template #overdue="{ row }">
                  <span class="num">{{ billOf(row).overdueDays ?? 0 }} 天</span>
                </template>
                <template #arrearsDays="{ row }">
                  <span class="num">{{ billOf(row).arrearsDays ?? 0 }} 天</span>
                </template>
                <template #lateFee="{ row }">
                  <span class="num">{{ money(billOf(row).lateFee) }}</span>
                </template>
                <template #status="{ row }">
                  <WTag :tone="billStatusTone(billOf(row))" plain>
                    {{ billStatusText(billOf(row)) }}
                  </WTag>
                </template>
                <template #action="{ row }">
                  <WButton
                    size="sm"
                    :variant="isBillSelected(billOf(row)) ? 'secondary' : 'ghost'"
                    data-testid="billing-bill-detail"
                    @click="selectBill(billOf(row))"
                  >
                    明细
                  </WButton>
                </template>
                <template #footer>
                  <div class="billing__foot">
                    <span>账单列表来自 <code>/api/v1/bills</code>；出现「数据时间」时表示命中缓存/降级快照。</span>
                    <span class="billing__foot-note">支付回调未接入：缴费状态为演示，不代表真实到账。</span>
                  </div>
                </template>
              </WTable>
              <div class="billing__pager">
                <WButton size="sm" variant="ghost" :disabled="billsPage <= 1" data-testid="billing-bills-prev" @click="goBillsPage(billsPage - 1)">
                  上一页
                </WButton>
                <span class="num" data-testid="billing-bills-pager-info">
                  第 {{ billsPage }} / {{ billsPageCount }} 页 · 共 {{ bills?.total ?? 0 }} 条
                </span>
                <WButton
                  size="sm"
                  variant="ghost"
                  :disabled="billsPage >= billsPageCount"
                  data-testid="billing-bills-next"
                  @click="goBillsPage(billsPage + 1)"
                >
                  下一页
                </WButton>
              </div>
            </template>
          </WCard>

          <!-- 账单明细：items 拆项 + 滞纳金/逾期/欠费天数（总额不重算） -->
          <WCard class="billing__detail" title="账单明细" subtitle="费项拆项与滞纳金均为收费系统返回；总额不前端重算">
            <template #extra>
              <WTag v-if="detailBill?.mock" tone="a" plain data-testid="billing-detail-mock">{{ mockBadgeText }}</WTag>
              <WTag v-if="detailStatusText" tone="g" plain>{{ detailStatusText }}</WTag>
            </template>

            <div data-testid="billing-detail-card">
              <WLoading v-if="detailLoading" center label="正在读取账单明细…" />
              <div v-else-if="detailError" class="billing__state" data-testid="billing-detail-error">
                <WEmpty icon="wallet" title="账单明细暂时无法加载" :description="detailError" compact />
              </div>
              <template v-else-if="detailBill">
                <div class="billing__tiles">
                  <div>
                    <b class="num" data-testid="billing-detail-total">{{ money(detailBill.total) }}</b>
                    <span>应缴合计（API）</span>
                  </div>
                  <div>
                    <b class="num">{{ money(detailBill.due) }}</b>
                    <span>本期待缴</span>
                  </div>
                  <div>
                    <b class="num" data-testid="billing-detail-latefee">{{ money(detailBill.lateFee) }}</b>
                    <span>滞纳金</span>
                  </div>
                  <div>
                    <b class="num" data-testid="billing-detail-overdue">{{ detailBill.overdueDays ?? 0 }}<small>天</small></b>
                    <span>逾期天数</span>
                  </div>
                  <div>
                    <b class="num" data-testid="billing-detail-arrearsdays">{{ detailBill.arrearsDays ?? 0 }}<small>天</small></b>
                    <span>连续欠费天数</span>
                  </div>
                </div>

                <div class="billing__items">
                  <div
                    v-for="item in detailBill.items"
                    :key="`${item.itemCode ?? item.label}`"
                    class="billing__item"
                    data-testid="billing-detail-item"
                  >
                    <span>
                      {{ item.label }}
                      <em v-if="item.note">{{ item.note }}</em>
                      <em v-if="item.knowledgeId">知识出处 {{ item.knowledgeId }}</em>
                    </span>
                    <b class="num">{{ money(item.amount) }}</b>
                  </div>
                  <p v-if="!detailBill.items.length" class="billing__muted">该账期没有费项明细（不编造金额）。</p>
                </div>

                <div class="billing__detail-meta">
                  <span v-if="detailBill.billNo">账单号 {{ detailBill.billNo }}</span>
                  <span v-if="detailBill.dueDate">到期日 {{ detailBill.dueDate }}</span>
                  <span data-testid="billing-detail-freshness">{{ detailDataTime }}</span>
                  <span v-if="detailDegraded">{{ degradedText(detailDegraded) }}</span>
                </div>
              </template>
              <WEmpty
                v-else
                icon="wallet"
                title="尚未选择账单"
                description="从上方台账 / 账单列表选择一行，明细与费用解释会一起更新。"
                compact
              />
            </div>
          </WCard>
        </div>

        <div class="billing__side">
          <!-- 费用解释卡：formula / breakdown / diffVsPrev / knowledgeRefs（可点开出处） -->
          <WCard class="billing__explain" title="费用解释 · 为什么这么多" subtitle="公式 + 分摊依据 + 与上期差额 + 知识出处">
            <template #extra>
              <WTag v-if="explain?.mock" tone="a" plain data-testid="billing-explain-mock">{{ mockBadgeText }}</WTag>
              <WTag v-else-if="explain" tone="g" plain>已溯源</WTag>
            </template>

            <div data-testid="billing-explain-card">
              <WLoading v-if="explainLoading" label="正在生成费用解释…" />
              <div v-else-if="explainError" class="billing__state" data-testid="billing-explain-error">
                <WEmpty
                  icon="book"
                  :title="explainForbidden ? '仅可解释本户账单' : '该账单暂时无法解释'"
                  :description="
                    explainForbidden
                      ? `${explainError}；跨房解释被服务端拒绝（E-2002），不做推测。`
                      : `${explainError}；无出处不解释，已引导转人工。`
                  "
                  compact
                />
              </div>
              <template v-else-if="explain">
                <div class="billing__explain-formula" data-testid="billing-explain-formula">{{ explain.formula }}</div>

                <div class="billing__explain-block" data-testid="billing-explain-breakdown">
                  <span class="billing__explain-cap">费项分摊（{{ explain.breakdown.length }} 项）</span>
                  <div v-for="line in explain.breakdown" :key="line.itemCode" class="billing__explain-line" data-testid="billing-explain-line">
                    <div class="billing__explain-line-head">
                      <b>{{ line.name }}</b>
                      <span class="num">{{ money(line.amount) }}</span>
                    </div>
                    <p class="billing__explain-rule">基数：{{ line.base }}</p>
                    <p class="billing__explain-rule">口径：{{ line.rule }}</p>
                  </div>
                </div>

                <div v-if="explain.diffVsPrev" class="billing__explain-diff" data-testid="billing-explain-diff">
                  <span class="billing__explain-cap">与上期差额</span>
                  <b class="num">{{ explain.diffVsPrev.amount > 0 ? `+${money(explain.diffVsPrev.amount)}` : money(explain.diffVsPrev.amount) }}</b>
                  <p>{{ explain.diffVsPrev.reason }}</p>
                </div>

                <div class="billing__explain-block" data-testid="billing-explain-refs">
                  <span class="billing__explain-cap">知识出处（点击看来源）</span>
                  <div class="billing__refs">
                    <button
                      v-for="ref in explain.knowledgeRefs"
                      :key="ref.knowledgeId"
                      type="button"
                      class="billing__ref"
                      :data-testid="`billing-explain-ref-${ref.knowledgeId}`"
                      @click="openKnowledge(ref)"
                    >
                      {{ ref.knowledgeId }} · {{ ref.title }} · {{ ref.version }}
                    </button>
                  </div>
                  <p v-if="!explain.knowledgeRefs.length" class="billing__muted">
                    无知识出处：按规则不解释、不编造，已引导转人工。
                  </p>
                </div>

                <div class="billing__explain-meta">
                  <span data-testid="billing-explain-time">{{ explainDataTime }}</span>
                  <span>{{ explain.roomCode }} · {{ explain.period }}</span>
                  <span v-if="explainDegraded">{{ degradedText(explainDegraded) }}</span>
                  <span>金额与差额均为收费系统口径，AI 不重算、不承诺减免</span>
                </div>
              </template>
              <WEmpty
                v-else
                icon="book"
                title="尚未加载解释"
                :description="selectedRoom ? '正在等待账单明细返回…' : '选择一条账单后查看费用解释与知识出处。'"
                compact
              />
            </div>
          </WCard>

          <!-- AI 催缴助手（P1 保留；文案只拼装 API 数字） -->
          <WCard title="AI 催缴助手" subtitle="按分层生成话术，人工确认后发送（mock-channel）">
            <template #extra>
              <WTag tone="v" plain>按分层生成</WTag>
            </template>

            <div v-if="selectedArrears" class="billing__owner">
              <span class="billing__owner-avatar">{{ selectedArrears.ownerNameMasked.slice(0, 1) }}</span>
              <div class="billing__owner-main">
                <b>{{ selectedArrears.ownerNameMasked }} · {{ selectedArrears.roomCode }}</b>
                <span>业主本人 · 逾期 {{ selectedArrears.overdueDays }} 天 · {{ ARREARS_BUCKET_LABELS[selectedArrears.bucket] }}</span>
              </div>
            </div>
            <p v-else class="billing__muted">先在左侧台账选择一条欠费记录，再生成对应的话术。</p>

            <div v-if="selectedArrears" class="billing__nums">
              <div>
                <b class="num">{{ money(selectedArrears.outstanding) }}</b>
                <span>当前欠费</span>
              </div>
              <div>
                <b class="num">{{ selectedArrears.period }}</b>
                <span>账期</span>
              </div>
              <div>
                <b class="num">{{ money(selectedArrears.lateFee) }}</b>
                <span>滞纳金</span>
              </div>
            </div>

            <div class="billing__tones" role="group" aria-label="话术语气">
              <button
                v-for="item in TONES"
                :key="item.key"
                type="button"
                class="billing__tone"
                :class="{ 'is-on': scriptTone === item.key }"
                :aria-pressed="scriptTone === item.key"
                @click="scriptTone = item.key"
              >
                {{ item.label }}
              </button>
            </div>

            <p class="billing__script">{{ scriptFor(scriptTone) }}</p>

            <div class="billing__channels">
              <button
                type="button"
                class="billing__channel"
                :class="{ 'is-on': channels.wecom }"
                @click="toggleChannel('wecom')"
              >
                企微私信
              </button>
              <button type="button" class="billing__channel" :class="{ 'is-on': channels.sms }" @click="toggleChannel('sms')">
                短信
              </button>
              <button type="button" class="billing__channel is-off" @click="toggleChannel('call')">电话外呼（红线）</button>
            </div>

            <div class="billing__redline">
              <WTag tone="r" plain>渠道红线</WTag>
              <span>不承诺减免 / 免滞纳金；不在群内提欠费；22:00—8:00 不外呼；单户每周 ≤2 次；不公开欠费名单。</span>
            </div>

            <div class="billing__acts">
              <WButton size="sm" variant="primary" data-testid="billing-task-open-side" @click="taskOpen = true">生成催缴任务</WButton>
              <WButton size="sm" @click="toast.info(demoNote('催缴任务不真实外发，仅生成演示工单'))">暂缓</WButton>
            </div>
          </WCard>

          <!-- 分层触达节奏（口径来自契约 §7.4，不写金额） -->
          <WCard class="billing__layers" title="分层触达节奏" subtitle="越往后，人介入越深（P3 触达走 mock-channel）">
            <div class="billing__layer">
              <WTag tone="n">D1 · 1—7 天</WTag>
              <b>服务号模板 + 账单卡</b>
              <em>AI 自动</em>
            </div>
            <div class="billing__layer">
              <WTag tone="b">D7 · 8—15 天</WTag>
              <b>管家企微 + 个性化话术</b>
              <em>管家确认</em>
            </div>
            <div class="billing__layer">
              <WTag tone="a">D15 · 16—30 天</WTag>
              <b>电话提醒 + 承诺登记</b>
              <em>人工为主</em>
            </div>
            <div class="billing__layer">
              <WTag tone="r">D30 · 30 天以上</WTag>
              <b>主管约谈 / 分期方案 / 法务</b>
              <em>主管 · 财务</em>
            </div>
            <p class="billing__layer-tip">
              分层与户数来自 <code>/fees/arrears</code> 的 summary；AI 只解释与整理证据链，不做减免承诺、不做 AI 语音外呼。
            </p>
          </WCard>
        </div>
      </div>

      <StateDemo screen="billing" />
      </template>
    </template>

    <!-- 动作弹层常驻挂载：无权限态下也能给出「为什么不能导出」等可见反馈（避免死按钮） -->
    <CollectionTaskModal
      v-model:open="taskOpen"
      :subject="collectionSubject"
      :tiers="collectionTiers"
      :default-channels="defaultChannels"
      :script="scriptFor(scriptTone)"
      :tone="TONE_LABEL[scriptTone]"
      @created="onCollectionCreated"
    />

    <FilterModal
      v-model:open="filterOpen"
      title="筛选欠费台账"
      :fields="filterFields"
      :model-value="filterValues"
      :preview="previewFilter"
      result-unit="户"
      :hint="
        denied
          ? '当前角色无费用台账权限：筛选不会读取数据，请切换管家 / 主管演示账号后重试。'
          : '分层筛选由服务端执行（/fees/arrears?bucket=）；确认后列表、四层卡与导出范围同步更新。金额与户数都来自 API 汇总，不做前端统计。'
      "
      @apply="applyFilters"
      @reset="resetFilters"
    />

    <ExportModal
      v-model:open="exportOpen"
      title="导出欠费台账"
      base-name="欠费台账"
      :columns="exportColumns"
      :rows="exportRows"
      :summary="exportSummary"
      hint="导出当前页 + 当前筛选结果；业主姓名只保留掩码，不含手机号等敏感字段。"
      @exported="onExported"
    />

    <WModal v-model:open="knowledgeOpen" title="知识出处" width="520px">
      <div class="billing__know" data-testid="billing-knowledge-source">
        <p class="billing__know-title">{{ knowledgeRef?.title ?? '—' }}</p>
        <dl class="billing__know-rows">
          <div>
            <dt>知识条目</dt>
            <dd><code>{{ knowledgeRef?.knowledgeId ?? '—' }}</code></dd>
          </div>
          <div>
            <dt>版本</dt>
            <dd><code>{{ knowledgeRef?.version ?? '—' }}</code></dd>
          </div>
        </dl>
        <p class="billing__know-note">
          解释口径与金额条目都绑定知识条目与版本；无出处时按规则拒答并转人工，不编造。金额由收费系统返回，AI 不重算、不减免。
        </p>
      </div>
      <template #footer>
        <WButton size="sm" @click="knowledgeOpen = false">知道了</WButton>
      </template>
    </WModal>
  </div>
</template>

<style scoped>
.billing-page {
  min-width: 0;
}

/* ---------- 页面级视图切换（费用台账 / 催缴工作台 / 费用看板） ---------- */
.billing__views {
  display: flex;
  gap: 2px;
  padding: 3px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-lg);
  background: var(--c-bg-2);
  align-self: flex-start;
}

.billing__view {
  height: 28px;
  padding: 0 16px;
  border-radius: var(--rd-md);
  font-size: var(--fs-xs);
  color: var(--c-muted);
  transition: background-color var(--tr-fast), color var(--tr-fast), box-shadow var(--tr-fast);
}

.billing__view:hover {
  color: var(--c-ink-2);
  background: rgba(255, 255, 255, 0.8);
}

.billing__view.is-on {
  background: var(--c-card);
  color: var(--c-brand);
  font-weight: var(--fw-semibold);
  box-shadow: var(--sh-sm);
}

.billing__view:focus-visible {
  outline: none;
  box-shadow: var(--sh-ring);
}

/* ---------- 四层卡（D1/D7/D15/D30，数据来自 summary.buckets） ---------- */
.billing__stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--sp-3);
}

.billing__stat {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 3px;
  padding: 12px 14px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-xl);
  background: var(--c-card);
  box-shadow: var(--sh-md);
  text-align: left;
  transition: border-color var(--tr-fast), box-shadow var(--tr-fast), transform var(--tr-fast);
}

.billing__stat:hover {
  border-color: var(--c-brand);
  box-shadow: var(--sh-ring);
}

.billing__stat.is-on {
  border-color: var(--c-brand);
  background: linear-gradient(180deg, #f4fbf9, var(--c-card));
}

.billing__stat:focus-visible {
  outline: none;
  box-shadow: var(--sh-ring);
}

.billing__stat-lab {
  font-size: var(--fs-xs);
  color: var(--c-muted);
}

.billing__stat-val {
  font-size: var(--fs-2xl);
  font-weight: var(--fw-semibold);
  color: var(--c-ink);
  line-height: 1.15;
}

.billing__stat-val small {
  margin-left: 2px;
  font-size: var(--fs-xs);
  font-weight: var(--fw-medium);
  color: var(--c-muted);
}

.billing__stat-delta {
  font-size: var(--fs-2xs);
  color: var(--c-faint);
}

/* ---------- 数据来源 / 降级条 ---------- */
.billing__metabar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  padding: 6px 10px;
  border: 1px solid var(--c-line-2);
  border-radius: var(--rd-md);
  background: var(--c-bg-2);
  font-size: var(--fs-2xs);
  color: var(--c-muted);
}

.billing__metabar-src {
  margin-left: auto;
  color: var(--c-faint);
}

.billing__degradebar {
  margin: 0;
  padding: 6px 10px;
  border: 1px dashed var(--c-amber);
  border-radius: var(--rd-md);
  background: var(--c-amber-soft);
  color: #9a4b05;
  font-size: var(--fs-2xs);
  line-height: var(--lh-base);
}

/* ---------- 主区 ---------- */
.billing__grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 348px;
  gap: var(--sp-3);
  align-items: start;
}

.billing__main {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
  min-width: 0;
}

.billing__card-title {
  font-size: 12.5px;
  font-weight: var(--fw-semibold);
  color: var(--c-ink);
}

.billing__card-sub {
  margin-left: 6px;
  font-size: var(--fs-2xs);
  color: var(--c-faint);
}

/* ---------- 页签 ---------- */
.billing__tabs {
  display: flex;
  gap: 2px;
  padding: 8px 12px 0;
  border-bottom: 1px solid var(--c-line-2);
}

.billing__tab {
  height: 26px;
  padding: 0 12px;
  border: 1px solid transparent;
  border-bottom: 0;
  border-radius: var(--rd-md) var(--rd-md) 0 0;
  font-size: var(--fs-2xs);
  color: var(--c-muted);
}

.billing__tab:hover {
  color: var(--c-ink-2);
}

.billing__tab.is-on {
  border-color: var(--c-line);
  background: var(--c-card);
  color: var(--c-brand);
  font-weight: var(--fw-semibold);
}

.billing__tab:focus-visible {
  outline: none;
  box-shadow: var(--sh-ring);
}

/* ---------- 工具条 ---------- */
.billing__toolbar {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  border-bottom: 1px solid var(--c-line-2);
  flex-wrap: wrap;
}

.billing__segs {
  display: flex;
  gap: 2px;
  padding: 2px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-md);
  background: var(--c-bg-2);
}

.billing__seg {
  height: 23px;
  padding: 0 8px;
  border-radius: var(--rd-sm);
  font-size: var(--fs-2xs);
  color: var(--c-muted);
  transition: background-color var(--tr-fast), color var(--tr-fast);
}

.billing__seg:hover {
  background: rgba(255, 255, 255, 0.85);
  color: var(--c-ink-2);
}

.billing__seg.is-on {
  background: var(--c-card);
  color: var(--c-brand);
  font-weight: var(--fw-semibold);
  box-shadow: var(--sh-sm);
}

.billing__seg:focus-visible {
  outline: none;
  box-shadow: var(--sh-ring);
}

.billing__search,
.billing__input {
  height: 26px;
  padding: 0 9px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-sm);
  background: var(--c-card);
  font-size: var(--fs-2xs);
  color: var(--c-ink);
}

.billing__search {
  width: 170px;
  margin-left: auto;
}

.billing__input {
  width: 118px;
}

.billing__search:focus,
.billing__input:focus {
  outline: none;
  border-color: var(--c-brand);
  box-shadow: var(--sh-ring);
}

/* ---------- 表格 ---------- */
.billing__brow {
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.billing__brow b {
  font-size: var(--fs-sm);
  color: var(--c-ink);
}

.billing__brow span {
  font-size: 9px;
  color: var(--c-faint);
}

.billing__amount {
  font-weight: var(--fw-semibold);
  color: var(--c-ink);
}

.billing__muted {
  font-size: var(--fs-2xs);
  color: var(--c-muted);
  line-height: var(--lh-base);
}

.billing__state {
  padding: 10px 12px;
}

.billing__foot {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.billing__foot-note {
  margin-left: auto;
  color: var(--c-amber);
}

.billing__foot code,
.billing__layer-tip code {
  padding: 0 3px;
  border-radius: var(--rd-xs);
  background: var(--c-bg-2);
  font-family: var(--font-mono);
  font-size: 9px;
}

/* ---------- 分页 ---------- */
.billing__pager {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 12px;
  border-top: 1px solid var(--c-line-2);
  background: var(--c-bg-2);
  font-size: var(--fs-2xs);
  color: var(--c-muted);
}

/* ---------- 明细 ---------- */
.billing__tiles {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 4px;
  padding: 6px 0 10px;
  border-bottom: 1px solid var(--c-line-2);
}

.billing__tiles div {
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.billing__tiles b {
  font-size: var(--fs-base);
  color: var(--c-ink);
}

.billing__tiles b small {
  margin-left: 1px;
  font-size: var(--fs-2xs);
  color: var(--c-muted);
}

.billing__tiles span {
  font-size: 9px;
  color: var(--c-faint);
}

.billing__items {
  display: flex;
  flex-direction: column;
  padding: 6px 0;
}

.billing__item {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
  padding: 5px 0;
  border-bottom: 1px dashed var(--c-line-2);
}

.billing__item:last-of-type {
  border-bottom: 0;
}

.billing__item span {
  display: flex;
  flex-direction: column;
  gap: 1px;
  font-size: var(--fs-xs);
  color: var(--c-ink-2);
}

.billing__item em {
  font-style: normal;
  font-size: 9px;
  color: var(--c-faint);
}

.billing__item b {
  flex: none;
  font-size: var(--fs-sm);
  color: var(--c-ink);
}

.billing__detail-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 10px;
  padding-top: 6px;
  border-top: 1px solid var(--c-line-2);
  font-size: 9px;
  color: var(--c-faint);
}

/* ---------- 解释卡 ---------- */
.billing__explain-formula {
  padding: 7px 9px;
  border: 1px solid #e6efeb;
  border-radius: var(--rd-sm);
  background: #f7faf9;
  font-size: var(--fs-2xs);
  color: #5a7280;
  line-height: var(--lh-base);
}

.billing__explain-block {
  margin-top: 8px;
}

.billing__explain-cap {
  display: block;
  margin-bottom: 4px;
  font-size: 9px;
  color: var(--c-faint);
  letter-spacing: 0.3px;
}

.billing__explain-line {
  padding: 5px 7px;
  border: 1px solid var(--c-line-2);
  border-radius: var(--rd-sm);
  background: var(--c-bg-2);
}

.billing__explain-line + .billing__explain-line {
  margin-top: 4px;
}

.billing__explain-line-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
}

.billing__explain-line-head b {
  font-size: var(--fs-xs);
  color: var(--c-ink);
}

.billing__explain-line-head span {
  font-size: var(--fs-xs);
  font-weight: var(--fw-semibold);
  color: var(--c-ink);
}

.billing__explain-rule {
  margin-top: 1px;
  font-size: 9px;
  color: var(--c-faint);
  line-height: 1.5;
}

.billing__explain-diff {
  margin-top: 8px;
  padding: 7px 9px;
  border: 1px dashed var(--c-amber);
  border-radius: var(--rd-sm);
  background: var(--c-amber-soft);
}

.billing__explain-diff b {
  font-size: var(--fs-sm);
  color: #9a4b05;
}

.billing__explain-diff p {
  margin-top: 2px;
  font-size: 9.5px;
  color: #9a4b05;
  line-height: var(--lh-base);
}

.billing__refs {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.billing__ref {
  height: 22px;
  padding: 0 8px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-pill);
  background: var(--c-card);
  font-size: 9.5px;
  color: var(--c-brand);
  transition: border-color var(--tr-fast), background-color var(--tr-fast);
}

.billing__ref:hover {
  border-color: var(--c-brand);
  background: var(--c-brand-soft);
}

.billing__ref:focus-visible {
  outline: none;
  box-shadow: var(--sh-ring);
}

.billing__explain-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 3px 10px;
  margin-top: 8px;
  padding-top: 6px;
  border-top: 1px solid var(--c-line-2);
  font-size: 9px;
  color: var(--c-faint);
}

/* ---------- 右侧 ---------- */
.billing__side {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
}

.billing__owner {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.billing__owner-avatar {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: var(--c-brand-soft);
  color: var(--c-brand);
  font-size: var(--fs-sm);
  font-weight: var(--fw-semibold);
}

.billing__owner-main {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}

.billing__owner-main b {
  font-size: var(--fs-sm);
  color: var(--c-ink);
}

.billing__owner-main span {
  font-size: var(--fs-2xs);
  color: var(--c-faint);
}

.billing__nums {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 4px;
  padding: 8px 0;
  border-top: 1px solid var(--c-line-2);
  border-bottom: 1px solid var(--c-line-2);
}

.billing__nums div {
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.billing__nums b {
  font-size: var(--fs-sm);
  color: var(--c-ink);
}

.billing__nums span {
  font-size: 9px;
  color: var(--c-faint);
}

.billing__tones {
  display: flex;
  gap: 3px;
  margin: 8px 0 6px;
}

.billing__tone {
  height: 22px;
  padding: 0 9px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-pill);
  background: var(--c-card);
  font-size: var(--fs-2xs);
  color: var(--c-muted);
  transition: border-color var(--tr-fast), color var(--tr-fast), background-color var(--tr-fast);
}

.billing__tone:hover {
  border-color: var(--c-brand);
  color: var(--c-brand);
}

.billing__tone.is-on {
  border-color: var(--c-brand);
  background: var(--c-brand-soft);
  color: var(--c-brand);
  font-weight: var(--fw-semibold);
}

.billing__tone:focus-visible {
  outline: none;
  box-shadow: var(--sh-ring);
}

.billing__script {
  padding: 8px 10px;
  border: 1px solid var(--c-line-2);
  border-radius: var(--rd-md);
  background: var(--c-bg-2);
  font-size: var(--fs-xs);
  color: var(--c-ink-2);
  line-height: var(--lh-loose);
}

.billing__channels {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 8px;
}

.billing__channel {
  height: 22px;
  padding: 0 8px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-pill);
  background: var(--c-card);
  font-size: var(--fs-2xs);
  color: var(--c-muted);
}

.billing__channel.is-on {
  border-color: #bfe7db;
  background: var(--c-brand-soft);
  color: var(--c-brand);
}

.billing__channel.is-off {
  border-style: dashed;
  color: var(--c-faint);
}

.billing__redline {
  display: flex;
  gap: 6px;
  align-items: flex-start;
  margin-top: 8px;
  padding: 7px 9px;
  border: 1px solid #f6d5d3;
  border-radius: var(--rd-sm);
  background: var(--c-red-soft);
  font-size: var(--fs-2xs);
  color: #b42318;
  line-height: var(--lh-base);
}

.billing__acts {
  display: flex;
  gap: 6px;
  margin-top: 10px;
}

.billing__layer {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 0;
  border-bottom: 1px dashed var(--c-line-2);
}

.billing__layer:last-of-type {
  border-bottom: 0;
}

.billing__layer b {
  flex: 1;
  min-width: 0;
  font-size: var(--fs-xs);
  font-weight: var(--fw-medium);
  color: var(--c-ink-2);
}

.billing__layer em {
  flex: none;
  font-style: normal;
  font-size: 9px;
  color: var(--c-faint);
}

.billing__layers :deep(.w-card__body) {
  padding-bottom: 8px;
}

.billing__layer-tip {
  margin-top: 6px;
  padding: 6px 8px;
  border-radius: var(--rd-sm);
  background: var(--c-amber-soft);
  color: #9a4b05;
  font-size: var(--fs-2xs);
  line-height: var(--lh-base);
}

/* ---------- 筛选回填 ---------- */
.billing__filtersummary {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px;
  border-bottom: 1px solid var(--c-line-2);
  background: var(--c-bg-2);
  font-size: var(--fs-2xs);
  color: var(--c-muted);
}

.billing__filtersummary > span {
  flex: 1;
  min-width: 0;
}

.billing__filterclear {
  flex: none;
  font-size: var(--fs-2xs);
  color: var(--c-brand);
}

.billing__filterclear:hover {
  text-decoration: underline;
}

/* ---------- 知识出处弹层 ---------- */
.billing__know-title {
  font-size: var(--fs-sm);
  font-weight: var(--fw-semibold);
  color: var(--c-ink);
}

.billing__know-rows {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin: 8px 0;
}

.billing__know-rows div {
  display: flex;
  align-items: baseline;
  gap: 10px;
  font-size: var(--fs-xs);
}

.billing__know-rows dt {
  flex: none;
  width: 64px;
  font-size: 9.5px;
  color: var(--c-faint);
}

.billing__know-rows dd {
  margin: 0;
  color: var(--c-ink-2);
}

.billing__know-rows code {
  padding: 0 3px;
  border-radius: var(--rd-xs);
  background: var(--c-bg-2);
  font-family: var(--font-mono);
  font-size: 10px;
}

.billing__know-note {
  font-size: var(--fs-2xs);
  color: var(--c-muted);
  line-height: var(--lh-base);
}

@media (max-width: 1279px) {
  .billing__stats {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .billing__grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .billing__tiles {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}
</style>
