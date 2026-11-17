<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { WButton, WCard, WEmpty, WLoading, WModal, WPageHeader, WTabs, WTag, useToast } from '@/components'
import { downloadCsv } from '@/components/admin-actions'
import PermissionDenied from '@/components/feedback/PermissionDenied.vue'
import ComplaintWorkbench from '@/views/complaint/ComplaintWorkbench.vue'
import DeskRealtimeBoard from './DeskRealtimeBoard.vue'
import { capabilityLabel } from '@/auth/capabilities'
import { ROLE_LABELS } from '@/app/roles'
import { SCREENS } from '@/app/screens'
import { useAuthStore } from '@/stores/auth'
import StateDemo from '@/content/StateDemo.vue'
import { demoNote, infoReceipt, successReceipt } from '@/content'
import {
  api,
  ApiError,
  DEFAULT_TICKET_LIST_SORT,
  TICKET_CATEGORY_LABELS,
  TICKET_STATUSES,
  TICKET_STATUS_LABELS,
  degradedText,
  errorText,
  forbiddenDetailsOf,
  formatIsoMinute,
  mockBadgeText,
} from '@/api'
import type {
  ActorRole,
  DegradedInfo,
  HandoffSummary,
  TicketCategory,
  TicketListItem,
  TicketListSort,
  TicketStatus,
} from '@/api'

/**
 * 05 坐席工作台（P1-017 + P2-012 + P2-021）
 * 三队列（待接入 / 进行中 / 待质检）+ 上下文同屏（摘要/要素/情绪/建议/ERP/质检）+ 分级与接管。
 *
 * P2 W6 数据来源（契约 v0.3.1）：
 * - **工单列表**（顶部「工单列表」卡）：`GET /api/v1/tickets`，支持 `status`（7 状态）/`category`/
 *   `team`/`dateFrom`/`dateTo`（按 updatedAt 闭区间）/`sort`（`TicketListSort`，默认 `updatedAt_desc`）
 *   + 关键字（房号/工单号，**前端过滤当前页**）；分页 `page`/`pageSize`/`total` 由服务端返回（真实分页）；
 *   「导出 CSV」导出**当前筛选结果**（真实 Blob 下载，文件名 `工单列表-YYYYMMDD.csv`）；
 *   行选中 → `GET /tickets/{no}/handoff-summary` 渲染右侧协同面板（保留 P2-012 既有 UI）；
 * - 会话队列仍是 P1 演示样例（平台内会话数据，契约未建模）；队列侧 ERP 在办数来自工单列表接口；
 * - 失败/降级时保留 P1 本地摘要，并明确标注「演示摘要 / 已降级」，不把 mock 当真实 ERP。
 */
const screen = SCREENS.desk
const toast = useToast()
const auth = useAuthStore()

/**
 * P3 W12：desk 顶部子视图（**不新增导航屏**）
 * - `queue`：既有坐席会话台（三队列 + 上下文同屏 + 工单列表，W2—W11 语义不变）；
 * - `complaint`：投诉处理台（`views/complaint/ComplaintWorkbench.vue`，复用 `/complaints` 同一组件）——
 *   steward 因 `/desk` 可见性矩阵不含其角色，走 `/complaints` 直达；agent/supervisor/admin 用本页签；
 * - `realtime`：P4 W17 实时队列 + SLA 看板 + 质检（契约 v1.2.0 `/desk/*`，按能力只读/可写）。
 */
type DeskTab = 'queue' | 'complaint' | 'realtime'
const deskTab = ref<DeskTab>('queue')
const DESK_TABS = [
  { key: 'queue', label: '坐席会话台' },
  { key: 'complaint', label: '投诉处理台' },
  // P4 W17：实时队列（等待/SLA 倒计时）+ SLA 看板 + 质检抽样评分/统计（契约 v1.2.0 /desk/*）
  { key: 'realtime', label: '实时队列与质检' },
]

/**
 * 403 无权限态（P2 W9 / task-33）
 * ===========================================================================
 * `/desk` 在 `ROLE_VISIBILITY` 里对 admin 可见，但 `PermissionMatrix` 里 admin 没有
 * `ticket.status` / `handoff.human` → 工单列表、转人工摘要、队列在办数全部 403。
 * 口径（不静默失败、不伪造数据）：
 * - 页面顶部渲染既有「无权限」设计态（所需能力人话 / 当前角色 / 可切换演示账号）；
 * - 工单列表卡、协同面板只给「无权限」说明文字，**不渲染未授权业务数据**；
 * - 无权限态的「重新加载」点击必有可见反馈（toast 说明 + 重新校验），不再是死按钮。
 */
interface DeskDeniedInfo {
  requiredCapability: string
  role: string
  traceId: string
}

const ticketListDenied = ref<DeskDeniedInfo | null>(null)
const handoffDenied = ref<DeskDeniedInfo | null>(null)
const queueDenied = ref<DeskDeniedInfo | null>(null)
const switchingAccount = ref(false)
/** 可切换账号白名单：既能进 /desk 又具备 ticket.status/handoff.human 的演示角色 */
const DESK_SWITCH_ROLES: readonly ActorRole[] = ['agent', 'supervisor']
const currentRoleLabel = computed(() => (auth.role ? ROLE_LABELS[auth.role] : '未登录'))
const deskDenied = computed<DeskDeniedInfo | null>(
  () => ticketListDenied.value ?? handoffDenied.value ?? queueDenied.value,
)
const deskDeniedDetail = computed(() => {
  const info = deskDenied.value
  if (!info) return ''
  const caps = [
    ticketListDenied.value?.requiredCapability,
    handoffDenied.value?.requiredCapability,
    queueDenied.value?.requiredCapability,
  ].filter((capability): capability is string => Boolean(capability))
  const unique = [...new Set(caps.length ? caps : [info.requiredCapability])]
  return `工单列表 / 转人工摘要 / 队列在办数需要能力 ${unique
    .map((capability) => capabilityLabel(capability))
    .join(' / ')}；当前演示角色「${currentRoleLabel.value}」不具备该能力，本页不读取、不展示未授权业务数据（不编造数据）。可切换坐席 / 主管演示账号后重新读取。`
})

/** 从 ApiError 取 403 细节（不是 403 返回 null，走原有失败语义） */
function deniedInfoOf(error: unknown, fallbackCapability: string): DeskDeniedInfo | null {
  const forbidden = forbiddenDetailsOf(error)
  if (!forbidden) return null
  return {
    requiredCapability: forbidden.requiredCapability || fallbackCapability,
    role: forbidden.role || (auth.role ?? ''),
    traceId: error instanceof ApiError ? error.traceId : '',
  }
}

/** 无权限态「重新加载」：先给 toast（避免点完零反馈），再重新校验一次 */
function retryDeskDenied() {
  const capability = deskDenied.value?.requiredCapability || 'ticket.status'
  toast.warning(
    `无权限：需要 ${capabilityLabel(capability)} 能力，当前演示角色「${currentRoleLabel.value}」不具备；` +
      '请切换坐席 / 主管账号后重试（已重新校验一次）',
  )
  void loadErpTickets()
  void loadTickets()
  void loadHandoff(selected.value)
}

/** 从无权限态切换演示账号：换演示 JWT（`auth.role` 变化由 watch 触发按新身份重取；失败保持原账号并提示） */
async function switchDeskAccount(role: ActorRole) {
  if (switchingAccount.value) return
  switchingAccount.value = true
  try {
    const ok = await auth.loginAsRole(role)
    if (!ok) {
      toast.error(`演示账号切换失败：${auth.failure?.message ?? '登录接口不可用'}`)
      return
    }
    toast.info(`已切换演示账号：${ROLE_LABELS[role]}（demo，非真实鉴权），正在重新读取工单与协同摘要…`)
  } finally {
    switchingAccount.value = false
  }
}

type QueueKey = 'waiting' | 'serving' | 'qc'
type Emotion = '激烈' | '焦急' | '平静'
type Grade = '一级' | '二级' | '三级'

interface ChatMessage {
  from: 'ai' | 'owner' | 'agent' | 'system'
  text: string
  time?: string
}

interface Conversation {
  id: string
  name: string
  room: string
  topic: string
  channel: string
  queue: QueueKey
  grade: Grade
  emotion: Emotion
  emotionScore: number
  wait: string
  sla: string
  aiDuration: string
  history: string
  messages: ChatMessage[]
  summary: string[]
  elements: { label: string; value: string; warn?: boolean }[]
  risk: string
  suggestions: string[]
  erp: { label: string; value: string }[]
  qualityNote: string
  /** 关联工单号（有则直接读 handoff-summary；无则按房号从工单列表匹配） */
  ticketNo?: string
}

const QUEUE_META: Record<QueueKey, { label: string; hint: string }> = {
  waiting: { label: '待接入', hint: 'AI 已接待，等待人工接管或升级' },
  serving: { label: '进行中', hint: '人工接待中，SLA 计时与质检同步' },
  qc: { label: '待质检', hint: '会话已结束，等待抽检与回溯' },
}

const EMOTION_TONE: Record<Emotion, 'r' | 'a' | 'g'> = { 激烈: 'r', 焦急: 'a', 平静: 'g' }
const GRADE_TONE: Record<Grade, 'r' | 'a' | 'n'> = { 一级: 'r', 二级: 'a', 三级: 'n' }

const QUICK_CHIPS = [
  { label: '先共情', text: '王女士，这种情况确实着急，我先帮您把流程走起来。' },
  { label: '确认上门时间', text: '师傅今天 17:30 前上门，您这个时间方便吗？' },
  { label: '告知已升级', text: '已按一级投诉升级并同步项目经理，30 分钟内给您书面处理时限。' },
  { label: '索取照片', text: '方便再拍一张楼下位置的照片吗？便于定位责任点。' },
]

