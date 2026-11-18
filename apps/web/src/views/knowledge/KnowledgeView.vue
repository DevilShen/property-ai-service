<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { WButton, WCard, WEmpty, WLoading, WModal, WPageHeader, WTabs, WTable, WTag, useToast } from '@/components'
import PermissionDenied from '@/components/feedback/PermissionDenied.vue'
import { RagAskPanel, RagHealthPanel } from '@/components/rag'
import { KnowledgeImportPanel } from '@/components/knowledge-import'
import { SCREENS } from '@/app/screens'
import { ROLE_LABELS } from '@/app/roles'
import { useAuthStore } from '@/stores/auth'
import { demoNote } from '@/content'
import {
  api,
  ApiError,
  degradedText,
  errorNextStep,
  errorText,
  forbiddenDetailsOf,
  formatIsoMinute,
} from '@/api'
import type {
  ActorRole,
  ForbiddenDetails,
  KnowledgeCategory,
  KnowledgeChangeRequest,
  KnowledgeDetail,
  KnowledgeItem,
  KnowledgeItemStatus,
  KnowledgeStats,
  ReviewDecision,
} from '@/api'
import type { TableColumn } from '@/types'
import StateDemo from '@/content/StateDemo.vue'
import {
  KNOWLEDGE_CATEGORY_OPTIONS,
  KNOWLEDGE_CHANGE_STATUS_LABELS,
  KNOWLEDGE_STATUS_LABELS,
  KNOWLEDGE_STATUS_OPTIONS,
  KNOWLEDGE_STATUS_TONES,
  REVIEW_DECISION_LABELS,
  allPendingReceipts,
  addYears,
  answerabilityOf,
  categoryLabel,
  ownerRows,
  pendingReceiptsOf,
  periodText,
  removeReceipt,
  saveReceipt,
  todayIso,
  versionRows,
  type KnowledgeChangeReceipt,
  type KnowledgeVersionRow,
} from './shared'

/**
 * 11 知识运营中心（P4-007，契约 v1.2.0 §13 / P4 W15）
 * ===========================================================================
 * 数据来源（**全部真实 API，不编造知识**）：
 * - 列表：`GET /api/v1/knowledge?status&category&owner&effectiveFrom&effectiveTo&page&pageSize`
 *   （服务端筛选 + 真实分页；owner 角色由服务端强制只回 published + 生效期）；
 * - 详情/版本：`GET /api/v1/knowledge/{id}`（含 `versions[]`）与 `GET /api/v1/knowledge/{id}/versions`；
 * - 变更提交：`POST /api/v1/knowledge/{id}/change`（staff → **201 pending_review，不直接生效**）；
 * - 审核：`POST /api/v1/knowledge/{id}/review`（**仅 it/admin**；approved 生成新版本并生效、rejected 需 note）；
 * - 采纳看板：`GET /api/v1/knowledge/stats`（adoptionRate / adopted / rejected / gaps / expiredSoon / owners
 *   全部由服务端聚合，前端只格式化，不自算）。
 *
 * 口径与红线（AGENTS.md「不编造知识」+ README §7.4）：
 * - 过期/草稿/待审/驳回**不得对外作答**：详情页对 staff 明确标注「已过期 · 不可对外作答」；
 *   检索（Copilot 知识秒查）只展示服务端返回的 published + 生效期命中的条目，前端不补展示；
 * - 引用必须带版本（`K-002 V1.1 · 公共能耗分摊办法 §3`）：版本历史来自服务端，前端不补写；
 * - 变更**不直接生效**：提交只进 `pending_review`，必须 it/admin 审核；提交人不得自审（服务端 403）；
 * - 权限边界在服务端：403 `E-2001`（含 `details.requiredCapability`）、409 `E-3002`
 *   （`change_pending` / `invalid_transition`）均按人话提示，页面隐藏入口不是安全边界；
 * - 演示口径：知识来自 `mock/data/knowledge.json`，审核为**进程内演示存储**（重启回滚），不接真实知识库。
 */
const screen = SCREENS.knowledge
const auth = useAuthStore()
const toast = useToast()
const router = useRouter()
const PAGE_SIZE = 10

/* ============================================== ⓪ 页签（P6 W33 RAG 增量） */
/**
 * 页签（**不改 12 屏导航矩阵**：RAG 问答/健康/知识导入挂在知识页内，不新增 12 屏导航项）：
 * - `ops`：原知识运营视图（列表/详情/变更/审核/采纳看板），默认页签，DOM 与既有断言完全一致；
 * - `import`：知识导入（P6 W34 task-87；`knowledge.import` 才显示；粘贴/上传/ZIP → 预览 → 提交待审/直审）；
 *   task-88 起页头另有「知识导入页」入口 → 非导航路由 `/knowledge/import`（同一组件/同一 API，
 *   供 12 屏矩阵里看不到本屏、但有 `knowledge.import` 能力的 agent 使用；本页签保留、staff 现状不变）；
 * - `ask`：知识秒查 RAG（`POST /api/v1/rag/query`：answer + `[n]` 引用 + 来源卡片 + 拒答 + 五态）；
 * - `health`：RAG 链路健康（`GET /api/v1/rag/health`，**仅 it/admin**；能力码 `rag.health` 来自
 *   `auth/capabilities.ts` 既有矩阵，前端只做入口显隐，真实 403 边界在 Java）。
 */
type KnowledgeTab = 'ops' | 'import' | 'ask' | 'health'
const activeTab = ref<KnowledgeTab>('ops')
const canRagHealth = computed(() => auth.hasCapability('rag.health'))
/** 导入入口按能力显隐（owner 无 knowledge.import → 不显示页签；真实边界在 Java 403） */
const canImport = computed(() => auth.hasCapability('knowledge.import'))
const tabs = computed(() => {
  const items: { key: KnowledgeTab; label: string; badge?: string }[] = [
    { key: 'ops', label: '运营视图' },
  ]
  if (canImport.value) items.push({ key: 'import', label: '导入', badge: 'P6' })
  items.push({ key: 'ask', label: '智能问答', badge: 'RAG' })
  if (canRagHealth.value) items.push({ key: 'health', label: 'RAG 健康', badge: 'it/admin' })
  return items
})

/** 审核仅 it/admin（Java `PermissionMatrix.KNOWLEDGE_REVIEW`；前端只用于隐藏入口） */
const canReview = computed(() => auth.role === 'it' || auth.role === 'admin')
const currentRoleLabel = computed(() => (auth.role ? ROLE_LABELS[auth.role] : '未登录'))
const switching = ref(false)
/** 无权限态可切换的演示账号（审核需要 it/admin；列表/统计 staff 亦可） */
const deniedCandidates: readonly ActorRole[] = ['it', 'admin']

/** 403 细节 + traceId（契约 `Forbidden`：requiredCapability/role/reason） */
type DeniedInfo = ForbiddenDetails & { traceId?: string }

function deniedOf(error: unknown): DeniedInfo | null {
  const details = forbiddenDetailsOf(error)
  if (!details) return null
  return { ...details, traceId: error instanceof ApiError ? error.traceId : '' }
}

/** 错误 → 人话（不展示上游原文/内部码；契约 17 类错误码统一走 content 文案） */
function apiMessage(error: unknown): string {
  if (error instanceof ApiError) return `${errorText(error.code)}；${errorNextStep(error.code)}`
  return '请求失败：请稍后重试或转人工'
}

/** 409 语义 → 人话（契约 `E-3002`：change_pending / invalid_transition / self_review_forbidden 等） */
function conflictMessage(error: unknown): string {
  if (!(error instanceof ApiError)) return apiMessage(error)
  const reason = typeof error.details?.reason === 'string' ? error.details.reason : ''
  const changeId = typeof error.details?.changeId === 'string' ? error.details.changeId : ''
  if (reason === 'change_pending') {
    return `该知识已有待审核变更单${changeId ? `（${changeId}）` : ''}，请等待 it/admin 审核完成后再提交`
  }
  if (reason === 'invalid_transition') {
    return `该变更单已处理或状态不允许审核${changeId ? `（${changeId}）` : ''}；已通过的变更不可重复审核，新调整请重新提交变更单`
  }
  if (reason === 'self_review_forbidden') {
    return '提交人不得审核自己提交的变更（需他人复核），请换一个 it/admin 演示账号'
  }
  return apiMessage(error)
}

