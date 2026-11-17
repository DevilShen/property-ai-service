import { computed, ref, watch } from 'vue'
import type { IconName } from '@/types'

/**
 * P1-025 新增连接器 4 步向导 · 状态与校验（纯前端 mock）
 * ---------------------------------------------------------------
 * 步骤：① 接入方式 → ② 授权与连通 → ③ 能力探测 → ④ 映射与灰度。
 * 口径来源：`_design/02-架构设计.md` §4 连接器模型 / §6 能力探测 / §7 路由与灰度，
 * 18 项能力编码取自 §3.2 能力清单（CAP-01…CAP-18）。
 *
 * 边界（AGENTS.md）：不接真实 ERP，不做真实鉴权，凭据只留在内存且不落盘、不打印；
 * 连通与探测均为 mock 延时，页面必须标注「演示」。
 *
 * 状态是模块级单例（同一时刻只有一个向导实例）：视图 setup 时 reset()，
 * 子步骤与视图共享同一份状态，避免层层透传 props。
 */

export type WizardStepKey = 1 | 2 | 3 | 4
export type AccessMode = 'rest' | 'esb' | 'view' | 'file'
export type VendorKey = 'nc' | 'eas' | 'my' | 'self' | 'xw'
export type ProbeState = 'supported' | 'degraded' | 'unsupported'
export type ConnState = 'idle' | 'testing' | 'success' | 'failed'
export type MappingTemplate = 'standard' | 'custom'
export type GrayRatio = 10 | 30 | 50 | 100
export type FallbackChannel = 'erp' | 'wecom' | 'manual'

export interface WizardStepMeta {
  key: WizardStepKey
  label: string
  title: string
  note: string
}

export interface AccessOption {
  key: AccessMode
  label: string
  cycle: string
  realtime: string
  icon: IconName
  note: string
}

export interface VendorOption {
  key: VendorKey
  name: string
  logo: string
  tone: 'g' | 'k' | 'm' | 's' | 'n'
  form: string
  version: string
}

export interface CapabilitySpec {
  code: string
  name: string
  level: 'L1' | 'L2'
  p0: boolean
  /** 缺能力时的替代通道文案 */
  fallback: string
  /** 替代通道分类：跳转 ERP / 转企微 / 落人工池 */
  channel: FallbackChannel | null
}

export interface ProbeResult extends CapabilitySpec {
  state: ProbeState
  evidence: string
}

export interface ConnResult {
  latencyMs: number
  version: string
  auth: string
  tls: string
  limit: string
}

export interface ConnError {
  code: string
  message: string
  retryable: boolean
}

/* ------------------------------- 静态元数据 ------------------------------- */

export const WIZARD_STEPS: WizardStepMeta[] = [
  { key: 1, label: '接入方式', title: '① 选择接入方式', note: '决定能力上限与适配周期' },
  { key: 2, label: '授权与连通', title: '② 授权与连通测试', note: '先只读沙箱，写能力单独授权' },
  { key: 3, label: '能力探测', title: '③ 能力探测结果', note: '18 项能力三态 + 降级通道' },
  { key: 4, label: '映射与灰度', title: '④ 映射与灰度发布', note: '标准模板 + 灰度比例' },
]

export const ACCESS_OPTIONS: AccessOption[] = [
  {
    key: 'rest',
    label: 'REST / SOAP API',
    cycle: '5—10 天',
    realtime: '实时',
    icon: 'plug',
    note: '厂商开放接口，能力上限最高，推荐首选',
  },
  {
    key: 'esb',
    label: 'ESB 中间件',
    cycle: '1—3 周',
    realtime: '准实时',
    icon: 'workflow',
    note: '经集团 ESB 转发，依赖对接方排期',
  },
  {
    key: 'view',
    label: '只读视图 + API',
    cycle: '3—5 天',
    realtime: '准实时',
    icon: 'mapping',
    note: '查询走只读视图，写能力需另开接口',
  },
  {
    key: 'file',
    label: '文件 / RPA',
    cycle: '1—3 周',
    realtime: 'T+1 批量',
    icon: 'refresh',
    note: '老系统兜底形态，以对账批次为主',
  },
]