const CONVERSATIONS: Conversation[] = [
  {
    id: 'C-0831',
    name: '王女士',
    room: '3-2-1802',
    topic: '主卧渗水 · 投诉升级',
    channel: '微信服务号',
    queue: 'waiting',
    grade: '一级',
    emotion: '激烈',
    emotionScore: 87,
    wait: '已接入 03:42',
    sla: 'SLA 01:42',
    aiDuration: 'AI 接待 3m42s',
    history: '历史报修 6 次 · 上次投诉 2026-08-12',
    messages: [
      { from: 'system', text: 'AI 已完成身份核验 · 接待 3 分 42 秒 · 转接原因：业主提及“起诉 / 12345”' },
      { from: 'ai', text: '已收到。主卧具体是哪个位置？方便拍张照片吗？', time: '14:52' },
      { from: 'owner', text: '靠窗的天花板，照片我发了两张。', time: '14:53' },
      {
        from: 'ai',
        text: '王女士，这种情况确实让人着急。我先帮您把情况记清楚，马上安排人处理，您不用再重复描述。',
        time: '14:54',
      },
      {
        from: 'owner',
        text: '主卧天花板一直滴水，楼上根本不管，你们物业也不管，再这样我就起诉、打 12345！',
        time: '14:55',
      },
      {
        from: 'ai',
        text: '理解您的愤怒，这已经影响到正常居住。我已经把您反映的情况和证据同步给客服主管，马上由人工接管，前面说过的内容不需要再说一遍。',
        time: '14:55',
      },
      { from: 'system', text: '已转人工 · 会话摘要 / 要素 / 情绪已同步（3.2s）' },
    ],
    summary: [
      '主卧天花板持续滴水，业主称已联系楼上未果。',
      '已提供 2 张照片，指向楼上卫生间防水问题。',
      '业主提及“起诉 / 12345”，情绪激烈，要求当天处理。',
      '诉求：立即止水 + 物业协调楼上 + 给出书面处理时限。',
    ],
    elements: [
      { label: '房号', value: '3-2-1802' },
      { label: '位置', value: '主卧天花板' },
      { label: '首次发生', value: '今天 08:00 左右' },
      { label: '证据', value: '照片 2 张 · 已存工单' },
      { label: '是否联系楼上', value: '否 · 需物业协调', warn: true },
      { label: '诉求', value: '止水 + 协调 + 时限' },
    ],
    risk: '命中“起诉 / 12345” → 建议按一级投诉处理，30 分钟内上报项目经理',
    suggestions: ['升级一级投诉并上报', '生成投诉单（附摘要）', '联系 1902 业主协调'],
    erp: [
      { label: '在办工单', value: 'WO-20261114-0832 · 处理中' },
      { label: '历史报修', value: '6 次 · 最近 10-28' },
      { label: '欠费', value: '¥0' },
      { label: '上次投诉', value: '2026-08-12 · 已闭环' },
    ],
    qualityNote: '待抽检：情绪升级路径 + 首响话术；建议 24h 内完成回溯。',
    ticketNo: 'WO-20261114-0832',
  },
  {
    id: 'C-0834',
    name: '李先生',
    room: '4-1-902',
    topic: '停车月卡续费失败',
    channel: '小程序 H5',
    queue: 'waiting',
    grade: '二级',
    emotion: '焦急',
    emotionScore: 62,
    wait: '等待 00:42',
    sla: 'SLA 03:10',
    aiDuration: 'AI 接待 1m08s',
    history: '历史报修 2 次 · 无投诉',
    messages: [
      { from: 'system', text: 'AI 已完成身份核验 · 业主查询停车月卡续费失败原因' },
      { from: 'owner', text: '月卡今天到期，小程序点续费一直提示“支付失败”。', time: '15:04' },
      {
        from: 'ai',
        text: '已查到您的月卡在 09 月有一次换牌记录，续费需要先确认新车牌。您可以提供车牌尾号吗？',
        time: '15:05',
      },
      { from: 'owner', text: '尾号 668，快点，我今天下班就要进地库。', time: '15:06' },
    ],
    summary: ['停车月卡到期续费失败，提示支付失败。', 'AI 定位到换牌记录未更新，需人工核对新车牌。', '业主时间敏感：今天下班前要进地库。'],
    elements: [
      { label: '房号', value: '4-1-902' },
      { label: '月卡状态', value: '2026-11-14 到期' },
      { label: '新车牌', value: '尾号 668（待核对）', warn: true },
      { label: '上次换牌', value: '2026-09-21' },
      { label: '支付方式', value: '微信支付 · 未扣款' },
      { label: '诉求', value: '当天恢复月卡' },
    ],
    risk: '未命中 L3 关键词；涉及支付状态，只做查询不承诺退款',
    suggestions: ['核对车牌并补录', '转停车管理员', '发送地库临时通行凭证'],
    erp: [
      { label: '月卡', value: 'MC-90231 · 已到期' },
      { label: '合同', value: 'P-1024 / 4-1-902' },
      { label: '欠费', value: '¥0' },
      { label: '上次工单', value: '2026-10-06 · 已闭环' },
    ],
    qualityNote: '待抽检：支付类问题是否越权承诺；本次无承诺记录。',
  },
  {
    id: 'C-0836',
    name: '张先生',
    room: '1-2-301',
    topic: '装修申报材料咨询',
    channel: '企业微信',
    queue: 'waiting',
    grade: '三级',
    emotion: '平静',
    emotionScore: 28,
    wait: '等待 01:18',
    sla: 'SLA 04:36',
    aiDuration: 'AI 接待 46s',
    history: '历史报修 1 次 · 首次装修',
    messages: [
      { from: 'system', text: 'AI 已回答装修申报流程，业主追问押金标准' },
      { from: 'owner', text: '押金一定要交吗？我朋友说可以谈。', time: '15:02' },
      {
        from: 'ai',
        text: '装修押金按《装修管理办法》与面积标准收取，小区统一口径，不能议价。我请管家把标准和退还条件发给您。',
        time: '15:03',
      },
      { from: 'owner', text: '行，那帮我约下现场看房。', time: '15:04' },
    ],
    summary: ['咨询装修申报流程与押金标准。', 'AI 已给出统一口径与退还条件，业主接受。', '待办：管家发送标准文件并预约现场勘察。'],
    elements: [
      { label: '房号', value: '1-2-301' },
      { label: '装修阶段', value: '未申报' },
      { label: '押金标准', value: '按面积计收（统一口径）' },
      { label: '证据', value: '无' },
      { label: '待办', value: '发送标准文件 + 预约勘察' },
      { label: '诉求', value: '预约现场' },
    ],
    risk: '未命中 L3 关键词；押金口径来自知识 K-1042 v3.2，需附出处',
    suggestions: ['发送押金标准文件', '预约现场勘察', '生成装修申报待办'],
    erp: [
      { label: '房档', value: '1-2-301 · 已收房' },
      { label: '装修单', value: '无' },
      { label: '欠费', value: '¥0' },
      { label: '绑定情况', value: '微信已绑定' },
    ],
    qualityNote: '待抽检：押金口径是否附知识出处与版本。',
  },
  {
    id: 'C-0829',
    name: '陈女士',
    room: '6-1-1102',
    topic: '电子发票申请',
    channel: '微信服务号',
    queue: 'serving',
    grade: '三级',
    emotion: '平静',
    emotionScore: 22,
    wait: '进行中 06:12',
    sla: 'SLA 02:05',
    aiDuration: 'AI 接待 1m20s',
    history: '历史报修 0 次 · 缴费及时',
    messages: [
      { from: 'system', text: '坐席已接管 · 会话摘要与要素已同步' },
      { from: 'agent', text: '陈女士您好，发票抬头需要开公司还是个人？', time: '14:58' },
      { from: 'owner', text: '公司抬头，税号我一会儿发。', time: '14:59' },
      { from: 'agent', text: '好的，我先登记，税号发来后 1 个工作日内开具并推送到服务号。', time: '15:00' },
    ],
    summary: ['申请 2026-11 物业费电子发票。', '抬头为公司，税号待业主补充。', '承诺 1 个工作日内开具并推送。'],
    elements: [
      { label: '房号', value: '6-1-1102' },
      { label: '账期', value: '2026-11 · ¥856.00' },
      { label: '发票类型', value: '增值税普通发票' },
      { label: '抬头', value: '公司（税号演示）' },
      { label: '证据', value: '无' },
      { label: '诉求', value: '1 个工作日内开票' },
    ],
    risk: '发票由 ERP 开具，AI 只受理申请，不承诺开票时效以外的内容',
    suggestions: ['登记税号', '转 ERP 自助开票', '结束会话并回访'],
    erp: [
      { label: '账单', value: '2026-11 · 已缴' },
      { label: '发票', value: '无 · 可申请' },
      { label: '抬头记录', value: '首次开票' },
      { label: '欠费', value: '¥0' },
    ],
    qualityNote: '质检通过：话术合规、无越权承诺、要素齐全 6/6。',
  },
  {
    id: 'C-0821',
    name: '赵先生',
    room: '2-3-601',
    topic: '公摊能耗费用争议',
    channel: '400 电话',
    queue: 'qc',
    grade: '二级',
    emotion: '激烈',
    emotionScore: 74,
    wait: '已结束 15:12',
    sla: '已结束',
    aiDuration: 'AI 接待 55s · 已转人工',
    history: '历史报修 3 次 · 欠费 76 天',
    messages: [
      { from: 'system', text: '会话已结束 · 转人工原因：业主对公摊口径不认可，要求书面说明' },
      { from: 'owner', text: '公摊电费凭什么涨这么多？我要看明细。', time: '15:06' },
      { from: 'agent', text: '我给您调取 9—11 月的公摊明细和公示记录，稍后发到服务号。', time: '15:08' },
      { from: 'owner', text: '行，先这样。', time: '15:10' },
    ],
    summary: ['质疑公摊电费上涨，要求书面明细。', '已调取 9—11 月分摊明细与公示记录。', '承诺次日 12:00 前发送书面说明。'],
    elements: [
      { label: '房号', value: '2-3-601' },
      { label: '争议费项', value: '公摊电费' },
      { label: '账期', value: '2026-08 起' },
      { label: '欠费', value: '¥2,308.00' },
      { label: '证据', value: '公示记录 3 份' },
      { label: '诉求', value: '书面明细 + 分期方案' },
    ],
    risk: '涉及欠费与减免诉求 → 不由 AI 承诺减免，转管家与主管处理',
    suggestions: ['发送分摊明细', '生成催缴跟进', '转主管约谈'],
    erp: [
      { label: '欠费', value: '¥2,308.00 · 76 天' },
      { label: '账单', value: '2026-08 起 3 笔' },
      { label: '分期', value: '未申报' },
      { label: '上次工单', value: '2026-09-18 · 已闭环' },
    ],
    qualityNote: '待抽检：欠费类话术是否触碰红线；无减免承诺记录，建议复核。',
  },
]