/* ============================================================ ① 采纳看板 */

const stats = ref<KnowledgeStats | null>(null)
const statsLoading = ref(false)
const statsError = ref('')
const statsDenied = ref<DeniedInfo | null>(null)
const statsDegraded = ref('')

async function loadStats(): Promise<void> {
  statsLoading.value = true
  statsError.value = ''
  statsDenied.value = null
  statsDegraded.value = ''
  try {
    const { data, degraded } = await api.knowledge.stats()
    stats.value = data
    if (degraded) statsDegraded.value = degradedText(degraded)
  } catch (error) {
    stats.value = null
    const denied = deniedOf(error)
    if (denied) statsDenied.value = denied
    else statsError.value = apiMessage(error)
  } finally {
    statsLoading.value = false
  }
}

/** 采纳率（服务端 0—1，前端只做百分比格式化；无引用时服务端给 0，不编造） */
const adoptionPercent = computed(() => {
  const rate = stats.value?.adoptionRate
  if (typeof rate !== 'number' || Number.isNaN(rate)) return '0%'
  return `${(Math.round(rate * 1000) / 10).toFixed(1)}%`
})
const publishedCount = computed(() => stats.value?.byStatus?.published ?? 0)
const expiredSoon = computed(() => stats.value?.expiredSoon ?? 0)
const gaps = computed(() => stats.value?.gaps ?? [])
const ownerStats = computed(() => stats.value?.owners ?? [])

/* ========================================================== ② 列表 + 筛选 */

const filters = reactive({
  status: '' as KnowledgeItemStatus | '',
  category: '' as KnowledgeCategory | '',
  owner: '',
  effectiveFrom: '',
  effectiveTo: '',
})

const items = ref<KnowledgeItem[]>([])
const total = ref(0)
const page = ref(1)
const listLoading = ref(false)
const listError = ref('')
const listDenied = ref<DeniedInfo | null>(null)
const listDegraded = ref('')

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / PAGE_SIZE)))
const canPrev = computed(() => page.value > 1 && !listLoading.value)
const canNext = computed(() => page.value < totalPages.value && !listLoading.value)

/** 责任人筛选项：以服务端 `stats.owners` 为准，并补当前页出现过的责任人（不去重编造） */
const ownerOptions = computed(() => {
  const names = new Set<string>()
  for (const owner of ownerStats.value) if (owner.owner) names.add(owner.owner)
  for (const item of items.value) if (item.owner) names.add(item.owner)
  return [...names].sort((a, b) => a.localeCompare(b, 'zh-Hans-CN'))
})

const hasFilter = computed(
  () =>
    Boolean(filters.status) ||
    Boolean(filters.category) ||
    Boolean(filters.owner) ||
    Boolean(filters.effectiveFrom) ||
    Boolean(filters.effectiveTo),
)

const tableRows = computed<Record<string, unknown>[]>(
  () => items.value.map((item) => ({ ...item })) as unknown as Record<string, unknown>[],
)

function itemOf(row: unknown): KnowledgeItem {
  return row as KnowledgeItem
}