export const VENDOR_OPTIONS: VendorOption[] = [
  { key: 'nc', name: '用友 NC Cloud', logo: '用', tone: 'g', form: 'REST API · V6.5', version: 'NC Cloud V6.5.3' },
  { key: 'eas', name: '金蝶 EAS', logo: '金', tone: 'k', form: 'ESB 中间件 · V8.2', version: 'EAS V8.2 SP3' },
  { key: 'my', name: '明源云', logo: '明', tone: 'm', form: 'REST API · 天际 3.0', version: '天际 3.0.2' },
  { key: 'self', name: '自研 ERP', logo: '自', tone: 's', form: '视图 + API', version: '自研 ERP v2.1.0' },
  { key: 'xw', name: '新视窗', logo: '新', tone: 'n', form: '文件 / RPA', version: '新视窗 V5.2' },
]

/** 18 项标准能力（_design 02 §3.2 CAP-01…CAP-18） */
export const CAPABILITIES: CapabilitySpec[] = [
  { code: 'room.query', name: '房档查询', level: 'L1', p0: true, fallback: '只读视图兜底 + 人工核对', channel: 'manual' },
  { code: 'owner.verify', name: '业主身份核验', level: 'L1', p0: true, fallback: '改为人工核验 + 管家电话确认', channel: 'manual' },
  { code: 'bill.query', name: '账单查询', level: 'L1', p0: true, fallback: '只答已确认部分，其余转人工', channel: 'manual' },
  { code: 'pay.jump', name: '缴费跳转', level: 'L2', p0: true, fallback: '隐藏入口 + 引导前台/公众号缴费', channel: 'manual' },
  { code: 'invoice.apply', name: '开票申请', level: 'L2', p0: true, fallback: '跳转 ERP 自助开票页', channel: 'erp' },
  { code: 'ticket.create', name: '工单创建', level: 'L2', p0: true, fallback: '落 AI 工单池，人工补录 ERP', channel: 'manual' },
  { code: 'ticket.assign', name: '工单派单/改派', level: 'L2', p0: false, fallback: '转人工派单', channel: 'manual' },
  { code: 'ticket.status', name: '工单进度', level: 'L1', p0: true, fallback: '以短信/电话进度为准并转人工', channel: 'manual' },
  { code: 'ticket.close', name: '完工回写', level: 'L2', p0: false, fallback: '人工回写完工结论', channel: 'manual' },
  { code: 'complaint.create', name: '投诉单创建', level: 'L2', p0: false, fallback: '落 AI 侧投诉池，人工补录', channel: 'manual' },
  { code: 'notice.push', name: '通知推送', level: 'L2', p0: false, fallback: '改用企微群发 + 短信', channel: 'wecom' },
  { code: 'notice.receipt', name: '通知回执', level: 'L1', p0: false, fallback: '人工回执确认', channel: 'manual' },
  { code: 'visit.record', name: '回访记录', level: 'L2', p0: false, fallback: '导出 CSV，次日人工回填', channel: 'manual' },
  { code: 'dunning.task', name: '催缴任务', level: 'L2', p0: true, fallback: 'AI 生成话术，人工确认外呼', channel: 'manual' },
  { code: 'kb.search', name: '知识检索', level: 'L1', p0: false, fallback: '平台内实现，不依赖 ERP', channel: null },
  { code: 'visitor.auth', name: '访客授权', level: 'L2', p0: false, fallback: '引导前台登记放行', channel: 'manual' },
  { code: 'parking.auth', name: '停车授权', level: 'L2', p0: false, fallback: '引导前台办理月卡/临停', channel: 'manual' },
  { code: 'handoff.human', name: '转人工', level: 'L2', p0: false, fallback: '平台内实现，不依赖 ERP', channel: null },
]

