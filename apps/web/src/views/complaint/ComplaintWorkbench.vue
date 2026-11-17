<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { WButton, WCard, WEmpty, WLoading, WModal, WTag, useToast } from '@/components'
import PermissionDenied from '@/components/feedback/PermissionDenied.vue'
import { useAuthStore } from '@/stores/auth'
import { ROLE_LABELS } from '@/app/roles'
import { demoNote } from '@/content'
import {
  api,
  ApiError,
  COMPLAINT_LEVELS,
  COMPLAINT_LEVEL_LABELS,
  COMPLAINT_LIST_SORTS,
  COMPLAINT_STATUSES,
  COMPLAINT_STATUS_LABELS,
  DEFAULT_COMPLAINT_LIST_SORT,
  degradedText,
  errorNextStep,
  errorText,
  forbiddenDetailsOf,
  formatIsoMinute,
  mockBadgeText,
} from '@/api'
import type {
  ActorRole,
  Complaint,
  ComplaintLevel,
  ComplaintListQuery,
  ComplaintListSort,
  ComplaintStats,
  ComplaintStatus,
  ComplaintTransitionAction,
  ForbiddenDetails,
} from '@/api'
import { capabilityLabel } from '@/auth/capabilities'
import {
  COMPLAINT_ACTION_LABELS,
  COMPLAINT_LEVEL_TONES,
  COMPLAINT_STATUS_TONES,
  allowedActionLabels,
  availableActions,
  categoryLabel,
  complaintAuditTimeline,
  complaintSlaView,
  levelLabelShort,
  maskRoomCode,
} from './shared'

/**
 * P3 W12 投诉处理台（P3-021/022，契约 v1.1.0 §12.3；desk「投诉处理台」页签 + `/complaints` 复用）
 * ===========================================================================
 * 数据来源（全部真实 API，不编造）：
 * - 统计卡：`GET /api/v1/complaints/stats`（total / byStatus / byLevel / avgFirstResponseMinutes /
 *   slaBreachCount / period 均由服务端给出，前端只格式化）；
 * - 列表：`GET /api/v1/complaints?status&level&roomCode&sort&page&pageSize`（服务端筛选/排序/真实分页）；
 * - 详情：投诉行即完整 `Complaint`（含 `audit[]`），「刷新详情」走 `GET /complaints/{id}`；
 * - 动作：`POST /complaints/{id}/transition`（accept / first_response / escalate / close / reopen，
 *   `version` 乐观锁必填）。
 *
 * 权限与状态（契约 README §7.2 / 状态机表）：
 * - 入口显隐按 `auth.hasCapability`（complaint.handle / complaint.escalate / complaint.read）**只是隐藏**，
 *   真正判定在 Java：越权 403 `E-2001` + `details.requiredCapability`，非法迁移 409 `E-3002` + `details.allowed`，
 *   版本冲突 409 `E-3002` + `details.reason=version_conflict`——页面按人话提示并给「刷新详情」重试；
 * - `escalate` 仅 supervisor（契约「L1/L2 升级红线」）；契约 transition 入参**没有** ticketNo/target，
 *   升级目标与工单联动由服务端只读规则决定（`escalatedTo`/`ticketNo` 回带），页面不伪造选择项。
 *
 * 隐私 / demo 口径（AGENTS.md NEVER #8/#11）：
 * - 列表房号按「后 2 位掩码」展示；投诉内容与业主姓名由服务端脱敏后返回，前端不再二次拼装；
 * - 页面标注「演示数据」：投诉为进程内演示存储（重启清空），不写 ERP、不外发通知、不做自动回复/自动结案。
 */
const auth = useAuthStore()
const toast = useToast()
const router = useRouter()

/** 演示账号切换白名单（能读投诉的 staff 角色；owner 侧走「我的投诉」，不在此列） */
const SWITCH_ROLES: readonly ActorRole[] = ['steward', 'agent', 'supervisor']
const PAGE_SIZE = 10

/* ------------------------------------------------------------------ SLA 走时 */

/** 每秒推进一次「现在」，用于 SLA 倒计时（只做时间差展示，不改变服务端口径） */
const nowMs = ref(Date.now())
let tickTimer: number | undefined

onMounted(() => {
  tickTimer = window.setInterval(() => {
    nowMs.value = Date.now()
  }, 1000)
  void loadAll()
})

onBeforeUnmount(() => {
  if (tickTimer) window.clearInterval(tickTimer)
})

/* --------------------------------------------------------------- 列表 + 筛选 */

const filters = reactive({
  status: '' as ComplaintStatus | '',
  level: '' as ComplaintLevel | '',
  roomCode: '',
  sort: DEFAULT_COMPLAINT_LIST_SORT as ComplaintListSort,
})
const page = ref(1)
const items = ref<Complaint[]>([])
const total = ref(0)
const listLoading = ref(false)
const listError = ref('')
const listDegraded = ref('')
const listDenied = ref<ForbiddenDetails | null>(null)

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / PAGE_SIZE)))
const canPrev = computed(() => page.value > 1 && !listLoading.value)
const canNext = computed(() => page.value < totalPages.value && !listLoading.value)

