<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { WButton, WIcon, WModal, WTag } from '@/components'
import {
  ApiError,
  TICKET_CATEGORY_LABELS,
  TICKET_STATUS_LABELS,
  TICKET_TRANSITIONS,
  degradedText,
  errorText,
  formatMoney,
  mockBadgeText,
} from '@/api'
import type {
  BillExplanation,
  BillExplanationRef,
  DegradedInfo,
  IssueScope,
  TicketCategory,
  TicketCreateRequest,
  TicketStatus,
  TicketUrgency,
} from '@/api'
import { actionSeed, useIdempotencyKey } from '@/composables/useIdempotencyKey'
import { useOwnerSession } from '@/composables/useOwnerSession'
import { useAuthStore } from '@/stores/auth'
import { BILL_PERIOD, HANDOFF, OWNER, REPAIR, REPAIR_STEPS, SUGGESTIONS } from '../data'
import { api } from '@/api'

/**
 * 业主端 · 智能管家（统一单入口会话）
 *
 * 产品口径：业主侧只有「一个输入框」，不区分费用查询 / 智能报修入口；
 * AI 先识别意图（账单 / 报修 / 转人工 / 查进度），再在同一会话里走对应能力：
 *   - 账单：身份核验（CAP-02 真实 API）→ 账单卡（CAP-03 实时账单，金额全部来自 ERP）
 *   - 报修：5 问结构化 → CAP-06 建单（Idempotency-Key）→ 工单号/状态/班组/ETA 来自响应
 *
 * P2 数据边界（契约 v0.3.0）：
 *   - 金额不自算、不自造：账单只有成功响应才展示数字，失败/降级一律「账单暂不可查，已转人工」；
 *   - 建单成功才展示工单号；失败（503/E-4001）提示「建单暂时失败，已转人工待办」+ 转人工，不编造单号；
 *     同房 24h 重复建单（422 E-1001 details.reason=duplicate_24h）展示**既有工单号**，让业主直接看进度；
 *   - `mock:true` 标注「演示数据（mock ERP）」；`degraded` 出现时展示降级条。
 */
const emit = defineEmits<{
  pay: []
  invoice: []
  bind: []
  handoff: [summary: string]
  /** 带工单号跳进度页（`/progress?ticket=`）；无工单号时跳默认演示单 */
  progress: [ticketNo?: string]
  reschedule: []
  urge: []
  voice: []
}>()

/**
 * 动作级权限占位：`owner.pay_jump` 只对业主/管理员开放（见 `app/permissions.ts` 的 CAPABILITIES）。
 * 无权限角色（如坐席/IT 借演示视角进入本屏）时隐藏「去缴费」，避免出现越权入口。
 */
withDefaults(defineProps<{ allowPay?: boolean }>(), { allowPay: true })

type Flow = 'idle' | 'verify' | 'repair' | 'finished'
type Intent = 'bill' | 'repair' | 'human' | 'progress' | 'code' | 'other'

interface Chip {
  label: string
  act: () => void
}

interface ChatMessage {
  id: number
  role: 'ai' | 'me'
  text?: string
  kind?: 'bill' | 'ticket' | 'handoff'
  chips?: Chip[]
}

const session = useOwnerSession()
const idempotency = useIdempotencyKey()
const auth = useAuthStore()

/**
 * 改约 / 催办走后端 `POST /tickets/{no}/transition`，需要能力 `ticket.assign`
 * （契约 v0.3.3 矩阵：管家/坐席/主管/IT/管理员；业主没有）。前端按能力隐藏入口，
 * 真实拦截在 Java BFF（403 E-2001）；不把业主的「申请改约」伪装成已受理。
 */
const canTransition = computed(() => auth.hasCapability('ticket.assign'))

const chatRef = ref<HTMLElement | null>(null)
const taRef = ref<HTMLTextAreaElement | null>(null)
const draft = ref('')
const focused = ref(false)
const messages = ref<ChatMessage[]>([])
const flow = ref<Flow>('idle')
const handedOff = ref(false)
const stepIndex = ref(0)
const answers = ref<Record<string, string>>({})
const sending = ref(false)
const sendingText = ref('')
const touched = ref<string[]>([])
/** 核验成功后要继续的业务动作（账单 / 报修 / 提交报修） */
const pendingFlow = ref<'bill' | 'repair' | 'repairSubmit' | ''>('')
let seed = 0

/** 账单展示态（全部来自 CAP-03 响应；失败时不展示任何金额） */
const bill = computed(() => session.bill.value)
const billDegraded = computed(() => session.billDegraded.value)
const billText = computed(() => degradedText(billDegraded.value))

/** 工单卡展示态（来自 CAP-06 建单响应 / 既有工单详情） */
const ticketNo = ref('')
const ticketStatus = ref<TicketStatus | ''>('')
const ticketStatusText = ref('')
const ticketCategory = ref('')
const ticketUrgency = ref('')
const ticketTeam = ref('')
const ticketEta = ref('')
const ticketLocation = ref('')
const ticketSlot = ref('')
const ticketMock = ref(true)
const ticketDuplicated = ref(false)
const ticketDegraded = ref<DegradedInfo | undefined>(undefined)
const ticketDegradedText = ref('')
const ticketErrorText = ref('')

/** 紧急程度中文（契约 TicketUrgency 三档；分类文案用 shared `TICKET_CATEGORY_LABELS`） */
const URGENCY_LABELS: Readonly<Record<TicketUrgency, string>> = {
  normal: '一般',
  urgent: '紧急',
  emergency: '应急',
}

/**
 * 契约驱动的动作可用性（`TICKET_TRANSITIONS`）：工单卡上只提供契约允许的动作，
 * 避免对「已完工/已回访」工单出现改了也提交不了的按钮。
 */
