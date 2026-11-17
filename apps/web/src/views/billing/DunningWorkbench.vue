<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { WButton, WCard, WEmpty, WLoading, WModal, WTable, WTag, useToast } from '@/components'
import type { TableColumn } from '@/types'
import { actionSeed, useIdempotencyKey } from '@/composables/useIdempotencyKey'
import {
  ARREARS_BUCKETS,
  ARREARS_BUCKET_LABELS,
  DUNNING_CHANNELS,
  DUNNING_CHANNEL_LABELS,
  DUNNING_FREQUENCY_CALL_LIMIT,
  DUNNING_FREQUENCY_WINDOW_DAYS,
  DUNNING_STATUSES,
  DUNNING_STATUS_LABELS,
  DUNNING_TONES,
  api,
  ApiError,
  degradedText,
  errorNextStep,
  errorText,
  forbiddenDetailsOf,
  formatIsoMinute,
  formatMoney,
} from '@/api'
import type {
  ArrearsBucket,
  DegradedInfo,
  DunningChannel,
  DunningCloseResult,
  DunningFrequencyCheck,
  DunningScript,
  DunningSendResult,
  DunningStatus,
  DunningTask,
  DunningTone,
} from '@/api'

/**
 * P3 W11 催缴工作台（P3-016/017/018；契约 v1.1.0 §12.2）
 * ===========================================================================
 * 数据口径（唯一事实源 `docs/openapi/openapi.yaml`，金额/户数全部来自服务端）：
 * - 列表：`GET /dunning/tasks?status&bucket&assignee&page&pageSize`（行内含 `frequencyCheck`）；
 * - 详情：`GET /dunning/tasks/{taskId}`（频次红线由服务端实时计算，前端只展示、不自行判定放行）；
 * - 话术：`POST .../script`（tone=friendly|firm → `content`/`forbiddenHits`/`version`；**生成不等于外发**）；
 * - 发送：`POST .../send`（必须 `confirmed: true`；P3 只经 `services/mock-channel` 受理，`status=mocked`，
 *   **不接真实企微/短信/语音外呼，不得说成「已送达业主」**——AGENTS.md NEVER #4/#7）；
 * - 关闭：`POST .../close`（reason 必填；只改任务状态，不写财务数据）。
 *
 * 红线（前端可解释，服务端仍强制校验——隐藏入口不是安全边界）：
 * - `forbiddenHits` 非空 → 禁用发送并说明「命中禁区不可发送」；
 * - `frequencyCheck.allowed=false` → 禁用发送并说明窗口/次数；若服务端仍返回 409 `E-3003`（并发/状态漂移），
 *   页面按 `details{windowDays,used,limit}` 展示频次红线人话；
 * - 409 `E-3002`（`stale_script`/`forbidden_hits`/`already_closed`）与 503 通道故障均给替代路径，
 *   **失败不伪造成功回执**。
 */
const toast = useToast()
const idempotency = useIdempotencyKey()

/** 服务端分页页大小（契约 pageSize 上限 100） */
const PAGE_SIZE = 10

/* ------------------------------------------------------------ 展示映射（非契约枚举） */

/** 催缴状态色调（文案取 shared `DUNNING_STATUS_LABELS`，不另写一套） */
const STATUS_TONE: Readonly<Record<DunningStatus, 'g' | 'b' | 'a' | 'r' | 'v' | 'n'>> = {
  pending: 'n',
  script_ready: 'b',
  sent: 'a',
  responded: 'b',
  promised: 'v',
  paid: 'g',
  closed: 'n',
}
/** 逾期分层色调（与费用台账一致：D1 最轻 → D30 最重） */
const BUCKET_TONE: Readonly<Record<ArrearsBucket, 'n' | 'b' | 'a' | 'r'>> = {
  D1: 'n',
  D7: 'b',
  D15: 'a',
  D30: 'r',
}
/** 话术语气展示文案（契约枚举 `DunningTone`；shared 未提供文案，这里只做展示映射） */
const TONE_LABEL: Readonly<Record<DunningTone, string>> = {
  friendly: '温和提醒',
  firm: '明确期限',
}

/* ---------------------------------------------------------------- 列表与筛选 */

const tasks = ref<DunningTask[]>([])
const total = ref(0)
const page = ref(1)
const listLoading = ref(false)
const listError = ref('')
const listDegraded = ref<DegradedInfo | undefined>(undefined)
/** 403（无 `dunning.task` 能力，如 admin）：整块渲染无权限态且不造数 */
const forbidden = ref<{ requiredCapability: string; role: string; traceId: string } | null>(null)

const statusFilter = ref<'' | DunningStatus>('')
const bucketFilter = ref<'' | ArrearsBucket>('')
const assigneeFilter = ref('')
/** 负责人候选（来自已加载任务，仅做筛选下拉提示；筛选由服务端精确匹配） */
const assigneeOptions = ref<string[]>([])

const pageCount = computed(() => Math.max(1, Math.ceil(total.value / PAGE_SIZE)))
const rows = computed<Record<string, unknown>[]>(() =>
  tasks.value.map((task) => ({ ...task }) as unknown as Record<string, unknown>),
)
const statNote = computed(
  () =>
    `当前筛选 ${total.value} 条任务 · 第 ${page.value}/${pageCount.value} 页` +
    `（数据来自 /api/v1/dunning/tasks${listDegraded.value ? '；已降级' : ''}）`,
)

const columns: TableColumn[] = [
  { key: 'room', title: '房号 / 业主（掩码）', width: '156px' },
  { key: 'period', title: '账期', width: '78px' },
  { key: 'outstanding', title: '欠费', width: '90px', align: 'right' },
  { key: 'bucket', title: '分层', width: '112px' },
  { key: 'status', title: '状态', width: '112px' },
  { key: 'assignee', title: '负责人', width: '84px' },
  { key: 'frequency', title: '频次（used/limit · 天）', width: '140px' },
  { key: 'action', title: '操作', width: '70px' },
]

function taskOf(row: unknown): DunningTask {
  return row as DunningTask
}

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

