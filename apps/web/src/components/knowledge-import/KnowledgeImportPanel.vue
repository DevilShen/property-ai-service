<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { WButton, WCard, WEmpty, WLoading, WTag, useToast } from '@/components'
import PermissionDenied from '@/components/feedback/PermissionDenied.vue'
import { useAuthStore } from '@/stores/auth'
import { ROLE_LABELS } from '@/app/roles'
import {
  ApiError,
  KNOWLEDGE_IMPORT_LIMITS,
  KNOWLEDGE_IMPORT_JOB_PAGE_SIZE,
  KNOWLEDGE_IMPORT_POLL_INTERVAL_MS,
  KNOWLEDGE_IMPORT_POLL_MAX_ATTEMPTS,
  api,
  degradedText,
  forbiddenDetailsOf,
  formatIsoMinute,
} from '@/api'
import type {
  ActorRole,
  ForbiddenDetails,
  KnowledgeCategory,
  KnowledgeCommitResult,
  KnowledgeImportCommitRequest,
  KnowledgeImportCreateRequest,
  KnowledgeImportJob,
  KnowledgeImportJobStatus,
} from '@/api'
import { actionSeed, useIdempotencyKey } from '@/composables/useIdempotencyKey'
import { KNOWLEDGE_CATEGORY_OPTIONS, todayIso } from '@/views/knowledge/shared'
import ImportJobTable from './ImportJobTable.vue'
import ImportPreview from './ImportPreview.vue'
import {
  IMPORT_FILE_ACCEPT,
  IMPORT_LIMIT_TEXT,
  IMPORT_SOURCE_LABELS,
  IMPORT_STATUS_LABELS,
  IMPORT_STATUS_TONES,
  canCancelImport,
  fileExtOf,
  formatBytes,
  importApiMessage,
  importErrorText,
  isImportInFlight,
  isImportTerminal,
  jobSizeText,
  validateImportFile,
} from './import-copy'

/**
 * 知识导入 P0/P1 面板（task-87；契约 v1.5.0 §12 / 设计 `docs/design/知识导入方案.md`）
 * ===========================================================================
 * 入口：知识页「导入」页签（`KnowledgeView`），本组件不新增路由、不改 12 屏基线。
 *
 * 结构：
 * 1) 方式卡片：粘贴文本 / 上传文件 / 批量 ZIP（`knowledge.import.batch` 才显示）；
 * 2) 粘贴表单：标题、正文（字符计数 /200,000）、类别、责任人、生效期、审核状态
 *    （默认待审；`knowledge.review`（it/admin）额外可直审）；
 * 3) 上传：拖拽 + 点击，白名单/大小提示（`KNOWLEDGE_IMPORT_LIMITS` 同源）；ZIP 走 `sourceType=batch`；
 * 4) 创建任务 → 2s 轮询 `GET /imports/{jobId}` 到 `preview_ready` / `failed` / `cancelled`；
 * 5) 预览（`ImportPreview`）：切分块勾选/元数据/PII/重复/警告/提交（待审或 it/admin 直审）/取消；
 * 6) 任务列表（`ImportJobTable`）+ 详情（状态人话、failed 原因 + 重试/取消、indexing/completed 索引结果）。
 *
 * 五态（e2e 覆盖）：
 * - 加载：`kimp-loading` / 创建中按钮 loading；空：`kimp-empty`（无任务 + 未选方式）；
 * - 错误：`kimp-error`（列表/创建/提交失败人话 + 重试）；超时：`kimp-timeout`（E-4001 / 轮询超时）；
 * - 降级：`kimp-degraded`（服务端 `degraded` 原因如实展示）；403：`kimp-denied`（能力码不可用）。
 *
 * 权限与红线（AGENTS.md NEVER #9/#11）：
 * - 能力 `knowledge.import` 缺失 → PermissionDenied（owner 走 Java 403 人话）；入口显隐**不是**安全边界；
 * - 直审 approved **仅 it/admin**（`knowledge.review`）：前端隐藏 + 服务端 403 兜底；
 * - 任务按项目隔离（`withProjectScope` 对 `/api/v1/knowledge` 前缀的 GET 注入当前项目；写操作不注入），
 *   跨项目任务服务端 404 `E-3001`（不区分无权限与不存在）；
 * - 演示口径：任务/原文为**进程内存储**（重启回滚），列表/详情页面标注「演示数据」。
 */
const props = defineProps<{
  /**
   * RAG 拒答沉淀预填（task-87 P1）：知识页「智能问答」拒答卡点「补充这条知识」后切到本页签，
   * 预填标题 = 问题、正文模板、`sourceType=badcase`（服务端 `KnowledgeImportSourceType`）。
   */
  prefill?: { question: string; sourceType: 'badcase' } | null
}>()

const emit = defineEmits<{
  /**
   * 打开知识运营视图：传 knowledgeId 时由父组件选中该条详情；
   * 省略/空串 = 仅切到「运营视图」页签（如任务已完成、重复知识跳转）。
   */
  'open-knowledge': [knowledgeId?: string]
}>()

const auth = useAuthStore()
const toast = useToast()

/** 页内视图：方式选择 / 粘贴表单 / 上传表单 / 预览 / 任务详情 */
type PanelView = 'choose' | 'paste' | 'file' | 'preview' | 'job'
const view = ref<PanelView>('choose')
/** 上传方式：file = 单文件（`sourceType=file`）；batch = ZIP（`sourceType=batch`） */
const uploadMode = ref<'file' | 'batch'>('file')

const canImport = computed(() => auth.hasCapability('knowledge.import'))
const canBatch = computed(() => auth.hasCapability('knowledge.import.batch'))
/** 直审 approved 仅 it/admin（服务端 403 details.requiredCapability=knowledge.review） */
const directApprover = computed(() => auth.hasCapability('knowledge.review'))
const currentRoleLabel = computed(() => (auth.role ? ROLE_LABELS[auth.role] : '未登录'))
const switching = ref(false)
/** 无权限态可切换的演示账号（具备 knowledge.import 的角色；owner 不在内） */
const deniedCandidates: readonly ActorRole[] = ['steward', 'agent', 'supervisor', 'it', 'admin']

type DeniedInfo = ForbiddenDetails & { traceId?: string }

function deniedOf(error: unknown): DeniedInfo | null {
  const details = forbiddenDetailsOf(error)
  if (!details) return null
  return { ...details, traceId: error instanceof ApiError ? error.traceId : '' }
}

/* ============================================================== ① 任务列表 */

const jobs = ref<KnowledgeImportJob[]>([])
const jobsTotal = ref(0)
const jobsPage = ref(1)
const jobsStatus = ref<KnowledgeImportJobStatus | ''>('')
const jobsLoading = ref(false)
const jobsError = ref('')
const jobsDenied = ref<DeniedInfo | null>(null)
const jobsDegraded = ref('')