async function loadList(): Promise<void> {
  listLoading.value = true
  listError.value = ''
  listDenied.value = null
  listDegraded.value = ''
  const query: ComplaintListQuery = { page: page.value, pageSize: PAGE_SIZE, sort: filters.sort }
  if (filters.status) query.status = filters.status
  if (filters.level) query.level = filters.level
  if (filters.roomCode.trim()) query.roomCode = filters.roomCode.trim()
  try {
    const { data, degraded } = await api.complaint.list(query)
    items.value = data.items
    total.value = data.total
    if (degraded) listDegraded.value = degradedText(degraded)
    const keep = data.items.find((item) => item.complaintId === selectedId.value)
    if (keep) {
      selected.value = keep
    } else if (data.items[0]) {
      selectComplaint(data.items[0])
    } else {
      selected.value = null
      selectedId.value = ''
    }
  } catch (error) {
    items.value = []
    total.value = 0
    const denied = forbiddenDetailsOf(error)
    if (denied) listDenied.value = denied
    else listError.value = apiMessage(error)
  } finally {
    listLoading.value = false
  }
}

function applyFilters(): void {
  page.value = 1
  void loadList()
}

/**
 * 用户点「刷新列表」：请求很快 / 数据未变时也要有可见反馈（P3 门禁「0 死按钮」口径），
 * 成功后 Toast 带服务端 total 与页码，失败给错误 Toast；列表数据口径不变。
 */
async function refreshList(): Promise<void> {
  await loadList()
  if (listDenied.value) {
    toast.warning('列表刷新被拒绝：当前演示角色无 complaint.read 能力')
    return
  }
  if (listError.value) {
    toast.error(`列表刷新未完成：${listError.value}`)
    return
  }
  toast.success(`列表已刷新 · 共 ${total.value} 条 · 第 ${page.value}/${totalPages.value} 页`)
}

function resetFilters(): void {
  filters.status = ''
  filters.level = ''
  filters.roomCode = ''
  filters.sort = DEFAULT_COMPLAINT_LIST_SORT
  applyFilters()
}

function gotoPage(next: number): void {
  if (next < 1 || next > totalPages.value) return
  page.value = next
  void loadList()
}

/* ---------------------------------------------------------------- 统计卡 */

const stats = ref<ComplaintStats | null>(null)
const statsLoading = ref(false)
const statsError = ref('')
const statsDegraded = ref('')
const statsDenied = ref<ForbiddenDetails | null>(null)

async function loadStats(): Promise<void> {
  statsLoading.value = true
  statsError.value = ''
  statsDenied.value = null
  statsDegraded.value = ''
  try {
    const { data, degraded } = await api.complaint.stats()
    stats.value = data
    if (degraded) statsDegraded.value = degradedText(degraded)
  } catch (error) {
    stats.value = null
    const denied = forbiddenDetailsOf(error)
    if (denied) statsDenied.value = denied
    else statsError.value = apiMessage(error)
  } finally {
    statsLoading.value = false
  }
}

/** 用户点统计卡「刷新」：完成后给回执（统计值未变时也有可见反馈） */
async function refreshStats(): Promise<void> {
  await loadStats()
  if (statsDenied.value) {
    toast.warning('统计刷新被拒绝：当前演示角色无 complaint.read 能力')
    return
  }
  if (statsError.value) {
    toast.error(`统计刷新未完成：${statsError.value}`)
    return
  }
  toast.success(`投诉统计已刷新 · 共 ${stats.value?.total ?? 0} 条 · 周期 ${stats.value?.period ?? '—'}`)
}

/* ------------------------------------------------------------------- 详情 */

const selectedId = ref('')
const selected = ref<Complaint | null>(null)
const detailLoading = ref(false)
const detailError = ref('')
const detailDenied = ref<ForbiddenDetails | null>(null)

const sla = computed(() => (selected.value ? complaintSlaView(selected.value, nowMs.value) : null))
const auditItems = computed(() => (selected.value ? complaintAuditTimeline(selected.value) : []))
const statusTone = computed(() =>
  selected.value ? COMPLAINT_STATUS_TONES[selected.value.status] ?? 'n' : 'n',
)
const levelTone = computed(() => (selected.value ? COMPLAINT_LEVEL_TONES[selected.value.level] ?? 'n' : 'n'))

function selectComplaint(item: Complaint): void {
  selectedId.value = item.complaintId
  selected.value = item
  detailError.value = ''
  detailDenied.value = null
  conflict.value = null
  actionError.value = ''
}

async function refreshSelected(): Promise<void> {
  if (!selectedId.value) return
  detailLoading.value = true
  detailError.value = ''
  detailDenied.value = null
  try {
    const { data } = await api.complaint.get(selectedId.value)
    selected.value = data
    conflict.value = null
    actionError.value = ''
    toast.info(`已刷新投诉详情：${data.complaintId} · 版本 ${data.version}`)
  } catch (error) {
    const denied = forbiddenDetailsOf(error)
    if (denied) detailDenied.value = denied
    else detailError.value = apiMessage(error)
  } finally {
    detailLoading.value = false
  }
}

/* ------------------------------------------------------------------- 动作 */

const actionOpen = ref(false)
const actionKind = ref<ComplaintTransitionAction>('accept')
const actionNote = ref('')
const actionBusy = ref(false)
const actionError = ref('')

type Conflict =
  | { kind: 'version_conflict'; currentVersion: number; version: number }
  | { kind: 'invalid_transition'; statusText: string; allowed: string[] }
const conflict = ref<Conflict | null>(null)
/** 模板里按 kind 分流用的窄化视图（避免在 template 内做联合类型收窄） */
const conflictVersion = computed(() => (conflict.value?.kind === 'version_conflict' ? conflict.value : null))
const conflictStatus = computed(() => (conflict.value?.kind === 'invalid_transition' ? conflict.value : null))

const actionsFor = computed<ComplaintTransitionAction[]>(() => {
  if (!selected.value) return []
  return availableActions(selected.value.status, (capability) => auth.hasCapability(capability))
})