function freqText(check?: DunningFrequencyCheck | null): string {
  if (!check) return '—'
  return `${check.used}/${check.limit} · ${check.windowDays} 天`
}

function collectAssignees(items: DunningTask[]): void {
  const set = new Set(assigneeOptions.value)
  for (const item of items) {
    if (item.assignedTo) set.add(item.assignedTo)
  }
  assigneeOptions.value = [...set]
}

async function loadTasks(): Promise<void> {
  listLoading.value = true
  listError.value = ''
  try {
    const { data, degraded } = await api.dunning.tasks({
      status: statusFilter.value || undefined,
      bucket: bucketFilter.value || undefined,
      assignee: assigneeFilter.value.trim() || undefined,
      page: page.value,
      pageSize: PAGE_SIZE,
    })
    tasks.value = data.items
    total.value = data.total
    listDegraded.value = degraded
    forbidden.value = null
    collectAssignees(data.items)
    ensureSelection(data.items)
  } catch (error) {
    tasks.value = []
    total.value = 0
    const denied = forbiddenDetailsOf(error)
    if (denied) {
      deniedFrom(error, denied.requiredCapability, denied.role)
      return
    }
    listError.value = `${errorText(codeOf(error))}；${errorNextStep(codeOf(error))}`
    listDegraded.value = degradedOf(error)
  } finally {
    listLoading.value = false
  }
}

function deniedFrom(error: unknown, requiredCapability: string, role: string): void {
  forbidden.value = {
    requiredCapability: requiredCapability || 'dunning.task',
    role: role || '',
    traceId: error instanceof ApiError ? error.traceId : '',
  }
}

function applyQuery(): void {
  page.value = 1
  void loadTasks()
}

function resetQuery(): void {
  statusFilter.value = ''
  bucketFilter.value = ''
  assigneeFilter.value = ''
  page.value = 1
  void loadTasks()
  toast.info('筛选已重置：回到全部催缴任务')
}

function goPage(next: number): void {
  const target = Math.min(Math.max(next, 1), pageCount.value)
  if (target === page.value) return
  page.value = target
  void loadTasks()
}

/* --------------------------------------------------------- 详情（/tasks/{id}） */

const selectedId = ref('')
const detail = ref<DunningTask | null>(null)
const detailLoading = ref(false)
const detailError = ref('')
const detailDegraded = ref<DegradedInfo | undefined>(undefined)
let detailSeq = 0

const selectedTask = computed<DunningTask | null>(
  () => detail.value ?? tasks.value.find((item) => item.taskId === selectedId.value) ?? null,
)

/** 频次红线：优先用服务端 409 回执的窗口数据（状态漂移时以服务端为准） */
const redline = ref<DunningFrequencyCheck | null>(null)
const frequency = computed<DunningFrequencyCheck | null>(
  () => redline.value ?? selectedTask.value?.frequencyCheck ?? null,
)

async function loadDetail(taskId: string): Promise<void> {
  const seq = ++detailSeq
  detailLoading.value = true
  detailError.value = ''
  try {
    const { data, degraded } = await api.dunning.task(taskId)
    if (seq !== detailSeq) return
    detail.value = data
    detailDegraded.value = degraded
  } catch (error) {
    if (seq !== detailSeq) return
    detail.value = null
    const denied = forbiddenDetailsOf(error)
    if (denied) {
      deniedFrom(error, denied.requiredCapability, denied.role)
      return
    }
    detailError.value = `${errorText(codeOf(error))}；${errorNextStep(codeOf(error))}`
    detailDegraded.value = degradedOf(error)
  } finally {
    if (seq === detailSeq) detailLoading.value = false
  }
}

function selectTask(taskId: string): void {
  selectedId.value = taskId
  detail.value = null
  detailError.value = ''
  detailDegraded.value = undefined
  script.value = null
  scriptError.value = ''
  receipt.value = null
  redline.value = null
  sendError.value = ''
  sendErrorKind.value = ''
  staleScript.value = false
  closeResult.value = null
  closeError.value = ''
  scriptChannel.value = tasks.value.find((item) => item.taskId === taskId)?.channel ?? 'wecom'
  void loadDetail(taskId)
}

function ensureSelection(items: DunningTask[]): void {
  if (!items.length) {
    selectedId.value = ''
    detail.value = null
    script.value = null
    receipt.value = null
    redline.value = null
    return
  }
  if (items.some((item) => item.taskId === selectedId.value)) return
  selectTask(items[0]!.taskId)
}

/* -------------------------------------------------- 话术（生成 ≠ 外发 + 禁区扫描） */

const tone = ref<DunningTone>('friendly')
const scriptChannel = ref<DunningChannel>('wecom')
const script = ref<DunningScript | null>(null)
const scriptLoading = ref(false)
const scriptError = ref('')
/** 话术版本过期（服务端 409 stale_script）后必须重新生成才能发送 */
const staleScript = ref(false)

const scriptForbidden = computed<string[]>(() => script.value?.forbiddenHits ?? [])

async function generateScript(): Promise<void> {
  const task = selectedTask.value
  if (!task) return
  scriptLoading.value = true
  scriptError.value = ''
  try {
    const { data } = await api.dunning.script(task.taskId, { tone: tone.value, channel: scriptChannel.value })
    script.value = data
    scriptChannel.value = data.channel
    staleScript.value = false
    receipt.value = null
    if (data.forbiddenHits.length) {
      toast.warning(`话术命中禁区规则 ${data.forbiddenHits.join('、')}：按红线不得发送，请调整话术后重新生成或转人工`)
    } else {
      toast.success(
        `话术已生成 ${data.version}（${TONE_LABEL[data.tone]}）· 生成不等于外发，需人工确认目标户与内容后才可发送`,
      )
    }
    await loadDetail(task.taskId)
    await loadTasks()
  } catch (error) {
    const code = codeOf(error)
    const reason = error instanceof ApiError && typeof error.details.reason === 'string' ? error.details.reason : ''
    if (code === 'E-3002' && reason === 'already_closed') {
      scriptError.value = '任务已关闭/已缴费，按契约不能再生成话术（服务端 409）；可查看历史或转人工'
    } else {
      scriptError.value = `${errorText(code)}；${errorNextStep(code)}`
    }
  } finally {
    scriptLoading.value = false
  }
}

