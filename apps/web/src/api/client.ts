import { clearSession, readSession, sessionExpired } from '@/auth/session'
import { notifyUnauthorized } from '@/auth/unauthorized'
import type { ApiFailure, ApiResponse, DegradedInfo, ErrorCode } from './contract'
import { ERROR_CODES } from './contract'
import { withProjectScope } from './project-scope'
import type { Health, ScreenKey, ScreenMeta, ScreenMock } from './types'

/**
 * 前端唯一 API 入口：只从 OpenAPI 契约派生。
 * ---------------------------------------------------------------------------
 * 开发环境走 Vite proxy（/api、/healthz 代理到 :4000）；生产由网关/BFF 提供同路径。
 *
 * P2 扩展（契约 v0.3.0）：
 * - 自定义请求头：`Idempotency-Key`（写操作幂等，`/tickets` 建单/迁移/评价必填）、
 *   `X-Trace-Id`（客户端 traceId，服务端优先沿用）；
 * - 统一信封解析：失败取 `error` / `message` / `retryable` / `details` / `degraded`；
 *   成功取 `data` + 可选 `degraded`（降级不是错误：2xx + 替代路径，页面必须标注「已降级」）；
 * - 网络中断 / 超时：按契约归一到 `E-4001`（上游超时），不在前端自造错误码。
 *
 * P2 W8 扩展（契约 v0.3.3 §8，鉴权开启）：
 * - 每个请求自动附 `Authorization: Bearer <演示 JWT>`（来源 `@/auth/session`，不存口令）；
 * - **401 全局出口**：清本地登录态 + `notifyUnauthorized()` 跳 `/login?returnTo=<当前路径>`（不白屏）；
 * - **403**：保留 `details{requiredCapability,role,reason}`（`forbiddenDetailsOf()`），页面渲染「无权限」态。
 */
const BASE = (import.meta.env.VITE_API_BASE as string | undefined) ?? ''

/** 默认超时：契约未规定具体值，P2 前端取 12s（超过即按 E-4001 处理，避免请求悬挂） */
const DEFAULT_TIMEOUT_MS = 12_000

/** HTTP 状态码 → 17 类错误码（仅当响应体缺失 `error` 时兜底；正常情况以响应体为准） */
const STATUS_ERROR_CODE: Readonly<Record<number, ErrorCode>> = {
  400: 'E-1001',
  403: 'E-2001',
  404: 'E-3001',
  409: 'E-3002',
  422: 'E-1001',
  429: 'E-4003',
  500: 'E-9001',
  502: 'E-4002',
  503: 'E-4001',
  504: 'E-4001',
}

/** 单次请求可选参数（在 RequestInit 之上补齐契约头与超时/JSON body） */
export interface ApiRequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  /** 请求体：对象按 JSON 序列化，字符串原样发送 */
  body?: unknown
  /**
   * multipart 请求体（`FormData`；与 `body` 互斥）。
   * `Content-Type` **不手工设置**：由浏览器补 `multipart/form-data; boundary=...`，
   * 手工设置会丢失 boundary 导致服务端解析失败（知识导入 task-87 上传/ZIP 用）。
   */
  formData?: FormData
  /** 写操作幂等键（映射为 `Idempotency-Key` 头；≥8 字符） */
  idempotencyKey?: string
  /** 客户端 traceId（映射为 `X-Trace-Id` 头） */
  traceId?: string
  /** 附加请求头 */
  headers?: Record<string, string>
  /** 超时毫秒；超时按 E-4001 处理 */
  timeoutMs?: number
  /** 外部取消信号（组件卸载等） */
  signal?: AbortSignal
  /**
   * 401 不跳登录页（P2 W8）：仅用于登录/登出等自管接口——
   * 登录 401 是「账号或口令错误」，应由登录页就地提示，而不是把用户弹回登录页。
   */
  noAuthRedirect?: boolean
}

/** 成功响应：`data` + 信封里的 `traceId` / `degraded` */
export interface ApiResult<T> {
  data: T
  traceId: string
  /** 降级标记；出现时页面必须标注「已降级」并说明写操作是否暂停 */
  degraded?: DegradedInfo
}