async function loadJobs(): Promise<void> {
  jobsLoading.value = true
  jobsError.value = ''
  jobsDenied.value = null
  jobsDegraded.value = ''
  try {
    const { data, degraded } = await api.knowledgeImport.list({
      page: jobsPage.value,
      pageSize: KNOWLEDGE_IMPORT_JOB_PAGE_SIZE,
      ...(jobsStatus.value ? { status: jobsStatus.value } : {}),
    })
    jobs.value = data.items
    jobsTotal.value = data.total
    if (degraded) jobsDegraded.value = degradedText(degraded)
  } catch (error) {
    jobs.value = []
    jobsTotal.value = 0
    const denied = deniedOf(error)
    if (denied) jobsDenied.value = denied
    else jobsError.value = importApiMessage(error)
  } finally {
    jobsLoading.value = false
  }
}

function filterJobs(status: KnowledgeImportJobStatus | ''): void {
  jobsStatus.value = status
  jobsPage.value = 1
  void loadJobs()
}

function gotoJobsPage(next: number): void {
  const totalPages = Math.max(1, Math.ceil(jobsTotal.value / KNOWLEDGE_IMPORT_JOB_PAGE_SIZE))
  if (next < 1 || next > totalPages) return
  jobsPage.value = next
  void loadJobs()
}

/* ====================================================== ② 当前任务与轮询 */

const activeJob = ref<KnowledgeImportJob | null>(null)
const activeJobLoading = ref(false)
const activeJobError = ref('')
const activeJobDenied = ref<DeniedInfo | null>(null)
const activeJobDegraded = ref('')
/** 轮询超时/网络中断（E-4001）——页面给「超时」态，可手动刷新任务 */
const pollTimedOut = ref(false)
/** 轮询令牌：换任务/卸载时递增，旧循环自动退出（避免覆盖新任务状态） */
let pollToken = 0

function stopPoll(): void {
  pollToken += 1
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => {
    setTimeout(resolve, ms)
  })
}

/**
 * 轮询任务详情（2s）直到 `preview_ready` / `failed` / `cancelled` / `completed`。
 * 创建接口是同步返回预览的（201 即带 chunks），这里仍按契约状态机轮询，
 * 避免把 in-flight 任务当终态；超时（E-4001）或达到最大次数 → `pollTimedOut`。
 */
async function pollJob(jobId: string): Promise<void> {
  stopPoll()
  const token = pollToken
  pollTimedOut.value = false
  for (let attempt = 0; attempt < KNOWLEDGE_IMPORT_POLL_MAX_ATTEMPTS; attempt += 1) {
    if (token !== pollToken) return
    if (attempt > 0) await sleep(KNOWLEDGE_IMPORT_POLL_INTERVAL_MS)
    if (token !== pollToken) return
    try {
      const { data, degraded } = await api.knowledgeImport.get(jobId)
      if (token !== pollToken) return
      if (activeJob.value?.jobId === jobId) activeJob.value = data
      if (degraded) activeJobDegraded.value = degradedText(degraded)
      if (data.status === 'preview_ready') {
        view.value = 'preview'
        void loadJobs()
        return
      }
      if (isImportTerminal(data.status)) {
        view.value = 'job'
        void loadJobs()
        return
      }
    } catch (error) {
      if (token !== pollToken) return
      const denied = deniedOf(error)
      if (denied) {
        activeJobDenied.value = denied
        return
      }
      pollTimedOut.value = error instanceof ApiError && error.code === 'E-4001'
      activeJobError.value = importApiMessage(error)
      return
    }
  }
  pollTimedOut.value = true
}

/** 打开任务（列表行）：preview_ready → 预览页；其它 → 详情卡（in-flight 继续轮询） */
async function openJob(job: KnowledgeImportJob): Promise<void> {
  stopPoll()
  activeJob.value = job
  activeJobError.value = ''
  activeJobDenied.value = null
  activeJobDegraded.value = ''
  pollTimedOut.value = false
  commitResult.value = null
  commitError.value = ''
  activeJobLoading.value = true
  view.value = job.status === 'preview_ready' ? 'preview' : 'job'
  try {
    const { data, degraded } = await api.knowledgeImport.get(job.jobId)
    activeJob.value = data
    if (degraded) activeJobDegraded.value = degradedText(degraded)
    view.value = data.status === 'preview_ready' ? 'preview' : 'job'
    if (isImportInFlight(data.status)) void pollJob(data.jobId)
  } catch (error) {
    const denied = deniedOf(error)
    if (denied) activeJobDenied.value = denied
    else activeJobError.value = importApiMessage(error)
  } finally {
    activeJobLoading.value = false
  }
}

/** 重新拉取当前任务详情（详情卡「重试」用；无当前任务时 no-op） */
function reloadActiveJob(): void {
  const job = activeJob.value
  if (!job) return
  void openJob(job)
}

/** 任务创建成功后的统一落地：预览就绪 → 预览页；否则详情 + 轮询 */function onJobCreated(job: KnowledgeImportJob): void {
  activeJob.value = job
  activeJobError.value = ''
  activeJobDenied.value = null
  activeJobDegraded.value = ''
  pollTimedOut.value = false
  commitResult.value = null
  commitError.value = ''
  view.value = job.status === 'preview_ready' ? 'preview' : 'job'
  void loadJobs()
  if (job.status === 'preview_ready') {
    toast.success(`任务 ${job.jobId} 预览就绪：${job.preview?.totalChunks ?? 0} 个切分块，请确认后提交`)
  } else if (job.status === 'failed') {
    toast.warning(`任务 ${job.jobId} 解析失败：${importErrorText(job.error)}`)
  } else {
    toast.info(`任务 ${job.jobId} 已创建（${IMPORT_STATUS_LABELS[job.status] ?? job.status}），正在轮询解析结果…`)
    void pollJob(job.jobId)
  }
}

/* ============================================================== ③ 粘贴表单 */

const pasteForm = reactive({
  title: '',
  content: '',
  category: '' as KnowledgeCategory | '',
  owner: '',
  effectiveFrom: todayIso(),
  effectiveTo: '',
  status: 'pending_review' as 'pending_review' | 'approved',
})
const pasteError = ref('')
const pasteMissing = ref<string[]>([])
/** RAG 缺口沉淀预填提示（sourceType=badcase；由 `prefill` prop 带入） */
const gapNote = ref('')
/** 预览页「提交后状态」初始值（粘贴表单选择；上传方式恒 pending_review） */
const commitStartStatus = ref<'pending_review' | 'approved'>('pending_review')
const creatingPaste = ref(false)
const pasteCreateError = ref('')
const pasteTimedOut = ref(false)

const pasteCharCount = computed(() => pasteForm.content.length)
const pasteOverLimit = computed(() => pasteCharCount.value > KNOWLEDGE_IMPORT_LIMITS.maxPasteChars)

function startPaste(): void {
  view.value = 'paste'
  gapNote.value = ''
  pasteError.value = ''
  pasteMissing.value = []
  pasteCreateError.value = ''
  pasteTimedOut.value = false
  if (!pasteForm.title && !pasteForm.content) pasteForm.effectiveFrom = todayIso()
}