export const GRAY_RATIO_LABELS: Record<GrayRatio, string> = {
  10: '10% 业主观察 2 周（推荐起点）',
  30: '30% 对比成功率与差异率',
  50: '50% 半量运行，准备好一键回滚',
  100: '100% 全量，旧连接器保留一键回滚',
}

export const FALLBACK_CHANNELS: { key: FallbackChannel; label: string; note: string }[] = [
  { key: 'erp', label: '跳转 ERP 自助页', note: '入口保留，AI 只给路径不代办' },
  { key: 'wecom', label: '改走企微 / 短信', note: '通知类能力换通道触达' },
  { key: 'manual', label: '落人工池 / 转人工', note: '缺口进人工待办，不让用户空等' },
]

/** 厂商基线差异（与 ERP 适配中心矩阵同口径；未列出的能力默认支持） */
const VENDOR_OVERRIDES: Record<VendorKey, Partial<Record<string, ProbeState>>> = {
  nc: { 'invoice.apply': 'degraded', 'notice.push': 'degraded' },
  eas: {
    'invoice.apply': 'degraded',
    'visit.record': 'degraded',
    'visitor.auth': 'unsupported',
    'parking.auth': 'unsupported',
  },
  my: { 'complaint.create': 'degraded', 'visit.record': 'degraded', 'parking.auth': 'unsupported' },
  self: {
    'invoice.apply': 'degraded',
    'notice.push': 'degraded',
    'visit.record': 'degraded',
    'dunning.task': 'degraded',
    'parking.auth': 'unsupported',
  },
  xw: {
    'owner.verify': 'degraded',
    'visit.record': 'degraded',
    'pay.jump': 'unsupported',
    'invoice.apply': 'unsupported',
    'ticket.assign': 'unsupported',
    'ticket.close': 'unsupported',
    'complaint.create': 'unsupported',
    'notice.push': 'unsupported',
    'notice.receipt': 'unsupported',
    'visitor.auth': 'unsupported',
    'parking.auth': 'unsupported',
  },
}

/** 接入形态对能力的二次收敛（只降不升） */
const ACCESS_OVERRIDES: Record<AccessMode, Partial<Record<string, ProbeState>>> = {
  rest: {},
  esb: { 'invoice.apply': 'degraded', 'notice.push': 'degraded' },
  view: {
    'invoice.apply': 'degraded',
    'ticket.create': 'degraded',
    'ticket.assign': 'degraded',
    'ticket.close': 'degraded',
    'complaint.create': 'degraded',
  },
  file: {
    'owner.verify': 'degraded',
    'ticket.create': 'degraded',
    'notice.push': 'degraded',
    'notice.receipt': 'degraded',
    'dunning.task': 'degraded',
    'pay.jump': 'unsupported',
    'invoice.apply': 'unsupported',
    'ticket.assign': 'unsupported',
    'ticket.close': 'unsupported',
    'complaint.create': 'unsupported',
    'visitor.auth': 'unsupported',
    'parking.auth': 'unsupported',
  },
}

export const PROBE_STATE_META: Record<ProbeState, { text: string; tone: 'g' | 'a' | 'n' }> = {
  supported: { text: '支持', tone: 'g' },
  degraded: { text: '降级', tone: 'a' },
  unsupported: { text: '不支持', tone: 'n' },
}

const STATE_RANK: Record<ProbeState, number> = { supported: 2, degraded: 1, unsupported: 0 }

/* --------------------------------- 状态 --------------------------------- */

const step = ref<WizardStepKey>(1)
const reached = ref<WizardStepKey>(1)

const accessMode = ref<AccessMode | ''>('')
const vendor = ref<VendorKey | ''>('')
const connectorName = ref('')