/* ------------------------------------------- 人工确认发送（confirmed: true） */

const sendOpen = ref(false)
const sendLoading = ref(false)
const sendError = ref('')
const sendErrorKind = ref<'' | 'frequency' | 'stale' | 'forbidden' | 'channel' | 'confirm' | 'other'>('')
const receipt = ref<DunningSendResult | null>(null)

const sendBlockReason = computed<string>(() => {
  const task = selectedTask.value
  if (!task) return '先选择一条催缴任务'
  if (task.status === 'closed' || task.status === 'paid') return '任务已关闭/已缴费，不再发送'
  if (!script.value) return '请先生成话术（生成不等于外发）'
  if (scriptForbidden.value.length) return '话术命中禁区，按红线不得发送'
  if (staleScript.value) return '话术版本已过期，请重新生成后再发送'
  const check = frequency.value
  if (check && !check.allowed) {
    return `频次红线：窗口 ${check.windowDays} 天内已触达 ${check.used}/${check.limit} 次，本次发送被拦截`
  }
  return ''
})
const canSend = computed(() => sendBlockReason.value === '')

function openSend(): void {
  if (!canSend.value) {
    toast.warning(sendBlockReason.value || '当前不可发送')
    return
  }
  sendError.value = ''
  sendErrorKind.value = ''
  sendOpen.value = true
}

async function confirmSend(): Promise<void> {
  const task = selectedTask.value
  const current = script.value
  if (!task || !current) return
  sendLoading.value = true
  sendError.value = ''
  sendErrorKind.value = ''
  try {
    const key = idempotency.ensure(
      actionSeed('dunning.send', { taskId: task.taskId, version: current.version, channel: scriptChannel.value }),
    )
    const { data } = await api.dunning.send(
      task.taskId,
      { scriptVersion: current.version, channel: scriptChannel.value, confirmed: true },
      key,
    )
    idempotency.settle()
    receipt.value = data
    redline.value = data.frequencyAfter
    sendOpen.value = false
    toast.success(`已由 mock 通道受理（未真实外发）：pushId ${data.pushId} · status=${data.status}`)
    await loadDetail(task.taskId)
    await loadTasks()
  } catch (error) {
    handleSendError(error)
    sendOpen.value = false
  } finally {
    sendLoading.value = false
  }
}

/** 发送失败：按契约 details 给替代路径；通道故障**不伪造成功回执** */
function handleSendError(error: unknown): void {
  const apiError = error instanceof ApiError ? error : null
  const code = apiError?.code ?? 'E-9001'
  const details = apiError?.details ?? {}
  const reason = typeof details.reason === 'string' ? details.reason : ''
  receipt.value = null
  if (code === 'E-3003' && reason === 'frequency_redline') {
    const used = typeof details.used === 'number' ? details.used : null
    const limit = typeof details.limit === 'number' ? details.limit : DUNNING_FREQUENCY_CALL_LIMIT
    const windowDays = typeof details.windowDays === 'number' ? details.windowDays : DUNNING_FREQUENCY_WINDOW_DAYS
    redline.value = { windowDays, used: used ?? limit, limit, allowed: false }
    sendErrorKind.value = 'frequency'
    sendError.value =
      `该户 ${windowDays} 天内已触达 ${used ?? limit}/${limit} 次，本次发送被频次红线拦截（服务端 409）。` +
      '请改日再联系、改走线下沟通，或由主管确认后处理；页面不重试、不伪造成功。'
    toast.warning('频次红线拦截：本户本窗口触达已达上限，本次未发送')
    return
  }
  if (code === 'E-3002' && reason === 'stale_script') {
    staleScript.value = true
    sendErrorKind.value = 'stale'
    sendError.value = '话术版本已过期（服务端 409 stale_script）：请重新生成话术后再发送，避免发送旧口径内容'
    toast.warning('话术版本已过期：请重新生成话术后再发送')
    return
  }
  if (code === 'E-3002' && reason === 'forbidden_hits') {
    sendErrorKind.value = 'forbidden'
    sendError.value = '话术命中禁区规则，按红线不得发送（服务端复核 409）；请修改话术或转人工'
    return
  }
  if (code === 'E-3002' && reason === 'already_closed') {
    sendErrorKind.value = 'other'
    sendError.value = '任务已关闭/已缴费，不能再发送（服务端 409）；只读查看即可'
    return
  }
  if (code === 'E-1001' && reason === 'confirmation_required') {
    sendErrorKind.value = 'confirm'
    sendError.value = '缺少人工确认（confirmed: true），本次未发送；请重新打开确认弹层核对内容与目标户'
    return
  }
  // 通道故障（mock-channel 超时/上游错误）→ 503 + degraded：不伪造成功，给降级/人工待办路径
  sendErrorKind.value = 'channel'
  const degraded = apiError?.degraded
  sendError.value =
    `发送未成功（单户通道不可用）：${errorText(code)}；${errorNextStep(code)}。` +
    '不记为已触达、不生成 mock 回执，请稍后重试或转人工待办。' +
    (degraded ? ` ${degradedText(degraded)}` : '')
}

/* ------------------------------------------------------------- 关闭任务 */

const CLOSE_REASONS: readonly { value: string; key: string }[] = [
  { value: '业主已缴费（实收以 ERP 为准）', key: 'paid' },
  { value: '已转人工线下跟进', key: 'human' },
  { value: '业主对金额有争议，待管家核实', key: 'dispute' },
  { value: '无需继续催缴（演示终止）', key: 'stop' },
]
const closeOpen = ref(false)
const closeKey = ref<string>(CLOSE_REASONS[0]!.key)
const closeDetail = ref('')
const closeLoading = ref(false)
const closeError = ref('')
const closeResult = ref<DunningCloseResult | null>(null)

const closeReasonText = computed(() => {
  const base = CLOSE_REASONS.find((item) => item.key === closeKey.value)?.value ?? CLOSE_REASONS[0]!.value
  const extra = closeDetail.value.trim()
  return extra ? `${base}；备注：${extra}` : base
})