const noteRequired = computed(() => actionKind.value === 'first_response' || actionKind.value === 'close')
const noteLabel = computed(() => {
  if (actionKind.value === 'first_response') return '本次联系/处理记录（必填，落 audit[]）'
  if (actionKind.value === 'close') return '结案说明 resolution（必填，落 audit[]）'
  if (actionKind.value === 'escalate') return '升级原因（可选，落 audit[]）'
  return '备注（可选，落 audit[]）'
})
const actionTitle = computed(
  () => `投诉${COMPLAINT_ACTION_LABELS[actionKind.value]}${selected.value ? ` · ${selected.value.complaintId}` : ''}`,
)

function openAction(action: ComplaintTransitionAction): void {
  actionKind.value = action
  actionNote.value = ''
  actionError.value = ''
  actionOpen.value = true
}

async function submitAction(): Promise<void> {
  const current = selected.value
  if (!current) return
  if (noteRequired.value && !actionNote.value.trim()) {
    actionError.value =
      actionKind.value === 'close' ? '请填写结案说明（resolution 必填）' : '请填写本次联系/处理记录（note 必填）'
    return
  }
  actionBusy.value = true
  actionError.value = ''
  const action = actionKind.value
  try {
    const { data } = await api.complaint.transition(current.complaintId, {
      action,
      version: current.version,
      note: actionNote.value.trim() || undefined,
    })
    selected.value = data
    selectedId.value = data.complaintId
    conflict.value = null
    actionOpen.value = false
    const extra = data.ticketNo ? `；已关联工单 ${data.ticketNo}` : ''
    toast.success(
      `已${COMPLAINT_ACTION_LABELS[action]}：${data.complaintId} · 当前状态「${COMPLAINT_STATUS_LABELS[data.status]}」${extra}${demoNote('未对外发送通知')}`,
    )
    await Promise.all([loadList(), loadStats()])
  } catch (error) {
    handleTransitionError(error)
  } finally {
    actionBusy.value = false
  }
}

/** 409/403/400 统一出口：按契约 details 给「用人话可执行的下一步」，不吞错、不伪造成功 */
function handleTransitionError(error: unknown): void {
  const denied = forbiddenDetailsOf(error)
  if (denied) {
    actionOpen.value = false
    actionError.value = `无权限（403）：需要能力 ${capabilityLabel(denied.requiredCapability)}；当前演示角色「${currentRoleLabel.value}」。`
    toast.error(`该动作需要能力 ${capabilityLabel(denied.requiredCapability)}（切换演示账号后可重试）`)
    return
  }
  if (error instanceof ApiError && error.httpStatus === 409) {
    actionOpen.value = false
    const details = error.details ?? {}
    const reason = typeof details.reason === 'string' ? details.reason : ''
    if (reason === 'version_conflict') {
      conflict.value = {
        kind: 'version_conflict',
        currentVersion: Number(details.currentVersion ?? 0),
        version: Number(details.version ?? selected.value?.version ?? 0),
      }
      toast.error('投诉已被他人更新（版本冲突）：请刷新详情后重试')
      return
    }
    const allowedList = Array.isArray(details.allowed) ? details.allowed.map((item) => String(item)) : []
    conflict.value = {
      kind: 'invalid_transition',
      statusText: typeof details.statusText === 'string' ? details.statusText : '',
      allowed: allowedList,
    }
    toast.error(
      `当前状态不支持该动作${allowedList.length ? `：可用 ${allowedActionLabels(allowedList).join(' / ')}` : ''}`,
    )
    return
  }
  if (error instanceof ApiError && error.httpStatus === 400) {
    actionError.value = `参数不完整（${error.code}）：${errorNextStep(error.code)}`
    toast.error('参数不完整：请补齐必填后再提交')
    return
  }
  actionError.value = apiMessage(error)
  toast.error(actionError.value)
}

/* ------------------------------------------------------------- 403 兜底切换 */

const switching = ref(false)
const currentRoleLabel = computed(() => (auth.role ? ROLE_LABELS[auth.role] : '未登录'))

async function switchAccount(role: ActorRole): Promise<void> {
  switching.value = true
  try {
    const ok = await auth.loginAsRole(role)
    if (!ok) {
      toast.error('切换演示账号失败：请到登录页重新选择演示账号')
      return
    }
    toast.success(`已切换演示账号：${ROLE_LABELS[role]}（demo，非真实鉴权）`)
    await loadAll()
  } finally {
    switching.value = false
  }
}

/* ------------------------------------------------------------------ 工具 */

async function loadAll(): Promise<void> {
  await Promise.all([loadList(), loadStats()])
}

function retryAll(): void {
  void loadAll()
}

/** 跳登录页切换演示账号（403 无权限态的第二条路径） */
function goLogin(): void {
  void router.push('/login')
}

/** 错误 → 人话（不展示上游原文；契约 17 类错误码统一走 content 文案） */
function apiMessage(error: unknown): string {
  if (error instanceof ApiError) return `${errorText(error.code)}；${errorNextStep(error.code)}`
  return '请求失败：请稍后重试或转人工'
}

function statusLabel(status: ComplaintStatus): string {
  return COMPLAINT_STATUS_LABELS[status] ?? status
}

function levelLabel(level: ComplaintLevel): string {
  return COMPLAINT_LEVEL_LABELS[level] ?? level
}

/** 列表 SLA 单元格（每秒随 nowMs 刷新；超时红色「已超时」） */
function rowSla(item: Complaint) {
  return complaintSlaView(item, nowMs.value)
}
</script>