const baseUrl = ref('')
const account = ref('')
const secret = ref('')
const simulateFailure = ref(false)
const connState = ref<ConnState>('idle')
const connResult = ref<ConnResult | null>(null)
const connError = ref<ConnError | null>(null)
const connAttempts = ref(0)

const probeState = ref<'idle' | 'probing' | 'done'>('idle')
const probeResults = ref<ProbeResult[]>([])
const probeAt = ref('')

const mappingTemplate = ref<MappingTemplate | ''>('')
const grayProjects = ref<string[]>([])
const grayRatio = ref<GrayRatio | 0>(0)

const submitted = ref(false)
const draftId = ref('')

const timers: number[] = []

function schedule(ms: number, task: () => void) {
  const timer = window.setTimeout(task, ms)
  timers.push(timer)
}

function clearTimers() {
  timers.splice(0).forEach((timer) => window.clearTimeout(timer))
}

/* ------------------------------- 派生数据 ------------------------------- */

const accessOption = computed(() => ACCESS_OPTIONS.find((item) => item.key === accessMode.value) ?? null)
const vendorOption = computed(() => VENDOR_OPTIONS.find((item) => item.key === vendor.value) ?? null)
const vendorName = computed(() => vendorOption.value?.name ?? '待选厂商')
const accessName = computed(() => accessOption.value?.label ?? '待选形态')

const connectorDisplayName = computed(
  () => connectorName.value.trim() || `${vendorName.value} · 新连接器（演示）`,
)

/** 地址格式提示（空值不算错，只提示格式） */
const baseUrlError = computed(() => {
  const value = baseUrl.value.trim()
  if (value === '') return ''
  return /^https?:\/\/\S+$/.test(value) ? '' : '地址需以 https:// 或 http:// 开头'
})

const credentialsFilled = computed(
  () => baseUrl.value.trim() !== '' && account.value.trim() !== '' && secret.value !== '' && baseUrlError.value === '',
)

const counts = computed(() => {
  const base = { supported: 0, degraded: 0, unsupported: 0 }
  for (const item of probeResults.value) base[item.state] += 1
  return base
})

const degradedItems = computed(() => probeResults.value.filter((item) => item.state === 'degraded'))
const unsupportedItems = computed(() => probeResults.value.filter((item) => item.state === 'unsupported'))

const fallbackGroups = computed(() =>
  FALLBACK_CHANNELS.map((channel) => ({
    ...channel,
    items: [...degradedItems.value, ...unsupportedItems.value].filter((item) => item.channel === channel.key),
  })).filter((group) => group.items.length > 0),
)

const validity = computed<Record<WizardStepKey, boolean>>(() => ({
  1: accessMode.value !== '' && vendor.value !== '',
  2: connState.value === 'success' && credentialsFilled.value,
  3: probeState.value === 'done' && probeResults.value.length === CAPABILITIES.length,
  4: mappingTemplate.value !== '' && grayProjects.value.length > 0 && grayRatio.value !== 0,
}))

const stepValid = computed(() => validity.value[step.value])

function validityOf(key: WizardStepKey): boolean {
  return validity.value[key]
}

/** 允许跳转：回看任意已到步骤，前进需未到达且前面全部校验通过 */
function canGo(key: WizardStepKey): boolean {
  if (key <= step.value) return true
  if (key > reached.value) return false
  for (let cursor = 1 as WizardStepKey; cursor < key; cursor = (cursor + 1) as WizardStepKey) {
    if (!validity.value[cursor]) return false
  }
  return true
}

const isPristine = computed(
  () =>
    step.value === 1 &&
    accessMode.value === '' &&
    vendor.value === '' &&
    connectorName.value === '' &&
    baseUrl.value === '' &&
    account.value === '' &&
    secret.value === '' &&
    !simulateFailure.value &&
    connState.value === 'idle' &&
    probeState.value === 'idle' &&
    mappingTemplate.value === '' &&
    grayProjects.value.length === 0 &&
    grayRatio.value === 0,
)

const dirty = computed(() => !isPristine.value && !submitted.value)