async function loadList(): Promise<void> {
  listLoading.value = true
  listError.value = ''
  listDenied.value = null
  listDegraded.value = ''
  try {
    const { data, degraded } = await api.knowledge.list({
      page: page.value,
      pageSize: PAGE_SIZE,
      ...(filters.status ? { status: filters.status } : {}),
      ...(filters.category ? { category: filters.category } : {}),
      ...(filters.owner ? { owner: filters.owner } : {}),
      ...(filters.effectiveFrom ? { effectiveFrom: filters.effectiveFrom } : {}),
      ...(filters.effectiveTo ? { effectiveTo: filters.effectiveTo } : {}),
    })
    items.value = data.items
    total.value = data.total
    if (degraded) listDegraded.value = degradedText(degraded)
    const stillThere = data.items.find((item) => item.knowledgeId === selectedId.value)
    if (stillThere) {
      // 保持当前选择，顺便刷新详情（状态/版本可能已被审核更新）
      void loadDetail(stillThere.knowledgeId)
    } else if (data.items[0]) {
      selectRow(data.items[0])
    } else {
      selectedId.value = ''
      detail.value = null
    }
  } catch (error) {
    items.value = []
    total.value = 0
    const denied = deniedOf(error)
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
 * 重置筛选：清空全部条件 → 回第 1 页 → **重新走服务端列表请求**。
 * 可见反馈口径（P4 死按钮门禁）：无论原本是否有筛选，点击后都必须有可见反馈 ——
 * 有筛选时列表/总结变化 + Toast；本来已是默认条件时（DOM 不变）靠 Toast 说明「已是默认」，
 * 不静默 no-op，也不编造列表数据。
 */
async function resetFilters(): Promise<void> {
  const hadFilter = hasFilter.value
  filters.status = ''
  filters.category = ''
  filters.owner = ''
  filters.effectiveFrom = ''
  filters.effectiveTo = ''
  page.value = 1
  await loadList()
  if (listDenied.value) {
    toast.warning('筛选已重置，但当前演示角色缺少 knowledge.read 能力，列表未刷新')
    return
  }
  if (listError.value) {
    toast.error(`筛选已重置，但知识列表刷新失败：${listError.value}`)
    return
  }
  const scope = hadFilter ? '已重置筛选（状态/分类/责任人/生效期已清空）' : '筛选条件已是默认（全部状态/分类/责任人，未限生效期）'
  toast.success(`${scope} · 共 ${total.value} 条 · 第 ${page.value}/${totalPages.value} 页`)
}

function gotoPage(next: number): void {
  if (next < 1 || next > totalPages.value) return
  page.value = next
  void loadList()
}

async function refreshAll(): Promise<void> {
  await Promise.all([loadList(), loadStats()])
  if (listDenied.value || statsDenied.value) {
    toast.warning('刷新被拒绝：当前演示角色缺少 knowledge.read / knowledge.stats 能力')
    return
  }
  if (listError.value) {
    toast.error(`知识列表刷新未完成：${listError.value}`)
    return
  }
  toast.success(`知识列表已刷新 · 共 ${total.value} 条 · 第 ${page.value}/${totalPages.value} 页`)
}

/* ============================================================== ③ 详情 */

const selectedId = ref('')
const detail = ref<KnowledgeDetail | null>(null)
const detailLoading = ref(false)
const detailError = ref('')
const detailDenied = ref<DeniedInfo | null>(null)

const answerability = computed(() => (detail.value ? answerabilityOf(detail.value) : null))
const versionTableRows = computed(() => (detail.value ? versionRows(detail.value.versions ?? []) : []))

/** 版本表格行（模板里不做 TS 断言，统一走本 helper） */
function versionRowOf(row: unknown): KnowledgeVersionRow {
  return row as KnowledgeVersionRow
}

const detailColumns: TableColumn[] = [
  { key: 'version', title: '版本', width: '58px' },
  { key: 'statusLabel', title: '状态', width: '76px' },
  { key: 'effectiveFrom', title: '生效日', width: '92px' },
  { key: 'submittedAtText', title: '提交时间', width: '92px' },
  { key: 'submittedBy', title: '提交人', width: '84px' },
  { key: 'changeText', title: '变更单 / 审核意见' },
]

async function loadDetail(knowledgeId: string): Promise<void> {
  detailLoading.value = true
  detailError.value = ''
  detailDenied.value = null
  try {
    const { data } = await api.knowledge.get(knowledgeId)
    detail.value = data
  } catch (error) {
    detail.value = null
    const denied = deniedOf(error)
    if (denied) detailDenied.value = denied
    else detailError.value = apiMessage(error)
  } finally {
    detailLoading.value = false
  }
}

function selectRow(item: KnowledgeItem): void {
  if (selectedId.value === item.knowledgeId && detail.value) return
  selectedId.value = item.knowledgeId
  void loadDetail(item.knowledgeId)
  reviewError.value = ''
  changeError.value = ''
}

/**
 * 列表行「详情 / 已选」按钮：点击必有可见反馈（P4 死按钮门禁）。
 * - 首次选中：右侧详情卡随 `loadDetail` 渲染（DOM 变化）+ Toast 说明选中对象；
 * - 重复点击已选行（按钮文案「已选」，DOM 不会变）：重读一次详情并 Toast 说明「已在详情栏」，
 *   既不造数、也不改选择语义（「变更」仍以该行为准）。
 */
function selectRowFromList(item: KnowledgeItem): void {
  const alreadySelected = isSelected(item.knowledgeId) && Boolean(detail.value)
  selectRow(item)
  if (alreadySelected) {
    void loadDetail(item.knowledgeId)
    toast.info(
      `${item.knowledgeId} 已在右侧详情栏（${item.version} · ${KNOWLEDGE_STATUS_LABELS[item.status] ?? item.status}），已重新读取详情`,
    )
    return
  }
  toast.success(`已选中 ${item.knowledgeId} · ${item.title}（详情/变更以此为准）`)
}

/** 选择态（表格行高亮用：WTable 无行点击事件，用操作列按钮区分） */
function isSelected(knowledgeId: string): boolean {
  return selectedId.value === knowledgeId
}

/* ================================================== ④ 变更回执（待审核） */

/** 回执缓存版本号：写入/移除回执后 +1，触发 computed 重算 */
const receiptsVersion = ref(0)

const selectedReceipts = computed<KnowledgeChangeReceipt[]>(() => {
  void receiptsVersion.value
  const id = selectedId.value
  return id ? pendingReceiptsOf(id) : []
})

const allReceipts = computed<KnowledgeChangeReceipt[]>(() => {
  void receiptsVersion.value
  return allPendingReceipts()
})

/** 待审核知识 ID 集合（列表行「变更加审中」标记用；来源 = POST /change 201 回执缓存） */
const pendingIdSet = computed<Set<string>>(() => {
  void receiptsVersion.value
  return new Set(allPendingReceipts().map((receipt) => receipt.knowledgeId))
})

function receiptStatusLabel(receipt: KnowledgeChangeReceipt): string {
  return KNOWLEDGE_CHANGE_STATUS_LABELS[receipt.status] ?? receipt.status
}

/* ======================================================== ⑤ 提交变更（staff） */

const changeOpen = ref(false)
const changeSubmitting = ref(false)
const changeError = ref('')
const changeMissing = ref<string[]>([])
const changeForm = reactive({
  content: '',
  effectiveFrom: '',
  effectiveTo: '',
  reason: '',
})

function openChange(): void {
  if (!detail.value) {
    toast.warning('请先选择一条知识再提交变更')
    return
  }
  changeError.value = ''
  changeMissing.value = []
  const from = (detail.value.effectiveFrom ?? '').trim() || todayIso()
  // mock 基线多数条目 `effectiveTo` 为 null（契约字段必填但服务端省略）：按生效日 +1 年预填，用户可改
  const to = (detail.value.effectiveTo ?? '').trim() || addYears(from, 1)
  changeForm.content = detail.value.content ?? ''
  changeForm.effectiveFrom = from
  changeForm.effectiveTo = to
  changeForm.reason = ''
  changeOpen.value = true
}

async function submitChange(): Promise<void> {
  const target = detail.value
  if (!target) return
  const missing: string[] = []
  if (!changeForm.content.trim()) missing.push('正文 content')
  if (!changeForm.effectiveFrom) missing.push('生效日 effectiveFrom')
  if (!changeForm.effectiveTo) missing.push('失效日 effectiveTo')
  if (!changeForm.reason.trim()) missing.push('变更原因 reason')
  changeMissing.value = missing
  if (missing.length > 0) {
    changeError.value = `请补齐必填项：${missing.join('、')}`
    return
  }
  if (changeForm.effectiveFrom > changeForm.effectiveTo) {
    changeError.value = '生效日不能晚于失效日（服务端会返回 400 E-1001）'
    return
  }
  const body: KnowledgeChangeRequest = {
    content: changeForm.content.trim(),
    effectiveFrom: changeForm.effectiveFrom,
    effectiveTo: changeForm.effectiveTo,
    reason: changeForm.reason.trim(),
  }
  changeSubmitting.value = true
  changeError.value = ''
  try {
    const { data } = await api.knowledge.change(target.knowledgeId, body)
    saveReceipt({
      changeId: data.changeId,
      knowledgeId: data.knowledgeId,
      knowledgeTitle: target.title,
      status: data.status,
      submittedBy: data.submittedBy,
      submittedAt: data.submittedAt,
      reason: data.reason,
    })
    receiptsVersion.value += 1
    changeOpen.value = false
    toast.success(`变更已提交：${data.changeId} · ${KNOWLEDGE_CHANGE_STATUS_LABELS[data.status]}（不直接生效，需 it/admin 审核）`)
    await Promise.all([loadList(), loadStats()])
  } catch (error) {
    changeError.value =
      error instanceof ApiError && error.httpStatus === 409 ? conflictMessage(error) : apiMessage(error)
  } finally {
    changeSubmitting.value = false
  }
}

/* ==================================================== ⑥ 审核（仅 it/admin） */

const reviewOpen = ref(false)
const reviewSubmitting = ref(false)
const reviewError = ref('')
const reviewForm = reactive({
  knowledgeId: '',
  changeId: '',
  decision: 'approved' as ReviewDecision,
  note: '',
})

function openReview(changeId?: string, knowledgeId?: string): void {
  const kid = (knowledgeId || detail.value?.knowledgeId || selectedId.value || '').trim()
  if (!kid) {
    toast.warning('请先选择一条知识再审核变更')
    return
  }
  if (!canReview.value) {
    toast.warning(`审核需 it/admin（knowledge.review）；当前角色「${currentRoleLabel.value}」无审核权限`)
    return
  }
  reviewError.value = ''
  reviewForm.knowledgeId = kid
  reviewForm.changeId = changeId || pendingReceiptsOf(kid)[0]?.changeId || ''
  reviewForm.decision = 'approved'
  reviewForm.note = ''
  reviewOpen.value = true
}

/** 从「待审变更（本机回执）」面板直接审核：先选中该知识，再开审核弹层 */
function reviewFromReceipt(receipt: KnowledgeChangeReceipt): void {
  if (selectedId.value !== receipt.knowledgeId) {
    selectedId.value = receipt.knowledgeId
    void loadDetail(receipt.knowledgeId)
  }
  openReview(receipt.changeId, receipt.knowledgeId)
}

async function submitReview(): Promise<void> {
  const kid = reviewForm.knowledgeId.trim()
  if (!kid) {
    reviewError.value = '缺少知识 ID：请先选择知识'
    return
  }
  if (!reviewForm.changeId.trim()) {
    reviewError.value = '请填写待审核变更单号（changeId，来自提交回执）'
    return
  }
  if (reviewForm.decision === 'rejected' && !reviewForm.note.trim()) {
    reviewError.value = '驳回必须填写审核意见（note，服务端会返回 400 E-1001）'
    return
  }
  reviewSubmitting.value = true
  reviewError.value = ''
  try {
    const { data } = await api.knowledge.review(kid, {
      changeId: reviewForm.changeId.trim(),
      decision: reviewForm.decision,
      ...(reviewForm.note.trim() ? { note: reviewForm.note.trim() } : {}),
    })
    removeReceipt(data.changeId)
    receiptsVersion.value += 1
    reviewOpen.value = false
    toast.success(
      `审核完成：${data.changeId} · ${KNOWLEDGE_CHANGE_STATUS_LABELS[data.status]}` +
        (data.status === 'approved' ? ' · 新版本已生效（检索/详情立即读新版本）' : ' · 变更单关闭，不产生新版本'),
    )
    await Promise.all([loadDetail(kid), loadList(), loadStats()])
  } catch (error) {
    if (error instanceof ApiError && (error.httpStatus === 409 || error.httpStatus === 403)) {
      reviewError.value = conflictMessage(error)
    } else {
      reviewError.value = apiMessage(error)
    }
  } finally {
    reviewSubmitting.value = false
  }
}

/* ============================================== ⑦ 新增知识（契约无此端点） */

const createOpen = ref(false)

/* ============================================ ⑧ 403 无权限态：切演示账号 */

async function switchAccount(role: ActorRole): Promise<void> {
  if (switching.value) return
  switching.value = true
  try {
    const ok = await auth.loginAsRole(role)
    if (!ok) {
      toast.error(`演示账号切换失败：${auth.failure?.message ?? '登录接口不可用'}`)
      return
    }
    toast.info(`已切换演示账号：${ROLE_LABELS[role]}（demo，非真实鉴权），正在重新读取知识数据…`)
  } finally {
    switching.value = false
  }
}

/* ================================ ⑨ 页签深链（RAG 缺口沉淀 → 导入页签 / 导入→运营） */

const route = useRoute()
/** RAG 拒答卡「补充这条知识」带来的预填（问题标题 + sourceType=badcase） */
const gapPrefill = ref<{ question: string; sourceType: 'badcase' } | null>(null)

function queryText(value: unknown): string {
  if (typeof value === 'string') return value
  if (Array.isArray(value) && typeof value[0] === 'string') return value[0]
  return ''
}

/**
 * 页签深链口径：`/knowledge?tab=import&gap=<问题>`（**保留兼容**：旧书签/旧链接仍可用；
 * task-88 起 `RagAskPanel` 拒答卡「补充这条知识」改跳非导航路由 `/knowledge/import?gap=`
 * ——因为 `knowledge.import` 含 agent 而本屏可见性矩阵不含 agent，页签对 agent 不可达）。
 * tab 只接受当前角色可见的页签，gap 只作为预填文本（≤200 字符）。
 */
function applyRouteQuery(): void {
  const tab = queryText(route.query.tab)
  if (tab && tabs.value.some((item) => item.key === tab)) {
    activeTab.value = tab as KnowledgeTab
  }
  const gap = queryText(route.query.gap).trim()
  if (gap) gapPrefill.value = { question: gap.slice(0, 200), sourceType: 'badcase' }
}

/** 导入页签内的「重复知识/完成任务」跳转：切回运营视图并按 id 读取知识详情（无 id 时只切页签） */
function openKnowledgeFromImport(knowledgeId?: string): void {
  activeTab.value = 'ops'
  const id = (knowledgeId ?? '').trim()
  if (!id) {
    toast.info('已切换到运营视图：可在知识清单按标题检索新导入的知识（提交待审的需 it/admin 审核后才发布）')
    return
  }
  selectedId.value = id
  void loadDetail(id)
  toast.info(`已切到运营视图并读取知识 ${id} 详情`)
}

/**
 * 知识页头入口 → 非导航路由 `/knowledge/import`（task-88）。
 * 与本页签是同一 `KnowledgeImportPanel` 与同一套 API；页签保留（staff/it/admin 现状不变），
 * 另开独立页是为了让 12 屏矩阵看不到本屏的 agent 也能直达导入（能力 knowledge.import 准入）。
 */
function openImportPage(): void {
  void router.push('/knowledge/import')
}

watch(
  () => route.query,
  () => applyRouteQuery(),
)

/**
 * 预填只消费一次：切离导入页签后清空 `gapPrefill`，避免再次进入时覆盖用户已编辑内容
 * （深链 `?tab=import&gap=` 仍在地址栏，但表单不会被二次重置）。
 */
watch(activeTab, (tab) => {
  if (tab !== 'import') gapPrefill.value = null
})

/* ================================================================= ⑩ 生命周期 */

onMounted(() => {
  applyRouteQuery()
  void Promise.all([loadList(), loadStats()])
})

/** 角色切换（顶栏演示账号）后：清掉旧身份的无权限态并全部重取 */
watch(
  () => auth.role,
  () => {
    listDenied.value = null
    statsDenied.value = null
    detailDenied.value = null
    page.value = 1
    void Promise.all([loadList(), loadStats()])
  },
)

const listColumns: TableColumn[] = [
  { key: 'knowledgeId', title: '知识 ID', width: '78px' },
  { key: 'title', title: '标题' },
  { key: 'version', title: '版本', width: '58px' },
  { key: 'status', title: '状态', width: '86px' },
  { key: 'owner', title: '责任人', width: '82px' },
  { key: 'period', title: '生效期', width: '150px' },
  { key: 'badcase', title: 'badcase', width: '72px' },
  { key: 'action', title: '操作', width: '118px' },
]

const detailMeta = computed(() => {
  if (!detail.value) return []
  const item = detail.value
  return [
    { label: '知识 ID', value: item.knowledgeId, testid: 'kb-detail-id' },
    { label: '版本', value: item.version || '—', testid: 'kb-detail-version' },
    { label: '状态', value: KNOWLEDGE_STATUS_LABELS[item.status] ?? item.status, testid: 'kb-detail-status' },
    { label: '分类', value: categoryLabel(item.category), testid: 'kb-detail-category' },
    { label: '责任人', value: item.owner || '—', testid: 'kb-detail-owner' },
    { label: '生效期', value: periodText(item), testid: 'kb-detail-period' },
  ]
})

const sourceText = computed(() => {
  const source = detail.value?.source
  if (!source) return '—'
  return [source.doc, source.clause, source.page].filter(Boolean).join(' · ')
})
</script>

<template>
  <div class="w-page kb-page" data-page="knowledge" data-testid="page-root">
    <WPageHeader :eyebrow="screen.eyebrow" :title="screen.title" :description="screen.description">
      <template #actions>
        <WTag tone="g" plain>知识来自 API · 前端不编造</WTag>
        <WButton size="sm" icon="refresh" data-testid="kb-refresh" @click="refreshAll">刷新</WButton>
        <WButton
          v-if="canImport"
          size="sm"
          variant="ghost"
          icon="arrow-right"
          data-testid="kb-open-import-page"
          @click="openImportPage"
        >
          知识导入页
        </WButton>
        <WButton size="sm" variant="primary" icon="plus" data-testid="knowledge-create-open" @click="createOpen = true">
          新增知识
        </WButton>
      </template>
    </WPageHeader>

    <!-- 页签：运营视图（原功能）/ 智能问答（RAG）/ RAG 健康（仅 it/admin）——不新增路由 -->
    <div class="kb__tabbar" data-testid="kb-tabs">
      <WTabs v-model="activeTab" :tabs="tabs" data-testid="kb-tabs-items" />
      <span class="kb__tabbar-note" data-testid="kb-tab-note">
        <template v-if="activeTab === 'ops'">知识运营：清单 / 版本 / 变更审核 / 采纳与缺口（12 屏基线不变）</template>
        <template v-else-if="activeTab === 'import'">
          知识导入：粘贴 / 上传 / 批量 ZIP → 切分预览 → 提交待审（it/admin 可直审；POST /api/v1/knowledge/imports）
        </template>
        <template v-else-if="activeTab === 'ask'">
          RAG 秒查：混合检索 + 引用校验 + 拒答（POST /api/v1/rag/query，能力 rag.query）
        </template>
        <template v-else>
          RAG 链路健康：编排层 / LLM / Embedding / 向量库（GET /api/v1/rag/health，仅 it/admin）
        </template>
      </span>
    </div>

    <template v-if="activeTab === 'ops'">
      <!-- ================= 采纳看板（GET /knowledge/stats） ================= -->
    <section class="kb__stats" data-testid="kb-stats">
      <div class="kb__stat" data-testid="kb-stats-total">
        <span class="kb__stat-lab">知识总数</span>
        <b class="kb__stat-val num" data-testid="kb-stats-total-value">{{ stats?.total ?? '—' }}</b>
        <span class="kb__stat-delta">
          <template v-if="stats">生效中 {{ publishedCount }} 条 · 已过期/草稿/待审/驳回按状态筛选可见</template>
          <template v-else>口径：GET /api/v1/knowledge/stats</template>
        </span>
      </div>
      <div class="kb__stat" data-testid="kb-stats-published">
        <span class="kb__stat-lab">生效中（published）</span>
        <b class="kb__stat-val num" data-testid="kb-stats-published-value">{{ stats ? publishedCount : '—' }}</b>
        <span class="kb__stat-delta">检索只返回 published 且在生效期内的条目</span>
      </div>
      <div class="kb__stat" data-testid="kb-stats-adoption">
        <span class="kb__stat-lab">采纳率 adoptionRate</span>
        <b class="kb__stat-val num" data-testid="kb-stats-adoption-value">{{ adoptionPercent }}</b>
        <span class="kb__stat-delta">
          <template v-if="stats">
            已采纳 {{ stats.adopted }} · 已驳回 {{ stats.rejected }}（adopted/(adopted+rejected)，服务端口径）
          </template>
          <template v-else>无引用数据记 0，不编造</template>
        </span>
      </div>
      <div class="kb__stat" :class="{ 'is-warn': expiredSoon > 0 }" data-testid="kb-stats-expired-soon">
        <span class="kb__stat-lab">30 天内到期 expiredSoon</span>
        <b class="kb__stat-val num" data-testid="kb-stats-expired-soon-value">{{ stats ? expiredSoon : '—' }}</b>
        <span class="kb__stat-delta">过期知识不得对外作答，需责任人复核</span>
      </div>
    </section>

    <p v-if="statsLoading && !stats" class="kb__loadline" data-testid="kb-stats-loading">
      <WLoading label="正在读取知识统计（GET /api/v1/knowledge/stats）…" />
    </p>
    <p v-else-if="statsDenied" class="kb__noteline" data-testid="kb-stats-denied">
      <WTag tone="r" plain>无权限 403</WTag>
      知识统计需要能力 {{ statsDenied.requiredCapability || 'knowledge.stats' }}（当前角色「{{ currentRoleLabel }}」不展示未授权数字）。
    </p>
    <p v-else-if="statsError" class="kb__noteline" data-testid="kb-stats-error">
      <WTag tone="r" plain>统计不可用</WTag>{{ statsError }}
      <WButton size="sm" variant="ghost" @click="loadStats">重试</WButton>
    </p>
    <p v-else-if="statsDegraded" class="kb__noteline" data-testid="kb-stats-degraded">
      <WTag tone="a" plain>已降级</WTag>{{ statsDegraded }}
    </p>

    <!-- ================= 列表 403（服务端能力边界） ================= -->
    <PermissionDenied
      v-if="listDenied"
      data-testid="kb-denied"
      :required-capability="listDenied.requiredCapability"
      :required-role="listDenied.role"
      :trace-id="listDenied.traceId"
      :switching="switching"
      :candidate-roles="deniedCandidates"
      detail="知识运营中心需要 knowledge.read 能力；当前演示角色不具备，页面不会展示未授权知识数据。"
      @switch="switchAccount"
    >
      <template #actions>
        <WButton size="sm" variant="primary" data-testid="kb-denied-retry" @click="refreshAll">重新加载</WButton>
      </template>
    </PermissionDenied>

    <div v-else class="kb__grid">
      <!-- ---------------------------- 列表 ---------------------------- -->
      <WCard class="kb__list" :padded="false" data-testid="kb-list-card">
        <template #head>
          <span class="kb__card-title">知识清单</span>
          <WTag tone="b" plain>{{ total }} 条</WTag>
          <WTag tone="n" plain>演示数据（mock 知识库）</WTag>
          <WTag v-if="listDegraded" tone="a" plain>已降级</WTag>
        </template>
        <template #extra>
          <span class="dim">第 {{ page }}/{{ totalPages }} 页 · 每页 {{ PAGE_SIZE }}</span>
        </template>

        <!-- 筛选：status / category / owner / 生效期（全部服务端执行） -->
        <div class="kb__filters" data-testid="kb-filters">
          <label class="kb__field">
            <span>状态</span>
            <select v-model="filters.status" data-testid="kb-filter-status" @change="applyFilters">
              <option value="">全部状态</option>
              <option v-for="option in KNOWLEDGE_STATUS_OPTIONS" :key="option.value" :value="option.value">
                {{ option.label }}
              </option>
            </select>
          </label>
          <label class="kb__field">
            <span>分类</span>
            <select v-model="filters.category" data-testid="kb-filter-category" @change="applyFilters">
              <option value="">全部分类</option>
              <option v-for="option in KNOWLEDGE_CATEGORY_OPTIONS" :key="option.value" :value="option.value">
                {{ option.label }}
              </option>
            </select>
          </label>
          <label class="kb__field">
            <span>责任人</span>
            <select v-model="filters.owner" data-testid="kb-filter-owner" @change="applyFilters">
              <option value="">全部责任人</option>
              <option v-for="name in ownerOptions" :key="name" :value="name">{{ name }}</option>
            </select>
          </label>
          <label class="kb__field">
            <span>生效期起</span>
            <input v-model="filters.effectiveFrom" type="date" data-testid="kb-filter-from" @change="applyFilters" />
          </label>
          <label class="kb__field">
            <span>生效期止</span>
            <input v-model="filters.effectiveTo" type="date" data-testid="kb-filter-to" @change="applyFilters" />
          </label>
          <WButton size="sm" variant="ghost" data-testid="kb-filter-reset" @click="resetFilters">重置</WButton>
          <span class="kb__filter-note" data-testid="kb-filter-summary">
            {{ hasFilter ? '已按条件筛选（服务端）' : '未筛选 · 按知识 ID 升序' }}
          </span>
        </div>

        <WTable
          :columns="listColumns"
          :rows="tableRows"
          row-key="knowledgeId"
          dense
          :loading="listLoading"
          empty-text="当前筛选无知识条目：清空筛选条件重试，或确认知识是否已入库。"
        >
          <template #knowledgeId="{ row }">
            <span class="num kb__id">{{ itemOf(row).knowledgeId }}</span>
          </template>
          <template #title="{ row }">
            <div class="kb__title">
              <b>{{ itemOf(row).title }}</b>
              <span>
                {{ categoryLabel(itemOf(row).category) }}
                <template v-if="(itemOf(row).refs ?? []).length"> · 关联 {{ (itemOf(row).refs ?? []).length }} 条</template>
              </span>
            </div>
          </template>
          <template #version="{ row }">
            <span class="num">{{ itemOf(row).version }}</span>
          </template>
          <template #status="{ row }">
            <span class="kb__statuscell" :data-testid="`kb-row-status-${itemOf(row).knowledgeId}`">
              <WTag :tone="KNOWLEDGE_STATUS_TONES[itemOf(row).status]">
                {{ KNOWLEDGE_STATUS_LABELS[itemOf(row).status] ?? itemOf(row).status }}
              </WTag>
              <WTag v-if="pendingIdSet.has(itemOf(row).knowledgeId)" tone="a" plain>变更加审中</WTag>
            </span>
          </template>
          <template #owner="{ row }">
            <span>{{ itemOf(row).owner }}</span>
          </template>
          <template #period="{ row }">
            <span class="kb__period">{{ periodText(itemOf(row)) }}</span>
          </template>
          <template #badcase="{ row }">
            <WTag v-if="itemOf(row).badcase" tone="a" plain>badcase</WTag>
            <span v-else class="dim">否</span>
          </template>
          <template #action="{ row }">
            <div class="kb__rowacts">
              <WButton
                size="sm"
                variant="ghost"
                :data-testid="`kb-row-view-${itemOf(row).knowledgeId}`"
                @click="selectRowFromList(itemOf(row))"
              >
                {{ isSelected(itemOf(row).knowledgeId) ? '已选' : '详情' }}
              </WButton>
              <WButton
                size="sm"
                variant="ghost"
                :data-testid="`kb-row-change-${itemOf(row).knowledgeId}`"
                @click="
                  () => {
                    selectRow(itemOf(row))
                    openChange()
                  }
                "
              >
                变更
              </WButton>
            </div>
          </template>
          <template #empty>
            <WEmpty
              compact
              icon="book"
              title="当前筛选无知识条目"
              description="调整状态/分类/责任人/生效期筛选；知识入库走导入与运营审核流程（本页不提供编造入口）。"
            >
              <WButton v-if="hasFilter" size="sm" @click="resetFilters">清空筛选</WButton>
            </WEmpty>
          </template>
          <template #footer>
            <div class="kb__foot">
              <span>
                分页由服务端执行（pageSize={{ PAGE_SIZE }}）；服务端口径：owner 角色只返回 published 且在生效期内的条目
                （本页为 staff/it/admin 运营视图）。
              </span>
              <span class="kb__pager">
                <WButton size="sm" :disabled="!canPrev" data-testid="kb-page-prev" @click="gotoPage(page - 1)">
                  上一页
                </WButton>
                <span class="kb__page-label num" data-testid="kb-page-label">{{ page }} / {{ totalPages }}</span>
                <WButton size="sm" :disabled="!canNext" data-testid="kb-page-next" @click="gotoPage(page + 1)">
                  下一页
                </WButton>
              </span>
            </div>
          </template>
        </WTable>

        <p v-if="listError" class="kb__noteline" data-testid="kb-list-error">
          <WTag tone="r" plain>列表不可用</WTag>{{ listError }}
          <WButton size="sm" variant="ghost" @click="loadList">重试</WButton>
        </p>
      </WCard>

      <!-- ---------------------------- 右栏 ---------------------------- -->
      <div class="kb__side">
        <!-- 详情 + 版本历史 + 变更/审核入口 -->
        <WCard title="知识详情" subtitle="正文 / 出处 / 版本历史 · 引用必须带版本" data-testid="kb-detail-card">
          <template #extra>
            <WTag v-if="canReview" tone="v" plain>it/admin · 可审核</WTag>
            <WTag v-else tone="n" plain>{{ currentRoleLabel }}</WTag>
          </template>

          <p v-if="detailLoading" class="kb__loadline"><WLoading label="正在读取知识详情…" /></p>
          <p v-else-if="detailDenied" class="kb__noteline" data-testid="kb-detail-denied">
            <WTag tone="r" plain>无权限 403</WTag>知识详情需要
            {{ detailDenied.requiredCapability || 'knowledge.read' }} 能力，页面不展示未授权内容。
          </p>
          <p v-else-if="detailError" class="kb__noteline" data-testid="kb-detail-error">
            <WTag tone="r" plain>详情不可用</WTag>{{ detailError }}
          </p>
          <p v-else-if="!detail" class="kb__noteline"><span class="dim">从左侧列表选择一条知识查看正文、出处与版本历史。</span></p>
          <template v-else>
            <div class="kb__detail" data-testid="kb-detail" :data-knowledge-id="detail.knowledgeId">
              <h4 class="kb__detail-title" data-testid="kb-detail-title">{{ detail.title }}</h4>
              <div class="kb__meta">
                <span v-for="item in detailMeta" :key="item.label" :data-testid="item.testid">
                  <em>{{ item.label }}</em><b>{{ item.value }}</b>
                </span>
              </div>

              <p
                class="kb__answer-note"
                :class="`is-${answerability?.tone ?? 'n'}`"
                data-testid="kb-detail-answerable"
              >
                {{ answerability?.note }}
              </p>

              <div class="kb__block">
                <b class="kb__block-title">正文（条款原文 · 服务端脱敏）</b>
                <p class="kb__content" data-testid="kb-detail-content">{{ detail.content }}</p>
              </div>

              <div class="kb__block">
                <b class="kb__block-title">出处</b>
                <p class="kb__source" data-testid="kb-detail-source">{{ sourceText }}</p>
                <div v-if="(detail.refs ?? []).length" class="kb__refs" data-testid="kb-detail-refs">
                  <WTag v-for="ref in detail.refs" :key="ref" tone="n" plain>{{ ref }}</WTag>
                </div>
              </div>

              <div class="kb__block">
                <b class="kb__block-title">版本历史（按提交时间升序 · 引用带版本）</b>
                <WTable :columns="detailColumns" :rows="versionTableRows" row-key="version" dense data-testid="kb-versions">
                  <template #version="{ value }"><span class="num">{{ value }}</span></template>
                  <template #statusLabel="{ row }">
                    <WTag :tone="versionRowOf(row).statusTone">{{ versionRowOf(row).statusLabel }}</WTag>
                  </template>
                  <template #changeText="{ row }">
                    <span class="dim">
                      {{ versionRowOf(row).changeText }}
                      <template v-if="versionRowOf(row).noteText !== '—'">· {{ versionRowOf(row).noteText }}</template>
                    </span>
                  </template>
                </WTable>
              </div>

              <!-- 变更（staff 均可提交：steward/agent/supervisor/it/admin） -->
              <div class="kb__actions">
                <WTag tone="n" plain>变更不直接生效：提交 → pending_review → it/admin 审核</WTag>
                <WButton size="sm" variant="primary" data-testid="kb-change-open" @click="openChange">提交变更</WButton>
              </div>

              <!-- 待审回执（本机缓存，服务端为准） -->
              <div v-if="selectedReceipts.length" class="kb__receipts" data-testid="kb-pending-receipts">
                <b class="kb__block-title">待审核变更（提交回执 · 本机缓存）</b>
                <div v-for="receipt in selectedReceipts" :key="receipt.changeId" class="kb__receipt" :data-testid="`kb-receipt-${receipt.changeId}`">
                  <div class="kb__receipt-main">
                    <b class="num">{{ receipt.changeId }}</b>
                    <span class="dim">
                      {{ receiptStatusLabel(receipt) }} · 提交人 {{ receipt.submittedBy }} ·
                      {{ formatIsoMinute(receipt.submittedAt) || '—' }}
                    </span>
                    <span v-if="receipt.reason" class="dim">原因：{{ receipt.reason }}</span>
                  </div>
                  <WButton
                    v-if="canReview"
                    size="sm"
                    variant="primary"
                    :data-testid="`kb-review-open-${receipt.changeId}`"
                    @click="openReview(receipt.changeId)"
                  >
                    审核
                  </WButton>
                </div>
                <p class="kb__hint">
                  回执来自本机对 <code>POST /knowledge/&#123;id&#125;/change</code> 201 响应的缓存（契约无「变更单列表」端点），
                  服务端仍按 changeId 校验状态。
                </p>
              </div>

              <!-- 审核权限说明：steward/supervisor 无审核权（服务端 403 knowledge.review） -->
              <div v-if="!canReview" class="kb__review-denied" data-testid="kb-review-denied">
                <WTag tone="n" plain>无审核权限</WTag>
                <span>
                  审核需 <code>knowledge.review</code>（仅 it/admin）；当前角色「{{ currentRoleLabel }}」只能提交变更，
                  审核入口在 it/admin 演示账号下出现。
                </span>
              </div>
              <div v-else class="kb__actions">
                <WButton size="sm" data-testid="kb-review-open" @click="openReview()">审核变更</WButton>
                <span class="dim">可审核 approved/rejected；驳回需填审核意见；提交人不得自审</span>
              </div>
            </div>
          </template>
        </WCard>

        <!-- 待审变更回执（本机缓存；it/admin 审核入口） -->
        <WCard
          v-if="allReceipts.length"
          title="待审变更（提交回执 · 本机缓存）"
          subtitle="来自 POST /knowledge/{id}/change 的 201 响应；服务端按 changeId 校验"
          data-testid="kb-pending-all"
        >
          <template #extra>
            <WTag tone="a" plain>{{ allReceipts.length }} 条待审</WTag>
          </template>
          <div v-for="receipt in allReceipts" :key="receipt.changeId" class="kb__receipt" :data-testid="`kb-pending-${receipt.changeId}`">
            <div class="kb__receipt-main">
              <b>{{ receipt.knowledgeId }} · {{ receipt.knowledgeTitle }}</b>
              <span class="dim">
                {{ receipt.changeId }} · {{ receipt.submittedBy }} · {{ formatIsoMinute(receipt.submittedAt) || '—' }}
              </span>
            </div>
            <WButton
              v-if="canReview"
              size="sm"
              variant="primary"
              :data-testid="`kb-pending-review-${receipt.changeId}`"
              @click="reviewFromReceipt(receipt)"
            >
              审核
            </WButton>
            <WTag v-else tone="n" plain>需 it/admin</WTag>
          </div>
          <p class="kb__hint">
            契约没有「变更单列表」端点，回执仅在本机缓存（≤30 条）用于审核入口；跨设备请以服务端审核结果为准。
          </p>
        </WCard>

        <!-- 采纳与缺口看板（数字来自 /knowledge/stats） -->
        <WCard title="采纳与缺口看板" subtitle="adoptionRate / 缺口 TopN / 责任人分布（服务端聚合）" data-testid="kb-board">
          <template #extra>
            <WTag tone="n" plain>演示数据（mock 知识库）</WTag>
          </template>

          <p v-if="statsLoading && !stats" class="kb__loadline"><WLoading label="正在读取知识统计…" /></p>
          <p v-else-if="statsDenied" class="kb__noteline" data-testid="kb-board-denied">
            <WTag tone="r" plain>无权限 403</WTag>采纳看板需要 {{ statsDenied.requiredCapability || 'knowledge.stats' }} 能力。
          </p>
          <p v-else-if="statsError" class="kb__noteline" data-testid="kb-board-error">
            <WTag tone="r" plain>看板不可用</WTag>{{ statsError }}
          </p>
          <template v-else-if="stats">
            <div class="kb__board-row">
              <span class="kb__board-lab">采纳率</span>
              <b class="num" data-testid="kb-board-adoption">{{ adoptionPercent }}</b>
              <span class="dim">已采纳 {{ stats.adopted }} / 已驳回 {{ stats.rejected }}</span>
              <div class="kb__bar"><i :style="{ width: `${Math.min(100, Math.round((stats.adoptionRate ?? 0) * 100))}%` }" /></div>
            </div>

            <b class="kb__block-title">一、缺口 TopN（被问到但未命中 · 补知识/改话术）</b>
            <div v-if="gaps.length === 0" class="kb__empty-note" data-testid="kb-gaps-empty">
              暂无缺口数据：近 30 天检索未命中记录为空（不编造缺口）。
            </div>
            <ul v-else class="kb__gap-list" data-testid="kb-gaps">
              <li v-for="gap in gaps" :key="gap.topic" :data-testid="`kb-gap-${gap.topic}`">
                <span class="kb__gap-q">{{ gap.topic }}</span>
                <span class="kb__gap-c num">被问 {{ gap.asked }} · 未命中 {{ gap.unanswered }}</span>
              </li>
            </ul>

            <b class="kb__block-title">二、责任人分布（过期/待审追到人）</b>
            <div v-if="ownerStats.length === 0" class="kb__empty-note" data-testid="kb-owners-empty">
              暂无责任人分布数据。
            </div>
            <WTable
              v-else
              :columns="[
                { key: 'owner', title: '责任人' },
                { key: 'total', title: '总数', width: '56px' },
                { key: 'published', title: '生效', width: '56px' },
                { key: 'expired', title: '过期', width: '56px' },
              ]"
              :rows="ownerRows(ownerStats)"
              row-key="owner"
              dense
              data-testid="kb-owners"
            />
          </template>
        </WCard>
      </div>
    </div>
    </template>

    <!-- ================= 知识导入（P6 W34 task-87；`knowledge.import` 才显示页签） ================= -->
    <template v-else-if="activeTab === 'import'">
      <KnowledgeImportPanel :prefill="gapPrefill" @open-knowledge="openKnowledgeFromImport" />
    </template>

    <!-- ================= 智能问答（RAG，POST /api/v1/rag/query） ================= -->
    <template v-else-if="activeTab === 'ask'">
      <RagAskPanel variant="page" heading="知识秒查" />
    </template>

    <!-- ================= RAG 链路健康（仅 it/admin；GET /api/v1/rag/health） ================= -->
    <template v-else>
      <RagHealthPanel variant="page" />
    </template>

    <!-- ================== 变更提交弹层（staff） ================== -->
    <WModal v-model:open="changeOpen" title="提交知识变更（进入待审核，不直接生效）" width="620px">
      <div class="kb__form" data-testid="kb-change-modal">
        <p class="kb__form-note">
          提交后状态为 <code>pending_review</code>，必须由 it/admin 审核通过才生成新版本并生效（提交人不得自审）。
        </p>
        <label class="kb__field is-block">
          <span>变更原因（必填 · 落审计与版本历史）</span>
          <input v-model="changeForm.reason" data-testid="kb-change-reason" placeholder="如：园区装修时段调整为 9:00 起" />
        </label>
        <div class="kb__form-grid">
          <label class="kb__field">
            <span>新生效日</span>
            <input v-model="changeForm.effectiveFrom" type="date" data-testid="kb-change-from" />
          </label>
          <label class="kb__field">
            <span>新失效日</span>
            <input v-model="changeForm.effectiveTo" type="date" data-testid="kb-change-to" />
          </label>
        </div>
        <label class="kb__field is-block">
          <span>新正文（必填 · 条款原文，服务端落库前脱敏）</span>
          <textarea v-model="changeForm.content" rows="5" data-testid="kb-change-content" />
        </label>
        <p v-if="changeError" class="kb__form-error" data-testid="kb-change-error">{{ changeError }}</p>
        <p v-if="changeMissing.length" class="kb__form-error" data-testid="kb-change-missing">
          缺少必填字段：{{ changeMissing.join('、') }}
        </p>
      </div>
      <template #footer>
        <WButton size="sm" variant="ghost" @click="changeOpen = false">取消</WButton>
        <WButton size="sm" variant="primary" :loading="changeSubmitting" data-testid="kb-change-submit" @click="submitChange">
          提交审核
        </WButton>
      </template>
    </WModal>

    <!-- ================== 审核弹层（仅 it/admin） ================== -->
    <WModal v-model:open="reviewOpen" title="审核知识变更（approved 生效 / rejected 驳回）" width="560px">
      <div class="kb__form" data-testid="kb-review-modal">
        <p class="kb__form-note">
          仅 it/admin 可审核（<code>knowledge.review</code>）；通过后立即生成新版本并生效（检索/详情读新版本）。
        </p>
        <label class="kb__field is-block">
          <span>待审核变更单号 changeId（来自提交回执）</span>
          <input v-model="reviewForm.changeId" data-testid="kb-review-change-id" placeholder="KC-20261116-0001" />
        </label>
        <div class="kb__seg" role="group" aria-label="审核决定">
          <button
            v-for="decision in (['approved', 'rejected'] as const)"
            :key="decision"
            type="button"
            class="kb__seg-item"
            :class="{ 'is-on': reviewForm.decision === decision }"
            :data-testid="`kb-review-decision-${decision}`"
            @click="reviewForm.decision = decision"
          >
            {{ REVIEW_DECISION_LABELS[decision] }}
          </button>
        </div>
        <label class="kb__field is-block">
          <span>审核意见（驳回必填 · ≤200 字符）</span>
          <textarea v-model="reviewForm.note" rows="3" data-testid="kb-review-note" placeholder="已核对原文条款，同意生效" />
        </label>
        <p v-if="reviewError" class="kb__form-error" data-testid="kb-review-error">{{ reviewError }}</p>
      </div>
      <template #footer>
        <WButton size="sm" variant="ghost" @click="reviewOpen = false">取消</WButton>
        <WButton size="sm" variant="primary" :loading="reviewSubmitting" data-testid="kb-review-submit" @click="submitReview">
          提交审核结果
        </WButton>
      </template>
    </WModal>

    <!-- ============ 新增知识：契约未开放新建端点（不编造入口） ============ -->
    <WModal v-model:open="createOpen" title="新增知识（契约未开放新建端点）" width="520px">
      <div class="kb__form" data-testid="kb-create-modal">
        <p class="kb__form-note">
          P4 W15 知识服务只开放 7 个端点（列表/详情/版本/变更/审核/检索/统计），**没有**「新建知识」API。
          为避免页面伪造入库，新增知识请走：
        </p>
        <ul class="kb__create-list">
          <li>① 在现有条目上「提交变更」→ <code>POST /knowledge/&#123;id&#125;/change</code> → it/admin 审核生效；</li>
          <li>② 到 Copilot「知识共建」提交 AI 答错/答不上的问题，进入缺口池由责任人补知识；</li>
          <li>③ 知识入库的导入 / 审批流程随 P5 多项目落库能力开放。</li>
        </ul>
        <p class="kb__hint">{{ demoNote('新增知识不落库') }}</p>
      </div>
      <template #footer>
        <WButton size="sm" @click="createOpen = false">知道了</WButton>
      </template>
    </WModal>

    <StateDemo screen="knowledge" />
  </div>
</template>

<style scoped>
.kb-page {
  min-width: 0;
}

/* ---------- 页签（P6 W33 RAG 增量：运营视图 / 智能问答 / RAG 健康） ---------- */
.kb__tabbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--sp-3);
}