const canRescheduleTicket = computed(
  () => Boolean(ticketStatus.value) && TICKET_TRANSITIONS.reschedule.from.includes(ticketStatus.value as TicketStatus),
)
const canUrgeTicket = computed(
  () => Boolean(ticketStatus.value) && TICKET_TRANSITIONS.urge.from.includes(ticketStatus.value as TicketStatus),
)

const placeholder = computed(() => {
  if (flow.value === 'repair') return '补充说明；没有补充可点上方「直接提交」'
  if (flow.value === 'finished') return '已受理，可继续问别的，或补充说明…'
  if (flow.value === 'verify') return '回复手机号后四位完成核验'
  return '说需求就行：查账单、报修、查进度、转人工…'
})

const handoffSummary = computed(() => {
  const what = touched.value.length > 0 ? touched.value.join(' / ') : '一般咨询'
  const who = session.isVerified.value
    ? `已核验手机号尾号 ${session.verifyResult.value?.ownerNameMasked ?? ''}`
    : '未核验身份'
  const ticket = ticketNo.value ? ` · 工单 ${ticketNo.value}（${ticketStatusText.value}）` : ''
  return `${what} · ${OWNER.room} · ${who}${ticket}`
})

function scrollBottom() {
  void nextTick(() => {
    const el = chatRef.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

function push(message: Omit<ChatMessage, 'id'>) {
  messages.value.push({ ...message, id: ++seed })
  scrollBottom()
}

function markIntent(label: string) {
  if (!touched.value.includes(label)) touched.value.push(label)
}

/** 输入框自适应高度（1—3 行），高度变化后贴底 */
function autoGrow() {
  const el = taRef.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = `${Math.min(el.scrollHeight, 64)}px`
}

function resetDraft() {
  draft.value = ''
  void nextTick(autoGrow)
}

function setSending(text: string, on: boolean) {
  sending.value = on
  sendingText.value = on ? text : ''
  scrollBottom()
}

// ---------------------------------------------------------------- 意图识别
function classify(text: string): Intent {
  if (/转人工|人工客服|真人|客服|投诉/.test(text)) return 'human'
  if (/进度|到哪|什么时候来|催一下|催办/.test(text)) return 'progress'
  if (/漏水|报修|维修|坏了|不出水|堵塞|堵了|跳闸|停电|停水|电梯|渗水|异响|灯坏|门锁/.test(text)) return 'repair'
  if (/账单|物业费|公摊|能耗费|车位费|欠费|缴费|多少钱|费用|发票|滞纳金|押金|退款/.test(text)) return 'bill'
  if (/^\d{4}$/.test(text)) return 'code'
  return 'other'
}

// ---------------------------------------------------------------- 核验（CAP-02）
function verifyChips(): Chip[] {
  return [
    { label: OWNER.phoneTail, act: () => void submitVerify(OWNER.phoneTail) },
    { label: '6603', act: () => void submitVerify('6603') },
    { label: '换绑手机号', act: () => verifyBind() },
    { label: '转人工', act: () => doHandoff() },
  ]
}

function askVerify(next: 'bill' | 'repair' | 'repairSubmit', text?: string) {
  pendingFlow.value = next
  flow.value = 'verify'
  push({
    role: 'ai',
    text:
      text ??
      `已定位到 ${OWNER.room}。为保护您的隐私，${next === 'bill' ? '查账单' : '报修'}前请确认业主手机号后四位。`,
    chips: verifyChips(),
  })
}

/** 提交核验：真实调用 POST /api/v1/owner/verify（失败不提示错因，只提示剩余次数/转人工） */
async function submitVerify(phoneTail: string) {
  if (sending.value) return
  push({ role: 'me', text: phoneTail })
  setSending('正在核验身份…', true)
  const outcome = await session.verify(OWNER.room, phoneTail)
  setSending('', false)

  if (outcome.verified) {
    flow.value = 'idle'
    markIntent('身份核验')
    const masked = outcome.result?.ownerNameMasked ?? ''
    const project = outcome.result?.projectName ?? OWNER.project
    push({
      role: 'ai',
      text: `核验通过${masked ? `（${masked}）` : ''} · ${project}。5 分钟内在本次会话内有效。`,
    })
    const next = pendingFlow.value
    pendingFlow.value = ''
    if (next === 'bill') void askBillNow()
    else if (next === 'repair') startRepair()
    else if (next === 'repairSubmit') void finishRepair()
    return
  }

  if (outcome.locked) {
    push({ role: 'ai', text: outcome.message ?? '核验次数已达上限，已按规则转人工核验（不提示失败原因）。' })
    doHandoff()
    return
  }
  push({
    role: 'ai',
    text: outcome.message ?? '核验未通过，可重试或转人工（不提示失败原因）。',
    chips: [
      { label: '再试一次', act: () => void submitVerify(OWNER.phoneTail) },
      { label: '转人工', act: () => doHandoff() },
    ],
  })
}

function verifyBind() {
  push({ role: 'me', text: '换绑手机号' })
  emit('bind')
}

// ---------------------------------------------------------------- 账单（CAP-03 + P3 费用解释）
function askBill() {
  if (!session.ensureVerified()) {
    askVerify('bill')
    return
  }
  void askBillNow()
}

/**
 * P3 W10 业主端费用解释（契约 v1.1.0 `GET /fees/explain`）
 * - 公式 / 分摊明细 / 与上期差异 / 知识出处全部来自响应，前端只渲染，**不自算金额**；
 * - `mock: true` → 标注「演示数据（mock ERP）」；失败/无出处 → 说明并给转人工，不编造；
 * - 跨房查询由服务端数据范围拒绝（403 `E-2002`），页面按其人话提示，不展示其他业主金额。
 */
const explain = ref<BillExplanation | null>(null)
const explainDegraded = ref<DegradedInfo | undefined>(undefined)
const explainErrorCode = ref('')
const knowledgeOpen = ref(false)
const knowledgeRef = ref<BillExplanationRef | null>(null)

async function loadExplain(): Promise<void> {
  explain.value = null
  explainErrorCode.value = ''
  explainDegraded.value = undefined
  try {
    const { data, degraded } = await api.fee.explain({ roomCode: OWNER.room, period: BILL_PERIOD })
    explain.value = data
    explainDegraded.value = degraded
  } catch (error) {
    explainErrorCode.value = error instanceof ApiError ? error.code : 'E-9001'
    explainDegraded.value = error instanceof ApiError ? error.degraded : undefined
  }
}

async function askBillNow() {
  markIntent('账单解释')
  setSending('正在向收费系统查询本月账单…', true)
  const outcome = await session.loadBill({ roomCode: OWNER.room, period: BILL_PERIOD })
  if (outcome.ok) void loadExplain()
  setSending('', false)
  if (!outcome.ok) {
    // 失败降级：不展示任何金额，直接转人工
    push({
      role: 'ai',
      text: `${outcome.message ?? '账单暂不可查，已转人工'}（${errorText(outcome.code ?? 'E-9001')}）`,
      chips: [{ label: '转人工', act: () => doHandoff() }],
    })
    return
  }
  push({ role: 'ai', text: '本月账单如下，明细与差额均由收费系统实时返回：', kind: 'bill' })
}

/** 账单解释里的知识出处（点击看来源条目 + 版本；无出处不解释） */
function openKnowledgeSource(ref: BillExplanationRef): void {
  knowledgeRef.value = ref
  knowledgeOpen.value = true
}

/** 上期差额展示：正数带 `+`，金额本身来自响应 `diffVsPrev.amount` */
const explainDiffText = computed(() => {
  const diff = explain.value?.diffVsPrev
  if (!diff) return ''
  const amount = formatMoney(diff.amount)
  return `${diff.amount > 0 ? '+' : ''}${amount ? `¥${amount}` : '—'}`
})

/* ---------------- 查其他房号（数据范围：仅本户；跨房 → 403 E-2002 人话） ---------------- */

interface RoomProbeResult {
  kind: 'forbidden' | 'error' | 'ok'
  room: string
  text: string
}

const roomProbe = ref('')
const roomProbeSending = ref(false)
const roomProbeResult = ref<RoomProbeResult | null>(null)

async function probeRoom(): Promise<void> {
  const room = roomProbe.value.trim()
  if (!room || roomProbeSending.value) return
  roomProbeSending.value = true
  roomProbeResult.value = null
  try {
    const { data } = await api.fee.explain({ roomCode: room, period: BILL_PERIOD })
    roomProbeResult.value = {
      kind: 'ok',
      room,
      text: `${room} ${data.period} 费用解释已返回（应缴 ${formatMoney(data.total) ? `¥${formatMoney(data.total)}` : '—'}，仅本户授权数据）。`,
    }
  } catch (error) {
    const code = error instanceof ApiError ? error.code : 'E-9001'
    const reason = error instanceof ApiError && typeof error.details.reason === 'string' ? error.details.reason : ''
    if (code === 'E-2002' || reason === 'out_of_scope') {
      roomProbeResult.value = {
        kind: 'forbidden',
        room,
        text: `「${room}」不在当前账号的授权范围内：只能查看本户 ${OWNER.room} 的账单，跨房查询已被拒绝（E-2002）。已记录，可转人工核验归属。`,
      }
    } else {
      roomProbeResult.value = {
        kind: 'error',
        room,
        text: `「${room}」的解释暂时查不到：${errorText(code)}；可核对房号后重试，或转人工。`,
      }
    }
  } finally {
    roomProbeSending.value = false
  }
}

/** 账单卡头部日期文案：来自响应 `dueDate`（ERP 给出），解析失败则不展示 */
function dueText(dueDate?: string | null): string {
  if (!dueDate) return ''
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(dueDate)
  return match ? `${match[2]}-${match[3]} 到期` : dueDate
}

/** 知识引用文案：`title（kbId version）`，全部来自响应 `cite` */
const billCite = computed(() => {
  const cite = bill.value?.cite
  if (!cite) return ''
  const title = cite.title ? `${cite.title} · ` : ''
  return `差额依据：${title}${cite.kbId} ${cite.version}`
})

// ---------------------------------------------------------------- 报修（5 问 → CAP-06）
function startRepair() {
  if (!session.ensureVerified()) {
    markIntent('报修受理')
    askVerify('repair', '报修需要先核验业主身份：请回复业主手机号后四位（5 分钟内有效），核验只用于本次会话。')
    return
  }
  flow.value = 'repair'
  stepIndex.value = 0
  answers.value = {}
  markIntent('报修受理')
  emitStep()
}

function emitStep() {
  const step = REPAIR_STEPS[stepIndex.value]
  if (!step) {
    void finishRepair()
    return
  }
  if (step.options.length > 0) {
    push({
      role: 'ai',
      text: step.question,
      chips: step.options.map((option) => ({ label: option, act: () => pickOption(option) })),
    })
    return
  }
  push({
    role: 'ai',
    text: step.question,
    chips: [{ label: '直接提交', act: () => submitNote() }],
  })
}

function pickOption(option: string) {
  push({ role: 'me', text: option })
  answers.value[REPAIR_STEPS[stepIndex.value]?.key ?? ''] = option
  stepIndex.value += 1
  emitStep()
}

function submitNote() {
  answers.value.note = ''
  void finishRepair()
}

/** 五问答案 → 契约建单请求（category/urgency 由报修位置与影响范围映射，见契约字典） */
function buildCreateRequest(): TicketCreateRequest {
  const location = answers.value.location?.trim() || '卫生间'
  const scope = answers.value.downstairs ?? ''
  const downstairs: IssueScope = scope === '是' ? 'downstairs' : scope === '否' ? 'none' : 'unknown'
  const urgency: TicketUrgency = downstairs === 'downstairs' ? 'emergency' : 'urgent'
  return {
    roomCode: OWNER.room,
    category: categoryOf(location),
    urgency,
    location,
    downstairs,
    duration: answers.value.duration?.trim() || '不确定',
    slot: answers.value.slot?.trim() || '尽快',
    note: answers.value.note?.trim() || undefined,
  }
}

/** 报修位置 → 标准分类（契约 TicketCategory；P2 只做渗漏/给排水演示，未知位置回落 plumbing） */
function categoryOf(location: string): TicketCategory {
  if (/门窗|门锁|入户|外墙/.test(location)) return 'door_window'
  if (/电梯/.test(location)) return 'elevator'
  if (/灯|电|跳闸|停电/.test(location)) return 'electrical'
  return 'plumbing'
}

/** 建单：CAP-06 真实 API（必须带 Idempotency-Key；同一动作重试复用同键） */
async function finishRepair() {
  flow.value = 'finished'
  if (!session.ensureVerified()) {
    askVerify('repairSubmit', '身份核验已超过 5 分钟有效期，请重新核验后提交报修（工单要素已保留）。')
    return
  }
  const request = buildCreateRequest()
  const key = idempotency.ensure(
    actionSeed('ticket.create', {
      roomCode: request.roomCode,
      category: request.category,
      urgency: request.urgency,
      location: request.location,
      downstairs: request.downstairs,
      duration: request.duration,
      slot: request.slot,
      note: request.note ?? '',
    }),
  )
  resetTicketCard()
  ticketCategory.value = TICKET_CATEGORY_LABELS[request.category]
  ticketUrgency.value = URGENCY_LABELS[request.urgency]
  ticketLocation.value = `${request.roomCode} · ${request.location}`
  ticketSlot.value = request.slot
  setSending('正在建单并写入 ERP（幂等提交）…', true)
  try {
    const { data, degraded } = await api.ticket.create(request, key)
    idempotency.settle()
    ticketNo.value = data.ticketNo
    ticketStatus.value = data.status
    ticketStatusText.value = TICKET_STATUS_LABELS[data.status] ?? data.status
    ticketTeam.value = data.team
    ticketEta.value = data.eta
    ticketMock.value = data.mock
    ticketDegraded.value = degraded
    ticketDegradedText.value = degradedText(degraded)
    push({
      role: 'ai',
      text: `已按 5 项要素受理并写入 ERP（${data.erp?.vendor ?? 'ERP'}）：`,
      kind: 'ticket',
    })
    push({ role: 'ai', text: REPAIR.closing })
  } catch (error) {
    setSending('', false)
    const apiError = error instanceof ApiError ? error : null
    const code = apiError?.code ?? 'E-9001'
    const details = apiError?.details ?? {}
    // 422 同房 24h 重复建单：按契约展示**既有工单号**，引导看进度，不重复建单
    if (details.reason === 'duplicate_24h' && typeof details.ticketNo === 'string') {
      ticketDuplicated.value = true
      ticketMock.value = true
      ticketDegraded.value = apiError?.degraded
      ticketDegradedText.value = degradedText(apiError?.degraded)
      const contractNo = details.ticketNo
      // 该房可能有多张单：优先展示「在办」的那张（可改约/催办），否则回落到契约给的既有单
      let targetNo = contractNo
      try {
        const { data } = await api.ticket.list({ pageSize: 50 })
        const active = data.items.find(
          (item) =>
            item.roomCode === request.roomCode &&
            item.category === request.category &&
            TICKET_TRANSITIONS.reschedule.from.includes(item.status),
        )
        if (active) targetNo = active.ticketNo
      } catch {
        // 列表不可用时直接用契约给的既有单号（不影响主流程）
      }
      try {
        const { data } = await api.ticket.get(targetNo)
        ticketNo.value = data.ticketNo
        ticketStatus.value = data.status
        ticketStatusText.value = data.statusText || TICKET_STATUS_LABELS[data.status]
        ticketCategory.value = TICKET_CATEGORY_LABELS[data.category]
        ticketUrgency.value = URGENCY_LABELS[data.urgency]
        ticketTeam.value = data.team ?? ''
        ticketEta.value = data.eta ?? ''
        ticketLocation.value = `${data.roomCode} · ${data.location}`
        ticketSlot.value = data.slot ?? ''
        ticketMock.value = data.mock
      } catch {
        ticketNo.value = targetNo
        ticketStatusText.value = TICKET_STATUS_LABELS.ACCEPTED
      }
      push({
        role: 'ai',
        text: '该房 24 小时内已有同类报修，本次未重复建单；这是正在处理的工单：',
        kind: 'ticket',
      })
      return
    }
    // 建单失败（503/E-4001 等）：不编造工单号，给人工待办路径
    ticketErrorText.value = `建单暂时失败，已转人工待办（${errorText(code)}）`
    ticketDegraded.value = apiError?.degraded
    ticketDegradedText.value = degradedText(apiError?.degraded)
    push({
      role: 'ai',
      text: `${ticketErrorText.value}。您也可以重新提交（同一动作会自动复用幂等键，不会重复建单）。`,
      kind: 'ticket',
      chips: [
        { label: '重试建单', act: () => void finishRepair() },
        { label: '转人工', act: () => doHandoff() },
      ],
    })
  } finally {
    setSending('', false)
  }
}

function resetTicketCard() {
  ticketNo.value = ''
  ticketStatus.value = ''
  ticketStatusText.value = ''
  ticketCategory.value = ''
  ticketUrgency.value = ''
  ticketTeam.value = ''
  ticketEta.value = ''
  ticketDuplicated.value = false
  ticketMock.value = true
  ticketDegraded.value = undefined
  ticketDegradedText.value = ''
  ticketErrorText.value = ''
}

// ---------------------------------------------------------------- 通用链路
function doHandoff() {
  if (handedOff.value) return
  handedOff.value = true
  flow.value = 'idle'
  push({ role: 'me', text: '转人工' })
  push({ role: 'ai', kind: 'handoff' })
  emit('handoff', handoffSummary.value)
}

function goProgress() {
  push({ role: 'me', text: '看一下工单进度' })
  push({ role: 'ai', text: '正在带您去工单进度：接单 / 出发 / 完工都会在这里更新。' })
  emit('progress', ticketNo.value || undefined)
}

function genericReply() {
  push({
    role: 'ai',
    text: '可以直接说需求，我会自动判断该查账单、报修还是转人工，不用先选类型。',
    chips: [
      { label: '查本月账单', act: () => withUser('这个月物业费怎么算的？', askBill) },
      { label: '我要报修', act: () => withUser('卫生间漏水了', startRepair) },
      { label: '转人工', act: () => doHandoff() },
    ],
  })
}

function withUser(text: string, next: () => void) {
  push({ role: 'me', text })
  next()
}

// ---------------------------------------------------------------- 输入
function send() {
  const text = draft.value.trim()
  if (!text) return
  push({ role: 'me', text })
  resetDraft()
  route(text)
}

function sendPreset(text: string) {
  push({ role: 'me', text })
  route(text)
}

function route(text: string) {
  if (flow.value === 'repair') {
    if (classify(text) === 'human') {
      doHandoff()
      return
    }
    // 报修过程中补充的文字按当前问题要素记录
    answers.value[REPAIR_STEPS[stepIndex.value]?.key ?? 'note'] = text
    stepIndex.value += 1
    emitStep()
    return
  }
  switch (classify(text)) {
    case 'human':
      doHandoff()
      return
    case 'progress':
      goProgress()
      return
    case 'bill':
      askBill()
      return
    case 'repair':
      startRepair()
      return
    case 'code':
      if (!session.ensureVerified()) void submitVerify(text)
      else push({ role: 'ai', text: '本月账单已在上面；需要其他帮助直接说就行。' })
      return
    default:
      if (flow.value === 'finished' && ticketNo.value) {
        push({ role: 'ai', text: '补充说明已记录；如需查进度可点工单卡「查看进度」。' })
        return
      }
      genericReply()
  }
}

// ---------------------------------------------------------------- 工单动作（真实 API）
/** 供宿主（OwnerView 改约弹层）调用：CAP-07 改约（状态不变，写操作幂等） */
async function reschedule(slot: string, reason?: string) {
  if (!ticketNo.value) return { ok: false, message: '当前没有可改约的工单' }
  if (!canRescheduleTicket.value) return { ok: false, message: '当前工单状态不支持改约' }
  const key = idempotency.ensure(actionSeed('ticket.reschedule', { ticketNo: ticketNo.value, slot }))
  try {
    const detail = await api.ticket.get(ticketNo.value)
    const { data } = await api.ticket.transition(
      ticketNo.value,
      { action: 'reschedule', payload: { slot, ...(reason ? { reason } : {}) }, version: detail.data.version },
      key,
    )
    idempotency.settle()
    ticketSlot.value = slot
    ticketStatus.value = data.status
    ticketStatusText.value = data.statusText || TICKET_STATUS_LABELS[data.status]
    return { ok: true }
  } catch (error) {
    const code = error instanceof ApiError ? error.code : 'E-9001'
    return { ok: false, code, message: errorText(code) }
  }
}

/** 供宿主（OwnerView 催办弹层）调用：CAP-09 催办（状态不变，写操作幂等） */
async function urge(reason: string) {
  if (!ticketNo.value) return { ok: false, message: '当前没有可催办的工单' }
  if (!canUrgeTicket.value) return { ok: false, message: '当前工单状态不支持催办' }
  const key = idempotency.ensure(actionSeed('ticket.urge', { ticketNo: ticketNo.value, reason }))
  try {
    const detail = await api.ticket.get(ticketNo.value)
    const { data } = await api.ticket.transition(
      ticketNo.value,
      { action: 'urge', payload: { reason, channel: 'chat' }, version: detail.data.version },
      key,
    )
    idempotency.settle()
    ticketStatus.value = data.status
    ticketStatusText.value = data.statusText || TICKET_STATUS_LABELS[data.status]
    return { ok: true }
  } catch (error) {
    const code = error instanceof ApiError ? error.code : 'E-9001'
    return { ok: false, code, message: errorText(code) }
  }
}

// ---------------------------------------------------------------- 初始化 / 暴露
function init() {
  seed = 0
  messages.value = []
  flow.value = 'idle'
  handedOff.value = false
  stepIndex.value = 0
  answers.value = {}
  sending.value = false
  touched.value = []
  pendingFlow.value = ''
  resetTicketCard()
  explain.value = null
  explainErrorCode.value = ''
  explainDegraded.value = undefined
  knowledgeOpen.value = false
  roomProbe.value = ''
  roomProbeResult.value = null
  roomProbeSending.value = false
  session.reset()
  idempotency.settle()
  resetDraft()
  push({
    role: 'ai',
    text: `${OWNER.name}您好，我是${OWNER.project}智能管家。只用一个对话框：查账单、报修、查进度、转人工，直接说就行。`,
  })
  push({ role: 'me', text: '这个月物业费怎么比上个月多 38 块？' })
  askVerify('bill')
}

/** 供父级注入一句话（如语音识别结果）：按自然语言原话进入同一会话，由 AI 识别意图 */
function ask(text: string) {
  const value = text.trim()
  if (!value) return
  push({ role: 'me', text: value })
  route(value)
}

defineExpose({ reset: init, ask, reschedule, urge, ticketNo: () => ticketNo.value })
init()
</script>

<template>
  <div class="own-phone">
    <div class="own-phone-status">
      <span>9:41</span>
      <span>5G ▮▮▮</span>
    </div>

    <div class="own-phone-head">
      <span class="own-phone-avatar">云</span>
      <div class="own-phone-title">
        <b>{{ OWNER.project }} · 智能管家</b>
        <small><i class="own-ai-dot" />AI 生成内容已标识 · 24 小时在线</small>
      </div>
      <button type="button" class="own-handoff" @click="doHandoff">转人工</button>
    </div>

    <div ref="chatRef" class="own-chat" aria-live="polite">
      <div class="own-time">今天 15:02</div>

      <div v-for="message in messages" :key="message.id" class="own-msg" :class="{ 'is-me': message.role === 'me' }">
        <span class="own-av">{{ message.role === 'me' ? '我' : 'AI' }}</span>
        <div class="own-bubble">
          <template v-if="message.kind === 'bill'">
            <div v-if="bill" class="own-bcard" data-source="erp">
              <div class="own-bcard__head">
                <div>
                  <span>{{ bill.period }} 本期应缴</span>
                  <b style="display: block">¥{{ formatMoney(bill.total) }}</b>
                </div>
                <span>{{ dueText(bill.dueDate) }}</span>
              </div>
              <div class="own-bcard__body">
                <div v-for="item in bill.items" :key="item.label" class="own-bline">
                  <span>{{ item.label }}<em v-if="item.note">{{ item.note }}</em></span>
                  <b>{{ formatMoney(item.amount) }}</b>
                </div>
                <div class="own-bcard__hr" />
                <div class="own-bline">
                  <span>上月账单 {{ formatMoney(bill.prevTotal) }} · 较上月</span>
                  <b style="color: var(--c-amber)">{{ formatMoney(bill.diff) }}</b>
                </div>
              </div>
              <div class="own-bcard__foot">
                <WButton v-if="allowPay" size="sm" variant="primary" @click="emit('pay')">去缴费</WButton>
                <WButton size="sm" @click="emit('invoice')">电子发票</WButton>
              </div>
            </div>
            <div class="own-datasrc">
              <WTag v-if="bill?.mock" tone="a" plain>{{ mockBadgeText }}</WTag>
              <WTag tone="g" plain>金额来自收费系统</WTag>
              <WTag v-if="explainDegraded" tone="r" plain>解释已降级</WTag>
            </div>

            <!-- P3 W10：费用解释（公式 / 分摊依据 / 与上期差额 / 知识出处） -->
            <div v-if="explain" class="own-explain" data-testid="own-bill-explain">
              <div class="own-explain__head">
                <b>费用解释</b>
                <WTag v-if="explain.mock" tone="a" plain>{{ mockBadgeText }}</WTag>
                <WTag tone="g" plain>{{ explain.roomCode }} · {{ explain.period }}</WTag>
              </div>
              <p class="own-explain__formula" data-testid="own-bill-formula">{{ explain.formula }}</p>
              <div
                v-for="line in explain.breakdown"
                :key="line.itemCode"
                class="own-explain__line"
                data-testid="own-bill-line"
              >
                <span>
                  {{ line.name }}
                  <em>基数：{{ line.base }}</em>
                  <em>口径：{{ line.rule }}</em>
                </span>
                <b>{{ formatMoney(line.amount) }}</b>
              </div>
              <div v-if="explain.diffVsPrev" class="own-explain__diff" data-testid="own-bill-diff">
                <span>较上期 {{ explainDiffText }}</span>
                <p>{{ explain.diffVsPrev.reason }}</p>
              </div>
              <div class="own-explain__refs">
                <button
                  v-for="ref in explain.knowledgeRefs"
                  :key="ref.knowledgeId"
                  type="button"
                  class="own-ref"
                  :data-testid="`own-bill-ref-${ref.knowledgeId}`"
                  @click="openKnowledgeSource(ref)"
                >
                  {{ ref.knowledgeId }} · {{ ref.title }} · {{ ref.version }}
                </button>
              </div>
              <p class="own-explain__note">解释与金额都来自收费系统 + 知识库；AI 不重算、不承诺减免（减免需人工审批）。</p>
            </div>
            <div v-else-if="explainErrorCode" class="own-explain is-error" data-testid="own-bill-explain-error">
              <span>费用解释暂时无法返回（{{ errorText(explainErrorCode) }}）；按规则不编造，已提供转人工。</span>
            </div>
            <div v-if="explainDegraded" class="own-degrade">
              <WIcon name="refresh" :size="11" />
              <span>{{ degradedText(explainDegraded) }}</span>
            </div>

            <div v-if="billDegraded" class="own-degrade">
              <WIcon name="refresh" :size="11" />
              <span>{{ billText }}</span>
            </div>
            <div class="own-cite">
              <WIcon name="book" :size="11" />
              <span>{{ billCite }}<template v-if="bill?.diffReason"> · {{ bill.diffReason }}</template></span>
            </div>
          </template>

          <template v-else-if="message.kind === 'ticket'">
            <div v-if="ticketNo" class="own-bcard" data-source="ticket">
              <div class="own-bcard__head">
                <div>
                  <span>{{ ticketStatusText }}{{ ticketDuplicated ? ' · 已有工单' : '' }}</span>
                  <b style="display: block; font-size: 12px">{{ ticketNo }}</b>
                </div>
                <WTag :tone="ticketMock ? 'a' : 'g'" plain>{{ ticketMock ? mockBadgeText : 'ERP 已写入' }}</WTag>
              </div>
              <div class="own-bcard__body">
                <div v-if="ticketCategory" class="own-bline">
                  <span>类型 / 等级</span>
                  <b>{{ ticketCategory }}<template v-if="ticketUrgency"> / {{ ticketUrgency }}</template></b>
                </div>
                <div class="own-bline"><span>派单班组</span><b>{{ ticketTeam || '待分配' }}</b></div>
                <div class="own-bline"><span>位置</span><b>{{ ticketLocation || OWNER.room }}</b></div>
                <div class="own-bline"><span>上门时间</span><b>{{ ticketEta || ticketSlot || '待确认' }}</b></div>
                <div v-if="ticketDuplicated" class="own-bline">
                  <span>重复建单</span>
                  <b style="color: var(--c-amber)">24h 内已有同类报修，未重复提交</b>
                </div>
              </div>
              <div class="own-bcard__foot">
                <WButton size="sm" @click="emit('progress', ticketNo)">查看进度</WButton>
                <template v-if="canTransition">
                  <WButton
                    size="sm"
                    :disabled="!canRescheduleTicket"
                    :title="canRescheduleTicket ? '提交改约（真实写入工单）' : '当前工单状态不支持改约'"
                    @click="emit('reschedule')"
                  >
                    改约时间
                  </WButton>
                  <WButton
                    size="sm"
                    variant="ghost"
                    :disabled="!canUrgeTicket"
                    :title="canUrgeTicket ? '提交催办（真实写入工单）' : '当前工单状态不支持催办'"
                    @click="emit('urge')"
                  >
                    催办
                  </WButton>
                </template>
                <span v-else class="own-bcard__cap-hint">
                  当前演示账号无「{{ 'ticket.assign' }}」能力：改约 / 催办由管家或坐席发起
                </span>
              </div>
            </div>
            <div v-else-if="ticketErrorText" class="own-bcard" data-source="degraded">
              <div class="own-bcard__head">
                <span>{{ ticketErrorText }}</span>
              </div>
              <div class="own-bcard__body">
                <div class="own-bline"><span>工单号</span><b>未生成（不编造）</b></div>
                <div class="own-bline"><span>后续</span><b>已进入人工待办队列</b></div>
              </div>
            </div>
            <div v-if="ticketDegradedText" class="own-degrade">
              <WIcon name="refresh" :size="11" />
              <span>{{ ticketDegradedText }}</span>
            </div>
            <div class="own-cite">
              <WIcon name="shield" :size="11" />
              <span>AI 只记录、不认定责任；渗漏责任与赔付由物业按流程处理。</span>
            </div>
          </template>

          <template v-else-if="message.kind === 'handoff'">
            <div class="own-bcard">
              <div class="own-bcard__head">
                <span>已转人工 · {{ HANDOFF.seat }}</span>
              </div>
              <div class="own-bcard__body">
                <div class="own-bline"><span>排队</span><b>{{ HANDOFF.queue }}</b></div>
                <div class="own-bline"><span>交接摘要</span><b>{{ handoffSummary }}</b></div>
              </div>
            </div>
          </template>

          <template v-else>
            {{ message.text }}
          </template>

          <div v-if="message.chips" class="own-chips">
            <button v-for="chip in message.chips" :key="chip.label" type="button" class="own-chip" @click="chip.act()">
              {{ chip.label }}
            </button>
          </div>
        </div>
      </div>

      <div v-if="sending" class="own-time">{{ sendingText || '正在处理…' }}</div>

      <!-- 数据范围自检：查其他房号只会拿到 403（E-2002），页面按其人话提示，不展示其他业主金额 -->
      <div v-if="bill" class="own-roomprobe" data-testid="own-roomprobe">
        <span class="own-roomprobe__cap">查其他房号（仅本户授权；跨房查询会被服务端拒绝）</span>
        <div class="own-roomprobe__row">
          <input
            v-model="roomProbe"
            class="own-roomprobe__input"
            type="text"
            placeholder="如 1-1-101"
            aria-label="查其他房号"
            data-testid="own-roomprobe-input"
            @keydown.enter.prevent="probeRoom"
          />
          <WButton size="sm" :loading="roomProbeSending" data-testid="own-roomprobe-query" @click="probeRoom">查一下</WButton>
        </div>
        <p
          v-if="roomProbeResult"
          class="own-roomprobe__result"
          :class="{ 'is-denied': roomProbeResult.kind === 'forbidden' }"
          data-testid="own-roomprobe-result"
        >
          {{ roomProbeResult.text }}
        </p>
      </div>
    </div>

    <!-- 统一输入区：快捷说法 + 语音 / 自适应输入 / 发送 + 快捷键与合规说明 -->
    <div class="own-composer">
      <div class="own-suggest">
        <span class="own-suggest__cap">试试这样说</span>
        <button
          v-for="item in SUGGESTIONS"
          :key="item.label"
          type="button"
          class="own-prompt"
          @click="sendPreset(item.text)"
        >
          {{ item.label }}
        </button>
      </div>

      <div class="own-input" :class="{ 'is-focus': focused }">
        <button type="button" class="own-input__mic" aria-label="语音输入" title="语音输入" @click="emit('voice')">
          <WIcon name="mic" :size="14" />
        </button>
        <textarea
          ref="taRef"
          v-model="draft"
          rows="1"
          class="own-input__area"
          :placeholder="placeholder"
          aria-label="向智能管家提问"
          @focus="focused = true"
          @blur="focused = false"
          @input="autoGrow"
          @keydown.enter.exact.prevent="send"
        />
        <button type="button" class="own-input__send" :disabled="!draft.trim()" aria-label="发送" @click="send">
          <WIcon name="arrow-up" :size="15" />
        </button>
      </div>

      <div class="own-composer__hint">
        <span>Enter 发送 · Shift+Enter 换行</span>
        <span>AI 生成内容仅供参考</span>
      </div>
    </div>

    <!-- 费用解释的知识出处（点击引用条目打开；无出处不解释） -->
    <WModal v-model:open="knowledgeOpen" title="费用解释出处" width="480px">
      <div class="own-know" data-testid="own-knowledge-source">
        <p class="own-know__title">{{ knowledgeRef?.title ?? '—' }}</p>
        <p class="own-know__row">知识条目 <code>{{ knowledgeRef?.knowledgeId ?? '—' }}</code></p>
        <p class="own-know__row">版本 <code>{{ knowledgeRef?.version ?? '—' }}</code></p>
        <p class="own-know__note">
          解释必须可溯源：口径与金额条目绑定知识 ID + 版本；无出处时按规则拒答并转人工。金额由收费系统返回，AI 不重算、不减免。
        </p>
      </div>
    </WModal>
  </div>
</template>

<style scoped>
/* P2 新增：数据来源角标 / 降级条（不改 owner.css，避免影响 P1 视觉基线） */
.own-datasrc {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
  margin-top: 5px;
}

.own-degrade {
  display: flex;
  align-items: flex-start;
  gap: 4px;
  margin-top: 5px;
  padding: 5px 7px;
  border: 1px dashed var(--c-amber, #d97706);
  border-radius: 6px;
  background: var(--c-amber-soft, #fff7e6);
  color: #9a4b05;
  font-size: 9.5px;
  line-height: 1.6;
}

/* P3 W10 新增：费用解释 / 知识出处 / 跨房数据范围自检（不改 owner.css） */
.own-explain {
  margin-top: 6px;
  padding: 7px 8px;
  border: 1px solid #e6efeb;
  border-radius: 8px;
  background: #f7faf9;
}

.own-explain.is-error {
  border-style: dashed;
  border-color: var(--c-amber, #d97706);
  background: var(--c-amber-soft, #fff7e6);
  color: #9a4b05;
  font-size: 9.5px;
  line-height: 1.6;
}

.own-explain__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
  margin-bottom: 4px;
}

.own-explain__head b {
  font-size: 10px;
  color: var(--c-ink, #10202b);
}

.own-explain__formula {
  padding: 5px 6px;
  border-radius: 5px;
  background: #ffffff;
  font-size: 9px;
  color: #5a7280;
  line-height: 1.55;
}

.own-explain__line {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
  margin-top: 4px;
  padding-top: 4px;
  border-top: 1px dashed #dfeae6;
}

.own-explain__line span {
  display: flex;
  flex-direction: column;
  gap: 1px;
  font-size: 9.5px;
  color: var(--c-ink-2, #31424d);
}

.own-explain__line em {
  font-style: normal;
  font-size: 8.5px;
  color: var(--c-faint, #8fa1ad);
}

.own-explain__line b {
  flex: none;
  font-size: 10px;
  color: var(--c-ink, #10202b);
}

.own-explain__diff {
  margin-top: 5px;
  padding: 5px 6px;
  border: 1px dashed var(--c-amber, #d97706);
  border-radius: 5px;
  background: var(--c-amber-soft, #fff7e6);
  font-size: 9.5px;
  color: #9a4b05;
}

.own-explain__diff p {
  margin-top: 2px;
  line-height: 1.5;
}

.own-explain__refs {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 6px;
}

.own-ref {
  height: 20px;
  padding: 0 7px;
  border: 1px solid #bfe7db;
  border-radius: 10px;
  background: #ffffff;
  font-size: 8.5px;
  color: #0a725f;
}

.own-ref:hover {
  background: var(--c-brand-soft, #e9f7f3);
}

.own-explain__note {
  margin-top: 5px;
  font-size: 8.5px;
  color: var(--c-faint, #8fa1ad);
  line-height: 1.5;
}

.own-roomprobe {
  margin: 8px 2px 0;
  padding: 7px 8px;
  border: 1px dashed #d6e3ea;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.72);
}

.own-roomprobe__cap {
  display: block;
  font-size: 8.5px;
  color: var(--c-faint, #8fa1ad);
}

.own-roomprobe__row {
  display: flex;
  align-items: center;
  gap: 5px;
  margin-top: 4px;
}

.own-roomprobe__input {
  flex: 1;
  min-width: 0;
  height: 24px;
  padding: 0 8px;
  border: 1px solid #d6e3ea;
  border-radius: 6px;
  background: #ffffff;
  font-size: 9.5px;
  color: var(--c-ink, #10202b);
}

.own-roomprobe__input:focus {
  outline: none;
  border-color: var(--c-brand, #0d9488);
}

.own-roomprobe__result {
  margin-top: 5px;
  padding: 5px 6px;
  border-radius: 5px;
  background: var(--c-bg-2, #f1f4f6);
  font-size: 9px;
  color: #5a7280;
  line-height: 1.55;
}

.own-roomprobe__result.is-denied {
  border: 1px solid #f6d5d3;
  background: var(--c-red-soft, #fdf3f2);
  color: #b42318;
}

.own-know__title {
  font-size: 11px;
  font-weight: 600;
  color: var(--c-ink, #10202b);
}

.own-know__row {
  margin-top: 5px;
  font-size: 10px;
  color: var(--c-ink-2, #31424d);
}

.own-know__row code {
  padding: 0 3px;
  border-radius: 3px;
  background: var(--c-bg-2, #f1f4f6);
  font-family: var(--font-mono, monospace);
  font-size: 9.5px;
}

.own-know__note {
  margin-top: 8px;
  font-size: 9.5px;
  color: var(--c-muted, #617585);
  line-height: 1.6;
}
</style>