/**
 * 统一 API 错误（失败信封 + 网络/超时的规范化结果）。
 * `details` 保留契约结构化补充（如 `received` 幂等键、`dup`: 重复建单 `ticketNo`、
 * `allowed`: 允许的迁移动作、`attemptsLeft`: 剩余核验次数），页面据此给替代路径。
 */
export class ApiError extends Error {
  readonly code: ErrorCode | string
  readonly traceId: string
  readonly retryable: boolean
  readonly details: Record<string, unknown>
  readonly degraded?: DegradedInfo
  /** HTTP 状态码；网络/超时为 0 */
  readonly httpStatus: number

  constructor(
    code: ErrorCode | string,
    traceId = '',
    options: {
      message?: string
      retryable?: boolean
      details?: Record<string, unknown>
      degraded?: DegradedInfo
      httpStatus?: number
    } = {},
  ) {
    super(options.message ?? code)
    this.name = 'ApiError'
    this.code = code
    this.traceId = traceId
    this.details = options.details ?? {}
    this.degraded = options.degraded
    this.httpStatus = options.httpStatus ?? 0
    const known = (ERROR_CODES as Record<string, { retryable: boolean } | undefined>)[code]
    this.retryable = options.retryable ?? known?.retryable ?? false
  }
}

function buildHeaders(options: ApiRequestOptions, hasBody: boolean): Record<string, string> {
  const headers: Record<string, string> = { Accept: 'application/json', ...(options.headers ?? {}) }
  if (hasBody && !headers['Content-Type']) headers['Content-Type'] = 'application/json'
  if (options.idempotencyKey) headers['Idempotency-Key'] = options.idempotencyKey
  if (options.traceId) headers['X-Trace-Id'] = options.traceId
  // P2 W8：统一附带演示 JWT（契约 v0.3.3 §8 `Authorization: Bearer <token>`）；
  // 未登录时不带（公开端点如 /auth/login、/healthz、12 屏 mock 不需要）。
  const session = readSession()
  if (session?.token && !headers.Authorization) headers.Authorization = `Bearer ${session.token}`
  return headers
}

/**
 * 403 的契约结构化细节（契约 v0.3.3 `components/responses/Forbidden`）：
 * `{ requiredCapability, role, reason: capability_denied }`。页面据此给「无权限」态。
 */
export interface ForbiddenDetails {
  requiredCapability: string
  role: string
  reason: string
}

/** 从任意异常里取 403 细节；不是 403 时返回 null（页面用它决定是否渲染「无权限」态） */
export function forbiddenDetailsOf(error: unknown): ForbiddenDetails | null {
  if (!(error instanceof ApiError) || error.httpStatus !== 403) return null
  const details = error.details ?? {}
  const requiredCapability = typeof details.requiredCapability === 'string' ? details.requiredCapability : ''
  const role = typeof details.role === 'string' ? details.role : ''
  const reason = typeof details.reason === 'string' ? details.reason : 'capability_denied'
  return { requiredCapability, role, reason }
}

/** 401 的契约原因（契约 v0.3.3 `components/responses/Unauthorized`） */
export function unauthorizedReasonOf(error: unknown): string | null {
  if (!(error instanceof ApiError) || error.httpStatus !== 401) return null
  const reason = error.details?.reason
  return typeof reason === 'string' ? reason : 'missing_token'
}

/**
 * P2 W8：本地已判定过期就不发请求（401 口径一致：E-2001 + details.reason=expired_token）。
 * JSON 与 Blob 两条请求路径共用，避免过期态发到服务端才 401。
 */
function assertSessionFresh(options: ApiRequestOptions): void {
  const session = readSession()
  if (!session || !sessionExpired(session)) return
  clearSession()
  if (!options.noAuthRedirect) notifyUnauthorized()
  throw new ApiError('E-2001', '', {
    message: '登录已过期，请重新登录',
    details: { reason: 'expired_token' },
    retryable: false,
    httpStatus: 401,
  })
}

/**
 * 底层 fetch（JSON / Blob 共用）：自动附 Bearer、超时、外部取消；
 * 网络中断 / 超时 / 主动取消 → 契约口径归一为 E-4001（上游超时，可重试）。
 */