function startUpload(batch: boolean): void {
  view.value = 'file'
  uploadMode.value = batch ? 'batch' : 'file'
  selectedFile.value = null
  fileError.value = ''
  fileCreateError.value = ''
  fileTimedOut.value = false
  // 上传入口没有审核状态选择：预览页默认待审
  commitStartStatus.value = 'pending_review'
}

function backToChoose(): void {
  stopPoll()
  view.value = 'choose'
  activeJob.value = null
  activeJobError.value = ''
  activeJobDenied.value = null
  commitResult.value = null
  commitError.value = ''
}

async function submitPaste(): Promise<void> {
  pasteMissing.value = []
  pasteError.value = ''
  pasteTimedOut.value = false
  const missing: string[] = []
  if (!pasteForm.title.trim()) missing.push('标题 title')
  if (!pasteForm.content.trim()) missing.push('正文 content')
  pasteMissing.value = missing
  if (missing.length > 0) {
    pasteError.value = `请补齐必填项：${missing.join('、')}`
    return
  }
  if (pasteOverLimit.value) {
    pasteError.value = `正文超过上限（413 E-1001）：${pasteCharCount.value.toLocaleString('zh-Hans-CN')} 字符 > ${KNOWLEDGE_IMPORT_LIMITS.maxPasteChars.toLocaleString('zh-Hans-CN')} 字符；请拆分后导入`
    return
  }
  if (pasteForm.effectiveFrom && pasteForm.effectiveTo && pasteForm.effectiveFrom > pasteForm.effectiveTo) {
    pasteError.value = '生效日不能晚于失效日（服务端会返回 400 E-1001）'
    return
  }
  if (pasteForm.status === 'approved' && !directApprover.value) {
    pasteError.value = '直审需 it/admin（knowledge.review）；当前角色只能提交「待审核」'
    return
  }
  const body: KnowledgeImportCreateRequest = {
    title: pasteForm.title.trim(),
    content: pasteForm.content,
    sourceType: gapNote.value ? 'badcase' : 'paste',
  }
  if (pasteForm.category) body.category = pasteForm.category
  if (pasteForm.owner.trim()) body.owner = pasteForm.owner.trim()
  if (pasteForm.effectiveFrom) body.effectiveFrom = pasteForm.effectiveFrom
  if (pasteForm.effectiveTo) body.effectiveTo = pasteForm.effectiveTo
  creatingPaste.value = true
  /** 粘贴表单选择的审核状态 → 预览页「提交后状态」初始值（真正生效在 commit） */
  commitStartStatus.value = pasteForm.status
  try {
    const key = idem.ensure(
      actionSeed('knowledge.import.create.paste', {
        title: body.title,
        charCount: body.content.length,
        sourceType: body.sourceType,
        category: body.category,
        owner: body.owner,
      }),
    )
    const { data } = await api.knowledgeImport.createPaste(body, key)
    idem.settle()
    onJobCreated(data)
  } catch (error) {
    pasteTimedOut.value = error instanceof ApiError && error.code === 'E-4001'
    pasteCreateError.value = importApiMessage(error)
    const denied = deniedOf(error)
    if (denied) pasteCreateError.value = `无权限（403 E-2001）：需要能力 ${denied.requiredCapability || 'knowledge.import'}；当前角色「${currentRoleLabel.value}」不可导入，可切换到 steward/it 演示账号`
  } finally {
    creatingPaste.value = false
  }
}

/* =============================================================== ④ 上传表单 */

const fileInput = ref<HTMLInputElement | null>(null)
const selectedFile = ref<File | null>(null)
const fileError = ref('')
const fileHover = ref(false)
const creatingFile = ref(false)
const fileCreateError = ref('')
const fileTimedOut = ref(false)
/** 最近一次上传的本地文件（失败重试时复用同一文件，不伪造内容） */
const lastUploadFile = ref<File | null>(null)
const lastUploadBatch = ref(false)

const uploadTitle = computed(() => (uploadMode.value === 'batch' ? '批量 ZIP 上传' : '上传文件'))
const uploadAccept = computed(() => (uploadMode.value === 'batch' ? '.zip' : IMPORT_FILE_ACCEPT))
const uploadLimitText = computed(() =>
  uploadMode.value === 'batch' ? IMPORT_LIMIT_TEXT.zip : IMPORT_LIMIT_TEXT.file,
)

function pickFile(): void {
  fileInput.value?.click()
}

function acceptFile(file: File | null): void {
  fileError.value = ''
  fileCreateError.value = ''
  fileTimedOut.value = false
  if (!file) {
    selectedFile.value = null
    return
  }
  const message = validateImportFile(file, uploadMode.value === 'batch')
  if (message) {
    fileError.value = message
    selectedFile.value = null
    return
  }
  selectedFile.value = file
}

function onFileInput(event: Event): void {
  const input = event.target as HTMLInputElement
  acceptFile(input.files?.[0] ?? null)
}

function onDrop(event: DragEvent): void {
  fileHover.value = false
  const files = event.dataTransfer?.files
  if (!files || files.length === 0) {
    fileError.value = '没有检测到拖入的文件：请重新拖拽或点击选择文件'
    return
  }
  if (files.length > 1) {
    fileError.value = '一次只处理 1 个文件：多个文件请打成一个 ZIP 后用「批量 ZIP」导入'
    return
  }
  acceptFile(files[0] ?? null)
}

async function uploadSelected(): Promise<void> {
  const file = selectedFile.value
  if (!file) {
    fileError.value = '请先选择文件（拖拽到虚线区或点击选择）'
    return
  }
  const message = validateImportFile(file, uploadMode.value === 'batch')
  if (message) {
    fileError.value = message
    return
  }
  creatingFile.value = true
  fileCreateError.value = ''
  fileTimedOut.value = false
  try {
    const key = idem.ensure(
      actionSeed('knowledge.import.create.file', {
        name: file.name,
        size: file.size,
        sourceType: uploadMode.value,
      }),
    )
    const { data } = await api.knowledgeImport.createFile(
      file,
      { sourceType: uploadMode.value === 'batch' ? 'batch' : 'file' },
      key,
    )
    idem.settle()
    lastUploadFile.value = file
    lastUploadBatch.value = uploadMode.value === 'batch'
    onJobCreated(data)
  } catch (error) {
    fileTimedOut.value = error instanceof ApiError && error.code === 'E-4001'
    fileCreateError.value = importApiMessage(error)
    const denied = deniedOf(error)
    if (denied) {
      fileCreateError.value = `无权限（403 E-2001）：需要能力 ${denied.requiredCapability || 'knowledge.import'}；ZIP 批量另需 knowledge.import.batch`
    }
  } finally {
    creatingFile.value = false
  }
}

/* ============================================================= ⑤ 预览/提交 */

const committing = ref(false)
const commitError = ref('')
const commitResult = ref<KnowledgeCommitResult | null>(null)
const idem = useIdempotencyKey()