<template>
  <div class="cmp" data-testid="complaint-workbench">
    <!-- 403：列表/统计均无权限时不渲染任何业务数据（统一无权限态 + 可切换演示账号） -->
    <PermissionDenied
      v-if="listDenied"
      data-testid="cmp-denied"
      :required-capability="listDenied.requiredCapability"
      :required-role="listDenied.role"
      :switching="switching"
      :candidate-roles="SWITCH_ROLES"
      detail="投诉处理台需要 complaint.read 能力；当前演示角色不具备，页面不会展示未授权投诉数据。"
      @switch="switchAccount"
    >
      <template #actions>
        <WButton size="sm" variant="primary" data-testid="cmp-denied-retry" @click="retryAll">重新加载</WButton>
        <WButton size="sm" data-testid="cmp-denied-owner" @click="goLogin">切换演示账号</WButton>
      </template>
    </PermissionDenied>

    <template v-else>
      <!-- ======================= 统计卡（/complaints/stats） ======================= -->
      <section class="cmp-stats" data-testid="cmp-stats">
        <header class="cmp-stats__head">
          <b>投诉统计</b>
          <span v-if="stats">统计周期 {{ stats.period }}</span>
          <span v-else>口径：平均首响 / SLA 超时由服务端聚合</span>
          <WTag tone="n" plain>{{ mockBadgeText }}</WTag>
          <WTag v-if="statsDegraded" tone="a" plain>已降级</WTag>
          <WButton size="sm" variant="ghost" icon="refresh" data-testid="cmp-stats-refresh" @click="refreshStats">刷新</WButton>
        </header>

        <WLoading v-if="statsLoading && !stats" label="正在读取投诉统计（GET /api/v1/complaints/stats）…" />
        <p v-else-if="statsDenied" class="cmp-note cmp-note--warn" data-testid="cmp-stats-denied">
          <WTag tone="a" plain>无权限 403</WTag>
          统计卡需要能力 {{ capabilityLabel(statsDenied.requiredCapability) }}（当前角色「{{ currentRoleLabel }}」不展示未授权数字）。
        </p>
        <p v-else-if="statsError" class="cmp-note cmp-note--warn" data-testid="cmp-stats-error">
          <WTag tone="r" plain>统计不可用</WTag>
          {{ statsError }}
          <WButton size="sm" variant="ghost" data-testid="cmp-stats-retry" @click="refreshStats">重试</WButton>
        </p>
        <div v-else-if="stats" class="cmp-stats__grid">
          <div class="cmp-stat" data-testid="cmp-stats-total">
            <b class="num">{{ stats.total }}</b><span>投诉总数</span>
          </div>
          <div class="cmp-stat" data-testid="cmp-stats-avg-first-response">
            <b class="num">{{ stats.avgFirstResponseMinutes }}</b><span>平均首响（分钟）</span>
          </div>
          <div class="cmp-stat" :class="{ 'is-bad': stats.slaBreachCount > 0 }" data-testid="cmp-stats-sla-breach">
            <b class="num">{{ stats.slaBreachCount }}</b><span>SLA 超时数</span>
          </div>
          <div class="cmp-stat cmp-stat--wide" data-testid="cmp-stats-by-status">
            <span class="cmp-stat__label">按状态</span>
            <div class="cmp-stat__tags">
              <span v-for="status in COMPLAINT_STATUSES" :key="status" :data-testid="`cmp-stat-status-${status}`">
                <WTag :tone="COMPLAINT_STATUS_TONES[status]" plain>{{ statusLabel(status) }}</WTag>
                <b class="num">{{ stats.byStatus[status] ?? 0 }}</b>
              </span>
            </div>
          </div>
          <div class="cmp-stat cmp-stat--wide" data-testid="cmp-stats-by-level">
            <span class="cmp-stat__label">按级别</span>
            <div class="cmp-stat__tags">
              <span v-for="level in COMPLAINT_LEVELS" :key="level" :data-testid="`cmp-stat-level-${level}`">
                <WTag :tone="COMPLAINT_LEVEL_TONES[level]" plain>{{ levelLabelShort(level) }}</WTag>
                <b class="num">{{ stats.byLevel[level] ?? 0 }}</b>
              </span>
            </div>
          </div>
        </div>
      </section>

      <div class="cmp-grid">
        <!-- ============================ 列表 ============================ -->
        <WCard class="cmp-listcard" :padded="false" data-testid="cmp-list-card">
          <template #head>
            <span class="cmp-card-title">投诉列表</span>
            <WTag tone="b" plain>{{ total }} 条</WTag>
            <WTag tone="n" plain>人工处置 · 不自动回复</WTag>
          </template>
          <template #extra>
            <WButton size="sm" variant="ghost" data-testid="cmp-list-reset" @click="resetFilters">清空筛选</WButton>
            <WButton size="sm" data-testid="cmp-list-refresh" :loading="listLoading" @click="refreshList">刷新列表</WButton>
          </template>

          <div class="cmp-filters" data-testid="cmp-filters">
            <label>
              <span>状态</span>
              <select v-model="filters.status" data-testid="cmp-filter-status" @change="applyFilters">
                <option value="">全部状态</option>
                <option v-for="status in COMPLAINT_STATUSES" :key="status" :value="status">
                  {{ statusLabel(status) }}
                </option>
              </select>
            </label>
            <label>
              <span>级别</span>
              <select v-model="filters.level" data-testid="cmp-filter-level" @change="applyFilters">
                <option value="">全部级别</option>
                <option v-for="level in COMPLAINT_LEVELS" :key="level" :value="level">
                  {{ levelLabelShort(level) }}
                </option>
              </select>
            </label>
            <label>
              <span>房号</span>
              <input
                v-model="filters.roomCode"
                type="text"
                placeholder="如 3-2-1802"
                data-testid="cmp-filter-room"
                @keyup.enter="applyFilters"
                @change="applyFilters"
              />
            </label>
            <label>
              <span>排序</span>
              <select v-model="filters.sort" data-testid="cmp-filter-sort" @change="applyFilters">
                <option v-for="sort in COMPLAINT_LIST_SORTS" :key="sort" :value="sort">
                  {{ sort === 'slaDueAt_asc' ? 'SLA 先到期优先' : '最新提交优先' }}
                </option>
              </select>
            </label>
          </div>

          <p v-if="listDegraded" class="cmp-note" data-testid="cmp-list-degraded">
            <WTag tone="a" plain>已降级</WTag>{{ listDegraded }}
          </p>

          <div v-if="listLoading" class="cmp-state" data-testid="cmp-list-loading">
            <WLoading label="正在读取投诉列表（GET /api/v1/complaints）…" />
          </div>

          <p v-else-if="listError" class="cmp-note cmp-note--warn" data-testid="cmp-list-error">
            <WTag tone="r" plain>列表不可用</WTag>
            {{ listError }}
            <WButton size="sm" variant="ghost" data-testid="cmp-list-retry" @click="refreshList">重试</WButton>
          </p>

          <WEmpty
            v-else-if="items.length === 0"
            compact
            icon="list-check"
            data-testid="cmp-list-empty"
            title="当前筛选无投诉"
            description="放宽状态 / 级别 / 房号，或清空筛选查看全量；业主提交投诉后此处实时出现。"
          />

          <div v-else class="cmp-table-wrap">
            <table class="cmp-table" data-testid="cmp-table">
              <thead>
                <tr>
                  <th>投诉号</th>
                  <th>房号（掩码）</th>
                  <th>级别</th>
                  <th>状态</th>
                  <th>首响 SLA</th>
                  <th>关联工单</th>
                  <th>更新时间</th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="row in items"
                  :key="row.complaintId"
                  class="cmp-row"
                  :class="{ 'is-on': row.complaintId === selectedId }"
                  data-testid="cmp-row"
                  :data-complaint-id="row.complaintId"
                  :data-status="row.status"
                  tabindex="0"
                  @click="selectComplaint(row)"
                  @keyup.enter="selectComplaint(row)"
                >
                  <td class="cmp-cell-id">
                    <b>{{ row.complaintId }}</b>
                    <span class="cmp-cell-sub">{{ categoryLabel(row.category) }}</span>
                  </td>
                  <td>
                    <span title="列表口径：房号后 2 位掩码">{{ maskRoomCode(row.roomCode) }}</span>
                  </td>
                  <td>
                    <WTag :tone="COMPLAINT_LEVEL_TONES[row.level]" plain>{{ levelLabelShort(row.level) }}</WTag>
                  </td>
                  <td>
                    <WTag :tone="COMPLAINT_STATUS_TONES[row.status]" plain>{{ statusLabel(row.status) }}</WTag>
                  </td>
                  <td>
                    <span
                      class="cmp-sla num"
                      :class="{ 'is-bad': rowSla(row).breached, 'is-warn': rowSla(row).tone === 'a' }"
                      data-testid="cmp-row-sla"
                      :data-sla-state="rowSla(row).kind"
                    >
                      {{ rowSla(row).text }}
                    </span>
                  </td>
                  <td>
                    <RouterLink
                      v-if="row.ticketNo"
                      class="cmp-link"
                      :to="{ path: '/progress', query: { ticket: row.ticketNo } }"
                      data-testid="cmp-row-ticket"
                      @click.stop
                    >
                      {{ row.ticketNo }}
                    </RouterLink>
                    <span v-else class="cmp-cell-sub">—</span>
                  </td>
                  <td class="cmp-cell-sub">{{ formatIsoMinute(row.updatedAt) }}</td>
                </tr>
              </tbody>
            </table>
          </div>

          <footer class="cmp-pager" data-testid="cmp-pager">
            <span data-testid="cmp-pager-info">第 {{ page }} / {{ totalPages }} 页 · 共 {{ total }} 条</span>
            <div>
              <WButton size="sm" variant="ghost" :disabled="!canPrev" data-testid="cmp-pager-prev" @click="gotoPage(page - 1)">
                上一页
              </WButton>
              <WButton size="sm" variant="ghost" :disabled="!canNext" data-testid="cmp-pager-next" @click="gotoPage(page + 1)">
                下一页
              </WButton>
            </div>
          </footer>
        </WCard>

        <!-- ============================ 详情 ============================ -->
        <WCard class="cmp-detailcard" data-testid="cmp-detail-card">
          <template #head>
            <span class="cmp-card-title">投诉详情</span>
            <WTag v-if="selected" tone="n" plain>版本 {{ selected.version }}</WTag>
            <WTag tone="n" plain>内容已脱敏</WTag>
          </template>
          <template #extra>
            <WButton
              size="sm"
              variant="ghost"
              data-testid="cmp-detail-refresh"
              :loading="detailLoading"
              :disabled="!selectedId"
              @click="refreshSelected"
            >
              刷新详情
            </WButton>
          </template>

          <PermissionDenied
            v-if="detailDenied"
            compact
            data-testid="cmp-detail-denied"
            :required-capability="detailDenied.requiredCapability"
            :required-role="detailDenied.role"
            :candidate-roles="SWITCH_ROLES"
            @switch="switchAccount"
          />

          <p v-else-if="detailError" class="cmp-note cmp-note--warn" data-testid="cmp-detail-error">
            <WTag tone="r" plain>详情不可用</WTag>
            {{ detailError }}
          </p>

          <WEmpty
            v-else-if="!selected"
            compact
            icon="info"
            data-testid="cmp-detail-empty"
            title="尚未选择投诉"
            description="在左侧列表点选一条投诉查看内容、SLA 倒计时、处理轨迹与可用动作。"
          />

          <div
            v-else
            class="cmp-detail"
            data-testid="cmp-detail"
            :data-complaint-id="selected.complaintId"
            :data-status="selected.status"
            :data-version="selected.version"
          >
            <div class="cmp-detail__head">
              <div class="cmp-detail__title">
                <b class="num" data-testid="cmp-detail-id">{{ selected.complaintId }}</b>
                <WTag :tone="statusTone" data-testid="cmp-detail-status">{{ statusLabel(selected.status) }}</WTag>
                <WTag :tone="levelTone" data-testid="cmp-detail-level">{{ levelLabel(selected.level) }}</WTag>
              </div>
              <div
                v-if="sla"
                class="cmp-detail__sla"
                :class="{ 'is-bad': sla.breached }"
                data-testid="cmp-detail-sla"
                :data-sla-state="sla.kind"
              >
                <b>{{ sla.text }}</b>
                <span>{{ sla.dueText }}</span>
              </div>
            </div>

            <dl class="cmp-rows">
              <div>
                <dt>房号</dt>
                <dd data-testid="cmp-detail-room">{{ selected.roomCode }}<em>· 业主 {{ selected.ownerNameMasked }}</em></dd>
              </div>
              <div>
                <dt>分类</dt>
                <dd data-testid="cmp-detail-category">{{ categoryLabel(selected.category) }}</dd>
              </div>
              <div>
                <dt>首次响应</dt>
                <dd data-testid="cmp-detail-first-response">
                  {{ selected.firstResponseAt ? formatIsoMinute(selected.firstResponseAt) : '尚未首响' }}
                </dd>
              </div>
              <div>
                <dt>关联工单</dt>
                <dd data-testid="cmp-detail-ticket">
                  <RouterLink
                    v-if="selected.ticketNo"
                    class="cmp-link"
                    :to="{ path: '/progress', query: { ticket: selected.ticketNo } }"
                  >
                    {{ selected.ticketNo }}（查看进度）
                  </RouterLink>
                  <span v-else>未关联（升级时服务端按同房未结案工单只读联动）</span>
                </dd>
              </div>
              <div>
                <dt>提交时间</dt>
                <dd>{{ formatIsoMinute(selected.createdAt) }}</dd>
              </div>
              <div v-if="selected.escalatedTo">
                <dt>受理层级</dt>
                <dd data-testid="cmp-detail-escalated-to">{{ selected.escalatedTo }}</dd>
              </div>
            </dl>

            <div class="cmp-content" data-testid="cmp-detail-content">{{ selected.content }}</div>

            <!-- 409：版本冲突 / 非法迁移（details.allowed 原样展示人话） -->
            <div v-if="conflict" class="cmp-conflict" data-testid="cmp-conflict">
              <WTag tone="a" plain>409 状态冲突</WTag>
              <p v-if="conflictVersion" data-testid="cmp-conflict-version">
                投诉已被他人更新（请求版本 {{ conflictVersion.version }} → 服务端版本 {{ conflictVersion.currentVersion }}），
                请刷新详情后重试；本次动作未生效。
              </p>
              <p v-else data-testid="cmp-conflict-status">
                当前状态「{{ conflictStatus?.statusText }}」不支持该动作；服务端返回可用动作：
                <span class="cmp-conflict__allowed" data-testid="cmp-conflict-allowed">
                  <WTag v-for="label in allowedActionLabels(conflictStatus?.allowed ?? [])" :key="label" tone="b" plain>{{ label }}</WTag>
                  <span v-if="!(conflictStatus?.allowed ?? []).length">无（该状态为终态）</span>
                </span>
              </p>
              <WButton size="sm" variant="primary" data-testid="cmp-conflict-refresh" @click="refreshSelected">刷新详情</WButton>
            </div>

            <p v-if="actionError" class="cmp-note cmp-note--warn" data-testid="cmp-action-error">{{ actionError }}</p>

            <div class="cmp-actions" data-testid="cmp-actions">
              <WButton
                v-for="action in actionsFor"
                :key="action"
                size="sm"
                :variant="action === 'escalate' ? 'danger' : action === 'close' ? 'primary' : 'secondary'"
                :data-testid="`cmp-action-${action}`"
                @click="openAction(action)"
              >
                {{ COMPLAINT_ACTION_LABELS[action] }}
              </WButton>
              <span v-if="!actionsFor.length" class="cmp-cell-sub" data-testid="cmp-actions-empty">
                当前状态「{{ statusLabel(selected.status) }}」与角色「{{ currentRoleLabel }}」下无可用动作
                （升级仅 supervisor 可执行；已结案仅可重开）。
              </span>
            </div>

            <!-- audit[] 时间线：人话动作 + 操作人岗位/掩码 + 备注 -->
            <div class="cmp-timeline" data-testid="cmp-detail-audit">
              <header>处理轨迹（audit[] · 人话动作）</header>
              <ol>
                <li v-for="(item, index) in auditItems" :key="`${item.at}-${item.action}-${index}`" :data-action="item.action">
                  <span class="cmp-timeline__dot" aria-hidden="true" />
                  <div class="cmp-timeline__body">
                    <div class="cmp-timeline__head">
                      <b>{{ item.title }}</b>
                      <time class="num">{{ item.at }}</time>
                    </div>
                    <p v-if="item.note">{{ item.note }}</p>
                  </div>
                </li>
              </ol>
              <p v-if="!auditItems.length" class="cmp-cell-sub">audit[] 为空（服务端未返回处理轨迹）。</p>
            </div>

            <p class="cmp-boundary" data-testid="cmp-boundary">
              处置口径：投诉必须人工受理/首响/升级/结案，AI 只做分级建议与留痕；本页不自动回复、不自动结案、不写 ERP。
              关联工单由服务端在升级时只读联动（契约 transition 无 ticketNo 入参），前端不伪造工单号。
            </p>
          </div>
        </WCard>
      </div>
    </template>

    <!-- ============================ 动作弹层 ============================ -->
    <WModal v-model:open="actionOpen" :title="actionTitle" width="560px">
      <div class="cmp-modal" data-testid="cmp-action-modal">
        <dl class="cmp-rows" v-if="selected">
          <div>
            <dt>投诉</dt>
            <dd>{{ selected.complaintId }} · {{ maskRoomCode(selected.roomCode) }} · {{ statusLabel(selected.status) }}</dd>
          </div>
          <div>
            <dt>动作</dt>
            <dd>{{ COMPLAINT_ACTION_LABELS[actionKind] }}（{{ actionKind }}）</dd>
          </div>
          <div v-if="actionKind === 'escalate'">
            <dt>升级目标</dt>
            <dd>服务端按契约固定受理层级（回带 <code>escalatedTo</code>）；契约 transition 无 target 入参</dd>
          </div>
          <div v-if="actionKind === 'escalate'">
            <dt>关联工单</dt>
            <dd>升级时服务端只读关联「同房最近未结案」工单并回带 <code>ticketNo</code>；不自动建单</dd>
          </div>
        </dl>

        <label class="cmp-field">
          <span>{{ noteLabel }}</span>
          <textarea
            v-model="actionNote"
            rows="4"
            data-testid="cmp-action-note"
            :placeholder="actionKind === 'close' ? '如：已上门处理并回访，业主确认不再复现' : '如：已电话联系业主，约定明早上门核查'"
          />
        </label>
        <p v-if="actionError" class="cmp-note cmp-note--warn" data-testid="cmp-action-modal-error">{{ actionError }}</p>
        <p class="cmp-cell-sub">
          提交后写入服务端 audit[] 并推进状态机；版本乐观锁 = {{ selected?.version ?? '—' }}（被他人抢先更新会提示 409）。{{
            noteRequired ? '该动作备注为服务端强制必填。' : '备注可选。'
          }}
        </p>
      </div>
      <template #footer>
        <WButton size="sm" variant="ghost" data-testid="cmp-action-cancel" @click="actionOpen = false">取消</WButton>
        <WButton size="sm" variant="primary" data-testid="cmp-action-submit" :loading="actionBusy" @click="submitAction">
          确认{{ COMPLAINT_ACTION_LABELS[actionKind] }}
        </WButton>
      </template>
    </WModal>
  </div>
