<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { WButton, WCard, WIcon, WLoading, WModal, WPageHeader, WTag, useToast } from '@/components'
import { RateModal, RescheduleModal, UrgeModal } from '@/components/actions'
import type { RateConfirm, RescheduleConfirm, UrgeConfirm } from '@/components/actions'
import PermissionDenied from '@/components/feedback/PermissionDenied.vue'
import { capabilityLabel } from '@/auth/capabilities'
import { ROLE_LABELS } from '@/app/roles'
import { SCREENS } from '@/app/screens'
import StateDemo from '@/content/StateDemo.vue'
import { DEMO_SUFFIX, demoNote, successReceipt } from '@/content'
import {
  api,
  ApiError,
  TICKET_CATEGORY_LABELS,
  TICKET_STATUSES,
  TICKET_STATUS_LABELS,
  TICKET_TRANSITIONS,
  degradedText,
  errorText,
  forbiddenDetailsOf,
  formatIsoMinute,
  formatIsoTime,
  mockBadgeText,
} from '@/api'
import type {
  ActorRole,
  DegradedInfo,
  DispatchPayload,
  ErpWriteback,
  ErpWritebackStatus,
  Ticket,
  TicketPush,
  TicketPushChannel,
  TicketPushStatus,
  TicketStatus,
} from '@/api'
import { actionSeed, useIdempotencyKey } from '@/composables/useIdempotencyKey'
import { useAppStore } from '@/stores/app'
import { useAuthStore } from '@/stores/auth'
import {
  AI_DID,
  DEMO_TICKET_NO,
  HUMAN_DID,
  KNOWLEDGE_SUGGESTION,
  QUALITY_NOTES,
  RESCHEDULE_SLOTS,
  REVIEW,
} from './data'

/**
 * 03 工单进度 · 闭环（P2 W6 接真实 API，契约 v0.3.1）
 * ---------------------------------------------------------------------------
 * 数据来源（契约 v0.3.1）：
 * - `GET /api/v1/tickets/{ticketNo}`：7 节点时间轴（`nodes`）+ 状态（`TicketStatus` 7 节点）+ 班组/ETA/版本
 *   + `feedback`（回访评分落库，刷新后仍在；未回访时不返回）；
 * - `GET /api/v1/tickets/{ticketNo}/pushes`：受理/派单/完工三次推送记录（`TicketPush`：通道/模板/状态/
 *   时间/脱敏正文）。P2 W6 推送由 mock 通道受理（`status=mocked`），**不代表已真实外发**；
 *   **不再由已回写节点派生推送**，无记录显示「尚无推送记录」（不造数）；
 * - `POST /tickets/{no}/transition`：派单（自动匹配班组，回显 `dispatch` + `erpWriteback.pending`）、
 *   改约、催办（写操作统一带 `Idempotency-Key`）；
 * - `POST /tickets/{no}/feedback`：评价服务（成功后工单进入 REVIEWED，响应含 `feedback` 摘要）。
 *
 * 回写状态口径（架构 §24.6 / 契约 `ErpWriteback`）：
 * - 数据源优先级：① 本页动作回执 `erpWriteback`（派单/完工返回，最权威）；
 *   ② 工单详情降级 `fallback=human`（回写失败已转人工待办）；③ 按 ERP 节点时间 + 契约 30 秒确认窗口
 *   推算（详情读接口不回传回写状态，推算结果在 UI 明确标注「按 30 秒窗口推算」）。
 *
 * 边界：节点时间/处理人以 ERP 回写为准，前端不猜测缺失节点（未到达节点显示「待执行」）；
 * `mock:true` 标注「演示数据（mock ERP）」，`degraded` 出现时显示降级条；不编造金额/工单号/推送。
 */
const screen = SCREENS.progress
const toast = useToast()
const route = useRoute()
const router = useRouter()
const idempotency = useIdempotencyKey()
const app = useAppStore()
const auth = useAuthStore()

/** 派单/完工等写动作只对内部角色开放（业主端进度页只读 + 评价） */
const STAFF_ROLES = ['steward', 'agent', 'supervisor', 'it', 'admin'] as const
const isStaff = computed(() => (STAFF_ROLES as readonly string[]).includes(app.currentRole))

/**
 * 改约 / 催办走后端 `POST /tickets/{no}/transition`，需要能力 `ticket.assign`
 * （契约 v0.3.3 矩阵：steward/agent/supervisor/it/admin；owner 没有）。
 * 前端按能力隐藏入口，真实拦截仍在 Java BFF（403 E-2001），不把越权入口暴露给业主。
 */
const canAssign = computed(() => auth.hasCapability('ticket.assign'))

/** 当前工单号：优先 `?ticket=` 深链，否则默认演示单 */
const ticketNo = computed(() => {
  const query = route.query.ticket
  const value = Array.isArray(query) ? query[0] : query
  return String(value ?? '').trim() || DEMO_TICKET_NO
})
const hasDeepLink = computed(() => Boolean(route.query.ticket))

const ticket = ref<Ticket | null>(null)
const loading = ref(true)
const loadError = ref<{ code: string; message: string } | null>(null)
/**
 * 403 无权限态（P2 W9 / task-33）：`GET /tickets/{no}` 返回 `E-2001` +
 * `details{requiredCapability, role}` 时，页面渲染「无权限」设计态，
 * **不渲染工单/节点/推送等未授权数据**，并给出可切换的演示账号（切换后重新拉取）。
 *
 * `traceId` 保留在状态里供排查，但**不在页面级无权限态里渲染**：
 * 该值每次请求都变，渲染进 DOM 会让审计/截图的基线每轮都不同（W9 死按钮审计需稳定基线）。
 */
const denied = ref<{ requiredCapability: string; role: string; traceId: string } | null>(null)
/** 从无权限态切换演示账号中（按钮 loading，避免重复登录） */
const switchingDenied = ref(false)
/** 可切换账号白名单：具备 ticket.status 且 /progress 可见的角色（不含切过去会被页面级拦截的角色） */
const PROGRESS_SWITCH_ROLES: readonly ActorRole[] = ['owner', 'steward', 'agent', 'supervisor']
const degraded = ref<DegradedInfo | undefined>(undefined)
const actionError = ref('')
/** 当前演示角色人话（403 说明用） */
const currentRoleLabel = computed(() => (auth.role ? ROLE_LABELS[auth.role] : '未登录'))

/* ------------------------- 三次推送（GET /tickets/{no}/pushes） ------------------------- */
/** 推送记录（契约 v0.3.1 `TicketPush`；无记录时为空数组，不造数） */
const pushes = ref<TicketPush[]>([])
const pushesLoading = ref(false)
const pushesError = ref('')
/** 推送接口 403（缺 ticket.status）：不算业务失败，按「无权限」口径说明且不补造 */
const pushesDenied = ref(false)
const pushesDegraded = ref<DegradedInfo | undefined>(undefined)
/** 推送请求序号：换单/重试时丢弃过期响应，避免 A 单推送覆盖 B 单 */
let pushesRequestId = 0

/* ------------------------- 回写状态（ErpWriteback） ------------------------- */
/** 本页写动作回执里的回写结果（派单/完工返回，权威来源） */
const writebackEcho = ref<ErpWriteback | null>(null)
/** 派单回显（仅 dispatch 返回；用于 Hero/要素卡即时刷新，无需等详情刷新） */
const dispatchEcho = ref<DispatchPayload | null>(null)
const dispatching = ref(false)