function openClose(): void {
  if (!selectedTask.value) return
  closeError.value = ''
  closeOpen.value = true
}

async function confirmClose(): Promise<void> {
  const task = selectedTask.value
  if (!task) return
  closeLoading.value = true
  closeError.value = ''
  try {
    const { data } = await api.dunning.close(task.taskId, { reason: closeReasonText.value })
    closeResult.value = data
    closeOpen.value = false
    toast.success(`任务已关闭：${data.taskId} · status=${data.status}（只改任务状态，不写财务数据）`)
    await loadDetail(task.taskId)
    await loadTasks()
  } catch (error) {
    const code = codeOf(error)
    const details = error instanceof ApiError ? error.details : {}
    if (code === 'E-3002' && details.reason === 'already_closed') {
      closeError.value = '任务已关闭/已缴费，不能重复关闭（服务端 409）'
    } else {
      closeError.value = `${errorText(code)}；${errorNextStep(code)}`
    }
  } finally {
    closeLoading.value = false
  }
}

/* --------------------------------------------------------------- 生命周期 */

onMounted(() => {
  void loadTasks()
})
</script>

<template>
  <section class="dun" data-testid="dunning-workbench">
    <!-- ① 无权限（403 无 dunning.task，如 admin）：不渲染任何任务/金额 -->
    <div v-if="forbidden" class="dun__state" data-testid="dunning-forbidden">
      <WEmpty
        icon="shield"
        title="当前角色没有催缴任务权限"
        :description="`催缴工作台需要能力 ${forbidden.requiredCapability}（当前演示角色「${forbidden.role || '未知'}」不具备，后端 403 E-2001）。本页不读取、不渲染任何催缴任务与业主金额；请切换管家 / 坐席 / 主管演示账号后重试。`"
      >
        <WButton size="sm" @click="loadTasks">重新校验一次</WButton>
      </WEmpty>
    </div>

    <template v-else>
      <!-- 数据来源 / 演示边界标注（通道未接入，不得声称真实外发） -->
      <div class="dun__metabar">
        <span data-testid="dunning-stat-note">{{ statNote }}</span>
        <WTag tone="a" plain data-testid="dunning-mock-note">通道未接入（演示）：发送仅由 mock-channel 受理留痕</WTag>
        <WTag v-if="listDegraded" tone="r" plain data-testid="dunning-degraded-tag">已降级</WTag>
      </div>
      <p v-if="listDegraded" class="dun__degradebar" data-testid="dunning-degraded">
        {{ degradedText(listDegraded) }}
      </p>

      <div class="dun__grid">
        <!-- ② 任务列表：status / bucket / assignee 筛选 + 服务端分页 -->
        <WCard class="dun__list" :padded="false">
          <template #head>
            <span class="dun__card-title">催缴任务</span>
            <span class="dun__card-sub">按欠费分层生成；行内展示频次红线校验结果（单户窗口内已触达/上限）</span>
          </template>
          <template #extra>
            <WTag tone="n" plain>共 {{ total }} 条</WTag>
          </template>

          <div class="dun__toolbar">
            <label class="dun__field">
              <span>状态</span>
              <select v-model="statusFilter" class="dun__input" data-testid="dunning-filter-status" @change="applyQuery">
                <option value="">全部状态</option>
                <option v-for="status in DUNNING_STATUSES" :key="status" :value="status">
                  {{ DUNNING_STATUS_LABELS[status] }}
                </option>
              </select>
            </label>
            <label class="dun__field">
              <span>分层</span>
              <select v-model="bucketFilter" class="dun__input" data-testid="dunning-filter-bucket" @change="applyQuery">
                <option value="">全部分层</option>
                <option v-for="bucket in ARREARS_BUCKETS" :key="bucket" :value="bucket">
                  {{ ARREARS_BUCKET_LABELS[bucket] }}
                </option>
              </select>
            </label>
            <label class="dun__field">
              <span>负责人</span>
              <select v-model="assigneeFilter" class="dun__input" data-testid="dunning-filter-assignee" @change="applyQuery">
                <option value="">不限</option>
                <option v-for="name in assigneeOptions" :key="name" :value="name">{{ name }}</option>
              </select>
            </label>
            <WButton size="sm" variant="primary" data-testid="dunning-query" @click="applyQuery">查询</WButton>
            <WButton size="sm" variant="ghost" data-testid="dunning-reset" @click="resetQuery">重置</WButton>
          </div>

          <div v-if="listError" class="dun__state" data-testid="dunning-error">
            <WEmpty icon="list-check" title="催缴任务暂时无法加载" :description="listError">
              <WButton size="sm" @click="loadTasks">重新加载</WButton>
            </WEmpty>
          </div>
          <WTable
            v-else
            :columns="columns"
            :rows="rows"
            dense
            row-key="taskId"
            :loading="listLoading"
            empty-text="该筛选下没有催缴任务；调整状态 / 分层 / 负责人后重试。"
            data-testid="dunning-list"
          >
            <template #empty>
              <WEmpty
                icon="list-check"
                title="该筛选下没有催缴任务"
                description="调整状态 / 分层 / 负责人筛选，或点「重置」查看全部；任务由欠费分层策略生成，无数据时不造数。"
                compact
              />
            </template>
            <template #room="{ row }">
              <div class="dun__brow" data-testid="dunning-row">
                <b>{{ taskOf(row).roomCode }}</b>
                <span>业主 {{ taskOf(row).ownerNameMasked }} · {{ taskOf(row).taskId }}</span>
              </div>
            </template>
            <template #period="{ row }">
              <span class="num">{{ taskOf(row).period }}</span>
            </template>
            <template #outstanding="{ row }">
              <span class="num dun__amount">{{ money(taskOf(row).outstanding) }}</span>
            </template>
            <template #bucket="{ row }">
              <WTag :tone="BUCKET_TONE[taskOf(row).bucket]">{{ ARREARS_BUCKET_LABELS[taskOf(row).bucket] }}</WTag>
            </template>
            <template #status="{ row }">
              <WTag :tone="STATUS_TONE[taskOf(row).status]" plain>{{ DUNNING_STATUS_LABELS[taskOf(row).status] }}</WTag>
            </template>
            <template #assignee="{ row }">
              <span>{{ taskOf(row).assignedTo || '—' }}</span>
            </template>
            <template #frequency="{ row }">
              <span class="num" data-testid="dunning-row-frequency">
                {{ freqText(taskOf(row).frequencyCheck) }}
                <WTag :tone="taskOf(row).frequencyCheck.allowed ? 'g' : 'r'" plain>
                  {{ taskOf(row).frequencyCheck.allowed ? '可发送' : '已达上限' }}
                </WTag>
              </span>
            </template>
            <template #action="{ row }">
              <WButton
                size="sm"
                variant="ghost"
                :data-testid="`dunning-open-${taskOf(row).taskId}`"
                @click="selectTask(taskOf(row).taskId)"
              >
                详情
              </WButton>
            </template>
            <template #footer>
              <div class="dun__foot">
                <span>任务与业主姓名均为服务端返回的掩码口径（不返回手机号/身份证）。</span>
                <span class="dun__foot-note">催缴触达只能单户、人工确认后经 mock 通道受理，不群发、不做 AI 语音外呼。</span>
              </div>
            </template>
          </WTable>

          <div class="dun__pager">
            <WButton size="sm" variant="ghost" :disabled="page <= 1" data-testid="dunning-prev" @click="goPage(page - 1)">
              上一页
            </WButton>
            <span class="num" data-testid="dunning-pager-info">
              第 {{ page }} / {{ pageCount }} 页 · 共 {{ total }} 条
            </span>
            <WButton
              size="sm"
              variant="ghost"
              :disabled="page >= pageCount"
              data-testid="dunning-next"
              @click="goPage(page + 1)"
            >
              下一页
            </WButton>
          </div>
        </WCard>

        <div class="dun__side">
          <!-- ③ 详情 + 频次红线（服务端实时校验，前端只展示） -->
          <WCard title="任务详情" subtitle="房号 / 账期 / 欠费 / 分层 / 负责人 / 频次红线">
            <template #extra>
              <WTag v-if="detailDegraded" tone="r" plain>已降级</WTag>
            </template>
            <div data-testid="dunning-detail">
              <WLoading v-if="detailLoading" label="正在读取任务详情…" />
              <div v-else-if="detailError" class="dun__state" data-testid="dunning-detail-error">
                <WEmpty icon="list-check" title="任务详情暂时无法加载" :description="detailError" compact>
                  <WButton v-if="selectedId" size="sm" @click="loadDetail(selectedId)">重新加载</WButton>
                </WEmpty>
              </div>
              <template v-else-if="selectedTask">
                <div class="dun__detailhead">
                  <div>
                    <b class="num" data-testid="dunning-detail-room">{{ selectedTask.roomCode }}</b>
                    <span>业主 {{ selectedTask.ownerNameMasked }} · {{ selectedTask.period }}</span>
                  </div>
                  <WTag :tone="STATUS_TONE[selectedTask.status]" data-testid="dunning-detail-status">
                    {{ DUNNING_STATUS_LABELS[selectedTask.status] }}
                  </WTag>
                </div>
                <div class="dun__tiles">
                  <div>
                    <b class="num" data-testid="dunning-detail-outstanding">{{ money(selectedTask.outstanding) }}</b>
                    <span>欠费（ERP 口径）</span>
                  </div>
                  <div>
                    <b>{{ ARREARS_BUCKET_LABELS[selectedTask.bucket] }}</b>
                    <span>逾期分层</span>
                  </div>
                  <div>
                    <b>{{ selectedTask.assignedTo || '—' }}</b>
                    <span>负责人（岗位名）</span>
                  </div>
                </div>
                <dl class="dun__rows">
                  <div>
                    <dt>任务号</dt>
                    <dd data-testid="dunning-detail-taskid"><code>{{ selectedTask.taskId }}</code></dd>
                  </div>
                  <div>
                    <dt>话术版本</dt>
                    <dd data-testid="dunning-detail-version">{{ selectedTask.scriptVersion || '未生成' }}</dd>
                  </div>
                  <div>
                    <dt>下一次应触达</dt>
                    <dd>{{ selectedTask.dueAt ? formatIsoMinute(selectedTask.dueAt) : '—' }}</dd>
                  </div>
                  <div>
                    <dt>最近动作</dt>
                    <dd>{{ selectedTask.lastActionAt ? formatIsoMinute(selectedTask.lastActionAt) : '—' }}</dd>
                  </div>
                </dl>

                <div class="dun__redline" :class="{ 'is-blocked': frequency && !frequency.allowed }" data-testid="dunning-frequency">
                  <div class="dun__redline-head">
                    <WTag :tone="frequency && !frequency.allowed ? 'r' : 'g'" plain>频次红线</WTag>
                    <b class="num">
                      {{ frequency ? `${frequency.used} / ${frequency.limit}` : '—' }}
                      <small>次 / {{ frequency?.windowDays ?? DUNNING_FREQUENCY_WINDOW_DAYS }} 天</small>
                    </b>
                  </div>
                  <p data-testid="dunning-frequency-note">
                    {{
                      frequency
                        ? frequency.allowed
                          ? `本户窗口（${frequency.windowDays} 天）内已触达 ${frequency.used} 次，仍可触达 ${Math.max(frequency.limit - frequency.used, 0)} 次`
                          : `本户窗口（${frequency.windowDays} 天）内已触达 ${frequency.used}/${frequency.limit} 次，已达上限：发送按钮禁用（服务端仍会 409 E-3003 复核）`
                        : '频次数据来自任务详情（服务端实时计算）'
                    }}
                  </p>
                </div>
              </template>
              <WEmpty
                v-else
                icon="list-check"
                title="尚未选择任务"
                description="从左侧列表点「详情」选择一条催缴任务。"
                compact
              />
            </div>
          </WCard>

          <!-- ④ 话术生成：tone/channel → content/forbiddenHits/version（生成≠外发） -->
          <WCard title="话术与人工确认发送" subtitle="生成不等于外发；命中禁区不得发送；发送走 mock 通道（status=mocked）">
            <template #extra>
              <WTag tone="v" plain>人工确认</WTag>
            </template>
            <div data-testid="dunning-script-panel">
              <div class="dun__tones" role="group" aria-label="话术语气">
                <button
                  v-for="value in DUNNING_TONES"
                  :key="value"
                  type="button"
                  class="dun__tone"
                  :class="{ 'is-on': tone === value }"
                  :aria-pressed="tone === value"
                  :data-testid="`dunning-tone-${value}`"
                  @click="tone = value"
                >
                  {{ TONE_LABEL[value] }}
                </button>
              </div>
              <div class="dun__scriptbar">
                <label class="dun__field">
                  <span>通道</span>
                  <select v-model="scriptChannel" class="dun__input" data-testid="dunning-channel">
                    <option v-for="channel in DUNNING_CHANNELS" :key="channel" :value="channel">
                      {{ DUNNING_CHANNEL_LABELS[channel] }}
                    </option>
                  </select>
                </label>
                <WButton
                  size="sm"
                  variant="primary"
                  :loading="scriptLoading"
                  :disabled="!selectedTask"
                  data-testid="dunning-script-generate"
                  @click="generateScript"
                >
                  生成话术
                </WButton>
              </div>

              <div v-if="scriptError" class="dun__state" data-testid="dunning-script-error">
                <WEmpty icon="chat" title="话术生成未完成" :description="scriptError" compact />
              </div>

              <div v-else-if="script" class="dun__script" :class="{ 'is-forbidden': scriptForbidden.length > 0 }" data-testid="dunning-script">
                <div class="dun__script-head">
                  <WTag :tone="script.tone === 'firm' ? 'a' : 'b'" plain>{{ TONE_LABEL[script.tone] }}</WTag>
                  <WTag tone="n" plain>{{ DUNNING_CHANNEL_LABELS[script.channel] }}</WTag>
                  <span class="num" data-testid="dunning-script-version">版本 {{ script.version }}</span>
                </div>
                <p class="dun__script-content" data-testid="dunning-script-content">{{ script.content }}</p>
                <div v-if="scriptForbidden.length" class="dun__forbidden" data-testid="dunning-script-forbidden">
                  <b>命中禁区不可发送</b>
                  <div class="dun__hits">
                    <WTag v-for="hit in scriptForbidden" :key="hit" tone="r" data-testid="dunning-forbidden-hit">
                      {{ hit }}
                    </WTag>
                  </div>
                  <p data-testid="dunning-script-forbidden-note">
                    服务端禁区扫描命中 {{ scriptForbidden.length }} 条规则：按红线「命中禁区不可发送」（前端拦截，send 服务端复核）。
                    请调整话术、改用人工沟通，或转主管确认；不承诺减免、不威胁、不公开欠费。
                  </p>
                </div>
                <p v-else class="dun__script-note" data-testid="dunning-script-clean">
                  禁区扫描通过（forbiddenHits 为空）：仍需人工确认目标户与内容后才可发送；生成不等于外发。
                </p>
              </div>
              <p v-else class="dun__muted" data-testid="dunning-script-empty">
                选择语气与通道后点「生成话术」：正文、禁区命中与版本均来自服务端（生成不等于外发）。
              </p>

              <div v-if="receipt" class="dun__receipt" data-testid="dunning-receipt">
                <div class="dun__receipt-head">
                  <WTag tone="g" plain>mock 通道已受理</WTag>
                  <WTag tone="a" plain data-testid="dunning-receipt-status">{{ receipt.status }}</WTag>
                </div>
                <dl class="dun__rows">
                  <div>
                    <dt>回执 pushId</dt>
                    <dd class="num" data-testid="dunning-receipt-pushid"><code>{{ receipt.pushId }}</code></dd>
                  </div>
                  <div>
                    <dt>受理时间</dt>
                    <dd data-testid="dunning-receipt-time">{{ formatIsoMinute(receipt.sentAt) }}</dd>
                  </div>
                  <div>
                    <dt>发送后频次</dt>
                    <dd class="num" data-testid="dunning-receipt-frequency">
                      {{ receipt.frequencyAfter.used }}/{{ receipt.frequencyAfter.limit }} · {{ receipt.frequencyAfter.windowDays }} 天
                    </dd>
                  </div>
                </dl>
                <p class="dun__receipt-note">未真实外发（不接企微/短信/语音）：status=mocked 仅表示测试替身已受理留痕，不代表业主已收到。</p>
              </div>

              <div v-if="sendError" class="dun__senderror" data-testid="dunning-send-error">
                <WTag :tone="sendErrorKind === 'frequency' ? 'r' : 'a'" plain>
                  {{ sendErrorKind === 'frequency' ? '频次红线拦截' : sendErrorKind === 'channel' ? '通道未接入/失败' : '发送未完成' }}
                </WTag>
                <p data-testid="dunning-send-error-note">{{ sendError }}</p>
              </div>

              <div class="dun__sendbar">
                <WButton
                  size="sm"
                  variant="primary"
                  :disabled="!canSend"
                  :title="sendBlockReason || '人工确认后发送'"
                  data-testid="dunning-send-open"
                  @click="openSend"
                >
                  人工确认后发送
                </WButton>
                <span v-if="sendBlockReason" class="dun__blocknote" data-testid="dunning-send-blocked">
                  {{ sendBlockReason }}
                </span>
              </div>
            </div>
          </WCard>

          <!-- ⑤ 关闭任务：原因单选 + 回执（只改任务状态，不写财务） -->
          <WCard title="关闭任务" subtitle="业主已缴费 / 已转人工 / 争议时关闭；只改任务状态，不写财务数据">
            <div data-testid="dunning-close-panel">
              <WButton size="sm" :disabled="!selectedTask" data-testid="dunning-close-open" @click="openClose">
                关闭任务
              </WButton>
              <div v-if="closeResult" class="dun__receipt" data-testid="dunning-close-receipt">
                <div class="dun__receipt-head">
                  <WTag tone="n" plain>任务已关闭</WTag>
                  <WTag tone="n" plain>{{ closeResult.status }}</WTag>
                </div>
                <p>
                  回执：<code>{{ closeResult.taskId }}</code> · 关闭时间 {{ formatIsoMinute(closeResult.closedAt) }}；
                  缴费以 ERP 实收为准，本操作不写财务数据。
                </p>
              </div>
              <p v-if="closeError" class="dun__senderror" data-testid="dunning-close-error">{{ closeError }}</p>
            </div>
          </WCard>
        </div>
      </div>
    </template>

    <!-- 人工确认弹层：复述目标户（掩码）与完整话术，确认后 confirmed:true -->
    <WModal v-model:open="sendOpen" title="人工确认后发送（单户，不群发）" width="620px">
      <div class="dun__sendmodal" data-testid="dunning-send-modal">
        <div class="dun__sendmodal-target" data-testid="dunning-send-modal-target">
          <b>{{ selectedTask?.roomCode }}</b>
          <span>业主 {{ selectedTask?.ownerNameMasked }}（掩码） · {{ selectedTask?.period }} · 欠费 {{ money(selectedTask?.outstanding) }}</span>
        </div>
        <dl class="dun__rows">
          <div>
            <dt>发送通道</dt>
            <dd>{{ DUNNING_CHANNEL_LABELS[scriptChannel] }}</dd>
          </div>
          <div>
            <dt>话术版本</dt>
            <dd class="num">{{ script?.version }}</dd>
          </div>
          <div>
            <dt>频次</dt>
            <dd class="num" data-testid="dunning-send-modal-frequency">
              {{ frequency ? `${frequency.used}/${frequency.limit} · ${frequency.windowDays} 天` : '—' }}
            </dd>
          </div>
        </dl>
        <p class="dun__sendmodal-content" data-testid="dunning-send-modal-content">{{ script?.content }}</p>
        <p class="dun__sendmodal-note">
          点击「确认发送」即表示<strong>人工确认</strong>内容与目标户（confirmed: true）。发送后仅由 mock 通道受理留痕
          （status=mocked），<strong>不接真实企微/短信、不产生真实外发</strong>，也不代表业主已收到；发送前请核对掩码房号。
        </p>
      </div>
      <template #footer>
        <WButton size="sm" variant="ghost" data-testid="dunning-send-cancel" @click="sendOpen = false">取消</WButton>
        <WButton size="sm" variant="primary" :loading="sendLoading" data-testid="dunning-send-confirm" @click="confirmSend">
          确认发送（confirmed: true）
        </WButton>
      </template>
    </WModal>

    <!-- 关闭任务弹层：原因单选（必填字符串落审计） -->
    <WModal v-model:open="closeOpen" title="关闭催缴任务" width="560px">
      <div class="dun__closemodal" data-testid="dunning-close-modal">
        <p class="dun__muted">
          关闭只改任务状态并落审计；<strong>不写财务数据</strong>，缴费以收费系统实收为准。
        </p>
        <div class="dun__reasons" role="radiogroup" aria-label="关闭原因">
          <label v-for="reason in CLOSE_REASONS" :key="reason.key" class="dun__reason">
            <input
              v-model="closeKey"
              type="radio"
              name="dunning-close-reason"
              :value="reason.key"
              :data-testid="`dunning-close-reason-${reason.key}`"
            />
            <span>{{ reason.value }}</span>
          </label>
        </div>
        <label class="dun__field dun__field--block">
          <span>备注（可选，追加到关闭原因）</span>
          <input v-model="closeDetail" class="dun__input dun__input--wide" type="text" placeholder="如：管家已电话确认" data-testid="dunning-close-note" />
        </label>
        <p class="dun__muted">关闭原因将提交为：{{ closeReasonText }}</p>
      </div>
      <template #footer>
        <WButton size="sm" variant="ghost" @click="closeOpen = false">取消</WButton>
        <WButton size="sm" variant="primary" :loading="closeLoading" data-testid="dunning-close-confirm" @click="confirmClose">
          确认关闭
        </WButton>
      </template>
    </WModal>
  </section>