.kb__tabbar-note {
  font-size: var(--fs-2xs);
  color: var(--c-faint);
  line-height: var(--lh-base);
}

/* ---------- 采纳看板指标 ---------- */
.kb__stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--sp-3);
}

.kb__stat {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 12px 14px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-xl);
  background: var(--c-card);
  box-shadow: var(--sh-md);
}

.kb__stat-lab {
  font-size: var(--fs-xs);
  color: var(--c-muted);
}

.kb__stat-val {
  font-size: var(--fs-2xl);
  font-weight: var(--fw-semibold);
  color: var(--c-ink);
  line-height: 1.15;
}

.kb__stat.is-warn .kb__stat-val {
  color: var(--c-amber);
}

.kb__stat-delta {
  font-size: var(--fs-2xs);
  color: var(--c-faint);
  line-height: var(--lh-base);
}

.kb__loadline {
  padding: 4px 2px;
}

.kb__noteline {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  padding: 7px 10px;
  border-radius: var(--rd-sm);
  background: var(--c-bg-2);
  font-size: var(--fs-2xs);
  color: var(--c-muted);
  line-height: var(--lh-base);
}

/* ---------- 主区 ---------- */
.kb__grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 380px;
  gap: var(--sp-3);
  align-items: start;
}

.kb__card-title {
  font-size: 12.5px;
  font-weight: var(--fw-semibold);
  color: var(--c-ink);
}