/** 回访评分：契约 `Ticket.feedback.score` 优先，页面内刚提交的分值兜底（刷新后仍以落库值展示） */
const localReviewScore = ref(5)
const localReviewTags = ref<string[]>([])
const feedback = computed(() => ticket.value?.feedback)
const reviewScore = computed(() => feedback.value?.score ?? localReviewScore.value)
const reviewTags = computed(() => feedback.value?.tags ?? localReviewTags.value)
const reviewed = computed(() => ticket.value?.status === 'REVIEWED' || Boolean(feedback.value))
const reviewOpen = ref(false)
const rescheduleOpen = ref(false)
const urgeOpen = ref(false)
const traceOpen = ref(false)
const rescheduleSlot = ref(RESCHEDULE_SLOTS[1] ?? '')
const knowledgeDraft = ref(false)
const submitting = ref(false)

const status = computed<TicketStatus | ''>(() => ticket.value?.status ?? '')

/** 契约驱动的按钮可用性（`TICKET_TRANSITIONS` 允许前置状态） */
const canFeedback = computed(() => Boolean(status.value) && status.value === 'COMPLETED')
const canReschedule = computed(
  () => Boolean(status.value) && TICKET_TRANSITIONS.reschedule.from.includes(status.value as TicketStatus),
)
const canUrge = computed(
  () => Boolean(status.value) && TICKET_TRANSITIONS.urge.from.includes(status.value as TicketStatus),
)
/** 派单：仅内部角色（管家/坐席/主管等）+ 契约允许前置状态（CREATED/ACCEPTED） */
const canDispatch = computed(
  () =>
    isStaff.value &&
    Boolean(status.value) &&
    TICKET_TRANSITIONS.dispatch.from.includes(status.value as TicketStatus) &&
    !dispatching.value,
)

/** 7 节点行：状态枚举来自契约，时间/处理人来自 `nodes`（未到达节点不做推测） */
interface NodeRow {
  code: TicketStatus
  label: string
  time: string
  at: string
  who: string
  ownerText: string
  reached: boolean
  current: boolean
  state: string
  band: 'AI' | 'ERP' | '待执行'
  ownerView: string
}

/** 业主可见文案（设计口径：每个节点「业主看到什么」；有 ERP 文案优先用 ERP） */
const OWNER_VIEW_TEXT: Record<TicketStatus, string> = {
  CREATED: '已提交报修，等待受理',
  ACCEPTED: '已受理，管家会尽快联系您',
  DISPATCHED: '已派单，师傅将按预约时段上门',
  ON_SITE: '师傅已上门处理中',
  COMPLETED: '已完工，可评价本次服务',
  REVIEWED: '已回访，感谢您的评价',
  ARCHIVED: '工单已归档',
}

const nodeRows = computed<NodeRow[]>(() => {
  const currentCode = status.value
  const currentIndex = currentCode ? TICKET_STATUSES.indexOf(currentCode) : -1
  const nodes = ticket.value?.nodes ?? []
  return TICKET_STATUSES.map((code, index) => {
    const node = nodes.find((item) => item.status === code)
    const reached = Boolean(node) || (currentIndex >= 0 && index < currentIndex)
    const current = code === currentCode
    return {
      code,
      label: TICKET_STATUS_LABELS[code],
      time: node?.at ? formatIsoTime(node.at) : '',
      at: node?.at ? formatIsoMinute(node.at) : '',
      who: node?.who ?? '',
      ownerText: node?.ownerText ?? '',
      reached,
      current,
      state: reached ? '完成' : current ? '进行中' : '待执行',
      band: node ? (/AI|客服中心/.test(node.who ?? '') ? 'AI' : 'ERP') : '待执行',
      ownerView: node?.ownerText || OWNER_VIEW_TEXT[code],
    }
  })
})

/** 已发生节点数（时间轴质量口径） */
const reachedCount = computed(() => nodeRows.value.filter((row) => row.reached).length)

/* ------------------------------ 三次推送（真实记录） ------------------------------ */
/** 推送通道 → 中文（契约 `TicketPushChannel`） */
const PUSH_CHANNEL_LABELS: Readonly<Record<TicketPushChannel, string>> = {
  wecom: '企微',
  sms: '短信',
  inapp: '站内',
}
/** 推送状态 → 中文 + 色调（契约 `TicketPushStatus`；`mocked` = 通道模拟已留痕，非真实外发） */
const PUSH_STATUS_VIEW: Readonly<
  Record<TicketPushStatus, { text: string; tone: 'g' | 'b' | 'a' | 'r' | 'n' }>
> = {
  mocked: { text: '通道模拟已留痕', tone: 'a' },
  sent: { text: '已发送', tone: 'g' },
  pending: { text: '确认中', tone: 'b' },
  failed: { text: '发送失败', tone: 'r' },
}
/** 推送模板 → 中文（模板编码由服务端给出，未知编码原样展示，不硬编码猜测） */
const PUSH_TEMPLATE_LABELS: Readonly<Record<string, string>> = {
  accepted: '受理通知',
  dispatched: '派单/出发通知',
  completed: '完工通知',
}

interface PushRow {
  pushId: string
  templateLabel: string
  channelLabel: string
  statusText: string
  statusTone: 'g' | 'b' | 'a' | 'r' | 'n'
  status: TicketPushStatus
  sentAtText: string
  contentMasked: string
  mock: boolean
}

/** 推送行：完全来自 `GET /tickets/{no}/pushes`（按 sentAt 倒序由服务端给出），不派生、不造数 */
const pushRows = computed<PushRow[]>(() =>
  pushes.value.map((item) => {
    const statusView = PUSH_STATUS_VIEW[item.status ?? 'pending']
    return {
      pushId: item.pushId,
      templateLabel: PUSH_TEMPLATE_LABELS[item.template] ?? item.template,
      channelLabel: PUSH_CHANNEL_LABELS[item.channel] ?? item.channel,
      statusText: statusView.text,
      statusTone: statusView.tone,
      status: item.status,
      sentAtText: item.sentAt ? formatIsoTime(item.sentAt) : '—',
      contentMasked: item.contentMasked ?? '',
      mock: item.mock,
    }
  }),
)
/** 推送失败态文案：降级不隐藏，也不补造推送 */
const pushesFallbackText = computed(() => (pushesError.value ? '推送记录暂不可用' : '尚无推送记录'))

/* ------------------------------ 回写状态（ErpWriteback） ------------------------------ */
interface WritebackView {
  status: ErpWritebackStatus
  text: string
  tone: 'g' | 'a' | 'r'
  hint: string
  /** 数据来源说明（动作回执 / 降级信号 / 按窗口推算），避免把推算当成 ERP 回执 */
  source: string
}

const WRITEBACK_TEXT: Readonly<Record<ErpWritebackStatus, { text: string; tone: 'g' | 'a' | 'r' }>> = {
  pending: { text: 'ERP 回写确认中（30 秒窗口）', tone: 'a' },
  confirmed: { text: 'ERP 已确认回写', tone: 'g' },
  failed: { text: '回写未确认 · 已转人工待办', tone: 'r' },
}

/** 契约里回写确认窗口 = 30 秒（OpenAPI `ErpWriteback.nextRetryAt` / 架构 §24.6） */
const WRITEBACK_WINDOW_MS = 30_000