async function performFetch(path: string, options: ApiRequestOptions): Promise<Response> {
  const controller = new AbortController()
  let timedOut = false
  const timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT_MS
  const timer = setTimeout(() => {
    timedOut = true
    controller.abort()
  }, timeoutMs)
  const onExternalAbort = () => controller.abort()
  options.signal?.addEventListener('abort', onExternalAbort)

  const hasJsonBody = options.body !== undefined
  const hasFormBody = options.formData !== undefined
  const hasBody = hasJsonBody || hasFormBody
  const method = options.method ?? (hasBody ? 'POST' : 'GET')
  // P5 W19：项目作用域统一注入（GET + 项目作用域白名单前缀；页面显式传 projectId 时不覆盖）
  const scopedPath = withProjectScope(path, method)
  try {
    return await fetch(`${BASE}${scopedPath}`, {
      method,
      // multipart 不设 Content-Type（浏览器补 boundary）；JSON 才补 application/json
      headers: buildHeaders(options, hasJsonBody),
      body: hasFormBody
        ? options.formData
        : hasJsonBody
          ? typeof options.body === 'string'
            ? options.body
            : JSON.stringify(options.body)
          : undefined,
      signal: controller.signal,
    })
  } catch (error) {
    const reason = timedOut
      ? `请求超时（${timeoutMs}ms）`
      : options.signal?.aborted
        ? '请求已取消'
        : '网络不可用或请求被中断'
    throw new ApiError('E-4001', options.traceId ?? '', {
      message: `${reason}：${error instanceof Error ? error.message : String(error)}`,
      retryable: !options.signal?.aborted,
      details: { path, timedOut },
    })
  } finally {
    clearTimeout(timer)
    options.signal?.removeEventListener('abort', onExternalAbort)
  }
}

/**
 * 失败响应 → {@link ApiError}（JSON 与 Blob 共用；失败信封仍是统一 ErrorResponse）。
 * P2 W8 全局错误出口（契约 v0.3.3 §8）：
 * - 401 → 清本地登录态 + 跳 `/login?returnTo=`（不白屏），并抛带 details.reason 的错误；
 * - 403 → 保留 details{requiredCapability,role}，由页面渲染「无权限」态（不编造数据）。
 */
function throwApiFailure(
  res: Response,
  failure: ApiFailure | null,
  options: ApiRequestOptions,
): never {
  const code: ErrorCode | string =
    failure?.error ??
    (STATUS_ERROR_CODE[res.status] as ErrorCode | undefined) ??
    (res.ok ? 'E-9001' : `HTTP_${res.status}`)
  if (res.status === 401 && !options.noAuthRedirect) {
    clearSession()
    notifyUnauthorized()
  }
  throw new ApiError(code, failure?.traceId ?? '', {
    message: failure?.message ?? (res.ok ? undefined : `HTTP ${res.status}`),
    retryable: failure?.retryable,
    details: failure?.details,
    degraded: failure?.degraded,
    httpStatus: res.status,
  })
}

/**
 * 统一请求方法（页面不直接 fetch，全部经由 SDK）。
 * 失败一律抛 {@link ApiError}：`res.ok === false`、`ok:false` 信封、非 JSON、网络中断、超时。
 */
export async function request<T>(path: string, options: ApiRequestOptions = {}): Promise<ApiResult<T>> {
  assertSessionFresh(options)
  const res = await performFetch(path, options)
  const body = (await res.json().catch(() => null)) as ApiResponse<T> | null

  if (!res.ok || !body || body.ok === false) {
    throwApiFailure(res, body && body.ok === false ? body : null, options)
  }

  return { data: body.data as T, traceId: body.traceId ?? '', degraded: body.degraded }
}

/**
 * multipart 请求（P6 W34 task-87：知识导入上传文件 / ZIP 批量）。
 * ---------------------------------------------------------------------------
 * 与 {@link request} 共用同一套鉴权头、超时、项目作用域与错误信封解析；
 * **不手工设置 `Content-Type`**（浏览器自动补 `multipart/form-data; boundary=...`），
 * 否则 boundary 丢失、Java 侧 `MultipartFile` 解析失败。
 */
export async function requestMultipart<T>(
  path: string,
  formData: FormData,
  options: Omit<ApiRequestOptions, 'body' | 'formData'> = {},
): Promise<ApiResult<T>> {
  return request<T>(path, { ...options, method: options.method ?? 'POST', formData })
}