async function commitPreview(payload: KnowledgeImportCommitRequest): Promise<void> {
  const job = activeJob.value
  if (!job) return
  committing.value = true
  commitError.value = ''
  try {
    const key = idem.ensure(
      actionSeed('knowledge.import.commit', {
        jobId: job.jobId,
        status: payload.status ?? 'pending_review',
        chunks: (payload.chunks ?? []).join(','),
        title: payload.title,
      }),
    )
    const { data, degraded } = await api.knowledgeImport.commit(job.jobId, payload, key)
    idem.settle()
    commitResult.value = data
    if (degraded) activeJobDegraded.value = degradedText(degraded)
    const itemCount = data.items.length
    if (payload.status === 'approved') {
      toast.success(`直审完成：生成 ${itemCount} 条知识并已索引，可立即被检索（知识 ID：${data.items.map((item) => item.knowledgeId).join('、')}）`)
    } else {
      toast.success(`已提交待审：生成 ${itemCount} 条待审知识（需 it/admin 审核通过后才发布/索引）`)
    }
    // 刷新任务状态（committing → completed）与列表
    try {
      const { data: refreshed } = await api.knowledgeImport.get(job.jobId)
      activeJob.value = refreshed
    } catch {
      // 详情刷新失败不影响提交结果展示（结果以 commit 响应为准）
    }
    await loadJobs()
  } catch (error) {
    const denied = deniedOf(error)
    if (denied) {
      commitError.value = `无权限（403 E-2001）：${denied.requiredCapability ? `需要能力 ${denied.requiredCapability}` : '当前角色不可直审'}；直审仅 it/admin，其它角色请提交「待审核」`
    } else if (error instanceof ApiError && error.code === 'E-3002') {
      const reason = typeof error.details?.reason === 'string' ? error.details.reason : ''
      commitError.value =
        reason === 'already_committed'
          ? '该任务已提交过（409 E-3002 already_committed）：不可重复生成知识，请在任务列表查看已完成结果'
          : `任务状态不允许提交（409 E-3002）：仅 preview_ready 可提交，请刷新任务状态后重试`
    } else {
      commitError.value = importApiMessage(error)
    }
  } finally {
    committing.value = false
  }
}

const submittingLabel = computed(() => {
  if (!committing.value) return ''
  return directApprover.value ? '正在提交并等待服务端处理（直审会同步触发索引，可能需要数秒）…' : '正在提交…'
})

/* =============================================================== ⑥ 取消任务 */

const canceling = ref(false)

async function cancelActiveJob(): Promise<void> {
  const job = activeJob.value
  if (!job) return
  canceling.value = true
  commitError.value = ''
  try {
    const { data } = await api.knowledgeImport.cancel(job.jobId)
    activeJob.value = data
    toast.info(`任务 ${job.jobId} 已取消（终态，原文不删除）`)
    view.value = 'job'
    await loadJobs()
  } catch (error) {
    if (error instanceof ApiError && error.code === 'E-3002') {
      commitError.value = '任务已进入终态（409 E-3002）：不可取消，请刷新查看最新状态'
    } else {
      commitError.value = importApiMessage(error)
    }
  } finally {
    canceling.value = false
  }
}

/* ========================================================== ⑦ 失败重试入口 */

function retryActiveJob(): void {
  const job = activeJob.value
  if (!job) return
  const retryable = job.error?.retryable !== false
  if (lastUploadFile.value && lastUploadBatch.value === (job.sourceType === 'batch') && retryable) {
    selectedFile.value = lastUploadFile.value
    uploadMode.value = job.sourceType === 'batch' ? 'batch' : 'file'
    view.value = 'file'
    fileCreateError.value = ''
    toast.info(`已带出上次上传的文件「${lastUploadFile.value.name}」：确认后重新创建任务（服务端失败原因：${importErrorText(job.error)}）`)
    return
  }
  toast.info(
    job.error?.retryable === false
      ? '该失败不可自动重试（需修正内容/格式）：请回到方式选择重新导入'
      : '服务端任务不保存可用于重试的本地文件：请重新上传/粘贴（失败原因见任务详情）',
  )
  backToChoose()
}

/* =========================================================== ⑧ 权限/账号切换 */

async function switchAccount(role: ActorRole): Promise<void> {
  if (switching.value) return
  switching.value = true
  try {
    const ok = await auth.loginAsRole(role)
    if (!ok) {
      toast.error(`演示账号切换失败：${auth.failure?.message ?? '登录接口不可用'}`)
      return
    }
    toast.info(`已切换演示账号：${ROLE_LABELS[role]}（demo，非真实鉴权），正在重新读取导入任务…`)
    jobsPage.value = 1
    activeJob.value = null
    view.value = 'choose'
    commitResult.value = null
    await loadJobs()
  } finally {
    switching.value = false
  }
}

/* ========================================================== ⑨ RAG 缺口沉淀预填 */

function applyGapPrefill(question: string): void {
  view.value = 'paste'
  pasteForm.title = question.slice(0, 200)
  pasteForm.content = `问题：${question}\n补充答案：`
  pasteForm.status = 'pending_review'
  pasteForm.effectiveFrom = todayIso()
  gapNote.value = '来自 RAG 拒答缺口沉淀（sourceType=badcase）：请补充标准答案/制度依据后提交，默认进入待审'
  pasteError.value = ''
  pasteMissing.value = []
  toast.info(`已预填缺口问题「${question}」：补全正文后提交待审（sourceType=badcase）`)
}

watch(
  () => props.prefill,
  (value) => {
    if (value?.question) applyGapPrefill(value.question)
  },
  { immediate: true },
)

/* ============================================================== ⑩ 生命周期 */

onBeforeUnmount(() => {
  stopPoll()
})

// 角色切换（顶栏演示账号）：清旧身份的无权限态并全部重取
watch(
  () => auth.role,
  () => {
    jobsDenied.value = null
    activeJobDenied.value = null
    activeJobError.value = ''
    jobsPage.value = 1
    view.value = 'choose'
    activeJob.value = null
    commitResult.value = null
    void loadJobs()
  },
)

/* ======================================================== 模板派生/小工具 */

const categoryOptions = computed(() => KNOWLEDGE_CATEGORY_OPTIONS)
const activeJobStatusLabel = computed(() =>
  activeJob.value ? IMPORT_STATUS_LABELS[activeJob.value.status] ?? activeJob.value.status : '',
)
const activeJobCanCancel = computed(() => (activeJob.value ? canCancelImport(activeJob.value.status) : false))
const activeJobCancelLoading = computed(() => canceling.value)

function openKnowledge(knowledgeId?: string): void {
  emit('open-knowledge', knowledgeId)
}

function goJobs(): void {
  view.value = 'choose'
  void loadJobs()
}

// 首次挂载：读取任务列表（导入页签按需渲染，不需要等全局 onMounted）
void loadJobs()
</script>