/**
 * 回写状态条：优先级
 * 1) 本页动作回执 `erpWriteback`（真实权威；如派单返回 pending）；
 * 2) 工单详情降级 `fallback=human`（Java 读路径确认回写失败 → 已进人工待办）；
 * 3) 按最近一次回写动作节点（已派单/已完工）时间 + 30 秒窗口推算，并在 UI 标注「按 30 秒窗口推算」；
 * 无回写动作 → null（不展示，避免把「没有动作」说成「回写完成」）。
 */
const writebackView = computed<WritebackView | null>(() => {
  const current = ticket.value
  const echo = writebackEcho.value
  if (echo) {
    const base = WRITEBACK_TEXT[echo.status]
    const attempts = `已尝试 ${echo.attempts} 次`
    const ref = echo.erpRef ? ` · ERP 引用 ${echo.erpRef}` : ''
    return {
      status: echo.status,
      text: base.text,
      tone: base.tone,
      hint:
        echo.status === 'pending'
          ? `${echo.message ?? 'ERP 受理中，30 秒后确认回写结果'}${echo.nextRetryAt ? ` · ${formatIsoMinute(echo.nextRetryAt)} 确认` : ''}（${attempts}${ref}）`
          : `${echo.message ?? (echo.status === 'confirmed' ? 'ERP 已确认回写' : '已进人工待办，不自动重写')}（${attempts}${ref}）`,
      source: '本页动作回执（TicketTransitionResult.erpWriteback）',
    }
  }
  if (degraded.value?.fallback === 'human') {
    return {
      status: 'failed',
      text: WRITEBACK_TEXT.failed.text,
      tone: 'r',
      hint: degradedText(degraded.value) || '回写失败已进人工待办，不自动重试；请人工核实 ERP 侧结果',
      source: '工单详情降级信号（degraded.fallback=human）',
    }
  }
  if (!current) return null
  const writebackNode = current.nodes
    .filter((node) => node.status === 'DISPATCHED' || node.status === 'COMPLETED')
    .reduce<(typeof current.nodes)[number] | null>((latest, node) => {
      if (!node.at) return latest
      if (!latest?.at) return node
      return Date.parse(node.at) >= Date.parse(latest.at) ? node : latest
    }, null)
  if (!writebackNode?.at) return null
  const elapsed = Date.now() - Date.parse(writebackNode.at)
  const pending = Number.isFinite(elapsed) && elapsed >= 0 && elapsed < WRITEBACK_WINDOW_MS
  const base = pending ? WRITEBACK_TEXT.pending : WRITEBACK_TEXT.confirmed
  return {
    status: pending ? 'pending' : 'confirmed',
    text: base.text,
    tone: base.tone,
    hint: pending
      ? `${TICKET_STATUS_LABELS[writebackNode.status]} 节点刚回写 ${Math.max(1, Math.round((WRITEBACK_WINDOW_MS - elapsed) / 1000))} 秒内确认；刷新可看结果`
      : `${TICKET_STATUS_LABELS[writebackNode.status]} 节点（${formatIsoMinute(writebackNode.at)}）回写窗口已过；读接口不回传回写结果，最终以 ERP 为准`,
    source: '按 ERP 节点时间 + 契约 30 秒确认窗口推算',
  }
})

/** Hero / 要素卡的班组与 ETA：派单回显优先（即时反映），否则用工单详情 */
const heroTeam = computed(() => {
  const echo = dispatchEcho.value
  if (echo?.teamName) return echo.teamName
  if (echo?.teamId) return echo.teamId
  return ticket.value?.team || ''
})
const heroEta = computed(() => {
  const echo = dispatchEcho.value
  if (echo?.eta) return String(echo.eta)
  return ticket.value?.eta || ''
})

/** Hero 顶部状态色（与 7 节点语义一致） */
const statusTone = computed<'g' | 'b' | 'a' | 'n'>(() => {
  switch (status.value) {
    case 'COMPLETED':
    case 'REVIEWED':
    case 'ARCHIVED':
      return 'g'
    case 'DISPATCHED':
    case 'ON_SITE':
      return 'b'
    case 'ACCEPTED':
      return 'a'
    default:
      return 'n'
  }
})

/** 下一步提示（给读者「现在该等什么 / 能做什么」；无 ticket.assign 能力的角色不提改约/催办入口） */
const nextHint = computed(() => {
  const current = ticket.value
  if (!current) return ''
  const canAskChange = canAssign.value ? '，着急可「催一下」或「改约」' : '；需要改约/催办请联系管家或坐席'
  switch (current.status) {
    case 'CREATED':
      return '等待受理：管家会在 2 小时内确认并派单'
    case 'ACCEPTED':
      return isStaff.value
        ? `已受理：内部角色可「派单」（按技能/楼栋/队列自动匹配班组）${canAskChange}`
        : `已受理：等待派单${canAskChange}`
    case 'DISPATCHED':
      return `等待上门：${current.eta || '预计时段待确认'}${canAskChange}`
    case 'ON_SITE':
      return '师傅已上门：处理完成后会自动回写完工'
    case 'COMPLETED':
      return '已完工：现在可以评价本次服务，评价会回流到班组考核'
    case 'REVIEWED':
      return canAssign.value
        ? '已回访：闭环完成；若有遗留问题可「催一下」或转人工'
        : '已回访：闭环完成；若有遗留问题请联系管家或转人工'
    case 'ARCHIVED':
      return '已归档：如需售后请重新报修或转人工'
    default:
      return ''
  }
})

const categoryText = computed(() => {
  const current = ticket.value
  if (!current) return ''
  const urgencyLabel: Record<string, string> = { normal: '一般', urgent: '紧急', emergency: '应急' }
  return `${TICKET_CATEGORY_LABELS[current.category]} / ${urgencyLabel[current.urgency] ?? current.urgency}`
})

const statusTagText = computed(() => ticket.value?.statusText ?? '')
const mockTag = computed(() => (ticket.value?.mock ? mockBadgeText : ''))
const degradedTag = computed(() => degradedText(degraded.value))

// ---------------------------------------------------------------- 数据加载
/**
 * 推送记录：`GET /tickets/{no}/pushes`（v0.3.1）。
 * 失败只降级推送区（不影响工单详情/时间轴），且**不补造**任何推送。
 */
async function loadPushes() {
  const requestId = ++pushesRequestId
  const requestTicketNo = ticketNo.value
  pushesLoading.value = true
  pushesError.value = ''
  pushesDenied.value = false
  pushesDegraded.value = undefined
  try {
    const { data, degraded: level } = await api.ticket.pushes(requestTicketNo)
    if (requestId !== pushesRequestId) return
    pushes.value = data.items ?? []
    pushesDegraded.value = level
  } catch (error) {
    if (requestId !== pushesRequestId) return
    pushes.value = []
    const apiError = error instanceof ApiError ? error : null
    const forbidden = forbiddenDetailsOf(error)
    if (forbidden) {
      // 403：推送区按「无权限」说明（不展示未授权记录、不补造），重试由 retryPushes 给可见反馈
      pushesDenied.value = true
      pushesError.value = `无权限：需要 ${capabilityLabel(forbidden.requiredCapability || 'ticket.status')}，当前角色「${forbidden.role || currentRoleLabel.value}」不具备`
      pushesDegraded.value = apiError?.degraded
      return
    }
    const code = apiError?.code ?? 'E-9001'
    pushesError.value = `${errorText(code)}（${code}）`
    pushesDegraded.value = apiError?.degraded
  } finally {
    if (requestId === pushesRequestId) pushesLoading.value = false
  }
}