/* ============================================================================
 * 文件流请求（P3-031 对账差异 CSV 导出）
 * ========================================================================== */

/** 文件流响应：Blob + 契约 `Content-Disposition` 文件名 + 类型/traceId */
export interface BlobResult {
  blob: Blob
  /** `Content-Disposition` 解析出的文件名；响应头缺失时为空串（调用方给缺省名） */
  filename: string
  contentType: string
  traceId: string
}

/** 去掉文件名里的路径部分（防 `../` 之类目录穿越；浏览器下载名只允许纯文件名） */
function basenameOfFilename(value: string): string {
  const parts = value.split(/[\\/]/)
  return (parts[parts.length - 1] ?? '').trim()
}

/**
 * 解析 `Content-Disposition` 文件名（RFC 6266）：
 * - `attachment; filename="recon-RECON-20261116-0001.csv"`（Java 端点当前形态）
 * - `filename=recon-xxx.csv`（无引号）
 * - `filename*=UTF-8''recon-xxx.csv`（RFC 5987 扩展，优先）
 * 缺省/无法解析返回空串，由调用方回落 `recon-<runId>.csv`（不编造名字）。
 */
export function parseContentDispositionFilename(header?: string | null): string {
  if (!header) return ''
  const extended = /filename\*\s*=\s*([^;]+)/i.exec(header)
  if (extended?.[1]) {
    let raw = extended[1].trim().replace(/^["']|["']$/g, '')
    const encoded = raw.split("''")
    if (encoded.length === 2) raw = encoded[1] ?? ''
    try {
      return basenameOfFilename(decodeURIComponent(raw))
    } catch {
      return basenameOfFilename(raw)
    }
  }
  const plain = /filename\s*=\s*(?:"([^"]*)"|([^;]+))/i.exec(header)
  const value = (plain?.[1] ?? plain?.[2] ?? '').trim()
  return basenameOfFilename(value)
}

/**
 * 文件流请求（唯一允许绕开 JSON 信封的路径）：
 * 2xx **直接返回二进制**（契约文件流例外：text/csv 附件下载），
 * 失败仍按统一 `ErrorResponse` 解析并抛 {@link ApiError}（401/403/404/409 语义与 JSON 请求一致）。
 */
export async function requestBlob(path: string, options: ApiRequestOptions = {}): Promise<BlobResult> {
  assertSessionFresh(options)
  const res = await performFetch(path, options)
  if (!res.ok) {
    const failure = (await res.json().catch(() => null)) as ApiResponse<unknown> | null
    throwApiFailure(res, failure && failure.ok === false ? failure : null, options)
  }
  const blob = await res.blob()
  return {
    blob,
    filename: parseContentDispositionFilename(res.headers.get('Content-Disposition')),
    contentType: res.headers.get('Content-Type') ?? '',
    traceId: res.headers.get('X-Trace-Id') ?? '',
  }
}

/**
 * `/healthz` 按 OpenAPI 契约返回**扁平** Health（`{ok,service,mock,now}`），
 * 不走 `{ok,data,error,traceId}` 统一信封；若复用 request() 会取到 undefined.data。
 * 这里单独解析，保持与前端的 API 状态徽标一致。
 */
export async function healthCheck(): Promise<Health> {
  let res: Response
  try {
    res = await fetch(`${BASE}/healthz`, { headers: { Accept: 'application/json' } })
  } catch {
    throw new ApiError('E-4001', '', { message: '健康检查请求失败（网络不可用）', retryable: true })
  }
  const body = (await res.json().catch(() => null)) as Health | null
  if (!res.ok || !body) {
    throw new ApiError((STATUS_ERROR_CODE[res.status] as ErrorCode | undefined) ?? `HTTP_${res.status}`, '', {
      httpStatus: res.status,
    })
  }
  return body
}

/** P1 遗留只读端点（12 屏 mock）：保留兼容，P2 新页面走下方命名空间 API */
export const metaApi = {
  listScreens: async (): Promise<ScreenMeta[]> => {
    const { data } = await request<{ screens: ScreenMeta[] }>('/api/v1/meta/screens')
    return data.screens
  },
  getScreen: (key: ScreenKey) => request<ScreenMock>(`/api/v1/screens/${key}`),
}