<template>
  <div class="kimp" data-testid="kimp-panel">
    <!-- ===================== 权限：knowledge.import 缺失（owner 403 人话） ===================== -->
    <PermissionDenied
      v-if="!canImport"
      compact
      data-testid="kimp-denied"
      required-capability="knowledge.import"
      :switching="switching"
      :candidate-roles="deniedCandidates"
      detail="知识导入需要 knowledge.import 能力（steward/agent/supervisor/it/admin）；owner 角色不可导入知识。页面不会展示未授权数据。"
      @switch="switchAccount"
    />

    <template v-else>
      <!-- ===================== ① 方式卡片 ===================== -->
      <WCard
        title="知识导入"
        subtitle="粘贴 / 上传 / 批量 ZIP → 解析切分预览 → 确认提交（默认待审；it/admin 可直审）"
        data-testid="kimp-methods"
      >
        <template #extra>
          <WTag tone="v" plain>演示数据（mock 知识库 · 进程内任务存储）</WTag>
          <WTag tone="n" plain>能力 {{ currentRoleLabel }} · knowledge.import</WTag>
        </template>

        <div class="kimp-methods">
          <button
            type="button"
            class="kimp-method"
            :class="{ 'is-on': view === 'paste' }"
            data-testid="kimp-choose-paste"
            @click="startPaste"
          >
            <b>粘贴文本</b>
            <span>制度条款/FAQ 直接粘贴，≤{{ KNOWLEDGE_IMPORT_LIMITS.maxPasteChars.toLocaleString('zh-Hans-CN') }} 字符</span>
            <em>来源 sourceType=paste / badcase</em>
          </button>
          <button
            type="button"
            class="kimp-method"
            :class="{ 'is-on': view === 'file' && uploadMode === 'file' }"
            data-testid="kimp-choose-file"
            @click="startUpload(false)"
          >
            <b>上传文件</b>
            <span>txt / md / docx / pdf，单文件 ≤{{ formatBytes(KNOWLEDGE_IMPORT_LIMITS.maxFileBytes) }}</span>
            <em>解析后先预览再提交</em>
          </button>
          <button
            v-if="canBatch"
            type="button"
            class="kimp-method"
            :class="{ 'is-on': view === 'file' && uploadMode === 'batch' }"
            data-testid="kimp-choose-batch"
            @click="startUpload(true)"
          >
            <b>批量 ZIP</b>
            <span>≤{{ KNOWLEDGE_IMPORT_LIMITS.maxZipFiles }} 个文件 / ≤{{ formatBytes(KNOWLEDGE_IMPORT_LIMITS.maxZipBytes) }}</span>
            <em>需 knowledge.import.batch</em>
          </button>
          <div v-else class="kimp-method is-locked" data-testid="kimp-choose-batch-locked">
            <b>批量 ZIP（不可用）</b>
            <span>需 knowledge.import.batch 能力：当前角色「{{ currentRoleLabel }}」不可用（服务端 403 兜底）</span>
            <em>可切换到 steward/it 演示账号</em>
          </div>
        </div>

        <p class="kimp-hint" data-testid="kimp-limits">
          限额（服务端强制，前端仅提示）：{{ IMPORT_LIMIT_TEXT.paste }} · {{ IMPORT_LIMIT_TEXT.file }} ·
          {{ IMPORT_LIMIT_TEXT.zip }} · {{ IMPORT_LIMIT_TEXT.rate }}（超限 413 E-1001 / 超频 429 E-4003）
        </p>
        <p class="kimp-hint" data-testid="kimp-scope-note">
          项目隔离：任务按项目保存（GET 自动带当前项目 projectId），跨项目任务服务端返回 404 E-3001（不区分无权限与不存在）；
          任务/原文为演示进程内存储，重启回滚。
        </p>
      </WCard>

      <!-- ===================== ② 粘贴表单 ===================== -->
      <WCard v-if="view === 'paste'" title="粘贴文本导入" data-testid="kimp-paste">
        <template #extra>
          <WTag v-if="gapNote" tone="a" plain data-testid="kimp-paste-gap-note">RAG 缺口沉淀 · badcase</WTag>
          <WTag tone="n" plain>POST /api/v1/knowledge/imports（JSON）</WTag>
        </template>

        <p v-if="gapNote" class="kimp-note kimp-note--warn" data-testid="kimp-paste-gap"> {{ gapNote }} </p>

        <div class="kimp-form-grid">
          <label class="kimp-field is-block">
            <span>标题（必填，≤200 字符）</span>
            <input v-model="pasteForm.title" maxlength="200" data-testid="kimp-paste-title" placeholder="如：园区停车位办理与收费说明" />
          </label>
          <label class="kimp-field is-block">
            <span>
              正文（必填 · 字符计数
              <b :class="{ 'kimp-over': pasteOverLimit }" data-testid="kimp-paste-charcount">
                {{ pasteCharCount.toLocaleString('zh-Hans-CN') }}
              </b>
              / {{ KNOWLEDGE_IMPORT_LIMITS.maxPasteChars.toLocaleString('zh-Hans-CN') }}）
            </span>
            <textarea
              v-model="pasteForm.content"
              rows="7"
              data-testid="kimp-paste-content"
              placeholder="粘贴制度条款/流程/FAQ 原文；Markdown 标题可用于切分（# / ## 会作为 sectionPath）"
            />
          </label>
          <label class="kimp-field">
            <span>类别</span>
            <select v-model="pasteForm.category" data-testid="kimp-paste-category">
              <option value="">不指定（服务端按导入缺省）</option>
              <option v-for="option in categoryOptions" :key="option.value" :value="option.value">
                {{ option.label }}
              </option>
            </select>
          </label>
          <label class="kimp-field">
            <span>责任人</span>
            <input v-model="pasteForm.owner" data-testid="kimp-paste-owner" placeholder="如：李管家（缺省 = 当前导入人）" />
          </label>
          <label class="kimp-field">
            <span>生效日（缺省 = 今天）</span>
            <input v-model="pasteForm.effectiveFrom" type="date" data-testid="kimp-paste-from" />
          </label>
          <label class="kimp-field">
            <span>失效日（可空 = 长期有效）</span>
            <input v-model="pasteForm.effectiveTo" type="date" data-testid="kimp-paste-to" />
          </label>
          <label class="kimp-field">
            <span>审核状态</span>
            <select v-model="pasteForm.status" data-testid="kimp-paste-status">
              <option value="pending_review">待审核（默认，需 it/admin 审核）</option>
              <option v-if="directApprover" value="approved">直接生效（直审，仅 it/admin）</option>
            </select>
          </label>
        </div>

        <p class="kimp-hint" data-testid="kimp-paste-status-hint">
          审核状态在**预览页提交时**生效（创建任务只做解析与切分）：默认「待审核」需 it/admin 审核通过后发布；
          it/admin 选「直接生效」时预览页仍可二次确认。前端隐藏直审入口只是提示，服务端 403 兜底。
        </p>
        <p v-if="pasteError" class="kimp-form-error" data-testid="kimp-paste-error">{{ pasteError }}</p>
        <p v-if="pasteMissing.length" class="kimp-form-error" data-testid="kimp-paste-missing">
          缺少必填字段：{{ pasteMissing.join('、') }}
        </p>
        <p v-if="pasteCreateError" class="kimp-form-error" data-testid="kimp-paste-create-error">
          {{ pasteCreateError }}
        </p>
        <p v-if="pasteTimedOut" class="kimp-note kimp-note--warn" data-testid="kimp-timeout">
          <WTag tone="a" plain>超时</WTag>请求超时（E-4001 可重试）：解析链路较慢或网络抖动，请稍后重试；任务可能已创建，可在下方任务列表确认。
        </p>

        <template #footer>
          <div class="kimp-actions">
            <span class="dim">
              提交后进入任务状态机；解析成功 → 预览页勾选切分块 → 提交待审（能力 knowledge.import）。
            </span>
            <span class="kimp-actions__btns">
              <WButton size="sm" variant="ghost" data-testid="kimp-paste-reset" @click="startPaste">清空</WButton>
              <WButton
                size="sm"
                variant="primary"
                :loading="creatingPaste"
                data-testid="kimp-paste-submit"
                @click="submitPaste"
              >
                创建导入任务并解析
              </WButton>
            </span>
          </div>
        </template>
      </WCard>

      <!-- ===================== ③ 上传表单 ===================== -->
      <WCard v-else-if="view === 'file'" :title="uploadTitle" data-testid="kimp-file">
        <template #extra>
          <WTag tone="n" plain>multipart/form-data · sourceType={{ uploadMode }}</WTag>
          <WTag v-if="uploadMode === 'batch'" tone="a" plain>需 knowledge.import.batch</WTag>
        </template>

        <div
          class="kimp-drop"
          :class="{ 'is-hover': fileHover }"
          role="button"
          tabindex="0"
          data-testid="kimp-file-dropzone"
          @click="pickFile"
          @keydown.enter="pickFile"
          @dragover.prevent="fileHover = true"
          @dragleave.prevent="fileHover = false"
          @drop.prevent="onDrop"
        >
          <b>{{ uploadMode === 'batch' ? '拖拽 ZIP 到这里，或点击选择' : '拖拽文件到这里，或点击选择' }}</b>
          <span>{{ uploadLimitText }}</span>
          <span v-if="uploadMode === 'batch'" class="dim">ZIP 内支持 txt / md / docx / pdf；超过 {{ KNOWLEDGE_IMPORT_LIMITS.maxZipFiles }} 个文件或解压总量超限会被服务端拒绝（413 E-1001）</span>
          <span v-else class="dim">白名单：{{ KNOWLEDGE_IMPORT_LIMITS.allowedFileTypes.map((type) => `.${type}`).join(' / ') }}（其余类型 400 E-1001）</span>
        </div>
        <input
          ref="fileInput"
          type="file"
          class="kimp-file-input"
          :accept="uploadAccept"
          data-testid="kimp-file-input"
          @change="onFileInput"
        />

        <p v-if="selectedFile" class="kimp-note" data-testid="kimp-file-selected">
          <WTag tone="g" plain>已选择</WTag>
          <b data-testid="kimp-file-name">{{ selectedFile.name }}</b>
          <span class="num">{{ formatBytes(selectedFile.size) }}</span>
          <span class="dim">· .{{ fileExtOf(selectedFile.name) }} · {{ uploadMode === 'batch' ? 'ZIP 批量' : '单文件' }}</span>
          <WButton size="sm" variant="ghost" data-testid="kimp-file-clear" @click="selectedFile = null">移除</WButton>
        </p>
        <p v-if="fileError" class="kimp-form-error" data-testid="kimp-file-error">{{ fileError }}</p>
        <p v-if="fileCreateError" class="kimp-form-error" data-testid="kimp-file-create-error">{{ fileCreateError }}</p>
        <p v-if="fileTimedOut" class="kimp-note kimp-note--warn" data-testid="kimp-timeout">
          <WTag tone="a" plain>超时</WTag>上传/解析超时（E-4001 可重试）：大文件解析较慢，任务可能已创建，请在下方任务列表确认后再重试。
        </p>

        <template #footer>
          <div class="kimp-actions">
            <span class="dim">上传后服务端同步解析并返回预览（Chunk 切分由编排层完成，前端不改写切分）。</span>
            <span class="kimp-actions__btns">
              <WButton size="sm" variant="ghost" data-testid="kimp-file-back" @click="backToChoose">返回</WButton>
              <WButton
                size="sm"
                variant="primary"
                :loading="creatingFile"
                :disabled="!selectedFile"
                data-testid="kimp-file-submit"
                @click="uploadSelected"
              >
                上传并解析
              </WButton>
            </span>
          </div>
        </template>
      </WCard>

      <!-- ===================== ④ 预览与提交 ===================== -->
      <ImportPreview
        v-else-if="view === 'preview' && activeJob"
        :job="activeJob"
        :direct-approver="directApprover"
        :initial-status="commitStartStatus"
        :submitting="committing"
        :submitting-label="submittingLabel"
        :error="commitError"
        :commit-result="commitResult"
        @submit="commitPreview"
        @cancel="cancelActiveJob"
        @open-knowledge="openKnowledge"
        @done="goJobs"
      />

      <!-- ===================== ⑤ 任务详情 ===================== -->
      <WCard
        v-else-if="view === 'job'"
        title="导入任务详情"
        :subtitle="activeJob ? `${activeJob.jobId} · ${IMPORT_SOURCE_LABELS[activeJob.sourceType] ?? activeJob.sourceType}` : '正在读取任务…'"
        data-testid="kimp-job-detail"
      >
        <template #extra>
          <WTag v-if="activeJob" :tone="IMPORT_STATUS_TONES[activeJob.status]">
            {{ activeJobStatusLabel }}
          </WTag>
          <WTag tone="n" plain>演示数据（进程内任务存储）</WTag>
        </template>

        <p v-if="activeJobLoading" class="kimp-note" data-testid="kimp-loading">
          <WLoading label="正在读取任务详情（GET /api/v1/knowledge/imports/{jobId}）…" />
        </p>
        <PermissionDenied
          v-else-if="activeJobDenied"
          compact
          data-testid="kimp-job-detail-denied"
          :required-capability="activeJobDenied.requiredCapability"
          :required-role="activeJobDenied.role"
          :trace-id="activeJobDenied.traceId"
          detail="任务详情需要 knowledge.import 能力；当前角色不可读取该任务（跨项目任务服务端按 404 处理）。"
        />
        <p v-else-if="activeJobError" class="kimp-form-error" data-testid="kimp-job-detail-error">
          {{ activeJobError }}
          <WButton v-if="activeJob" size="sm" variant="ghost" data-testid="kimp-job-detail-reload" @click="reloadActiveJob">重试</WButton>
        </p>
        <p v-if="pollTimedOut" class="kimp-note kimp-note--warn" data-testid="kimp-timeout">
          <WTag tone="a" plain>超时</WTag>轮询超时（E-4001 可重试）：任务仍在处理，可点任务列表「刷新」查看最新状态。
        </p>
        <p v-if="activeJobDegraded" class="kimp-note kimp-note--warn" data-testid="kimp-degraded">
          <WTag tone="a" plain>已降级</WTag>{{ activeJobDegraded }}
        </p>

        <template v-if="activeJob">
          <div class="kimp-preview__meta" data-testid="kimp-job-meta">
            <span><em>任务</em><b class="num">{{ activeJob.jobId }}</b></span>
            <span><em>状态</em><b data-testid="kimp-job-detail-status">{{ activeJobStatusLabel }}</b></span>
            <span><em>来源</em><b>{{ IMPORT_SOURCE_LABELS[activeJob.sourceType] ?? activeJob.sourceType }}</b></span>
            <span><em>项目</em><b class="num">{{ activeJob.projectId }}</b></span>
            <span><em>标题</em><b>{{ activeJob.title || '—' }}</b></span>
            <span><em>文件</em><b>{{ activeJob.fileName || '—' }}</b></span>
            <span><em>大小/块</em><b class="num">{{ jobSizeText(activeJob) }}</b></span>
            <span><em>创建人</em><b>{{ activeJob.createdBy }}</b></span>
            <span><em>创建时间</em><b class="num">{{ formatIsoMinute(activeJob.createdAt) || '—' }}</b></span>
            <span><em>更新时间</em><b class="num">{{ formatIsoMinute(activeJob.updatedAt) || '—' }}</b></span>
            <span v-if="(activeJob.piiMaskedCount ?? 0) > 0"><em>脱敏</em><b class="num">{{ activeJob.piiMaskedCount }} 处</b></span>
            <span><em>切分块</em><b class="num">{{ activeJob.preview?.totalChunks ?? 0 }}</b></span>
          </div>

          <!-- failed：原因 + 重试/取消 -->
          <div v-if="activeJob.status === 'failed'" class="kimp-job__block" data-testid="kimp-job-failed">
            <b>失败原因（服务端 error.code / retryable）</b>
            <p class="kimp-job__error" data-testid="kimp-job-failed-reason">{{ importErrorText(activeJob.error) }}</p>
            <p class="kimp-hint">
              失败任务不产生知识：可「重新导入」修正内容/格式（加密 PDF、损坏 docx、空文本等属于不可重试）；
              服务端保留原文与任务记录用于回溯（不自动清理）。
            </p>
            <div class="kimp-actions__btns">
              <WButton size="sm" variant="primary" data-testid="kimp-job-retry" @click="retryActiveJob">重新导入</WButton>
              <WButton
                size="sm"
                variant="danger"
                :loading="activeJobCancelLoading"
                :disabled="!activeJobCanCancel"
                data-testid="kimp-job-cancel"
                @click="cancelActiveJob"
              >
                取消任务
              </WButton>
              <WButton size="sm" variant="ghost" data-testid="kimp-job-back" @click="backToChoose">返回方式选择</WButton>
            </div>
          </div>

          <!-- preview_ready：去预览 -->
          <div v-else-if="activeJob.status === 'preview_ready'" class="kimp-job__block" data-testid="kimp-job-preview-ready">
            <b>预览就绪：{{ activeJob.preview?.totalChunks ?? 0 }} 个切分块</b>
            <p class="kimp-hint">下一步在预览页勾选要入库的块、确认元数据与审核状态后提交。</p>
            <div class="kimp-actions__btns">
              <WButton size="sm" variant="primary" data-testid="kimp-job-goto-preview" @click="view = 'preview'">去预览并提交</WButton>
              <WButton
                size="sm"
                variant="danger"
                :loading="activeJobCancelLoading"
                data-testid="kimp-job-cancel"
                @click="cancelActiveJob"
              >
                取消任务
              </WButton>
            </div>
          </div>

          <!-- indexing / committing：进行中 -->
          <div v-else-if="activeJob.status === 'indexing' || activeJob.status === 'committing'" class="kimp-job__block" data-testid="kimp-job-indexing">
            <b>{{ activeJob.status === 'indexing' ? '索引中：知识已生成，正在写入检索索引' : '提交中：正在生成知识' }}</b>
            <p class="kimp-hint">
              页面每 2s 轮询任务状态；完成后任务变为 completed。索引失败时直审会回滚已生成知识并保持 preview_ready（503 E-5002）。
            </p>
          </div>

          <!-- completed：索引结果 -->
          <div v-else-if="activeJob.status === 'completed'" class="kimp-job__block" data-testid="kimp-job-completed">
            <b>已完成：知识已生成{{ commitResult ? '并提交成功' : '' }}</b>
            <ul v-if="commitResult" class="kimp-job__items">
              <li v-for="item in commitResult.items" :key="item.knowledgeId" :data-testid="`kimp-job-item-${item.knowledgeId}`">
                <WTag tone="g" plain>{{ item.knowledgeId }}</WTag>
                <span class="num">{{ item.version }}</span>
                <span class="dim">状态 {{ item.status === 'published' ? '已发布（可检索）' : '待审核（未生效）' }}</span>
                <WButton size="sm" variant="ghost" :data-testid="`kimp-job-open-knowledge-${item.knowledgeId}`" @click="openKnowledge(item.knowledgeId)">
                  打开知识详情
                </WButton>
              </li>
            </ul>
            <p v-else class="kimp-hint">
              任务已完成：知识已生成并触发索引（服务端审计 knowledge.index）；本次会话未保留提交回执时，
              请在「运营视图」按标题检索新知识确认。
            </p>
            <div class="kimp-actions__btns">
              <WButton size="sm" variant="primary" data-testid="kimp-job-open-ops" @click="openKnowledge()">打开知识运营视图</WButton>
              <WButton size="sm" variant="ghost" data-testid="kimp-job-back" @click="backToChoose">返回方式选择</WButton>
            </div>
          </div>

          <!-- cancelled -->
          <div v-else-if="activeJob.status === 'cancelled'" class="kimp-job__block" data-testid="kimp-job-cancelled">
            <b>任务已取消（终态）</b>
            <p class="kimp-hint">已取消任务不生成知识、不可再提交；原文与任务记录保留用于回溯（不自动清理）。</p>
            <div class="kimp-actions__btns">
              <WButton size="sm" variant="primary" data-testid="kimp-job-back" @click="backToChoose">返回方式选择</WButton>
            </div>
          </div>

          <!-- pending / parsing：等待中 -->
          <div v-else class="kimp-job__block" data-testid="kimp-job-waiting">
            <b>{{ activeJobStatusLabel }}：正在解析</b>
            <p class="kimp-hint">解析在编排层完成（docx/pdf/zip），页面每 2s 轮询直到预览就绪或失败。</p>
          </div>
        </template>
      </WCard>

      <!-- ===================== ⑥ 空态 / 降级 / 错误 / 任务列表 ===================== -->
      <p v-if="jobsDegraded && view === 'choose'" class="kimp-note kimp-note--warn" data-testid="kimp-degraded">
        <WTag tone="a" plain>已降级</WTag>{{ jobsDegraded }}
      </p>

      <PermissionDenied
        v-if="jobsDenied && view === 'choose'"
        compact
        data-testid="kimp-jobs-denied"
        :required-capability="jobsDenied.requiredCapability"
        :required-role="jobsDenied.role"
        :trace-id="jobsDenied.traceId"
        :switching="switching"
        :candidate-roles="deniedCandidates"
        detail="导入任务列表需要 knowledge.import 能力；当前角色不可读取任务（owner 403）。"
        @switch="switchAccount"
      />

      <WEmpty
        v-if="view === 'choose' && !jobsLoading && !jobsError && !jobsDenied && jobs.length === 0"
        compact
        icon="report"
        title="还没有导入任务"
        description="选择上方任一方式开始：粘贴文本 → 预览切分块 → 提交待审；上传 docx/pdf 或批量 ZIP 同样先预览再提交。"
        data-testid="kimp-empty"
      />

      <ImportJobTable
        v-if="view === 'choose'"
        :jobs="jobs"
        :total="jobsTotal"
        :page="jobsPage"
        :page-size="KNOWLEDGE_IMPORT_JOB_PAGE_SIZE"
        :loading="jobsLoading"
        :error="jobsError"
        :status="jobsStatus"
        @refresh="loadJobs"
        @open="openJob"
        @filter="filterJobs"
        @page="gotoJobsPage"
      />

      <p class="kimp-hint">
        口径：切分/脱敏/去重由编排层完成（Java 只做 BFF 编排）；预览块 text 已脱敏；警告不阻断；
        直审 approved 仅 it/admin 且会同步索引。演示环境任务为进程内存储（重启回滚），非生产知识库。
      </p>
    </template>
  </div>