async function loadTicket() {
  loading.value = true
  loadError.value = null
  denied.value = null
  actionError.value = ''
  // 换单时清掉上一张单的回执/派单回显，避免把 A 单的回写结果展示到 B 单
  writebackEcho.value = null
  dispatchEcho.value = null
  void loadPushes()
  try {
    const { data, degraded: level } = await api.ticket.get(ticketNo.value)
    ticket.value = data
    degraded.value = level
    if (data.feedback?.score) {
      localReviewScore.value = data.feedback.score
      localReviewTags.value = data.feedback.tags ?? []
    }
  } catch (error) {
    ticket.value = null
    const apiError = error instanceof ApiError ? error : null
    const forbidden = forbiddenDetailsOf(error)
    if (forbidden) {
      // 403（E-2001 + details.requiredCapability/role）→ 无权限态；不进入「空态/失败态」文案
      denied.value = {
        requiredCapability: forbidden.requiredCapability || 'ticket.status',
        role: forbidden.role || (auth.role ?? ''),
        traceId: apiError?.traceId ?? '',
      }
      degraded.value = apiError?.degraded
      return
    }
    const code = apiError?.code ?? 'E-9001'
    loadError.value = { code, message: errorText(code) }
    degraded.value = apiError?.degraded
  } finally {
    loading.value = false
  }
}

onMounted(loadTicket)
watch(ticketNo, loadTicket)
/**
 * 演示账号 / 角色切换 → 按新身份重新读取工单（P2 W9）：
 * 同一路由内切账号不会重新挂载组件，若不重取会残留上一个角色的工单数据
 * （越权显示 + 死按钮审计基线漂移）；403 时自然进入「无权限」态。
 */
watch(
  () => auth.role,
  (next, prev) => {
    if (!next || next === prev) return
    void loadTicket()
  },
)