const taken = ref<Record<string, boolean>>({})
const queueTab = ref<QueueKey>('waiting')
const selectedId = ref('C-0831')
const loading = ref(true)
const detailLoading = ref(false)
const takingOver = ref(false)
const draft = ref('')
const sentMessages = ref<Record<string, ChatMessage[]>>({})
const settingsOpen = ref(false)
const callbackOpen = ref(false)
const escalateOpen = ref(false)
const qualityPick = ref('C-0821')
const timers: number[] = []

/* ---------------- P2：ERP 工单 / 转人工摘要（真实 API） ---------------- */
/** 队列侧：ERP 在办工单（`GET /api/v1/tickets`，可选 status 过滤） */
const erpTickets = ref<TicketListItem[]>([])
const erpTicketTotal = ref(0)
const erpQueueTotal = ref(0)
const erpQueueStatus = ref<TicketStatus>('DISPATCHED')
const erpDegraded = ref<DegradedInfo | undefined>(undefined)
const erpError = ref('')
/** 选中会话的转人工摘要（`GET /tickets/{no}/handoff-summary`） */
const handoff = ref<HandoffSummary | null>(null)
const handoffLoading = ref(false)
const handoffError = ref('')
const handoffDegraded = ref<DegradedInfo | undefined>(undefined)
/** 当前会话对应的工单号（响应优先，其次 P1 样例） */
const handoffTicketNo = ref('')

/** 摘要接口不可用时的统一降级文案（保留 P1 本地摘要，但明确标注） */
const handoffDegradedText = computed(() => degradedText(handoffDegraded.value) || degradedText(erpDegraded.value))
const summaryFromApi = computed(() => Boolean(handoff.value))
const summaryTagText = computed(() => {
  if (handoff.value) return handoff.value.mock ? mockBadgeText : 'ERP 实时摘要'
  if (handoffDenied.value) return '无权限（不展示摘要）'
  return '演示摘要（接口不可用）'
})

/** 要素字典 → 中文标签（契约 `TicketCategory` / `TicketUrgency` / `IssueScope`） */
const ELEMENT_LABELS: Readonly<Record<string, string>> = {
  category: '类型',
  urgency: '等级',
  location: '位置',
  downstairs: '影响范围',
  duration: '持续时长',
  slot: '预约时段',
  roomCode: '房号',
}
const URGENCY_LABELS: Readonly<Record<string, string>> = { normal: '一般', urgent: '紧急', emergency: '应急' }
const SCOPE_LABELS: Readonly<Record<string, string>> = {
  none: '未漏到楼下',
  downstairs: '已漏到楼下',
  unknown: '不确定',
}

function elementValue(key: string, value: string): string {
  if (key === 'category') return TICKET_CATEGORY_LABELS[value as keyof typeof TICKET_CATEGORY_LABELS] ?? value
  if (key === 'urgency') return URGENCY_LABELS[value] ?? value
  if (key === 'downstairs') return SCOPE_LABELS[value] ?? value
  return value
}

/** 已采集要素：接口优先，接口不可用时回退 P1 样例 */
const elementRows = computed<Array<{ label: string; value: string; warn?: boolean }>>(() => {
  const data = handoff.value
  if (!data) return selected.value.elements
  return Object.entries(data.elements)
    .filter(([key]) => key !== 'roomCode')
    .map(([key, value]) => ({ label: ELEMENT_LABELS[key] ?? key, value: elementValue(key, value) }))
})

/** 会话摘要：接口优先（≤5 行），失败回退 P1 样例 */
const summaryLines = computed<string[]>(() => handoff.value?.summary ?? selected.value.summary)

/** AI 建议动作：接口优先 */
const suggestionActions = computed<string[]>(() => {
  const fromApi = handoff.value?.suggestedActions ?? []
  return fromApi.length > 0 ? fromApi : selected.value.suggestions
})

/** 未完成动作（接口 `pendingActions`） */
const pendingActions = computed<string[]>(() => handoff.value?.pendingActions ?? [])

/** 情绪：接口 `emotion`（缺失时用 P1 样例） */
const emotionText = computed(() => handoff.value?.emotion ?? selected.value.emotion)

/** 会话对应的工单号：接口回带 > 会话样例 > 房号匹配 ERP 工单 */
function resolveTicketNo(item: Conversation): string {
  if (handoffTicketNo.value) return handoffTicketNo.value
  if (item.ticketNo) return item.ticketNo
  const byRoom = erpTickets.value.find((ticket) => ticket.roomCode === item.room)
  return byRoom?.ticketNo ?? ''
}

/** ERP 工单行（右侧「ERP 实时数据」）：来自工单列表，接口失败时回退 P1 样例 */
const erpTicketRows = computed<Array<{ label: string; value: string }>>(() => {
  const ticket = erpTickets.value.find((item) => item.roomCode === selected.value.room)
  if (!ticket) return selected.value.erp
  return [
    { label: '工单号', value: ticket.ticketNo },
    { label: '状态', value: `${ticket.statusText}（${ticket.status}）` },
    { label: '班组', value: ticket.team || '待分配' },
    { label: '预计上门', value: ticket.eta || '待确认' },
    { label: '版本。', value: String(ticket.version) },
  ]
})

/** 队列侧读取 `GET /api/v1/tickets?status=`（在办工单数 + 房号匹配用） */
async function loadErpTickets() {
  erpError.value = ''
  queueDenied.value = null
  try {
    const [all, byStatus] = await Promise.all([
      api.ticket.list({ pageSize: 20 }),
      api.ticket.list({ status: erpQueueStatus.value, pageSize: 1 }),
    ])
    erpTickets.value = all.data.items
    erpTicketTotal.value = all.data.total
    erpQueueTotal.value = byStatus.data.total
    erpDegraded.value = all.degraded ?? byStatus.degraded
  } catch (error) {
    const denied = deniedInfoOf(error, 'ticket.status')
    if (denied) {
      // 403：队列侧不展示未授权工单数，归入页面无权限态（不当作「降级」）
      queueDenied.value = denied
      erpError.value = `无权限：需要 ${capabilityLabel(denied.requiredCapability)}（403），队列侧不展示未授权工单数`
      erpDegraded.value = undefined
      return
    }
    erpError.value = errorText(error instanceof Error && 'code' in error ? String(error.code) : 'E-9001')
    erpDegraded.value = undefined
  }
}

/** 读选中会话的转人工摘要（CAP-18）；失败保留 P1 摘要并标注降级 */
async function loadHandoff(item: Conversation) {
  handoff.value = null
  handoffError.value = ''
  handoffDenied.value = null
  handoffDegraded.value = undefined
  handoffTicketNo.value = ''
  // 选中会话时清掉工单列表的行选中态，避免两套选中互相打架
  selectedTicketNo.value = ''
  const ticketNo = item.ticketNo ?? erpTickets.value.find((ticket) => ticket.roomCode === item.room)?.ticketNo ?? ''
  if (!ticketNo) {
    handoffError.value = '该会话尚未关联工单，摘要暂用 P1 样例'
    return
  }
  handoffLoading.value = true
  try {
    const { data, degraded } = await api.ticket.handoffSummary(ticketNo)
    handoff.value = data
    handoffTicketNo.value = data.ticketNo
    handoffDegraded.value = degraded
  } catch (error) {
    const denied = deniedInfoOf(error, 'handoff.human')
    if (denied) {
      // 403：协同面板渲染无权限说明，不展示未授权摘要（也不把演示样例当真实摘要）
      handoffDenied.value = denied
      return
    }
    const code = error instanceof Error && 'code' in error ? String(error.code) : 'E-9001'
    handoffError.value = `转人工摘要暂不可用（${errorText(code)}），已回退演示摘要`
  } finally {
    handoffLoading.value = false
  }
}

/* ---------------- P2-021：工单列表（筛选 / 排序 / 真实分页 / 导出 / 行选中） ---------------- */
interface TicketFilters {
  status: TicketStatus | ''
  category: TicketCategory | ''
  team: string
  /** 日期（input[type=date]，本地日历日；查询时转 ISO 8601 的当日 00:00 / 23:59） */
  dateFrom: string
  dateTo: string
  sort: TicketListSort
  /** 关键字：房号 / 工单号，前端过滤当前页（契约无关键字参数） */
  keyword: string
}

const EMPTY_FILTERS = (): TicketFilters => ({
  status: '',
  category: '',
  team: '',
  dateFrom: '',
  dateTo: '',
  sort: DEFAULT_TICKET_LIST_SORT,
  keyword: '',
})

const ticketFilters = ref<TicketFilters>(EMPTY_FILTERS())
const ticketPage = ref(1)
const ticketPageSize = ref(10)
const ticketRows = ref<TicketListItem[]>([])
const ticketTotal = ref(0)
const ticketLoading = ref(false)
const ticketError = ref('')
const ticketDegraded = ref<DegradedInfo | undefined>(undefined)
/** 行选中（联动右侧协同面板的 handoff-summary） */
const selectedTicketNo = ref('')

const STATUS_OPTIONS = TICKET_STATUSES.map((status) => ({ value: status, label: TICKET_STATUS_LABELS[status] }))
const CATEGORY_OPTIONS = (Object.keys(TICKET_CATEGORY_LABELS) as TicketCategory[]).map((category) => ({
  value: category,
  label: TICKET_CATEGORY_LABELS[category],
}))
const SORT_OPTIONS: Array<{ value: TicketListSort; label: string }> = [
  { value: 'updatedAt_desc', label: '更新时间 · 新→旧' },
  { value: 'createdAt_asc', label: '创建时间 · 旧→新' },
  { value: 'createdAt_desc', label: '创建时间 · 新→旧' },
]