</template>

<style scoped>
.kimp {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
  min-width: 0;
}

.kimp-methods {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(210px, 1fr));
  gap: 9px;
}

.kimp-method {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 11px 12px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-lg);
  background: var(--c-card);
  text-align: left;
  cursor: pointer;
  transition: border-color var(--tr-fast), background-color var(--tr-fast), box-shadow var(--tr-fast);
}

.kimp-method:hover {
  border-color: var(--c-brand);
  background: var(--c-brand-soft);
}

.kimp-method.is-on {
  border-color: var(--c-brand);
  background: var(--c-brand-soft);
  box-shadow: var(--sh-ring);
}

.kimp-method b {
  font-size: var(--fs-sm);
  font-weight: var(--fw-semibold);
  color: var(--c-ink);
}

.kimp-method span {
  font-size: var(--fs-2xs);
  color: var(--c-muted);
  line-height: 1.6;
}

.kimp-method em {
  font-style: normal;
  font-size: 9.5px;
  color: var(--c-faint);
}

.kimp-method.is-locked {
  cursor: not-allowed;
  opacity: 0.62;
  background: var(--c-bg-2);
}

.kimp-form-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
  gap: 9px 12px;
}

.kimp-field {
  display: flex;
  flex-direction: column;
  gap: 3px;
  font-size: 9.5px;
  color: var(--c-faint);
}