// ---------------------------------------------------------------- 导出（来自 API 数据）
function downloadCsvFile(filename: string, header: string[], rows: string[][]): void {
  const escape = (value: string) => `"${value.replace(/"/g, '""')}"`
  const content = [header, ...rows].map((row) => row.map(escape).join(',')).join('\r\n')
  const blob = new Blob([`\ufeff${content}`], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  URL.revokeObjectURL(url)
}

function exportTicket() {
  const current = ticket.value
  if (!current) return
  downloadCsvFile(
    `${current.ticketNo}-工单.csv`,
    ['字段', '值', '来源'],
    [
      ['工单号', current.ticketNo, `ERP ${current.erp?.vendor ?? ''}`],
      ['状态', `${current.statusText}（${current.status}）`, 'ERP 状态回写'],
      ['类型 / 等级', categoryText.value, '报修五问 + 契约字典'],
      ['位置', `${current.roomCode} · ${current.location}`, '房档 room.query'],
      ['派单班组', current.team || '待分配', 'ERP'],
      ['预计上门', current.eta || current.slot || '待确认', 'ERP'],
      ['版本号', String(current.version), '平台乐观锁'],
      ['数据来源', current.mock ? 'mock ERP（演示）' : 'ERP', current.mock ? 'mock-erp 测试替身' : 'ERP'],
    ],
  )
  toast.success(successReceipt('导出工单 CSV', `${current.ticketNo} 单条工单`))
}

function exportEvidence() {
  const current = ticket.value
  if (!current) return
  downloadCsvFile(
    `${current.ticketNo}-证据包.csv`,
    ['证据项', '内容', '留痕'],
    [
      ['工单号', current.ticketNo, 'ERP'],
      ['traceId', current.traceId ?? '—', '全链路回溯'],
      ['ERP 引用', `${current.erp?.vendor ?? '—'} · ${current.erp?.ref ?? '—'}`, '连接器映射'],
      ['节点数', `${current.nodes.length} 个已回写节点`, 'ERP 审计'],
      ['数据来源', current.mock ? 'mock ERP（演示）' : 'ERP', current.mock ? 'mock-erp 测试替身' : 'ERP'],
    ],
  )
  toast.success(successReceipt('导出证据包 CSV', '节点 / ERP 引用 / traceId'))
}

function copyTraceId() {
  const traceId = ticket.value?.traceId
  if (!traceId) {
    toast.warning('当前工单未返回 traceId（演示数据），无法复制')
    return
  }
  void navigator.clipboard
    .writeText(traceId)
    .then(() => toast.success(successReceipt('复制 traceId', traceId)))
    .catch(() => toast.error('复制失败：请检查剪贴板权限'))
}

// ---------------------------------------------------------------- 工单动作（真实 API）
async function refreshAfterAction() {
  await loadTicket()
}

/** 记录 transition 响应里的回写/派单回显（v0.3.1：dispatch/complete 返回，或上次 pending 确认后回显） */
function captureWriteback(result: { erpWriteback?: ErpWriteback; dispatch?: DispatchPayload }): void {
  if (result.erpWriteback) writebackEcho.value = result.erpWriteback
  if (result.dispatch) dispatchEcho.value = result.dispatch
}

/**
 * 派单（CAP-07）：`POST /transition` + `action=dispatch`。
 * 契约 v0.3.1 允许不带 teamName/teamId（服务端按技能/楼栋/队列自动派单并回显 `dispatch`），
 * 响应同时带 `erpWriteback.pending`（30 秒确认窗口），即时刷新 Hero/要素卡班组与 ETA。
 */
async function confirmDispatch() {
  if (!canDispatch.value || !ticket.value) {
    toast.warning('当前状态不支持派单（仅「待受理/已受理」可由内部角色派单）')
    return
  }
  dispatching.value = true
  actionError.value = ''
  const key = idempotency.ensure(actionSeed('ticket.dispatch', { ticketNo: ticketNo.value, version: ticket.value.version }))
  try {
    const { data } = await api.ticket.transition(
      ticketNo.value,
      { action: 'dispatch', payload: {}, version: ticket.value.version },
      key,
    )
    idempotency.settle()
    captureWriteback(data)
    const team = data.dispatch?.teamName ?? data.dispatch?.teamId ?? ticket.value.team ?? '待分配'
    const eta = data.dispatch?.eta ? String(data.dispatch.eta) : ticket.value.eta ?? ''
    toast.success(successReceipt('派单', `${team}${eta ? ` · ${eta}` : ''} · ${data.statusText}`))
    await refreshAfterAction()
  } catch (error) {
    const code = error instanceof ApiError ? error.code : 'E-9001'
    actionError.value = `派单未完成：${errorText(code)}`
    toast.error(`${actionError.value}；稍后重试或转人工`)
  } finally {
    dispatching.value = false
  }
}

async function confirmReview(payload: RateConfirm) {
  if (reviewed.value) {
    reviewOpen.value = false
    toast.info('本次工单已完成回访，不能重复评价；有遗留问题可点「催一下」或转人工')
    return
  }
  if (!canFeedback.value) {
    toast.warning(`当前状态「${statusTagText.value}」不支持评价，请等完工后再评价（仅 COMPLETED 可回访）`)
    return
  }
  submitting.value = true
  actionError.value = ''
  const key = idempotency.ensure(
    actionSeed('ticket.feedback', { ticketNo: ticketNo.value, score: payload.score, tags: payload.tags.join(',') }),
  )
  try {
    const { data } = await api.ticket.feedback(
      ticketNo.value,
      { score: payload.score, tags: payload.tags.length ? payload.tags : undefined, note: payload.note || undefined },
      key,
    )
    idempotency.settle()
    localReviewScore.value = data.score
    localReviewTags.value = data.tags ?? []
    reviewOpen.value = false
    const tags = data.tags && data.tags.length > 0 ? ` · ${data.tags.join(' / ')}` : ''
    toast.success(
      successReceipt(
        '提交服务评价',
        `${data.score} 分${tags} · ${data.statusText}${data.supervisorAlert ? ' · 已触发主管提醒' : ''}`,
      ),
    )
    await refreshAfterAction()
  } catch (error) {
    const code = error instanceof ApiError ? error.code : 'E-9001'
    reviewOpen.value = false
    actionError.value = `评价未完成：${errorText(code)}`
    toast.error(`${actionError.value}；${code === 'E-3002' ? '请刷新工单状态后再试' : '稍后重试或转人工'}`)
  } finally {
    submitting.value = false
  }
}

async function confirmReschedule(payload: RescheduleConfirm) {
  rescheduleOpen.value = false
  if (!canReschedule.value) {
    toast.warning('当前状态不支持改约，请稍后刷新工单状态或转人工')
    return
  }
  if (!ticket.value) return
  actionError.value = ''
  const key = idempotency.ensure(actionSeed('ticket.reschedule', { ticketNo: ticketNo.value, slot: payload.slot }))
  try {
    const { data } = await api.ticket.transition(
      ticketNo.value,
      { action: 'reschedule', payload: { slot: payload.slot, ...(payload.note ? { reason: payload.note } : {}) }, version: ticket.value.version },
      key,
    )
    idempotency.settle()
    rescheduleSlot.value = payload.slot
    captureWriteback(data)
    toast.success(successReceipt('提交改约', `${payload.slot} · ${data.statusText}`))
    await refreshAfterAction()
  } catch (error) {
    const code = error instanceof ApiError ? error.code : 'E-9001'
    actionError.value = `改约未完成：${errorText(code)}`
    toast.error(`${actionError.value}；稍后重试或转人工`)
  }
}

async function confirmUrge(payload: UrgeConfirm) {
  urgeOpen.value = false
  if (!canUrge.value) {
    toast.warning('当前状态不支持催办，请稍后刷新工单状态或转人工')
    return
  }
  if (!ticket.value) return
  actionError.value = ''
  const key = idempotency.ensure(actionSeed('ticket.urge', { ticketNo: ticketNo.value, reason: payload.reason }))
  try {
    const { data } = await api.ticket.transition(
      ticketNo.value,
      { action: 'urge', payload: { reason: payload.reason, channel: 'chat' }, version: ticket.value.version },
      key,
    )
    idempotency.settle()
    captureWriteback(data)
    toast.success(successReceipt('催办', `${payload.reason} · ${data.statusText}`))
    await refreshAfterAction()
  } catch (error) {
    const code = error instanceof ApiError ? error.code : 'E-9001'
    actionError.value = `催办未完成：${errorText(code)}`
    toast.error(`${actionError.value}；稍后重试或转人工`)
  }
}

function openFeedback() {
  if (!canFeedback.value && !reviewed.value) {
    toast.warning(`当前状态「${statusTagText.value}」不可评价：仅「已完工」工单可回访`)
    return
  }
  reviewOpen.value = true
}

function openReschedule() {
  if (!canReschedule.value) {
    toast.warning('当前状态不支持改约（仅已受理/已派单/处理中可改约）')
    return
  }
  rescheduleOpen.value = true
}

function openUrge() {
  if (!canUrge.value) {
    toast.warning('当前状态不支持催办（已完工/已回访请走回访或转人工）')
    return
  }
  urgeOpen.value = true
}

function generateKnowledge() {
  knowledgeDraft.value = true
  toast.success(`已生成知识草稿《卫生间渗漏初判与楼上协调话术》${demoNote('待知识运营复核后才对外生效')}`)
}

function goOwner() {
  void router.push('/owner')
}

function retry() {
  void loadTicket()
}

/**
 * 无权限态的「重新加载」：**必须给可见反馈**（P2 W9 死按钮口径）。
 * 403 是能力矩阵判定，重试同一账号仍会 403，因此先 toast 说明「需要什么能力 / 当前角色 / 怎么恢复」，
 * 再重新校验一次（切了账号或权限变更后可直接恢复，不白屏）。
 */
function retryDenied() {
  const capability = denied.value?.requiredCapability || 'ticket.status'
  toast.warning(
    `无权限：需要 ${capabilityLabel(capability)} 能力，当前演示角色「${currentRoleLabel.value}」不具备；` +
      '请切换管家 / 坐席账号后重试（已重新校验一次）',
  )
  void loadTicket()
}

/** 从无权限态切换演示账号：换演示 JWT（`auth.role` 变化由 watch 触发按新身份重取，失败保持原账号并提示） */
async function switchDeniedAccount(role: ActorRole) {
  if (switchingDenied.value) return
  switchingDenied.value = true
  try {
    const ok = await auth.loginAsRole(role)
    if (!ok) {
      toast.error(`演示账号切换失败：${auth.failure?.message ?? '登录接口不可用'}`)
      return
    }
    toast.info(`已切换演示账号：${ROLE_LABELS[role]}（demo，非真实鉴权），正在重新读取工单…`)
  } finally {
    switchingDenied.value = false
  }
}

/** 推送区「重试」：失败（含无权限）时给 toast，避免点完零反馈的死按钮 */
async function retryPushes() {
  await loadPushes()
  if (pushesError.value) {
    toast.error(`推送记录仍不可用：${pushesError.value}；已留痕的推送不会被补造`)
  }
}
</script>

<template>
  <div class="w-page" data-page="progress" data-testid="page-root">
    <WPageHeader :eyebrow="screen.eyebrow" :title="screen.title" :description="screen.description">
      <template #actions>
        <WTag :tone="reviewed ? 'g' : 'a'" plain>
          {{ reviewed ? `已回访 · ${reviewScore} 分` : statusTagText || '加载中' }}
        </WTag>
        <WTag tone="n" plain>{{ ticketNo }}</WTag>
        <WTag v-if="mockTag" tone="a">{{ mockTag }}</WTag>
        <WButton size="sm" icon="chat" @click="goOwner">返回业主端</WButton>
        <WButton size="sm" variant="primary" icon="download" :disabled="!ticket" @click="exportTicket">导出工单</WButton>
      </template>
    </WPageHeader>

    <!-- 降级 / 动作失败提示条（不隐藏、不假装成功） -->
    <div v-if="degradedTag" class="prg-notice" data-testid="progress-degraded">
      <WIcon name="refresh" :size="13" />
      <span>{{ degradedTag }}</span>
    </div>
    <div v-if="actionError" class="prg-notice is-error" data-testid="progress-action-error">
      <WIcon name="shield" :size="13" />
      <span>{{ actionError }}</span>
      <WButton size="sm" variant="ghost" @click="retry">刷新工单</WButton>
    </div>

    <div v-if="loading" class="prg-loading">
      <WLoading center :label="`正在读取工单 ${ticketNo} 的节点与状态…`" />
    </div>

    <!-- 403 无权限态（P2 W9）：不渲染工单/节点/推送等业务数据；重试给 toast 反馈，可切换演示账号 -->
    <PermissionDenied
      v-else-if="denied"
      data-testid="progress-denied"
      :required-capability="denied.requiredCapability"
      :required-role="denied.role"
      :switching="switchingDenied"
      :candidate-roles="PROGRESS_SWITCH_ROLES"
      :detail="`工单进度需要能力 ${capabilityLabel(denied.requiredCapability)}；当前演示角色「${currentRoleLabel}」不具备该能力，本页不读取/展示工单、7 节点时间轴与推送等未授权数据（不编造数据）。`"
      @switch="switchDeniedAccount"
    >
      <template #actions>
        <WButton size="sm" variant="primary" data-testid="progress-denied-retry" @click="retryDenied">重新加载</WButton>
        <WButton size="sm" variant="ghost" @click="goOwner">返回业主端</WButton>
      </template>
    </PermissionDenied>

    <!-- 无工单参数且加载失败：空态 + 引导去业主端报修（不造数据） -->
    <WCard v-else-if="loadError && !hasDeepLink" data-testid="progress-empty">
      <div class="prg-empty">
        <WIcon name="report" :size="22" />
        <b>暂无工单可展示</b>
        <p>
          工单进度需要先在业主端完成报修建单（AI 建单后会拿到真实工单号）。{{ loadError.message }}（{{ loadError.code }}）
        </p>
        <div class="prg-acts prg-acts--fit">
          <WButton size="sm" variant="primary" @click="goOwner">去业主端报修</WButton>
          <WButton size="sm" variant="ghost" @click="retry">重新加载</WButton>
        </div>
      </div>
    </WCard>

    <!-- 深链工单加载失败：明确失败态 + 重试 / 返回 -->
    <WCard v-else-if="loadError" data-testid="progress-error">
      <div class="prg-empty">
        <WIcon name="shield" :size="22" />
        <b>工单 {{ ticketNo }} 暂时无法加载</b>
        <p>{{ loadError.message }}（错误码 {{ loadError.code }}）。请核对工单号，或稍后重试 / 转人工。</p>
        <div class="prg-acts prg-acts--fit">
          <WButton size="sm" variant="primary" @click="retry">重新加载</WButton>
          <WButton size="sm" variant="ghost" @click="goOwner">返回业主端</WButton>
        </div>
      </div>
    </WCard>

    <template v-else-if="ticket">
      <!-- ================= ① Hero：状态 / 7 步进度 / 关键 KPI / 主操作 ================= -->
      <section class="prg-hero2">
        <div class="prg-hero2__main">
          <span class="prg-hero2__eyebrow">REPAIR ORDER · 报修闭环</span>
          <div class="prg-hero2__title">
            <h2>{{ ticket.ticketNo }}</h2>
            <WTag :tone="statusTone">{{ ticket.statusText }}</WTag>
            <WTag tone="n" plain>v{{ ticket.version }}</WTag>
            <WTag v-if="mockTag" tone="a" plain>{{ mockTag }}</WTag>
          </div>
          <p class="prg-hero2__sub">
            {{ categoryText }} · {{ ticket.roomCode }} · {{ ticket.location }}
            <em>· 更新于 {{ formatIsoMinute(ticket.updatedAt) }}</em>
          </p>

          <div class="prg-steps" data-testid="progress-steps">
            <div
              v-for="(row, index) in nodeRows"
              :key="row.code"
              class="prg-step"
              :class="{
                'is-done': row.reached,
                'is-current': row.current,
                'is-pending': !row.reached && !row.current,
              }"
              :data-status="row.code"
              :title="row.label"
            >
              <span class="prg-step__dot" />
              <span v-if="index < nodeRows.length - 1" class="prg-step__line" />
              <small>{{ row.label }}</small>
            </div>
          </div>
        </div>

        <div class="prg-hero2__side">
          <div class="prg-hero2__kpis">
            <div>
              <b>{{ reachedCount }}<em>/7</em></b>
              <span>节点已回写</span>
            </div>
            <div data-testid="progress-hero-eta">
              <b>{{ heroEta || '待确认' }}</b>
              <span>预计上门</span>
            </div>
            <div data-testid="progress-hero-team">
              <b>{{ heroTeam || '待分配' }}</b>
              <span>派单班组</span>
            </div>
          </div>
          <div class="prg-acts">
            <WButton
              v-if="isStaff && TICKET_TRANSITIONS.dispatch.from.includes(status as TicketStatus)"
              size="sm"
              variant="ai"
              :loading="dispatching"
              :disabled="!canDispatch"
              data-testid="progress-dispatch"
              @click="confirmDispatch"
            >
              派单
            </WButton>
            <WButton v-if="canAssign" size="sm" :disabled="!canUrge" @click="openUrge">催一下</WButton>
            <WButton v-if="canAssign" size="sm" :disabled="!canReschedule" @click="openReschedule">改约</WButton>
            <WButton size="sm" variant="primary" :disabled="!canFeedback && !reviewed" @click="openFeedback">
              {{ reviewed ? '重新评价' : '评价服务' }}
            </WButton>
          </div>
          <p v-if="!canAssign" class="prg-dim prg-dim--block" data-testid="progress-assign-hint">
            当前演示账号无 <code>ticket.assign</code> 能力：改约 / 催办 / 派单由管家或坐席发起（业主可查看进度、提交评价）。
          </p>
          <p class="prg-hero2__next">
            <WIcon name="sparkles" :size="12" />
            <span>{{ nextHint }}</span>
          </p>
        </div>
      </section>

      <!-- ERP 回写状态条（v0.3.1：pending / confirmed / failed；来源在条内标注，推算不当回执） -->
      <div
        v-if="writebackView"
        class="prg-wb"
        :class="`is-${writebackView.status}`"
        data-testid="progress-writeback"
        :data-status="writebackView.status"
      >
        <WTag :tone="writebackView.tone" plain>ERP 回写</WTag>
        <b>{{ writebackView.text }}</b>
        <span class="prg-wb__hint">{{ writebackView.hint }}</span>
        <em class="prg-wb__src">{{ writebackView.source }}</em>
      </div>

      <!-- ================= ② 主区（时间轴为主叙事）+ 右侧佐证 ================= -->
      <div class="prg-grid">
        <div class="prg-main">
          <WCard title="闭环时间轴" subtitle="谁在做 · 系统回写了什么 · 业主看到什么（ERP 审计回写，全程留痕）">
            <template #extra>
              <WTag tone="v" plain>{{ reachedCount }} / 7 节点已回写</WTag>
              <WTag tone="g" plain>{{ ticket.mock ? 'mock ERP' : 'ERP' }}</WTag>
            </template>
            <div class="prg-tl">
              <div
                v-for="row in nodeRows"
                :key="row.code"
                class="prg-row"
                :class="{ 'is-active': row.current, 'is-pending': !row.reached && !row.current }"
                :data-status="row.code"
              >
                <div class="prg-row__time">{{ row.time || '—' }}</div>
                <div class="prg-row__dot"><i /></div>
                <div class="prg-row__body">
                  <div class="prg-row__head">
                    <b>{{ row.label }}</b>
                    <WTag :tone="row.band === 'AI' ? 'v' : row.band === 'ERP' ? 'b' : 'n'" plain>{{ row.band }}</WTag>
                    <span class="prg-row__state">{{ row.state }}</span>
                  </div>
                  <p class="prg-row__sys">
                    {{ row.ownerText || (row.reached ? 'ERP 已回写该节点' : '尚未到达该节点，不做推测') }}
                  </p>
                  <p class="prg-row__owner">
                    <WIcon name="chat" :size="11" />
                    <span>业主看到：{{ row.reached ? row.ownerView : '—（未到达）' }}</span>
                  </p>
                </div>
                <div class="prg-row__meta">
                  <span>{{ row.who || '—' }}</span>
                  <span>{{ row.at || '待回写' }}</span>
                </div>
              </div>
            </div>
            <div class="prg-sum">
              <div><b>{{ ticket.version }}</b><span>乐观锁版本</span></div>
              <div><b>{{ reachedCount }}</b><span>已回写节点</span></div>
              <div><b>{{ ticket.nodes.length }}</b><span>审计节点数</span></div>
              <div><b>{{ ticket.aiCreated ? 'AI 建单' : '人工建单' }}</b><span>建单来源</span></div>
              <div><b>{{ ticket.mock ? 'mock' : 'ERP' }}</b><span>数据来源</span></div>
            </div>
          </WCard>

          <WCard
            title="三次推送"
            subtitle="受理 / 派单出发 / 完工：记录来自推送通道回执（GET /tickets/{no}/pushes），无记录不补造"
          >
            <template #extra>
              <WTag tone="a" plain>通道未接入 · 演示</WTag>
              <WTag v-if="pushes.length" tone="n" plain>{{ pushes.length }} 条留痕</WTag>
            </template>

            <div v-if="pushesLoading" class="prg-push-state" data-testid="progress-pushes-loading">
              <WLoading label="正在读取推送记录…" />
            </div>

            <!-- 推送接口失败：明确失败态，不派生、不编造 -->
            <div
              v-else-if="pushesError"
              class="prg-push-state is-error"
              data-testid="progress-pushes-error"
            >
              <WIcon name="shield" :size="14" />
              <b>{{ pushesDenied ? '推送记录无权读取（403）' : '推送记录暂不可用' }}</b>
              <span>
                {{ pushesError }} ·
                {{ pushesDenied ? '无权限时重试不会恢复，请切换管家 / 坐席账号后点击重试' : '已留痕的推送不会被补造，可稍后刷新重试' }}
              </span>
              <WButton size="sm" variant="ghost" data-testid="progress-pushes-retry" @click="retryPushes">重试</WButton>
            </div>

            <!-- 无记录：空态（不造数） -->
            <div v-else-if="pushRows.length === 0" class="prg-push-state" data-testid="progress-pushes-empty">
              <WIcon name="bell" :size="14" />
              <b>{{ pushesFallbackText }}</b>
              <span>建单/派单/完工后由推送通道生成记录；当前工单尚无留痕</span>
            </div>

            <div v-else class="prg-pushbar" data-testid="progress-pushes">
              <div
                v-for="push in pushRows"
                :key="push.pushId"
                class="prg-push3 is-done"
                :data-push-id="push.pushId"
                data-testid="progress-push-item"
                :data-status="push.status"
              >
                <span class="prg-push3__icon"><WIcon name="bell" :size="12" /></span>
                <div>
                  <b>{{ push.templateLabel }}</b>
                  <span>{{ push.channelLabel }} · {{ push.sentAtText }} · {{ push.pushId }}</span>
                  <span v-if="push.contentMasked">{{ push.contentMasked }}</span>
                </div>
                <WTag :tone="push.statusTone" plain>{{ push.statusText }}</WTag>
              </div>
            </div>

            <p class="prg-dim prg-dim--block">
              {{ QUALITY_NOTES[0] }}；上方为推送通道回执（P2 W6 为 mock 通道，状态「通道模拟已留痕」），
              <b>通道未接入（演示），不代表已真实外发短信 / 企微模板消息</b>。
            </p>
          </WCard>

          <WCard title="回访与知识沉淀" subtitle="闭环的最后一环：把一次解决变成可复用知识">
            <template #extra>
              <WTag :tone="reviewed ? 'g' : 'a'">{{ reviewed ? '已回访' : '待回访' }}</WTag>
            </template>
            <div class="prg-review">
              <div class="prg-review__main">
                <div class="prg-quote">{{ REVIEW.script }}</div>
                <div class="prg-kv" style="margin-top: 6px">
                  <span><WIcon name="shield" :size="11" /> {{ REVIEW.note }}</span>
                </div>
                <div class="prg-acts prg-acts--fit" style="margin-top: 8px">
                  <WButton
                    size="sm"
                    :variant="reviewed ? 'secondary' : 'primary'"
                    :disabled="!canFeedback && !reviewed"
                    @click="openFeedback"
                  >
                    {{ reviewed ? '重新评价' : '去评价' }}
                  </WButton>
                  <WButton size="sm" variant="ghost" v-if="canAssign" :disabled="!canUrge" @click="openUrge">催一下</WButton>
                </div>
                <p v-if="reviewed" class="prg-review__receipt" data-testid="progress-feedback">
                  <WIcon name="check" :size="11" /> 已回访 · {{ reviewScore }} 分
                  <template v-if="reviewTags.length">· {{ reviewTags.join(' / ') }}</template>
                  <template v-if="feedback?.reviewedAt">· {{ formatIsoMinute(feedback.reviewedAt) }}</template>
                  <template v-if="!feedback">{{ DEMO_SUFFIX }}</template>
                </p>
                <p v-if="feedback?.note" class="prg-dim prg-dim--block">评价备注：{{ feedback.note }}</p>
                <p v-else-if="!canFeedback && !reviewed" class="prg-dim prg-dim--block">
                  评价仅在「已完工」后开放（当前：{{ ticket.statusText }}）。
                </p>
              </div>
              <div class="prg-review__side">
                <div class="prg-kv"><span>知识沉淀建议</span><WTag tone="v" plain>AI 生成</WTag></div>
                <p class="prg-dim prg-dim--block">{{ KNOWLEDGE_SUGGESTION }}</p>
                <div class="prg-acts prg-acts--fit" style="margin-top: 7px">
                  <WButton size="sm" :disabled="knowledgeDraft" @click="generateKnowledge">
                    {{ knowledgeDraft ? '已生成草稿' : '生成知识草稿' }}
                  </WButton>
                  <WTag :tone="knowledgeDraft ? 'a' : 'n'" plain>{{ knowledgeDraft ? '待复核' : '未生成' }}</WTag>
                </div>
              </div>
            </div>
          </WCard>
        </div>

        <!-- 右侧：要素 / 业主端预览 / 数据链路 / 分工 -->
        <aside class="prg-side">
          <div class="prg-side-card">
            <h4><WIcon name="idcard" :size="12" />工单要素</h4>
            <div class="prg-kv"><span>房号</span><b>{{ ticket.roomCode }}</b></div>
            <div class="prg-kv"><span>类型 / 等级</span><b>{{ categoryText }}</b></div>
            <div class="prg-kv"><span>位置</span><b>{{ ticket.location }}</b></div>
            <div class="prg-kv"><span>预约时段</span><b>{{ ticket.slot || '待确认' }}</b></div>
            <div class="prg-kv"><span>持续时长</span><b>{{ ticket.duration || '—' }}</b></div>
            <div class="prg-kv" data-testid="progress-el-team"><span>派单班组</span><b>{{ heroTeam || '待分配' }}</b></div>
            <div class="prg-kv" data-testid="progress-el-eta"><span>预计上门</span><b>{{ heroEta || '待确认' }}</b></div>
            <div class="prg-kv"><span>状态</span><b>{{ ticket.statusText }}（{{ ticket.status }}）</b></div>
            <div v-if="ticket.note" class="prg-kv"><span>补充</span><b>{{ ticket.note }}</b></div>
          </div>

          <div class="prg-side-card">
            <h4><WIcon name="chat" :size="12" />业主端看到的进度</h4>
            <div class="prg-owner">
              <div
                v-for="row in nodeRows"
                :key="row.code"
                class="prg-node"
                :class="{ 'is-now': row.current }"
                :data-status="row.code"
              >
                <span class="prg-node__dot" />
                <div class="prg-node__body">
                  <b>{{ row.label }}</b>
                  <span>{{ row.reached ? row.ownerView : '等待前序节点完成' }}{{ row.at ? ` · ${row.at}` : '' }}</span>
                </div>
                <span class="prg-node__state">{{ row.state }}</span>
              </div>
            </div>
            <p class="prg-dim prg-dim--block">业主侧只读展示前三段：谁在做、到哪一步、下一步做什么。</p>
          </div>

          <div class="prg-side-card">
            <h4><WIcon name="shield" :size="12" />数据链路与证据</h4>
            <div class="prg-kv"><span>traceId</span><b class="prg-mono">{{ ticket.traceId ?? '—' }}</b></div>
            <div class="prg-kv"><span>ERP 引用</span><b>{{ ticket.erp?.vendor ?? '—' }} · {{ ticket.erp?.ref ?? '—' }}</b></div>
            <div class="prg-kv"><span>节点 / 版本</span><b>{{ ticket.nodes.length }} 个 · v{{ ticket.version }}</b></div>
            <div class="prg-acts prg-acts--wrap" style="margin-top: 7px">
              <WButton size="sm" variant="ghost" icon="copy" @click="copyTraceId">复制 traceId</WButton>
              <WButton size="sm" variant="ghost" @click="traceOpen = true">全链路回溯</WButton>
              <WButton size="sm" @click="exportEvidence">证据包</WButton>
            </div>
            <p class="prg-dim prg-dim--block">{{ QUALITY_NOTES[1] }}。</p>
          </div>

          <div class="prg-side-card">
            <h4><WIcon name="sparkles" :size="12" />AI 做了什么</h4>
            <ul class="prg-list">
              <li v-for="item in AI_DID" :key="item.text">{{ item.text }}</li>
            </ul>
            <h4 style="margin-top: 8px"><WIcon name="user" :size="12" />人做了什么</h4>
            <ul class="prg-list is-human">
              <li v-for="item in HUMAN_DID" :key="item">{{ item }}</li>
            </ul>
          </div>
        </aside>
      </div>
    </template>

    <!-- 评价（统一动作弹层：1—5 星 + 标签多选 + 备注 + reviewed 回执） -->
    <RateModal
      v-model:open="reviewOpen"
      :reviewed="reviewed"
      :receipt="reviewed ? `已评价回执：${reviewScore} 分${DEMO_SUFFIX}` : ''"
      :script="REVIEW.script"
      :policy="REVIEW.note"
      @confirm="confirmReview"
    />

    <!-- 改约（统一动作弹层：时段单选 + 补充说明） -->
    <RescheduleModal
      v-model:open="rescheduleOpen"
      :slots="RESCHEDULE_SLOTS"
      :current="rescheduleSlot"
      @confirm="confirmReschedule"
    />

    <!-- 催办（统一动作弹层：原因单选 + 补充说明） -->
    <UrgeModal v-model:open="urgeOpen" @confirm="confirmUrge" />

    <!-- 全链路回溯摘要（数据来自工单详情响应） -->
    <WModal v-model:open="traceOpen" title="全链路回溯摘要" width="560px">
      <div v-if="ticket" class="prg-trace">
        <div><span>{{ formatIsoTime(ticket.createdAt) }}</span><b>ticket.create 建单</b><em>{{ ticket.erp?.vendor }}</em></div>
        <div v-for="row in nodeRows.filter((item) => item.reached)" :key="row.code">
          <span>{{ row.time || '—' }}</span>
          <b>{{ row.label }} · {{ row.who || 'ERP 回写' }}</b>
          <em>{{ row.at || '' }}</em>
        </div>
      </div>
      <div class="prg-quote" style="margin-top: 8px">
        数据来源：{{ ticket?.mock ? 'mock ERP 测试替身（演示）' : 'ERP 连接器' }}；状态文案取契约 7 节点；未接真实短信/微信推送。
      </div>
      <p class="prg-dim prg-dim--block" style="margin-top: 8px">完整回放与证据包见「全链路回溯」（10 屏），本页只做摘要预览。</p>
      <template #footer>
        <WButton size="sm" variant="ghost" @click="traceOpen = false">关闭</WButton>
        <WButton size="sm" icon="download" @click="exportEvidence">导出证据包</WButton>
      </template>
    </WModal>

    <StateDemo screen="progress" />
  </div>