</template>

<style scoped>
.cmp {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
  min-width: 0;
}

.cmp-stats {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px 14px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-lg);
  background: var(--c-card);
}

.cmp-stats__head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  font-size: var(--fs-xs);
  color: var(--c-muted);
}

.cmp-stats__head b {
  font-size: 13px;
  color: var(--c-ink);
}

.cmp-stats__grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(120px, 160px)) minmax(0, 1fr) minmax(0, 1fr);
  gap: 8px;
}

.cmp-stat {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px 10px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-md);
  background: var(--c-bg-2);
}

.cmp-stat b {
  font-size: 18px;
  line-height: 1.2;
  color: var(--c-ink);
}

.cmp-stat span {
  font-size: 9.5px;
  color: var(--c-muted);
}

.cmp-stat.is-bad b {
  color: var(--c-red);
}

.cmp-stat--wide {
  gap: 5px;
}

.cmp-stat__label {
  font-size: 9.5px;
  color: var(--c-faint);
}

.cmp-stat__tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 10px;
}

.cmp-stat__tags > span {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 10px;
}

.cmp-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(0, 1fr);
  gap: var(--sp-3);
  align-items: start;
}

.cmp-card-title {
  font-size: 12.5px;
  font-weight: var(--fw-semibold);
  color: var(--c-ink);
}

