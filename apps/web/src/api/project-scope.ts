/**
 * 项目作用域注入（P5 W19，契约 v1.3.0 `components/parameters/ProjectId`）
 * ===========================================================================
 * 背景：v1.3.0 起 rooms / bills / tickets / complaints / fees.arrears / dunning.tasks /
 * audit.events 等**列表与详情**接口接受可选 `projectId` 查询参数：
 * - 缺省 = 当前 actor 项目（`Actor.projectId`；it/admin 平台级缺省为全部项目）；
 * - 非本项目 → 403 `E-2002` + `details.reason=cross_project`（**数据范围由服务端强制**）。
 *
 * 为什么在请求层统一注入，而不是每个页面自己拼：
 * 1) 「切换项目后所有业务请求都带当前项目」是**全局一致性**要求，逐页拼参必然漏；
 * 2) 与 `Authorization` 同层（`client.ts` 的 `performFetch`），页面代码不感知注入；
 * 3) 只对**白名单前缀 + GET** 注入：写操作不注入；`/events` 由事件页自己控制项目筛选
 *    （支持「全部项目」）；`/knowledge*`（P5 W22 起含列表/详情/检索/统计）按当前项目注入；
 * 4) 页面显式传了 `projectId` 时不覆盖（调用方优先）。
 *
 * 存储：`localStorage['property.project']`（由 `@/stores/project` 写入/校验后写入），
 * 与 `localStorage['property.auth']` 分列——项目选择不是登录态，不随 token 生命周期。
 */

/** 项目选择的持久化键（与登录态 `property.auth` 分列；e2e/深链可预置同一键） */
export const PROJECT_SCOPE_STORAGE_KEY = 'property.project'

/**
 * 会携带 `projectId` 的接口前缀（与 OpenAPI v1.3.0 的 `ProjectId` 参数声明一致）：
 * 只覆盖「业务列表与详情」；`GET /api/v1/events` 由事件页自己控制项目筛选（支持「全部项目」）。
 */
const SCOPED_PREFIXES: readonly string[] = [
  '/api/v1/rooms',
  '/api/v1/bills',
  '/api/v1/tickets',
  '/api/v1/complaints',
  '/api/v1/fees/arrears',
  '/api/v1/dunning/tasks',
  '/api/v1/audit/events',
  // P5 W22（P5-023）：知识管理视图按项目隔离（列表/详情/检索/统计）；映射/灰度是平台配置视图（仅 it/admin），
  // 其页面不注入项目作用域（由平台级全量视图展示，跨项目读仍受服务端作用域校验）。
  // 注意：/api/v1/knowledge 的 projectId 是**契约补口 P5-024**（v1.3.0 OpenAPI 未声明，向后兼容可选参数）。
  '/api/v1/knowledge',
]

/** 读当前项目作用域（无存储/不可解析 → 空串 = 不注入，服务端按 actor 默认口径） */
export function readProjectScope(): string {
  try {
    const raw = window.localStorage.getItem(PROJECT_SCOPE_STORAGE_KEY)
    return raw ? raw.trim() : ''
  } catch {
    // 隐私模式 / 无 localStorage：退化为不注入（服务端仍按 actor 项目强制数据范围）
    return ''
  }
}

/** 写当前项目作用域（store 校验「项目在可见列表内」后调用；空值 = 清除） */
export function writeProjectScope(projectId: string): void {
  try {
    if (projectId) window.localStorage.setItem(PROJECT_SCOPE_STORAGE_KEY, projectId)
    else window.localStorage.removeItem(PROJECT_SCOPE_STORAGE_KEY)
  } catch {
    // 忽略：不影响请求（无注入时服务端按 actor 项目兜底）
  }
}

/** 路径是否属于「项目作用域」白名单（前缀匹配，按路径段边界判断，避免 /rooms-x 误命中） */
export function isProjectScopedPath(path: string): boolean {
  const clean = path.split('?')[0] ?? ''
  return SCOPED_PREFIXES.some((prefix) => clean === prefix || clean.startsWith(`${prefix}/`))
}

/**
 * 给 GET 请求追加 `projectId`（页面已显式传入时保持原样）。
 * 非白名单端点 / 非 GET / 无当前项目 → 原样返回，不改动请求。
 */
export function withProjectScope(path: string, method: string = 'GET'): string {
  if (method.toUpperCase() !== 'GET') return path
  if (!isProjectScopedPath(path)) return path
  const [clean, query = ''] = path.split('?')
  const params = new URLSearchParams(query)
  if (params.has('projectId')) return path
  const projectId = readProjectScope()
  if (!projectId) return path
  params.set('projectId', projectId)
  return `${clean}?${params.toString()}`
}
