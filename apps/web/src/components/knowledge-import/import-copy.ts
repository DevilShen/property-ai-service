import {
  ApiError,
  KNOWLEDGE_IMPORT_JOB_STATUSES,
  KNOWLEDGE_IMPORT_LIMITS,
  errorNextStep,
  errorText,
  type KnowledgeImportCommitStatus,
  type KnowledgeImportError,
  type KnowledgeImportFileType,
  type KnowledgeImportJobStatus,
  type KnowledgeImportSourceType,
  type KnowledgeImportWarning,
} from '@/api'

/**
 * P6 W34 知识导入前端口径（task-87；契约 v1.5.0 §12）
 * ===========================================================================
 * 纯函数 + 文案常量，只做「服务端事实 → 人话」的映射，**不改写契约语义**：
 * - 状态/来源/文件类型/提交状态取值全部来自 `@/api`（shared §12 单一事实源），这里只补中文短文案与 tone；
 * - 限额数字一律读 `KNOWLEDGE_IMPORT_LIMITS`（10MB / 200,000 字符 / 50 文件 / 50MB / 10 次·分），
 *   **不硬编码**；前端只做提示与提交前预校验，真实边界在 Java（413 `E-1001` / 429 `E-4003`）；
 * - 失败原因 `error.code` 必须取 17 类 `ErrorCode`：文案走 `errorText/errorNextStep`，不直出上游报文；
 * - 演示口径：任务为**进程内存储**（重启回滚），页面必须标注「演示数据」，不得说成生产知识库。
 */

type Tone = 'g' | 'b' | 'a' | 'r' | 'v' | 'n'

/** 导入任务状态 → 人话（8 态全集，与 shared `KnowledgeImportJobStatus` 一一对应） */
export const IMPORT_STATUS_LABELS: Readonly<Record<KnowledgeImportJobStatus, string>> = {
  pending: '已受理（排队中）',
  parsing: '解析中',
  preview_ready: '预览就绪（待确认提交）',
  committing: '提交中',
  indexing: '索引中',
  completed: '已完成',
  failed: '失败',
  cancelled: '已取消',
}

/** 导入任务状态 → 短标签（列表列宽有限） */
export const IMPORT_STATUS_SHORT_LABELS: Readonly<Record<KnowledgeImportJobStatus, string>> = {
  pending: '已受理',
  parsing: '解析中',
  preview_ready: '待提交',
  committing: '提交中',
  indexing: '索引中',
  completed: '已完成',
  failed: '失败',
  cancelled: '已取消',
}

/** 状态 → WTag tone（仅视觉，不改语义） */
export const IMPORT_STATUS_TONES: Readonly<Record<KnowledgeImportJobStatus, Tone>> = {
  pending: 'n',
  parsing: 'b',
  preview_ready: 'a',
  committing: 'b',
  indexing: 'b',
  completed: 'g',
  failed: 'r',
  cancelled: 'n',
}

/** 来源类型 → 人话（paste/file/batch/badcase） */
export const IMPORT_SOURCE_LABELS: Readonly<Record<KnowledgeImportSourceType, string>> = {
  paste: '粘贴文本',
  file: '文件上传',
  batch: 'ZIP 批量',
  badcase: 'RAG 缺口沉淀',
}

/** 文件类型 → 展示文案（zip 仅批量） */
export const IMPORT_FILE_TYPE_LABELS: Readonly<Record<KnowledgeImportFileType, string>> = {
  txt: 'TXT',
  md: 'Markdown',
  docx: 'Word',
  pdf: 'PDF',
  zip: 'ZIP 批量',
}

/** 提交后知识状态 → 人话（`KnowledgeImportCommitStatus` 全集） */
export const IMPORT_COMMIT_STATUS_LABELS: Readonly<Record<KnowledgeImportCommitStatus, string>> = {
  draft: '草稿（未生效）',
  pending_review: '待审核（未生效）',
  approved: '已直审生效（可检索）',
}