.kimp-field input,
.kimp-field select,
.kimp-field textarea {
  padding: 6px 8px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-sm);
  background: var(--c-card);
  font-size: var(--fs-xs);
  color: var(--c-ink-2);
  font-family: inherit;
}

.kimp-field input,
.kimp-field select {
  height: 30px;
}

.kimp-field textarea {
  resize: vertical;
  line-height: 1.7;
}

.kimp-field.is-block {
  grid-column: 1 / -1;
}

.kimp-over {
  color: #c0392b;
}

.kimp-drop {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 5px;
  min-height: 128px;
  padding: 16px;
  border: 1px dashed var(--c-line);
  border-radius: var(--rd-lg);
  background: var(--c-bg-2);
  text-align: center;
  cursor: pointer;
  transition: border-color var(--tr-fast), background-color var(--tr-fast);
}

.kimp-drop.is-hover,
.kimp-drop:hover {
  border-color: var(--c-brand);
  background: var(--c-brand-soft);
}

.kimp-drop b {
  font-size: var(--fs-sm);
  color: var(--c-ink-2);
}

.kimp-drop span {
  font-size: var(--fs-2xs);
  color: var(--c-muted);
  line-height: 1.6;
}

.kimp-file-input {
  display: none;
}

.kimp-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px;
}