</template>

<style src="./progress.css"></style>

<style scoped>
/* P2 新增：降级 / 动作失败提示条（不改 progress.css 的布局骨架） */
.prg-notice {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 10px;
  padding: 7px 10px;
  border: 1px dashed var(--c-amber);
  border-radius: var(--rd-md);
  background: var(--c-amber-soft);
  color: var(--c-amber);
  font-size: var(--fs-xs);
  line-height: 1.6;
}

.prg-notice.is-error {
  border-color: var(--c-red);
  background: var(--c-red-soft);
  color: var(--c-red);
}

.prg-loading {
  display: grid;
  place-items: center;
  min-height: 360px;
}

.prg-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 34px 16px;
  text-align: center;
}

.prg-empty b {
  font-size: var(--fs-base);
  color: var(--c-ink);
}

.prg-empty p {
  max-width: 520px;
  font-size: var(--fs-sm);
  line-height: 1.8;
  color: var(--c-muted);
}

/* P2 W6：ERP 回写状态条（pending/confirmed/failed 三态） */
.prg-wb {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  margin: 10px 0 0;
  padding: 7px 10px;
  border: 1px solid var(--c-line);
  border-left: 3px solid var(--c-muted);
  border-radius: var(--rd-md);
  background: var(--c-bg-2);
  font-size: var(--fs-xs);
  color: var(--c-ink-2);
  line-height: var(--lh-base);
}