/** 本地日历日 → ISO 8601（含时区偏移）；非法日期返回 undefined（不下发脏参数） */
function dayIso(day: string, endOfDay: boolean): string | undefined {
  if (!day) return undefined
  const date = new Date(`${day}T${endOfDay ? '23:59:59' : '00:00:00'}`)
  return Number.isNaN(date.getTime()) ? undefined : date.toISOString()
}

/** 关键字过滤：房号 / 工单号（当前页内，前端过滤；契约无 keyword 参数） */
const visibleTickets = computed<TicketListItem[]>(() => {
  const keyword = ticketFilters.value.keyword.trim().toLowerCase()
  if (!keyword) return ticketRows.value
  return ticketRows.value.filter(
    (item) =>
      item.ticketNo.toLowerCase().includes(keyword) || (item.roomCode ?? '').toLowerCase().includes(keyword),
  )
})

const ticketTotalPages = computed(() => Math.max(1, Math.ceil(ticketTotal.value / ticketPageSize.value)))
const canPrevPage = computed(() => ticketPage.value > 1 && !ticketLoading.value)
const canNextPage = computed(() => ticketPage.value < ticketTotalPages.value && !ticketLoading.value)
const ticketListDegradedText = computed(() => degradedText(ticketDegraded.value))

/** 读工单列表（筛选/排序/分页全部下发给 API，`page/pageSize/total` 以响应为准） */
async function loadTickets(): Promise<void> {
  ticketLoading.value = true
  ticketError.value = ''
  ticketListDenied.value = null
  try {
    const filters = ticketFilters.value
    const dateFrom = dayIso(filters.dateFrom, false)
    const dateTo = dayIso(filters.dateTo, true)
    const { data, degraded } = await api.ticket.list({
      status: filters.status || undefined,
      category: filters.category || undefined,
      team: filters.team.trim() || undefined,
      dateFrom,
      dateTo,
      sort: filters.sort,
      page: ticketPage.value,
      pageSize: ticketPageSize.value,
    })
    ticketRows.value = data.items
    ticketTotal.value = data.total
    ticketPage.value = data.page
    ticketDegraded.value = degraded
  } catch (error) {
    ticketRows.value = []
    ticketTotal.value = 0
    const denied = deniedInfoOf(error, 'ticket.status')
    if (denied) {
      // 403：工单列表卡渲染无权限说明，不展示未授权工单行
      ticketListDenied.value = denied
      ticketDegraded.value = undefined
      return
    }
    const code = error instanceof Error && 'code' in error ? String(error.code) : 'E-9001'
    ticketError.value = `工单列表暂不可用（${errorText(code)}）`
    ticketDegraded.value = error instanceof Error && 'degraded' in error
      ? ((error as { degraded?: DegradedInfo }).degraded ?? undefined)
      : undefined
  } finally {
    ticketLoading.value = false
  }
}

/** 工单列表失败态「重试」：网络/服务失败保持可用；仍失败则 toast 说明（不留零反馈按钮） */
async function retryTickets(): Promise<void> {
  await loadTickets()
  if (ticketError.value) toast.error(`工单列表仍不可用：${ticketError.value}；已保留筛选条件，可稍后再试或转人工`)
}

/** 改筛选条件：回到第 1 页重新查（与真实分页一致，不做本地假分页） */
function applyTicketFilters(): void {
  ticketPage.value = 1
  selectedTicketNo.value = ''
  void loadTickets()
}

function resetTicketFilters(): void {
  ticketFilters.value = EMPTY_FILTERS()
  applyTicketFilters()
  toast.info('工单列表筛选已重置：全部状态 / 分类 / 班组 / 日期')
}

function gotoPage(page: number): void {
  const target = Math.min(Math.max(1, page), ticketTotalPages.value)
  if (target === ticketPage.value) return
  ticketPage.value = target
  void loadTickets()
}

type TicketCsvColumn = { key: string; title: string }

/** 导出当前筛选结果为 CSV（真实 Blob 下载；列表接口不返回「位置」，该列用「—」占位，不编造） */
function exportTickets(): void {
  const rows = visibleTickets.value
  if (rows.length === 0) {
    toast.warning('当前筛选结果为空，无可导出的工单；请放宽筛选条件')
    return
  }
  const columns: TicketCsvColumn[] = [
    { key: 'ticketNo', title: '工单号' },
    { key: 'statusText', title: '状态' },
    { key: 'category', title: '类型' },
    { key: 'roomCode', title: '房号' },
    { key: 'location', title: '位置' },
    { key: 'team', title: '班组' },
    { key: 'eta', title: 'ETA' },
    { key: 'updatedAt', title: '更新时间' },
    { key: 'version', title: '版本' },
  ]
  const result = downloadCsv({
    columns,
    baseName: '工单列表',
    rows: rows.map((item) => ({
      ticketNo: item.ticketNo,
      statusText: `${item.statusText}（${item.status}）`,
      category: TICKET_CATEGORY_LABELS[item.category] ?? item.category,
      roomCode: item.roomCode,
      location: '—',
      team: item.team || '待分配',
      eta: item.eta || '待确认',
      updatedAt: formatIsoMinute(item.updatedAt),
      version: `v${item.version}`,
    })),
  })
  toast.success(
    successReceipt(
      '导出工单列表 CSV',
      `${result.filename} · ${result.rowCount} 条 · 导出口径 = 当前筛选结果${
        ticketFilters.value.keyword.trim() ? `（含关键字「${ticketFilters.value.keyword.trim()}」前端过滤）` : ''
      }`,
    ),
  )
}

/** 行选中：读该工单转人工摘要（CAP-18），失败保留「已降级」提示不阻塞 */
async function loadHandoffByTicketNo(ticketNo: string): Promise<void> {
  if (!ticketNo) return
  selectedTicketNo.value = ticketNo
  handoff.value = null
  handoffError.value = ''
  handoffDenied.value = null
  handoffDegraded.value = undefined
  handoffTicketNo.value = ticketNo
  handoffLoading.value = true
  try {
    const { data, degraded } = await api.ticket.handoffSummary(ticketNo)
    handoff.value = data
    handoffTicketNo.value = data.ticketNo
    handoffDegraded.value = degraded
  } catch (error) {
    const denied = deniedInfoOf(error, 'handoff.human')
    if (denied) {
      handoffDenied.value = denied
      return
    }
    const code = error instanceof Error && 'code' in error ? String(error.code) : 'E-9001'
    handoffError.value = `转人工摘要暂不可用（${errorText(code)}），已保留工单号，稍后可重试`
  } finally {
    handoffLoading.value = false
  }
}

function selectTicketRow(item: TicketListItem): void {
  if (selectedTicketNo.value === item.ticketNo) return
  detailLoading.value = true
  schedule(180, () => {
    detailLoading.value = false
  })
  void loadHandoffByTicketNo(item.ticketNo)
}

function queueOf(item: Conversation): QueueKey {
  if (item.queue === 'qc') return 'qc'
  return taken.value[item.id] ? 'serving' : item.queue
}

const queueCounts = computed(() => {
  const counts: Record<QueueKey, number> = { waiting: 0, serving: 0, qc: 0 }
  for (const item of CONVERSATIONS) counts[queueOf(item)] += 1
  return counts
})

const queueTabs = computed(() =>
  (Object.keys(QUEUE_META) as QueueKey[]).map((key) => ({
    key,
    label: `${QUEUE_META[key].label} ${queueCounts.value[key]}`,
  })),
)

const queueItems = computed(() => CONVERSATIONS.filter((item) => queueOf(item) === queueTab.value))

const selected = computed(() => CONVERSATIONS.find((item) => item.id === selectedId.value) ?? CONVERSATIONS[0])

const isTaken = computed(() => Boolean(taken.value[selected.value.id]))

const messages = computed<ChatMessage[]>(() => [
  ...selected.value.messages,
  ...(sentMessages.value[selected.value.id] ?? []),
])

const elementFilled = computed(() => {
  const rows = elementRows.value
  const filled = rows.filter((item) => !item.warn && Boolean(item.value) && item.value !== '未填写').length
  return `${filled} / ${rows.length}`
})

const qualityStat = [
  { key: 'batch', label: '本周抽检', value: '18 / 40 条' },
  { key: 'pass', label: '抽检通过率', value: '94.4%' },
  { key: 'badcase', label: 'badcase', value: '2 条待复盘' },
]

const qualityConversations = CONVERSATIONS.filter((item) => item.queue === 'qc' || item.id === 'C-0829')

function schedule(ms: number, task: () => void) {
  const timer = window.setTimeout(task, ms)
  timers.push(timer)
}

onMounted(() => {
  // 先并行拉 ERP 工单（队列侧数据）与 工单列表（P2-021），失败也不阻塞 P1 视觉骨架
  void loadErpTickets()
  void loadTickets()
  schedule(480, () => {
    loading.value = false
    void loadHandoff(selected.value)
  })
})

/**
 * 演示账号 / 角色切换 → 按新身份重新读取（P2 W9）：
 * 同一路由内切账号不会重新挂载组件，不重取会残留上一个角色可见的 ERP 数据；
 * 403 时自然进入「无权限」态（admin 无 ticket.status/handoff.human）。
 */
watch(
  () => auth.role,
  (next, prev) => {
    if (!next || next === prev) return
    void loadErpTickets()
    void loadTickets()
    void loadHandoff(selected.value)
  },
)

onBeforeUnmount(() => {
  timers.forEach((timer) => window.clearTimeout(timer))
})

function selectConversation(id: string) {
  if (id === selectedId.value) return
  selectedId.value = id
  detailLoading.value = true
  schedule(220, () => {
    detailLoading.value = false
  })
  void loadHandoff(selected.value)
}

function switchQueue(key: QueueKey) {
  queueTab.value = key
  if (!queueItems.value.some((item) => item.id === selectedId.value)) {
    const first = queueItems.value[0]
    if (first) selectConversation(first.id)
  }
}

function takeOver() {
  if (isTaken.value || takingOver.value) return
  takingOver.value = true
  const item = selected.value
  schedule(900, () => {
    takingOver.value = false
    taken.value = { ...taken.value, [item.id]: true }
    toast.success(`已接入 ${item.name} 的会话，摘要 / 要素 / 情绪与 ERP 数据已同屏同步`)
    queueTab.value = 'serving'
  })
}