</template>

<style scoped>
.dun {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
  min-width: 0;
}

/* ---------- 来源 / 降级条 ---------- */
.dun__metabar {
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

.dun__metabar > span:first-child {
  margin-right: auto;
}

.dun__degradebar {
  margin: 0;
  padding: 6px 10px;
  border: 1px dashed var(--c-amber);
  border-radius: var(--rd-md);
  background: var(--c-amber-soft);
  color: #9a4b05;
  font-size: var(--fs-2xs);
}

.dun__grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 372px;
  gap: var(--sp-3);
  align-items: start;
}

.dun__side {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
  min-width: 0;
}

.dun__card-title {
  font-size: 12.5px;
  font-weight: var(--fw-semibold);
  color: var(--c-ink);
}

.dun__card-sub {
  margin-left: 6px;
  font-size: var(--fs-2xs);
  color: var(--c-faint);
}

/* ---------- 工具条 ---------- */
.dun__toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  padding: 8px 12px;
  border-bottom: 1px solid var(--c-line-2);
}

.dun__field {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: var(--fs-2xs);
  color: var(--c-muted);
}

.dun__field--block {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 4px;
  margin-top: 8px;
}

.dun__input {
  height: 26px;
  padding: 0 8px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-sm);
  background: var(--c-card);
  font-size: var(--fs-2xs);
  color: var(--c-ink);
}