/** 已知警告码 → 人话标题；未知 code 前向兼容（按通用警告展示，不编造含义） */
export const IMPORT_WARNING_LABELS: Readonly<Record<string, string>> = {
  pii_masked: '隐私信息已脱敏',
  duplicate_suspected: '疑似重复知识',
  truncated: '内容被截断',
  empty_text: '文本为空',
  injection_risk: '疑似提示注入',
  file_skipped: '文件已跳过',
}

/** 警告 → 标题（未知 code 原样展示 code，便于排查；消息本身用服务端人话） */
export function warningLabel(warning: KnowledgeImportWarning): string {
  return IMPORT_WARNING_LABELS[warning.code] ?? `提示（${warning.code}）`
}

/** 字节数 → 人话（1 位小数；用于限额提示与任务大小） */
export function formatBytes(bytes?: number | null): string {
  if (bytes === null || bytes === undefined || Number.isNaN(bytes)) return '—'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

/** 限额人话（全部取自 shared `KNOWLEDGE_IMPORT_LIMITS`，禁止页面硬编码数字） */
export const IMPORT_LIMIT_TEXT = {
  paste: `粘贴 ≤${KNOWLEDGE_IMPORT_LIMITS.maxPasteChars.toLocaleString('zh-Hans-CN')} 字符`,
  file: `单文件 ≤${formatBytes(KNOWLEDGE_IMPORT_LIMITS.maxFileBytes)}（${KNOWLEDGE_IMPORT_LIMITS.allowedFileTypes
    .map((type) => type.toUpperCase())
    .join(' / ')}）`,
  zip: `ZIP ≤${KNOWLEDGE_IMPORT_LIMITS.maxZipFiles} 个文件 / ≤${formatBytes(
    KNOWLEDGE_IMPORT_LIMITS.maxZipBytes,
  )}（解压总量由服务端校验）`,
  rate: `≤${KNOWLEDGE_IMPORT_LIMITS.maxPerMinute} 次/分钟/账号 · 在途任务 ≤${KNOWLEDGE_IMPORT_LIMITS.maxConcurrentJobs} 个`,
} as const

/** 上传控件 accept（单文件白名单） */
export const IMPORT_FILE_ACCEPT = KNOWLEDGE_IMPORT_LIMITS.allowedFileTypes.map((type) => `.${type}`).join(',')

/** 文件扩展名（小写，不含点；无扩展名返回空串） */
export function fileExtOf(fileName: string): string {
  const clean = (fileName ?? '').trim()
  const index = clean.lastIndexOf('.')
  if (index < 0 || index === clean.length - 1) return ''
  return clean.slice(index + 1).toLowerCase()
}

/**
 * 提交前本地预校验（**只做提示**，不替代服务端）：
 * 返回 null = 通过；否则返回人话（含 413 `E-1001` 口径的限额说明）。
 * - ZIP：仅 `batch` 入口可选，校验 50MB 总量（文件数与解压总量由服务端校验，前端解压会二次消耗内存）；
 * - 单文件：白名单 + 10MB。
 */
export function validateImportFile(file: { name: string; size: number }, batch: boolean): string | null {
  const ext = fileExtOf(file.name)
  if (batch) {
    if (ext !== 'zip') return `批量导入只接受 .zip（当前文件 ${file.name}）；单个文件请用「上传文件」方式`
    if (file.size > KNOWLEDGE_IMPORT_LIMITS.maxZipBytes) {
      return `ZIP 超过上限（413 E-1001）：${formatBytes(file.size)} > ${formatBytes(
        KNOWLEDGE_IMPORT_LIMITS.maxZipBytes,
      )}；请拆分后重试（单包 ≤${KNOWLEDGE_IMPORT_LIMITS.maxZipFiles} 个文件）`
    }
    return null
  }
  const allowed = KNOWLEDGE_IMPORT_LIMITS.allowedFileTypes as readonly string[]
  if (!allowed.includes(ext)) {
    return `不支持的文件类型（400 E-1001）：.${ext || '(无扩展名)'}；白名单 ${allowed
      .map((type) => `.${type}`)
      .join(' / ')}`
  }
  if (file.size > KNOWLEDGE_IMPORT_LIMITS.maxFileBytes) {
    return `文件超过上限（413 E-1001）：${formatBytes(file.size)} > ${formatBytes(
      KNOWLEDGE_IMPORT_LIMITS.maxFileBytes,
    )}；请压缩或拆分后重试`
  }
  return null
}

/** 页面仍在等的状态（用于轮询与「解析中」提示） */
export function isImportInFlight(status: KnowledgeImportJobStatus): boolean {
  return status === 'pending' || status === 'parsing' || status === 'committing' || status === 'indexing'
}

/** 终态（completed/failed/cancelled）：不再轮询 */
export function isImportTerminal(status: KnowledgeImportJobStatus): boolean {
  return status === 'completed' || status === 'failed' || status === 'cancelled'
}

/** 可取消（pending/parsing/preview_ready；终态服务端 409 E-3002） */
export function canCancelImport(status: KnowledgeImportJobStatus): boolean {
  return status === 'pending' || status === 'parsing' || status === 'preview_ready'
}

/** 可提交（仅 preview_ready 可 commit） */
export function canCommitImport(status: KnowledgeImportJobStatus): boolean {
  return status === 'preview_ready'
}

/**
 * 失败原因 → 人话（`error.code` 取 17 类 `ErrorCode`；服务端 `message` 已是中文说明，只做兜底拼接）。
 * 不展示内部字段/上游报文；`retryable` 决定是否给「重试」入口。
 */
export function importErrorText(error?: KnowledgeImportError | null): string {
  if (!error) return '任务失败（服务端未返回原因）：可取消后重试，或转 it/admin 排查'
  const base = errorText(error.code)
  const next = errorNextStep(error.code)
  const detail = (error.message ?? '').trim()
  const retry = error.retryable ? '可重试。' : '不可重试，请修正内容后重新导入。'
  return `${base}${detail ? `：${detail}` : ''}；${next}（${error.code}）${retry}`
}

/**
 * 任意异常 → 导入场景人话（超时/超频/超限/403 分别给可操作提示）。
 * 未知异常不展示堆栈/内部码，统一转人工口径。
 */
export function importApiMessage(caught: unknown, fallback = '导入请求失败：请稍后重试或转人工'): string {
  if (!(caught instanceof ApiError)) return fallback
  const error = caught
  if (error.code === 'E-4003') {
    return `导入过于频繁（429 E-4003）：${IMPORT_LIMIT_TEXT.rate}；请等待 1 分钟后重试`
  }
  if (error.code === 'E-4001') {
    const timedOut = error.details?.timedOut === true
    return timedOut
      ? '请求超时（E-4001 可重试）：解析/索引链路较慢，请稍后重试或在任务列表点「刷新」查看最新状态'
      : '网络不可用或请求被中断（E-4001）：请检查网络后重试'
  }
  if (error.code === 'E-1001' && error.details && typeof error.details.limit === 'number') {
    return `超过服务端限额（413 E-1001）：上限 ${String(error.details.limit)}，实际 ${String(
      error.details.length ?? '—',
    )}；请压缩/拆分后重试`
  }
  return `${errorText(error.code)}；${errorNextStep(error.code)}`
}

/** 任务一行展示的「文件/字符」摘要（数据全部来自服务端字段，不补造） */
export function jobSizeText(job: {
  sizeBytes?: number | null
  charCount?: number | null
  preview?: { totalChunks?: number } | null
}): string {
  const parts: string[] = []
  if (typeof job.sizeBytes === 'number') parts.push(formatBytes(job.sizeBytes))
  if (typeof job.charCount === 'number') parts.push(`${job.charCount} 字符`)
  if (typeof job.preview?.totalChunks === 'number') parts.push(`${job.preview.totalChunks} 块`)
  return parts.join(' · ') || '—'
}

/** 状态筛选下拉（顺序 = shared `KNOWLEDGE_IMPORT_JOB_STATUSES`，禁止前端另起一套顺序） */
export const IMPORT_STATUS_OPTIONS: readonly { value: KnowledgeImportJobStatus; label: string }[] =
  KNOWLEDGE_IMPORT_JOB_STATUSES.map((status) => ({ value: status, label: IMPORT_STATUS_LABELS[status] }))