function sendMessage() {
  const text = draft.value.trim()
  if (!text) {
    toast.warning('请输入回复内容，或使用右侧 AI 建议动作')
    return
  }
  if (!isTaken.value) {
    toast.warning('当前会话由 AI 接待，先点「接管会话」再回复，避免双人重复应答')
    return
  }
  const list = sentMessages.value[selected.value.id] ?? []
  sentMessages.value = {
    ...sentMessages.value,
    [selected.value.id]: [...list, { from: 'agent', text, time: `15:0${Math.min(9, list.length + 1)}` }],
  }
  draft.value = ''
}

function useQuickChip(text: string) {
  draft.value = text
}

function runSuggestion(action: string) {
  if (action.includes('升级')) {
    escalateOpen.value = true
    return
  }
  toast.success(`已执行「${action}」` + demoNote('结果写入本次会话协同面板，仅本地'))
}

function openCallback() {
  callbackOpen.value = true
}

function confirmCallback() {
  callbackOpen.value = false
  toast.success(`已创建回拨任务，15 分钟内由当前坐席外呼` + demoNote('未真实外呼'))
}

function confirmEscalate() {
  escalateOpen.value = false
  toast.success('已升级为一级投诉，并推送项目经理与区域负责人（30 分钟内上报）')
}

function saveSettings() {
  settingsOpen.value = false
  toast.success(successReceipt('保存工作台设置', '分级规则 / SLA / 质检抽检比例'))
}

function submitQuality() {
  toast.success('已提交质检结论：本次会话计入本周抽检批次')
}

function jumpTrace() {
  toast.info(infoReceipt('全链路回溯', '在「全链路回溯」（10 屏）查看完整调用链'))
}

function selectQuality(id: string) {
  qualityPick.value = id
  selectConversation(id)
}
</script>