.kb__filters {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  flex-wrap: wrap;
  padding: 9px 12px;
  border-bottom: 1px solid var(--c-line-2);
  background: var(--c-bg-2);
}

.kb__field {
  display: flex;
  flex-direction: column;
  gap: 3px;
  font-size: 9.5px;
  color: var(--c-faint);
}

.kb__field.is-block {
  display: flex;
  width: 100%;
}

.kb__field select,
.kb__field input,
.kb__field textarea {
  height: 26px;
  padding: 0 8px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-sm);
  background: var(--c-card);
  font-size: var(--fs-2xs);
  color: var(--c-ink);
  font-family: inherit;
}

.kb__field textarea {
  height: auto;
  padding: 7px 9px;
  line-height: 1.7;
  resize: vertical;
}

.kb__field select:focus,
.kb__field input:focus,
.kb__field textarea:focus {
  outline: none;
  border-color: var(--c-brand);
  box-shadow: var(--sh-ring);
}

.kb__filter-note {
  margin-left: auto;
  font-size: 9.5px;
  color: var(--c-faint);
}

.kb__id {
  font-size: var(--fs-2xs);
  color: var(--c-brand);
}

.kb__title {
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.kb__title b {
  font-size: var(--fs-xs);
  color: var(--c-ink);
  font-weight: var(--fw-medium);
}

.kb__title span {
  font-size: 9px;
  color: var(--c-faint);
}

.kb__period {
  font-size: var(--fs-2xs);
  color: var(--c-muted);
}

.kb__rowacts {
  display: flex;
  gap: 2px;
}

.kb__statuscell {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  flex-wrap: wrap;
}

.kb__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
  flex-wrap: wrap;
  font-size: var(--fs-2xs);
  color: var(--c-faint);
  line-height: var(--lh-base);
}