.dun__input--wide {
  width: 100%;
}

.dun__input:focus {
  outline: none;
  border-color: var(--c-brand);
  box-shadow: var(--sh-ring);
}

/* ---------- 表格 ---------- */
.dun__brow {
  display: flex;
  flex-direction: column;
  gap: 1px;
  line-height: 1.35;
}

.dun__brow b {
  font-size: var(--fs-xs);
  color: var(--c-ink);
}

.dun__brow span {
  font-size: var(--fs-2xs);
  color: var(--c-faint);
}

.dun__amount {
  font-weight: var(--fw-semibold);
  color: var(--c-ink);
}

.dun__foot {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.dun__foot-note {
  color: var(--c-faint);
}

.dun__pager {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  padding: 8px 12px;
  border-top: 1px solid var(--c-line-2);
  font-size: var(--fs-2xs);
  color: var(--c-muted);
}

/* ---------- 详情 ---------- */
.dun__detailhead {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
}

.dun__detailhead > div {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.dun__detailhead b {
  font-size: var(--fs-lg);
  color: var(--c-ink);
}

.dun__detailhead span {
  font-size: var(--fs-2xs);
  color: var(--c-faint);
}

.dun__tiles {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 6px;
  margin-top: 10px;
}

.dun__tiles > div {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px 10px;
  border: 1px solid var(--c-line-2);
  border-radius: var(--rd-md);
  background: var(--c-bg-2);
}

.dun__tiles b {
  font-size: var(--fs-md);
  color: var(--c-ink);
}

.dun__tiles span {
  font-size: var(--fs-2xs);
  color: var(--c-faint);
}

.dun__rows {
  margin: 10px 0 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.dun__rows > div {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  font-size: var(--fs-2xs);
}

.dun__rows dt {
  color: var(--c-faint);
}

.dun__rows dd {
  margin: 0;
  color: var(--c-ink-2);
  text-align: right;
}

/* ---------- 频次红线 ---------- */
.dun__redline {
  margin-top: 10px;
  padding: 8px 10px;
  border: 1px solid var(--c-line-2);
  border-radius: var(--rd-md);
  background: var(--c-bg-2);
}

.dun__redline.is-blocked {
  border-color: #f0c3c0;
  background: var(--c-red-soft);
}

.dun__redline-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.dun__redline-head b {
  font-size: var(--fs-md);
  color: var(--c-ink);
}

.dun__redline-head small {
  margin-left: 2px;
  font-size: var(--fs-2xs);
  font-weight: var(--fw-medium);
  color: var(--c-muted);
}

.dun__redline p {
  margin: 4px 0 0;
  font-size: var(--fs-2xs);
  line-height: var(--lh-base);
  color: var(--c-muted);
}

/* ---------- 话术 ---------- */
.dun__tones {
  display: flex;
  gap: 2px;
  padding: 2px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-md);
  background: var(--c-bg-2);
}

.dun__tone {
  flex: 1;
  height: 24px;
  border-radius: var(--rd-sm);
  font-size: var(--fs-2xs);
  color: var(--c-muted);
}

.dun__tone.is-on {
  background: var(--c-card);
  color: var(--c-brand);
  font-weight: var(--fw-semibold);
  box-shadow: var(--sh-sm);
}

.dun__tone:focus-visible {
  outline: none;
  box-shadow: var(--sh-ring);
}

.dun__scriptbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
}

.dun__scriptbar > .w-btn {
  margin-left: auto;
}

.dun__script {
  margin-top: 8px;
  padding: 10px;
  border: 1px solid var(--c-line-2);
  border-radius: var(--rd-md);
  background: var(--c-bg-2);
}

.dun__script.is-forbidden {
  border-color: #f0c3c0;
  background: #fdf4f3;
}

.dun__script-head {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-2xs);
  color: var(--c-muted);
}