<template>
  <div class="w-page desk-page" data-page="desk" data-testid="page-root">
    <WPageHeader :eyebrow="screen.eyebrow" :title="screen.title" :description="screen.description">
      <template #actions>
        <WTag tone="a">1 个一级投诉待上报</WTag>
        <WTag tone="g" plain>当前在线 6 席</WTag>
        <WButton size="sm" icon="users" @click="settingsOpen = true">工作台设置</WButton>
      </template>
    </WPageHeader>

    <!-- P3 W12：坐席会话台 / 投诉处理台 子视图切换（同一路由，不新增导航屏） -->
    <div class="desk__tabbar" data-testid="desk-tabs">
      <WTabs v-model="deskTab" size="sm" :tabs="DESK_TABS" />
      <span class="desk__tabbar-hint">
        {{ deskTab === 'complaint' ? '投诉处置必须人工：受理 / 首响 / 升级（仅主管）/ 结案 / 重开，全程 audit 留痕' : deskTab === 'realtime' ? 'P4 实时队列（SLA 倒计时/接管互斥 409/释放）+ SLA 看板 + 质检评分（0—100）与统计，全部来自 /desk/* 接口' : '三队列 + 上下文同屏 + 工单列表（W2—W11 既有语义）' }}
      </span>
    </div>

    <div v-if="loading" class="desk-loading">
      <WLoading center label="正在同步会话队列、AI 摘要与 ERP 只读数据…" />
    </div>

    <template v-else>
      <ComplaintWorkbench v-if="deskTab === 'complaint'" />
      <DeskRealtimeBoard v-else-if="deskTab === 'realtime'" />
      <template v-else>
      <!-- 403 无权限态（P2 W9）：admin 能进 /desk 但无 ticket.status/handoff.human，不静默失败 -->
      <PermissionDenied
        v-if="deskDenied"
        compact
        data-testid="desk-denied"
        :required-capability="deskDenied.requiredCapability"
        :required-role="deskDenied.role"
        :switching="switchingAccount"
        :candidate-roles="DESK_SWITCH_ROLES"
        :detail="deskDeniedDetail"
        @switch="switchDeskAccount"
      >
        <template #actions>
          <WButton size="sm" variant="primary" data-testid="desk-denied-retry" @click="retryDeskDenied">重新加载</WButton>
        </template>
      </PermissionDenied>

      <!-- ============ P2-021 工单列表：筛选 / 排序 / 真实分页 / 导出 CSV / 行选中 ============ -->
      <WCard class="desk__tickets" :padded="false" data-testid="desk-tickets">
        <template #head>
          <span class="desk__card-title">工单列表</span>
          <WTag tone="b" plain>{{ ticketTotal }} 条</WTag>
          <WTag v-if="ticketListDegradedText" tone="a" plain>已降级</WTag>
          <WTag v-if="ticketListDenied" tone="n" plain>无权限 403</WTag>
          <WTag v-else-if="ticketError" tone="r" plain>列表不可用</WTag>
        </template>
        <template #extra>
          <WButton size="sm" variant="ghost" data-testid="desk-ticket-reset" @click="resetTicketFilters">清空筛选</WButton>
          <WButton
            size="sm"
            variant="primary"
            icon="download"
            data-testid="desk-ticket-export"
            :disabled="ticketLoading || visibleTickets.length === 0"
            @click="exportTickets"
          >
            导出 CSV
          </WButton>
        </template>

        <!-- 403：只给无权限说明，不渲染未授权工单数据（重试/切换账号在页面顶部无权限态） -->
        <div v-if="ticketListDenied" class="desk__tstate" data-testid="desk-ticket-denied">
          <WEmpty
            compact
            icon="lock"
            title="工单列表无权限（403）"
            :description="`需要能力 ${capabilityLabel(ticketListDenied.requiredCapability)}；当前角色「${currentRoleLabel}」不具备。已在上方无权限态说明，可切换坐席 / 主管演示账号后重新读取。`"
          />
        </div>

        <template v-else>
        <div class="desk__filters" data-testid="desk-ticket-filters">
          <label>
            <span>状态</span>
            <select v-model="ticketFilters.status" data-testid="desk-filter-status" @change="applyTicketFilters">
              <option value="">全部状态</option>
              <option v-for="option in STATUS_OPTIONS" :key="option.value" :value="option.value">
                {{ option.label }}（{{ option.value }}）
              </option>
            </select>
          </label>
          <label>
            <span>类型</span>
            <select v-model="ticketFilters.category" data-testid="desk-filter-category" @change="applyTicketFilters">
              <option value="">全部类型</option>
              <option v-for="option in CATEGORY_OPTIONS" :key="option.value" :value="option.value">
                {{ option.label }}
              </option>
            </select>
          </label>
          <label>
            <span>班组</span>
            <input
              v-model="ticketFilters.team"
              type="text"
              placeholder="如 水电二班"
              data-testid="desk-filter-team"
              @keyup.enter="applyTicketFilters"
              @change="applyTicketFilters"
            />
          </label>
          <label>
            <span>更新起</span>
            <input v-model="ticketFilters.dateFrom" type="date" data-testid="desk-filter-date-from" @change="applyTicketFilters" />
          </label>
          <label>
            <span>更新起</span>
            <input v-model="ticketFilters.dateTo" type="date" data-testid="desk-filter-date-to" @change="applyTicketFilters" />
          </label>
          <label>
            <span>排序</span>
            <select v-model="ticketFilters.sort" data-testid="desk-filter-sort" @change="applyTicketFilters">
              <option v-for="option in SORT_OPTIONS" :key="option.value" :value="option.value">{{ option.label }}</option>
            </select>
          </label>
          <label class="desk__filters-kw">
            <span>关键字</span>
            <input
              v-model="ticketFilters.keyword"
              type="text"
              placeholder="房号 / 工单号（当前页前端过滤）"
              data-testid="desk-filter-keyword"
            />
          </label>
        </div>

        <p v-if="ticketError" class="desk__degrade" data-testid="desk-ticket-error">
          <WTag tone="r" plain>降级提示</WTag>
          {{ ticketError }}
          <WButton size="sm" variant="ghost" data-testid="desk-ticket-retry" @click="retryTickets">重试</WButton>
        </p>
        <p v-else-if="ticketListDegradedText" class="desk__degrade" data-testid="desk-ticket-degraded">
          <WTag tone="a" plain>已降级</WTag>
          {{ ticketListDegradedText }}（数据为平台快照/缓存，不代表 ERP 实时）         </p>

        <div v-if="ticketLoading" class="desk__tstate" data-testid="desk-ticket-loading">
          <WLoading label="正在读取工单列表（GET /api/v1/tickets）…" />
        </div>

        <WEmpty
          v-else-if="visibleTickets.length === 0"
          compact
          icon="list-check"
          data-testid="desk-ticket-empty"
          title="当前筛选无工单"
          description="放宽状态 / 类型 / 班组 / 日期，或清空筛选查看全量工单。"
        />

        <table v-else class="desk__table" data-testid="desk-ticket-table">
          <thead>
            <tr>
              <th>工单号</th>
              <th>状态</th>
              <th>类型</th>
              <th>房号</th>
              <th>班组</th>
              <th>ETA</th>
              <th>更新时间</th>
              <th>版本</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="row in visibleTickets"
              :key="row.ticketNo"
              class="desk__trow"
              :class="{ 'is-on': row.ticketNo === selectedTicketNo }"
              :data-ticket-no="row.ticketNo"
              data-testid="desk-ticket-row"
              tabindex="0"
              @click="selectTicketRow(row)"
              @keyup.enter="selectTicketRow(row)"
            >
              <td class="desk__trow-no">
                <b>{{ row.ticketNo }}</b>
                <WTag v-if="row.aiCreated" tone="v" plain>AI 建单</WTag>
              </td>
              <td><WTag :tone="row.status === 'COMPLETED' || row.status === 'REVIEWED' ? 'g' : row.status === 'DISPATCHED' || row.status === 'ON_SITE' ? 'b' : 'a'" plain>{{ row.statusText }}</WTag></td>
              <td>{{ TICKET_CATEGORY_LABELS[row.category] ?? row.category }}</td>
              <td>{{ row.roomCode }}</td>
              <td>{{ row.team || '待分配' }}</td>
              <td>{{ row.eta || '待确认' }}</td>
              <td class="num">{{ formatIsoMinute(row.updatedAt) }}</td>
              <td class="num">v{{ row.version }}</td>
            </tr>
          </tbody>
        </table>
        </template>

        <template #footer>
          <div v-if="!ticketListDenied" class="desk__pager" data-testid="desk-ticket-pager">
            <span>
              第 {{ ticketPage }} / {{ ticketTotalPages }} 页 · 共 {{ ticketTotal }} 条（pageSize {{ ticketPageSize }}）
              <template v-if="ticketFilters.keyword.trim()"> · 当前页关键字过滤后 {{ visibleTickets.length }} 条</template>
            </span>
            <span class="desk__pager-acts">
              <WButton size="sm" variant="ghost" data-testid="desk-ticket-prev" :disabled="!canPrevPage" @click="gotoPage(ticketPage - 1)">
                上一页               </WButton>
              <WButton size="sm" variant="ghost" data-testid="desk-ticket-next" :disabled="!canNextPage" @click="gotoPage(ticketPage + 1)">
                下一页               </WButton>
            </span>
          </div>
        </template>
      </WCard>

      <div class="desk">
      <!-- 队列 -->
      <WCard class="desk__queue" :padded="false">
        <template #head>
          <span class="desk__card-title">会话队列</span>
          <WTag tone="a">{{ queueCounts.waiting }} 待接入</WTag>
        </template>
        <template #extra>
          <WButton size="sm" variant="ghost" icon="filter" @click="toast.info(infoReceipt('队列筛选', '按分级 / 情绪 / 渠道筛选'))">
            筛选
          </WButton>
        </template>

        <div class="desk__queue-body">
          <div class="desk__qtabs" role="tablist">
            <button
              v-for="tab in queueTabs"
              :key="tab.key"
              type="button"
              role="tab"
              class="desk__qtab"
              :class="{ 'is-on': queueTab === tab.key }"
              :aria-selected="queueTab === tab.key"
              @click="switchQueue(tab.key)"
            >
              {{ tab.label }}
            </button>
          </div>

          <p class="desk__queue-hint">{{ QUEUE_META[queueTab].hint }}</p>

          <WEmpty
            v-if="queueItems.length === 0"
            compact
            icon="headset"
            title="该队列暂无会话"
            description="切换其它队列，或等待新会话进入；队列数据来自 mock。"
          />

          <button
            v-for="item in queueItems"
            :key="item.id"
            type="button"
            class="desk__qitem"
            :class="{ 'is-on': item.id === selectedId }"
            @click="selectConversation(item.id)"
          >
            <span class="desk__avatar" :class="`is-${EMOTION_TONE[item.emotion]}`">{{ item.name.slice(0, 1) }}</span>
            <span class="desk__qmain">
              <span class="desk__qrow">
                <b>{{ item.name }}</b>
                <WTag :tone="GRADE_TONE[item.grade]">{{ item.grade }}</WTag>
              </span>
              <span class="desk__qtopic">{{ item.room }} · {{ item.topic }}</span>
              <span class="desk__qmeta">
                <span>{{ item.wait }}</span>
                <WTag :tone="EMOTION_TONE[item.emotion]" plain>{{ item.emotion }}</WTag>
              </span>
            </span>
          </button>

          <div class="desk__qsum">
            <div><b class="num">18s</b><span>平均首响</span></div>
            <div><b class="num">92%</b><span>分级准确</span></div>
            <div><b class="num">0</b><span>错误承诺</span></div>
          </div>
          <p class="desk__qerp" data-testid="desk-queue-erp">
            <WTag :tone="erpError ? 'r' : 'b'" plain>
              {{ queueDenied ? '队列无权限' : erpError ? '队列降级' : 'ERP 队列' }}
            </WTag>
            <span v-if="!erpError">
              在办工单 {{ erpQueueTotal }} 条 · 全量 {{ erpTicketTotal }} 条（{{ erpQueueStatus }}）
            </span>
            <span v-else>{{ erpError }}</span>
          </p>
        </div>
      </WCard>

      <!-- 会话详情 -->
      <WCard class="desk__conv" :padded="false">
        <template #head>
          <span class="desk__avatar is-r">{{ selected.name.slice(0, 1) }}</span>
          <span class="desk__conv-title">
            <b>{{ selected.name }} · {{ selected.room }}</b>
            <span>{{ selected.channel }} · 业主本人 · 微信已绑定 · {{ selected.history }}</span>
          </span>
        </template>
        <template #extra>
          <WTag v-if="resolveTicketNo(selected)" tone="n" plain>{{ resolveTicketNo(selected) }}</WTag>
          <WTag :tone="EMOTION_TONE[selected.emotion]">情绪 {{ selected.emotionScore }}</WTag>
          <WTag tone="a">{{ selected.sla }}</WTag>
          <WTag tone="v">{{ selected.aiDuration }}</WTag>
          <WButton size="sm" icon="clock" @click="openCallback">回拨</WButton>
          <WButton v-if="!isTaken" size="sm" variant="primary" :loading="takingOver" icon="headset" @click="takeOver">
            接管会话
          </WButton>
          <WTag v-else tone="g">已接入 · 人工接待中</WTag>
        </template>

        <div class="desk__conv-body">
          <WLoading v-if="detailLoading" center label="正在加载会话上下文…" />
          <div v-else class="desk__msgs">
            <template v-for="(message, index) in messages" :key="`${message.from}-${index}`">
              <div v-if="message.from === 'system'" class="desk__sysline" :class="{ 'is-ok': index > 0 }">
                <span>{{ message.text }}</span>
              </div>
              <div v-else class="desk__msg" :class="`is-${message.from}`">
                <span class="desk__msg-avatar">{{ message.from === 'ai' ? 'AI' : message.from === 'owner' ? selected.name.slice(0, 1) : '张' }}</span>
                <div class="desk__bubble">
                  <p>{{ message.text }}</p>
                  <span v-if="message.time" class="desk__msg-time num">{{ message.time }}</span>
                </div>
              </div>
            </template>
          </div>

          <div class="desk__composer">
            <div class="desk__quick">
              <span class="dim">快捷话术</span>
              <button v-for="chip in QUICK_CHIPS" :key="chip.label" type="button" @click="useQuickChip(chip.text)">
                {{ chip.label }}
              </button>
            </div>
            <div class="desk__cbox">
              <input
                v-model="draft"
                type="text"
                :placeholder="isTaken ? '输入回复，或使用右侧 AI 建议动作…' : '会话由 AI 接待中，点击「接管会话」后可回复'"
                @keyup.enter="sendMessage"
              />
              <WButton size="sm" variant="primary" icon="arrow-right" @click="sendMessage">发送</WButton>
            </div>
            <p class="desk__composer-note">
              群服务 / 支付 / 发票均未接入真实通道，本页只做会话协同演示；发送内容不进真实消息队列。             </p>
          </div>
        </div>
      </WCard>

      <!-- 协同面板 -->
      <WCard class="desk__ctx" :padded="false">
        <template #head><span class="desk__card-title">AI 协同面板</span></template>
        <template #extra>
          <WTag tone="v">实时</WTag>
          <WTag :tone="summaryFromApi ? 'g' : 'a'" plain>{{ summaryTagText }}</WTag>
          <WTag v-if="handoffTicketNo" tone="n" plain>{{ handoffTicketNo }}</WTag>
        </template>

        <div class="desk__ctx-body">
          <!-- 403：协同面板只给无权限说明，不渲染未授权摘要/要素/情绪（重试与切账号在顶部无权限态） -->
          <div v-if="handoffDenied" class="desk__ctx-denied" data-testid="desk-handoff-denied">
            <p class="desk__ctx-denied-head">
              <WTag tone="r" plain>无权限 403</WTag>
              <b>协同面板不展示未授权摘要</b>
            </p>
            <p class="desk__ctx-denied-text">
              转人工摘要 / 已采集要素 / 情绪判定需要能力               <code>{{ capabilityLabel(handoffDenied.requiredCapability) }}</code>；当前角色「{{ currentRoleLabel }}」不具备。               本面板不读取、不编造该会话的摘要数据；可切换坐席 / 主管演示账号后在页面上方「重新加载」。             </p>
          </div>

          <template v-else>
          <p v-if="handoffLoading" class="desk__degrade" data-testid="desk-handoff-loading">
            <WTag tone="b" plain>加载中</WTag>
            正在读取转人工摘要（GET /tickets/{no}/handoff-summary）…
          </p>
          <p v-if="handoffError && !handoffLoading" class="desk__degrade" data-testid="desk-handoff-degraded">
            <WTag tone="a" plain>降级提示</WTag>
            {{ handoffError }}
          </p>
          <p v-if="handoffDegradedText" class="desk__degrade" data-testid="desk-handoff-degraded-level">
            <WTag tone="a" plain>已降级</WTag>
            {{ handoffDegradedText }}
          </p>

          <section class="desk__block">
            <header class="desk__block-h">
              <span>会话摘要</span><em>{{ summaryFromApi ? '来自转人工摘要 API' : '演示摘要' }}</em>
            </header>
            <ul class="desk__sumlist" data-testid="desk-summary">
              <li v-for="line in summaryLines" :key="line">{{ line }}</li>
            </ul>
            <p v-if="pendingActions.length" class="desk__kv">
              <span>未完成动作</span>
              <b>{{ pendingActions.join(' / ') }}</b>
            </p>
          </section>

          <section class="desk__block">
            <header class="desk__block-h">
              <span>已采集要素</span><em>{{ elementFilled }}</em>
            </header>
            <div v-for="item in elementRows" :key="item.label" class="desk__kv">
              <span>{{ item.label }}</span>
              <b :class="{ 'is-warn': item.warn }">{{ item.value }}</b>
            </div>
          </section>

          <section class="desk__block">
            <header class="desk__block-h">
              <span>情绪与风险</span><em>{{ handoff ? '摘要接口判定' : '规则命中（演示）' }}</em>
            </header>
            <div class="desk__emoji">
              <span>{{ emotionText }}</span>
              <div class="desk__bar"><i :style="{ width: `${selected.emotionScore}%` }" /></div>
              <b class="num">{{ selected.emotionScore }}</b>
            </div>
            <p class="desk__risk">{{ handoff?.emotion ? `摘要接口情绪：${handoff.emotion}` : selected.risk }}</p>
          </section>

          <section class="desk__block">
            <header class="desk__block-h">
              <span>AI 建议动作</span><em>{{ summaryFromApi ? '来自摘要接口' : '点击执行（演示）' }}</em>
            </header>
            <div class="desk__acts">
              <WButton
                v-for="(action, index) in suggestionActions"
                :key="action"
                size="sm"
                :variant="index === 0 ? 'ai' : 'secondary'"
                :icon="index === 0 ? 'shield' : index === 1 ? 'report' : 'users'"
                @click="runSuggestion(action)"
              >
                {{ action }}
              </WButton>
            </div>
          </section>

          <section v-if="handoff?.history?.length" class="desk__block">
            <header class="desk__block-h">
              <span>脱敏历史片段</span><em>最近 7 天</em>
            </header>
            <div v-for="(item, index) in handoff.history" :key="`${item.at}-${index}`" class="desk__kv">
              <span>{{ formatIsoMinute(item.at) }} · {{ item.channel }}</span>
              <b>{{ item.text }}</b>
            </div>
          </section>

          <section class="desk__block">
            <header class="desk__block-h">
              <span>ERP 实时数据</span><em>{{ erpTicketRows.some((row) => row.label === '工单号') ? '工单列表 · 只读' : '演示样例' }}</em>
            </header>
            <div v-for="item in erpTicketRows" :key="item.label" class="desk__kv">
              <span>{{ item.label }}</span>
              <b>{{ item.value }}</b>
            </div>
            <div class="desk__kv">
              <span>队列工单</span>
              <b>在办 {{ erpQueueTotal }} 条 · 全量 {{ erpTicketTotal }} 条</b>
            </div>
            <p class="desk__degrade">
              <WTag tone="a" plain>{{ erpError ? '降级提示' : '数据边界' }}</WTag>
              {{ erpError || '数据来自 Java BFF 只读查询（P2 第 1 周 ERP 为 mock-erp 演示数据）；写操作一律走人工，不在本页提交。' }}
            </p>
          </section>

          <section class="desk__block">
            <header class="desk__block-h">
              <span>质检与回溯</span><em>本周</em>
            </header>
            <div v-for="row in qualityStat" :key="row.key" class="desk__kv">
              <span>{{ row.label }}</span>
              <b>{{ row.value }}</b>
            </div>
            <div class="desk__acts">
              <WButton size="sm" icon="check" @click="submitQuality">提交质检结论</WButton>
              <WButton size="sm" variant="ghost" icon="activity" @click="jumpTrace">查看回溯</WButton>
            </div>
          </section>

          <section v-if="queueTab === 'qc'" class="desk__block">
            <header class="desk__block-h">
              <span>待质检会话</span><em>抽检 5 条</em>
            </header>
            <button
              v-for="item in qualityConversations"
              :key="item.id"
              type="button"
              class="desk__qc-item"
              :class="{ 'is-on': qualityPick === item.id }"
              @click="selectQuality(item.id)"
            >
              <b>{{ item.name }} · {{ item.topic }}</b>
              <span>{{ item.qualityNote }}</span>
            </button>
          </section>
          </template>
        </div>
      </WCard>
      </div>
      </template>
    </template>

    <WModal v-model:open="settingsOpen" title="工作台设置" width="520px">
      <div class="desk__modal-rows">
        <div class="desk__modal-row">
          <span>分级规则</span>
          <b>一级投诉：起诉 / 12345 / 人身安全 → 30 分钟内上报</b>
        </div>
        <div class="desk__modal-row">
          <span>SLA 阈值</span>
          <b>首响 ≤ 20s · 一级投诉解决 ≤ 4h · 普通 议 24h</b>
        </div>
        <div class="desk__modal-row">
          <span>质检抽检比例</span>
          <b>每日 20% · AI 建单 100% 抽检（前两周）</b>
        </div>
        <p class="desk__modal-note">设置只影响演示数据，不写入真实配置；真实权限矩阵见「角色与权限」说明。</p>
      </div>
      <template #footer>
        <WButton size="sm" variant="ghost" @click="settingsOpen = false">取消</WButton>
        <WButton size="sm" variant="primary" @click="saveSettings">保存设置</WButton>
      </template>
    </WModal>

    <WModal v-model:open="callbackOpen" title="发起回拨" width="460px">
      <div class="desk__modal-rows">
        <div class="desk__modal-row"><span>会话</span><b>{{ selected.name }} · {{ selected.room }}</b></div>
        <div class="desk__modal-row"><span>号码</span><b class="num">138****8821（已脱敏）</b></div>
        <div class="desk__modal-row"><span>回拨时段</span><b>10 分钟内 · 记录进会话时间线</b></div>
        <p class="desk__modal-note">22:00—8:00 不外呼；单户每周 ≤2 次，红线在费用页统一展示。</p>
      </div>
      <template #footer>
        <WButton size="sm" variant="ghost" @click="callbackOpen = false">取消</WButton>
        <WButton size="sm" variant="primary" @click="confirmCallback">确认回拨</WButton>
      </template>
    </WModal>

    <WModal v-model:open="escalateOpen" title="升级一级投诉并上报" width="500px">
      <div class="desk__modal-rows">
        <div class="desk__modal-row"><span>分级</span><b>一级投诉 · 30 分钟内上报项目经理</b></div>
        <div class="desk__modal-row"><span>随附材料</span><b>会话摘要 4 条 · 要素 6 项 · 照片 2 张</b></div>
        <div class="desk__modal-row"><span>通知对象</span><b>项目经理 · 区域负责人 · 客服主管</b></div>
        <p class="desk__modal-note">升级后 SLA 切换为 4 小时；AI 不认定责任，只留痕与转派。</p>
      </div>
      <template #footer>
        <WButton size="sm" variant="ghost" @click="escalateOpen = false">取消</WButton>
        <WButton size="sm" variant="primary" @click="confirmEscalate">确认升级</WButton>
      </template>
    </WModal>

    <StateDemo screen="desk" />
  </div>