const footerHint = computed(() => {
  if (step.value === 1) {
    return validity.value[1]
      ? `已选：${accessName.value} · ${vendorName.value}`
      : '必填：接入形态 + 厂商模板，未选不能进入下一步'
  }
  if (step.value === 2) {
    if (connState.value === 'success' && connResult.value) {
      return `已连通：${connResult.value.latencyMs}ms · ${connResult.value.version}`
    }
    if (connState.value === 'failed') return '连通失败：按错误码重试，或返回上一步更换接入方式'
    return '必填：网关地址 / 账号 / 密钥，且「测试连通」通过后才能继续'
  }
  if (step.value === 3) {
    if (probeState.value === 'probing') return '正在逐能力探测（mock 1.2s）…'
    if (probeState.value === 'done') {
      return `${counts.value.supported} 项支持 · ${counts.value.degraded} 项降级 · ${counts.value.unsupported} 项不支持（共 18 项）`
    }
    return '先完成能力探测才能进入映射与灰度'
  }
  if (validity.value[4]) return '信息齐全：可创建连接器草稿（演示，不落库）'
  return '必填：映射模板 + 至少一个灰度项目 + 灰度比例'
})

/* ------------------------------- 交互动作 ------------------------------- */

function enterStep(key: WizardStepKey) {
  step.value = key
  if (key > reached.value) reached.value = key
  if (key === 3 && probeState.value === 'idle') runProbe()
}

function goStep(key: WizardStepKey) {
  if (!canGo(key)) return
  enterStep(key)
}

function next() {
  if (!stepValid.value || step.value >= 4) return
  enterStep((step.value + 1) as WizardStepKey)
}

function prev() {
  if (step.value > 1) enterStep((step.value - 1) as WizardStepKey)
}

function selectAccessMode(mode: AccessMode) {
  accessMode.value = mode
}

function selectVendor(key: VendorKey) {
  vendor.value = key
}

function fillDemoCredentials() {
  baseUrl.value = 'https://erp.example.com/openapi/v5'
  account.value = 'ai-service@yunqi'
  secret.value = 'demo-only-not-stored'
}

function resetConnection() {
  connState.value = 'idle'
  connResult.value = null
  connError.value = null
  connAttempts.value = 0
  probeState.value = 'idle'
  probeResults.value = []
  probeAt.value = ''
}

function testConnection() {
  if (connState.value === 'testing' || !credentialsFilled.value) return
  clearTimers()
  connAttempts.value += 1
  const attempt = connAttempts.value
  const option = vendorOption.value
  connState.value = 'testing'
  connError.value = null
  schedule(900, () => {
    // 演示开关：首次注入一次 E-4001，重试即成功，用于展示失败态与重试
    if (simulateFailure.value && attempt === 1) {
      connState.value = 'failed'
      connError.value = {
        code: 'E-4001',
        message: '上游超时：1200ms 未收到响应（沙箱代理 mock）',
        retryable: true,
      }
      return
    }
    connState.value = 'success'
    connResult.value = {
      latencyMs: simulateFailure.value ? 1240 : 620,
      version: option?.version ?? 'Unknown V0.0',
      auth: 'OAuth2 Client Credentials',
      tls: 'TLS 1.3 · 沙箱环境',
      limit: '100 QPS / 项目级 · 超时 800ms',
    }
    probeState.value = 'idle'
    probeResults.value = []
  })
}

function resolveProbeState(code: string, vendorKey: VendorKey, mode: AccessMode): ProbeState {
  let state: ProbeState = 'supported'
  const vendorState = VENDOR_OVERRIDES[vendorKey][code]
  if (vendorState && STATE_RANK[vendorState] < STATE_RANK[state]) state = vendorState
  const modeState = ACCESS_OVERRIDES[mode][code]
  if (modeState && STATE_RANK[modeState] < STATE_RANK[state]) state = modeState
  return state
}