.kimp-actions__btns {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 6px;
}

.kimp-note {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 8px;
  padding: 7px 10px;
  border-radius: var(--rd-sm);
  background: var(--c-bg-2);
  font-size: var(--fs-2xs);
  color: var(--c-muted);
  line-height: var(--lh-base);
}

.kimp-note--warn {
  background: var(--c-amber-soft);
  color: #9a4b05;
}

.kimp-form-error {
  margin-top: 8px;
  padding: 7px 10px;
  border-left: 2px solid #c0392b;
  border-radius: 0 var(--rd-sm) var(--rd-sm) 0;
  background: var(--c-amber-soft);
  color: #9a4b05;
  font-size: var(--fs-2xs);
  line-height: var(--lh-base);
}

.kimp-hint {
  margin-top: 7px;
  font-size: 9.5px;
  color: var(--c-faint);
  line-height: 1.6;
}

.kimp-preview__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 16px;
  margin-bottom: 9px;
  padding: 8px 10px;
  border: 1px solid var(--c-line-2);
  border-radius: var(--rd-md);
  background: var(--c-bg-2);
}

.kimp-preview__meta span {
  display: inline-flex;
  align-items: baseline;
  gap: 5px;
  font-size: var(--fs-2xs);
  color: var(--c-ink-2);
}

.kimp-preview__meta em {
  font-style: normal;
  font-size: 9.5px;
  color: var(--c-faint);
}

.kimp-job__block {
  margin-top: 9px;
  padding: 9px 11px;
  border: 1px solid var(--c-line-2);
  border-radius: var(--rd-md);
  background: var(--c-card);
  font-size: var(--fs-2xs);
  color: var(--c-muted);
}

.kimp-job__block b {
  display: block;
  margin-bottom: 5px;
  font-size: var(--fs-xs);
  color: var(--c-ink-2);
}

.kimp-job__error {
  margin-bottom: 6px;
  padding: 6px 9px;
  border-left: 2px solid #c0392b;
  border-radius: 0 var(--rd-sm) var(--rd-sm) 0;
  background: var(--c-amber-soft);
  color: #9a4b05;
  line-height: 1.6;
}

.kimp-job__items {
  display: flex;
  flex-direction: column;
  gap: 5px;
  margin: 0 0 8px;
  padding: 0;
  list-style: none;
}

.kimp-job__items li {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
}
</style>