.dun__script-head .num {
  margin-left: auto;
}

.dun__script-content {
  margin: 8px 0 0;
  font-size: var(--fs-xs);
  line-height: var(--lh-base);
  color: var(--c-ink-2);
  white-space: pre-wrap;
}

.dun__script-note {
  margin: 8px 0 0;
  font-size: var(--fs-2xs);
  color: var(--c-faint);
}

.dun__forbidden {
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px dashed #f0c3c0;
}

.dun__forbidden b {
  font-size: var(--fs-xs);
  color: var(--c-red);
}

.dun__hits {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 4px;
}

.dun__forbidden p {
  margin: 6px 0 0;
  font-size: var(--fs-2xs);
  line-height: var(--lh-base);
  color: #9a2f28;
}

/* ---------- 回执 / 错误 ---------- */
.dun__receipt {
  margin-top: 10px;
  padding: 10px;
  border: 1px solid #cfe9dd;
  border-radius: var(--rd-md);
  background: #f3fbf7;
}

.dun__receipt-head {
  display: flex;
  align-items: center;
  gap: 6px;
}

.dun__receipt-note,
.dun__receipt p {
  margin: 6px 0 0;
  font-size: var(--fs-2xs);
  line-height: var(--lh-base);
  color: var(--c-muted);
}