</template>

<style scoped>
.desk-page {
  min-width: 0;
}

/* P3 W12：坐席会话台 / 投诉处理台 子视图切换条 */
.desk__tabbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: var(--sp-3);
}

.desk__tabbar-hint {
  font-size: 9.5px;
  color: var(--c-faint);
}

.desk-loading {
  display: grid;
  place-items: center;
  min-height: 420px;
}

.desk {
  display: grid;
  grid-template-columns: 248px minmax(0, 1fr) 320px;
  gap: var(--sp-3);
  align-items: stretch;
}

.desk__card-title {
  font-size: 12.5px;
  font-weight: var(--fw-semibold);
  color: var(--c-ink);
}

/* P2 W9：协同面板 403 无权限说明（不渲染未授权摘要） */
.desk__ctx-denied {
  display: flex;
  flex-direction: column;
  gap: 5px;
  margin-bottom: 10px;
  padding: 9px 10px;
  border: 1px dashed var(--c-amber);
  border-radius: var(--rd-md);
  background: var(--c-amber-soft);
}

.desk__ctx-denied-head {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-xs);
  color: var(--c-ink);
}

.desk__ctx-denied-text {
  font-size: var(--fs-xs);
  color: var(--c-ink-2);
  line-height: var(--lh-base);
}

.desk__ctx-denied-text code {
  padding: 0 3px;
  border-radius: var(--rd-xs);
  background: var(--c-bg-2);
  font-family: var(--font-mono);
  font-size: 9.5px;
}

/* ---------- P2-021 工单列表（筛选 / 真实分页 / 导出） ---------- */
.desk__tickets {
  margin-bottom: var(--sp-3);
}

.desk__filters {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 10px 16px;
  border-bottom: 1px solid var(--c-line-2);
  background: var(--c-bg-2);
}

.desk__filters label {
  display: flex;
  flex-direction: column;
  gap: 3px;
  font-size: var(--fs-2xs);
  color: var(--c-faint);
}

.desk__filters select,
.desk__filters input {
  height: 28px;
  min-width: 120px;
  padding: 0 8px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-sm);
  background: var(--c-card);
  font-size: var(--fs-xs);
  color: var(--c-ink);
}

.desk__filters select:focus,
.desk__filters input:focus {
  outline: none;
  border-color: var(--c-brand);
  box-shadow: var(--sh-ring);
}

.desk__filters-kw input {
  min-width: 210px;
}

.desk__tstate {
  display: flex;
  justify-content: center;
  padding: 18px 16px;
}

.desk__table {
  width: 100%;
  border-collapse: separate;
  border-spacing: 0;
  font-size: var(--fs-xs);
}

.desk__table th {
  padding: 7px 12px;
  background: var(--c-card);
  border-bottom: 1px solid var(--c-line);
  color: var(--c-muted);
  font-size: 9.5px;
  font-weight: var(--fw-semibold);
  letter-spacing: 0.6px;
  text-align: left;
  white-space: nowrap;
}

.desk__table td {
  padding: 7px 12px;
  border-bottom: 1px solid #f0f3f5;
  color: var(--c-ink-2);
  vertical-align: middle;
  white-space: nowrap;
}

.desk__table td.num {
  font-variant-numeric: tabular-nums;
  color: var(--c-muted);
}

.desk__trow {
  cursor: pointer;
  transition: background-color var(--tr-fast);
}

.desk__trow:hover td {
  background: var(--c-bg-2);
}

.desk__trow.is-on td {
  background: var(--c-brand-soft);
}

.desk__trow:focus-visible {
  outline: none;
  box-shadow: var(--sh-ring);
}

.desk__trow-no {
  display: flex;
  align-items: center;
  gap: 6px;
}

.desk__trow-no b {
  font-family: var(--font-mono);
  font-size: var(--fs-2xs);
  color: var(--c-ink);
}

.desk__pager {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-2);
  font-size: var(--fs-2xs);
  color: var(--c-muted);
}

.desk__pager-acts {
  display: flex;
  gap: 4px;
}

/* ---------- 队列 ---------- */
.desk__queue-body {
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
  padding: 10px;
  min-height: 520px;
}