.cmp-filters {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 10px 12px;
  border-bottom: 1px solid var(--c-line);
}

.cmp-filters label {
  display: flex;
  flex-direction: column;
  gap: 3px;
  font-size: 9.5px;
  color: var(--c-faint);
}

.cmp-filters select,
.cmp-filters input {
  height: 28px;
  padding: 0 8px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-sm);
  background: var(--c-card);
  font-size: var(--fs-xs);
  color: var(--c-ink-2);
}

.cmp-table-wrap {
  overflow-x: auto;
}

.cmp-table {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--fs-xs);
}

.cmp-table th {
  padding: 7px 10px;
  border-bottom: 1px solid var(--c-line);
  background: var(--c-bg-2);
  font-size: 9.5px;
  font-weight: var(--fw-medium);
  color: var(--c-faint);
  text-align: left;
  white-space: nowrap;
}

.cmp-table td {
  padding: 8px 10px;
  border-bottom: 1px solid var(--c-line);
  color: var(--c-ink-2);
  vertical-align: middle;
}

.cmp-row {
  cursor: pointer;
  transition: background-color var(--tr-fast);
}

.cmp-row:hover {
  background: var(--c-bg-2);
}

.cmp-row.is-on {
  background: var(--c-brand-soft);
}

.cmp-row:focus-visible {
  outline: none;
  box-shadow: var(--sh-ring);
}