.dun__senderror {
  margin-top: 10px;
  padding: 8px 10px;
  border: 1px dashed var(--c-amber);
  border-radius: var(--rd-md);
  background: var(--c-amber-soft);
  color: #9a4b05;
  font-size: var(--fs-2xs);
  line-height: var(--lh-base);
}

.dun__sendbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
}

.dun__blocknote {
  font-size: var(--fs-2xs);
  color: var(--c-muted);
}

.dun__muted {
  margin: 8px 0 0;
  font-size: var(--fs-2xs);
  line-height: var(--lh-base);
  color: var(--c-faint);
}

.dun__state {
  padding: 12px;
}

/* ---------- 弹层 ---------- */
.dun__sendmodal-target {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 10px;
  border: 1px solid var(--c-line-2);
  border-radius: var(--rd-md);
  background: var(--c-bg-2);
}

.dun__sendmodal-target b {
  font-size: var(--fs-lg);
  color: var(--c-ink);
}

.dun__sendmodal-target span {
  font-size: var(--fs-2xs);
  color: var(--c-muted);
}

.dun__sendmodal-content {
  margin: 10px 0 0;
  padding: 10px;
  border: 1px solid var(--c-line-2);
  border-radius: var(--rd-md);
  background: var(--c-card);
  font-size: var(--fs-xs);
  line-height: var(--lh-base);
  color: var(--c-ink-2);
  white-space: pre-wrap;
}

.dun__sendmodal-note {
  margin: 10px 0 0;
  font-size: var(--fs-2xs);
  line-height: var(--lh-base);
  color: var(--c-muted);
}

.dun__reasons {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-top: 8px;
}

.dun__reason {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-xs);
  color: var(--c-ink-2);
}

.dun__reason input:focus-visible {
  outline: none;
  box-shadow: var(--sh-ring);
}
</style>