.kb__pager {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.kb__page-label {
  font-size: var(--fs-2xs);
  color: var(--c-muted);
}

/* ---------- 右栏 ---------- */
.kb__side {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
  min-width: 0;
}

.kb__detail {
  display: flex;
  flex-direction: column;
  gap: 9px;
}

.kb__detail-title {
  font-size: var(--fs-md);
  font-weight: var(--fw-semibold);
  color: var(--c-ink);
  line-height: 1.4;
}

.kb__meta {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 6px;
  padding: 8px 10px;
  border: 1px solid var(--c-line-2);
  border-radius: var(--rd-md);
  background: var(--c-bg-2);
}

.kb__meta span {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}

.kb__meta em {
  font-style: normal;
  font-size: 9px;
  color: var(--c-faint);
}

.kb__meta b {
  font-size: 10.5px;
  font-weight: var(--fw-medium);
  color: var(--c-ink-2);
  word-break: break-all;
}

.kb__answer-note {
  padding: 6px 9px;
  border-radius: var(--rd-sm);
  font-size: var(--fs-2xs);
  line-height: var(--lh-base);
}

.kb__answer-note.is-g {
  background: var(--c-brand-soft);
  color: #0a725f;
}

.kb__answer-note.is-a {
  background: var(--c-amber-soft);
  color: #9a4b05;
}

.kb__answer-note.is-r {
  background: var(--c-red-soft);
  color: #b42318;
}

.kb__answer-note.is-n {
  background: var(--c-bg-2);
  color: var(--c-muted);
}

.kb__block {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.kb__block-title {
  font-size: 9.5px;
  font-weight: var(--fw-semibold);
  letter-spacing: 0.4px;
  color: var(--c-faint);
  text-transform: uppercase;
}

.kb__content {
  font-size: var(--fs-xs);
  line-height: 1.8;
  color: var(--c-ink-2);
}

.kb__source {
  font-size: var(--fs-2xs);
  color: var(--c-ink-2);
}

.kb__refs {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.kb__actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  padding-top: 6px;
  border-top: 1px dashed var(--c-line-2);
}

.kb__receipts {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 8px 10px;
  border: 1px solid var(--c-line-2);
  border-radius: var(--rd-md);
  background: var(--c-amber-soft);
}

.kb__receipt {
  display: flex;
  align-items: center;
  gap: 8px;
  justify-content: space-between;
}

.kb__receipt-main {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
  font-size: var(--fs-2xs);
  color: var(--c-ink-2);
}

.kb__review-denied {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  padding: 6px 9px;
  border: 1px dashed var(--c-line);
  border-radius: var(--rd-sm);
  font-size: var(--fs-2xs);
  color: var(--c-muted);
  line-height: var(--lh-base);
}

.kb__hint {
  font-size: 9px;
  color: var(--c-faint);
  line-height: 1.6;
}

.kb__hint code,
.kb__review-denied code,
.kb__form-note code {
  padding: 0 3px;
  border-radius: var(--rd-xs);
  background: rgba(14, 29, 43, 0.06);
  font-family: var(--font-mono);
  font-size: 9px;
}

/* ---------- 看板 ---------- */
.kb__board-row {
  display: grid;
  grid-template-columns: auto auto 1fr;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  font-size: var(--fs-xs);
}

.kb__board-lab {
  color: var(--c-faint);
  font-size: 9.5px;
}

.kb__board-row b {
  font-size: var(--fs-lg);
  color: var(--c-ink);
}

.kb__bar {
  grid-column: 1 / -1;
  height: 5px;
  border-radius: var(--rd-pill);
  background: var(--c-line-2);
  overflow: hidden;
}

.kb__bar i {
  display: block;
  height: 100%;
  border-radius: var(--rd-pill);
  background: var(--c-brand-gradient);
}

.kb__gap-list {
  display: flex;
  flex-direction: column;
}

.kb__gap-list li {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 5px 0;
  border-bottom: 1px dashed var(--c-line-2);
  font-size: var(--fs-2xs);
}

.kb__gap-list li:last-child {
  border-bottom: 0;
}

.kb__gap-q {
  color: var(--c-ink-2);
}

.kb__gap-c {
  flex: none;
  color: var(--c-faint);
}

.kb__empty-note {
  padding: 7px 9px;
  border-radius: var(--rd-sm);
  background: var(--c-bg-2);
  font-size: var(--fs-2xs);
  color: var(--c-muted);
  line-height: var(--lh-base);
}

/* ---------- 弹层表单 ---------- */
.kb__form {
  display: flex;
  flex-direction: column;
  gap: 9px;
}

.kb__form-note {
  font-size: var(--fs-2xs);
  color: var(--c-muted);
  line-height: var(--lh-base);
}

.kb__form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.kb__form-error {
  padding: 6px 9px;
  border-radius: var(--rd-sm);
  background: var(--c-red-soft);
  color: #b42318;
  font-size: var(--fs-2xs);
  line-height: var(--lh-base);
}

.kb__seg {
  display: inline-flex;
  gap: 2px;
  padding: 2px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-md);
  background: var(--c-bg-2);
  align-self: flex-start;
}

.kb__seg-item {
  height: 24px;
  padding: 0 10px;
  border-radius: var(--rd-sm);
  font-size: var(--fs-2xs);
  color: var(--c-muted);
}

.kb__seg-item.is-on {
  background: var(--c-card);
  color: var(--c-brand);
  font-weight: var(--fw-semibold);
  box-shadow: var(--sh-sm);
}

.kb__seg-item:focus-visible {
  outline: none;
  box-shadow: var(--sh-ring);
}

.kb__create-list {
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding-left: 2px;
  font-size: var(--fs-xs);
  color: var(--c-ink-2);
  line-height: var(--lh-base);
}

@media (max-width: 1279px) {
  .kb__stats {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .kb__grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