.prg-wb b {
  font-weight: var(--fw-semibold);
  color: var(--c-ink);
}

.prg-wb__hint {
  color: var(--c-muted);
}

.prg-wb__src {
  margin-left: auto;
  font-style: normal;
  font-size: var(--fs-2xs);
  color: var(--c-faint);
}

.prg-wb.is-pending {
  border-left-color: var(--c-amber);
  background: var(--c-amber-soft);
}

.prg-wb.is-confirmed {
  border-left-color: var(--c-brand);
  background: var(--c-brand-soft);
}

.prg-wb.is-failed {
  border-left-color: var(--c-red);
  background: var(--c-red-soft);
}

/* P2 W6：推送区加载/失败/空态（推送失败只降级本区块，不影响工单主体） */
.prg-push-state {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  padding: 12px 10px;
  border: 1px dashed var(--c-line);
  border-radius: var(--rd-md);
  background: var(--c-bg-2);
  font-size: var(--fs-xs);
  color: var(--c-muted);
  line-height: var(--lh-base);
}

.prg-push-state b {
  color: var(--c-ink);
}

.prg-push-state.is-error {
  border-color: var(--c-red);
  background: var(--c-red-soft);
  color: var(--c-red);
}

.prg-push-state.is-error b {
  color: var(--c-red);
}
</style>