.cmp-cell-id {
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.cmp-cell-id b {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--c-ink);
}

.cmp-cell-sub {
  font-size: 9.5px;
  color: var(--c-faint);
}

.cmp-sla {
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--c-muted);
  white-space: nowrap;
}

.cmp-sla.is-warn {
  color: var(--c-amber);
}

.cmp-sla.is-bad {
  font-weight: var(--fw-semibold);
  color: var(--c-red);
}

.cmp-link {
  color: var(--c-brand);
  text-decoration: none;
}

.cmp-link:hover {
  text-decoration: underline;
}

.cmp-pager {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 12px;
  font-size: 10px;
  color: var(--c-muted);
}

.cmp-pager > div {
  display: flex;
  gap: 6px;
}

.cmp-state {
  padding: 20px 12px;
}

.cmp-note {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  margin: 8px 12px;
  padding: 8px 10px;
  border: 1px dashed var(--c-line);
  border-radius: var(--rd-md);
  background: var(--c-bg-2);
  font-size: 10px;
  color: var(--c-ink-2);
}

.cmp-note--warn {
  border-color: var(--c-amber);
  background: var(--c-amber-soft);
}

.cmp-detail {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px 14px;
}

.cmp-detail__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
}

.cmp-detail__title {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
}

.cmp-detail__title b {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--c-ink);
}