.desk__qtabs {
  display: flex;
  gap: 2px;
  padding: 3px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-md);
  background: var(--c-bg-2);
}

.desk__qtab {
  flex: 1;
  height: 24px;
  border-radius: var(--rd-sm);
  font-size: var(--fs-xs);
  font-weight: var(--fw-medium);
  color: var(--c-muted);
  transition: background-color var(--tr-fast), color var(--tr-fast);
}

.desk__qtab:hover {
  color: var(--c-ink-2);
  background: rgba(255, 255, 255, 0.8);
}

.desk__qtab.is-on {
  background: var(--c-card);
  color: var(--c-brand);
  font-weight: var(--fw-semibold);
  box-shadow: var(--sh-sm);
}

.desk__qtab:focus-visible {
  outline: none;
  box-shadow: var(--sh-ring);
}

.desk__queue-hint {
  font-size: var(--fs-2xs);
  color: var(--c-faint);
  line-height: var(--lh-base);
}

.desk__qitem {
  display: flex;
  gap: 8px;
  width: 100%;
  padding: 8px;
  border: 1px solid transparent;
  border-radius: var(--rd-md);
  background: transparent;
  text-align: left;
  transition: background-color var(--tr-fast), border-color var(--tr-fast);
}

.desk__qitem:hover {
  background: var(--c-bg-2);
}

.desk__qitem.is-on {
  border-color: var(--c-brand);
  background: var(--c-brand-soft);
}

.desk__qitem:focus-visible {
  outline: none;
  box-shadow: var(--sh-ring);
}

.desk__avatar {
  display: grid;
  place-items: center;
  flex: none;
  width: 30px;
  height: 30px;
  border-radius: 50%;
  font-size: var(--fs-sm);
  font-weight: var(--fw-semibold);
  background: var(--c-brand-soft);
  color: var(--c-brand);
}

.desk__avatar.is-r {
  background: var(--c-red-soft);
  color: var(--c-red);
}

.desk__avatar.is-a {
  background: var(--c-amber-soft);
  color: var(--c-amber);
}

.desk__avatar.is-g {
  background: #eef2f4;
  color: var(--c-muted);
}

.desk__qmain {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  flex: 1;
}

.desk__qrow {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 4px;
  font-size: var(--fs-sm);
  color: var(--c-ink);
}

.desk__qtopic,
.desk__qmeta {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  font-size: var(--fs-2xs);
  color: var(--c-muted);
}

.desk__qmeta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 4px;
}

.desk__qsum {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 4px;
  margin-top: auto;
  padding-top: 8px;
  border-top: 1px solid var(--c-line-2);
}

.desk__qsum div {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 1px;
}

.desk__qsum b {
  font-size: var(--fs-base);
  color: var(--c-ink);
}

.desk__qsum span {
  font-size: 9px;
  color: var(--c-faint);
}

/* P2：队列侧 ERP 工单数（只读接口） */
.desk__qerp {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
  font-size: var(--fs-2xs);
  color: var(--c-muted);
  line-height: var(--lh-base);
}

/* ---------- 会话 ---------- */
.desk__conv :deep(.w-card__head) {
  align-items: flex-start;
  flex-wrap: wrap;
}

.desk__conv-title {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  margin-right: auto;
}

.desk__conv-title b {
  font-size: var(--fs-base);
  color: var(--c-ink);
}

.desk__conv-title span {
  font-size: var(--fs-2xs);
  color: var(--c-faint);
}

.desk__conv-body {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 520px;
}

.desk__msgs {
  display: flex;
  flex-direction: column;
  gap: 8px;
  flex: 1;
  min-height: 0;
  max-height: 420px;
  overflow-y: auto;
  padding: 12px 14px;
}

.desk__sysline {
  display: flex;
  justify-content: center;
  font-size: var(--fs-2xs);
  color: var(--c-faint);
  text-align: center;
}

.desk__sysline span {
  padding: 3px 10px;
  border-radius: var(--rd-pill);
  background: var(--c-bg-2);
  border: 1px dashed var(--c-line);
}

.desk__sysline.is-ok span {
  border-style: solid;
  border-color: #bfe7db;
  background: var(--c-success-soft);
  color: #0a725f;
}

.desk__msg {
  display: flex;
  gap: 8px;
  align-items: flex-start;
}

.desk__msg.is-owner,
.desk__msg.is-agent {
  flex-direction: row-reverse;
}

.desk__msg-avatar {
  display: grid;
  place-items: center;
  flex: none;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: var(--c-ai-soft);
  color: var(--c-ai);
  font-size: 9px;
  font-weight: var(--fw-semibold);
}

.desk__msg.is-owner .desk__msg-avatar {
  background: var(--c-bg-2);
  color: var(--c-muted);
  border: 1px solid var(--c-line);
}

.desk__msg.is-agent .desk__msg-avatar {
  background: var(--c-brand-soft);
  color: var(--c-brand);
}

.desk__bubble {
  max-width: 78%;
  padding: 8px 11px;
  border-radius: var(--rd-lg);
  background: var(--c-bg-2);
  border: 1px solid var(--c-line-2);
  color: var(--c-ink-2);
  font-size: var(--fs-sm);
  line-height: var(--lh-base);
}

.desk__msg.is-ai .desk__bubble {
  background: #f7f5ff;
  border-color: #e9e5fd;
}

.desk__msg.is-agent .desk__bubble {
  background: var(--c-brand-soft);
  border-color: #cfe9e2;
}

.desk__msg-time {
  display: block;
  margin-top: 3px;
  font-size: 9px;
  color: var(--c-faint);
}

.desk__composer {
  flex: none;
  padding: 10px 14px 12px;
  border-top: 1px solid var(--c-line-2);
  background: var(--c-bg-2);
}

.desk__quick {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px;
  margin-bottom: 6px;
}

.desk__quick button {
  height: 21px;
  padding: 0 8px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-pill);
  background: var(--c-card);
  font-size: var(--fs-2xs);
  color: var(--c-muted);
  transition: border-color var(--tr-fast), color var(--tr-fast);
}

.desk__quick button:hover {
  border-color: var(--c-brand);
  color: var(--c-brand);
}

.desk__cbox {
  display: flex;
  align-items: center;
  gap: 6px;
}

.desk__cbox input {
  flex: 1;
  height: 32px;
  padding: 0 10px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-md);
  background: var(--c-card);
  font-size: var(--fs-sm);
  color: var(--c-ink);
}

.desk__cbox input:focus {
  outline: none;
  border-color: var(--c-brand);
  box-shadow: var(--sh-ring);
}

.desk__composer-note {
  margin-top: 6px;
  font-size: var(--fs-2xs);
  color: var(--c-faint);
}

/* ---------- 协同面板 ---------- */
.desk__ctx-body {
  display: flex;
  flex-direction: column;
  gap: 10px;
  max-height: 640px;
  overflow-y: auto;
  padding: 12px 14px;
}

.desk__block {
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--c-line-2);
}

.desk__block:last-child {
  border-bottom: 0;
  padding-bottom: 0;
}

.desk__block-h {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--sp-2);
  font-size: var(--fs-xs);
  font-weight: var(--fw-semibold);
  color: var(--c-ink-2);
}

.desk__block-h em {
  font-style: normal;
  font-size: var(--fs-2xs);
  font-weight: var(--fw-regular);
  color: var(--c-faint);
}

.desk__sumlist {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin: 0;
  padding-left: 14px;
  list-style: disc;
  font-size: var(--fs-xs);
  color: var(--c-muted);
  line-height: var(--lh-base);
}

.desk__kv {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  font-size: var(--fs-xs);
}

.desk__kv span {
  color: var(--c-faint);
  white-space: nowrap;
}

.desk__kv b {
  font-weight: var(--fw-medium);
  color: var(--c-ink-2);
  text-align: right;
}

.desk__kv b.is-warn {
  color: var(--c-amber);
}

.desk__emoji {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-xs);
  color: var(--c-ink-2);
}

.desk__emoji > span {
  flex: none;
}

.desk__bar {
  flex: 1;
  height: 6px;
  border-radius: var(--rd-pill);
  background: var(--c-line-2);
  overflow: hidden;
}

.desk__bar i {
  display: block;
  height: 100%;
  border-radius: var(--rd-pill);
  background: linear-gradient(90deg, #f59e0b, #dc2626);
}

.desk__risk {
  padding: 6px 8px;
  border-radius: var(--rd-sm);
  background: var(--c-amber-soft);
  color: #9a4b05;
  font-size: var(--fs-2xs);
  line-height: var(--lh-base);
}

.desk__acts {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}

.desk__degrade {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
  margin-top: 2px;
  padding: 6px 8px;
  border-radius: var(--rd-sm);
  background: var(--c-bg-2);
  border: 1px dashed var(--c-line);
  font-size: var(--fs-2xs);
  color: var(--c-muted);
  line-height: var(--lh-base);
}

.desk__qc-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 6px 8px;
  border: 1px solid var(--c-line-2);
  border-radius: var(--rd-sm);
  background: var(--c-bg-2);
  text-align: left;
}

.desk__qc-item.is-on {
  border-color: var(--c-brand);
  background: var(--c-brand-soft);
}

.desk__qc-item b {
  font-size: var(--fs-xs);
  color: var(--c-ink-2);
}

.desk__qc-item span {
  font-size: var(--fs-2xs);
  color: var(--c-muted);
  line-height: var(--lh-base);
}

/* ---------- 弹层 ---------- */
.desk__modal-rows {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.desk__modal-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--sp-3);
  padding: 7px 10px;
  border-radius: var(--rd-sm);
  background: var(--c-bg-2);
  font-size: var(--fs-xs);
}

.desk__modal-row span {
  flex: none;
  color: var(--c-faint);
}

.desk__modal-row b {
  font-weight: var(--fw-medium);
  color: var(--c-ink-2);
  text-align: right;
}

.desk__modal-note {
  font-size: var(--fs-2xs);
  color: var(--c-faint);
  line-height: var(--lh-base);
}

@media (max-width: 1279px) {
  .desk {
    grid-template-columns: 232px minmax(0, 1fr);
  }

  .desk__ctx {
    grid-column: 1 / -1;
  }
}
</style>