function buildProbeResults(vendorKey: VendorKey, mode: AccessMode): ProbeResult[] {
  return CAPABILITIES.map((cap) => {
    const state = resolveProbeState(cap.code, vendorKey, mode)
    const evidence =
      state === 'supported'
        ? '只读合成请求 3/3 通过 · 字段齐全'
        : state === 'degraded'
          ? `能力受限：${cap.fallback}`
          : `未探测到可用接口：${cap.fallback}`
    return { ...cap, state, evidence }
  })
}

function runProbe() {
  if (probeState.value === 'probing') return
  clearTimers()
  probeState.value = 'probing'
  probeResults.value = []
  const vendorKey = (vendor.value || 'nc') as VendorKey
  const mode = (accessMode.value || 'rest') as AccessMode
  schedule(1200, () => {
    probeResults.value = buildProbeResults(vendorKey, mode)
    probeState.value = 'done'
    probeAt.value = '刚刚 · 演示探测'
  })
}

function selectMappingTemplate(template: MappingTemplate) {
  mappingTemplate.value = template
}

function toggleGrayProject(id: string) {
  grayProjects.value = grayProjects.value.includes(id)
    ? grayProjects.value.filter((item) => item !== id)
    : [...grayProjects.value, id]
}

function selectGrayRatio(ratio: GrayRatio) {
  grayRatio.value = ratio
}

/** 提交草稿：返回草稿号（视图负责 Toast + 跳回 /erp），校验不通过返回空串 */
function submitDraft(): string {
  if (!validity.value[4] || submitted.value) return ''
  const nextId = `CFG-2026-${String(118 + grayProjects.value.length * 7).padStart(3, '0')}`
  draftId.value = nextId
  submitted.value = true
  return nextId
}

function reset() {
  clearTimers()
  step.value = 1
  reached.value = 1
  accessMode.value = ''
  vendor.value = ''
  connectorName.value = ''
  baseUrl.value = ''
  account.value = ''
  secret.value = ''
  simulateFailure.value = false
  connState.value = 'idle'
  connResult.value = null
  connError.value = null
  connAttempts.value = 0
  probeState.value = 'idle'
  probeResults.value = []
  probeAt.value = ''
  mappingTemplate.value = ''
  grayProjects.value = []
  grayRatio.value = 0
  submitted.value = false
  draftId.value = ''
}

// 凭据或厂商/形态变化 → 之前的连通与探测结论作废（不保留"假支持"）
watch([baseUrl, account, secret], () => {
  clearTimers()
  resetConnection()
})

watch([accessMode, vendor], () => {
  clearTimers()
  resetConnection()
})

watch(simulateFailure, () => {
  if (connState.value !== 'success') {
    clearTimers()
    connState.value = 'idle'
    connResult.value = null
    connError.value = null
  }
  connAttempts.value = 0
})

/* --------------------------------- 出口 --------------------------------- */

export function useConnectorWizard() {
  return {
    // 状态
    step,
    reached,
    accessMode,
    vendor,
    connectorName,
    baseUrl,
    account,
    secret,
    simulateFailure,
    connState,
    connResult,
    connError,
    probeState,
    probeResults,
    probeAt,
    mappingTemplate,
    grayProjects,
    grayRatio,
    submitted,
    draftId,
    // 派生
    accessOption,
    vendorOption,
    vendorName,
    accessName,
    connectorDisplayName,
    baseUrlError,
    credentialsFilled,
    counts,
    degradedItems,
    unsupportedItems,
    fallbackGroups,
    validity,
    stepValid,
    validityOf,
    canGo,
    dirty,
    footerHint,
    // 动作
    goStep,
    next,
    prev,
    selectAccessMode,
    selectVendor,
    fillDemoCredentials,
    testConnection,
    runProbe,
    selectMappingTemplate,
    toggleGrayProject,
    selectGrayRatio,
    submitDraft,
    reset,
    clearTimers,
  }
}