.cmp-detail__sla {
  display: flex;
  flex-direction: column;
  gap: 1px;
  padding: 5px 8px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-sm);
  background: var(--c-bg-2);
  text-align: right;
}

.cmp-detail__sla b {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--c-ink-2);
}

.cmp-detail__sla span {
  font-size: 9px;
  color: var(--c-faint);
}

.cmp-detail__sla.is-bad {
  border-color: var(--c-red);
  background: var(--c-red-soft);
}

.cmp-detail__sla.is-bad b {
  color: var(--c-red);
}

.cmp-rows {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 6px 14px;
  margin: 0;
}

.cmp-rows > div {
  display: flex;
  gap: 8px;
  font-size: 10px;
}

.cmp-rows dt {
  flex: none;
  width: 58px;
  color: var(--c-faint);
}

.cmp-rows dd {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
  margin: 0;
  color: var(--c-ink-2);
}

.cmp-rows em {
  font-style: normal;
  color: var(--c-faint);
}

.cmp-rows code {
  padding: 0 3px;
  border-radius: var(--rd-xs);
  background: var(--c-bg-2);
  font-family: var(--font-mono);
  font-size: 9px;
}

.cmp-content {
  padding: 9px 11px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-md);
  background: var(--c-bg-2);
  font-size: var(--fs-xs);
  line-height: var(--lh-base);
  color: var(--c-ink-2);
  white-space: pre-wrap;
  word-break: break-word;
}

.cmp-conflict {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
  padding: 9px 11px;
  border: 1px solid var(--c-amber);
  border-radius: var(--rd-md);
  background: var(--c-amber-soft);
  font-size: 10px;
  color: var(--c-ink-2);
}

.cmp-conflict__allowed {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 4px;
}

.cmp-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
}

.cmp-timeline {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding-top: 8px;
  border-top: 1px dashed var(--c-line);
}

.cmp-timeline header {
  font-size: 10px;
  color: var(--c-faint);
}

.cmp-timeline ol {
  display: flex;
  flex-direction: column;
  margin: 0;
  padding: 0 0 0 2px;
  list-style: none;
}

.cmp-timeline li {
  position: relative;
  display: flex;
  gap: 8px;
  padding-bottom: 10px;
}

.cmp-timeline li:not(:last-child)::before {
  content: '';
  position: absolute;
  left: 4px;
  top: 12px;
  bottom: 0;
  width: 1px;
  background: var(--c-line);
}

.cmp-timeline__dot {
  width: 9px;
  height: 9px;
  margin-top: 3px;
  flex: none;
  border-radius: 50%;
  border: 2px solid var(--c-card);
  background: var(--c-brand);
  box-shadow: 0 0 0 1px var(--c-line);
  z-index: 1;
}

.cmp-timeline__body {
  flex: 1;
  min-width: 0;
}

.cmp-timeline__head {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.cmp-timeline__head b {
  font-size: 10.5px;
  color: var(--c-ink-2);
}

.cmp-timeline__head time {
  margin-left: auto;
  font-size: 9px;
  color: var(--c-faint);
}

.cmp-timeline__body p {
  margin: 2px 0 0;
  font-size: 10px;
  color: var(--c-muted);
  line-height: var(--lh-base);
  word-break: break-word;
}

.cmp-boundary {
  margin: 0;
  padding: 8px 10px;
  border: 1px dashed var(--c-line);
  border-radius: var(--rd-md);
  font-size: 9.5px;
  line-height: var(--lh-base);
  color: var(--c-faint);
}

.cmp-modal {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.cmp-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 10px;
  color: var(--c-faint);
}

.cmp-field textarea {
  padding: 8px 10px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-md);
  background: var(--c-card);
  font-family: inherit;
  font-size: var(--fs-xs);
  line-height: var(--lh-base);
  color: var(--c-ink-2);
  resize: vertical;
}

.cmp-field textarea:focus-visible {
  outline: none;
  border-color: var(--c-brand);
  box-shadow: var(--sh-ring);
}

@media (max-width: 1180px) {
  .cmp-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .cmp-stats__grid {
    grid-template-columns: repeat(3, minmax(110px, 1fr));
  }
}
</style>
