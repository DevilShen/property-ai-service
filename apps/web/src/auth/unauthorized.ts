/**
 * 401 统一出口（P2 W8）
 * ===========================================================================
 * 契约 v0.3.3 §8：业务端点缺 token / 伪造 / 过期 → 401 `E-2001`；
 * 前端必须在**一个出口**清掉本地登录态并跳 `/login?returnTo=<当前路径>`，避免白屏。
 *
 * 为什么不在 `api/client.ts` 直接 import router：
 * - client 是所有页面/单测共用的底层模块，直接依赖 router 会把「请求层」和「路由层」耦死，
 *   也让 node 环境的单测无法导入（createWebHistory 需要 DOM）。
 * 因此这里提供注册式回调：`main.ts` 注册路由跳转实现；未注册时（极端场景）退回整页跳转。
 */
export type UnauthorizedHandler = (returnTo: string) => void

let handler: UnauthorizedHandler | null = null

/** 注册 401 跳转实现（应用启动时调用一次，幂等覆盖） */
export function setUnauthorizedHandler(next: UnauthorizedHandler | null): void {
  handler = next
}

/** 浏览器当前路径（含 query，供 returnTo 回跳；服务端/单测环境回落 `/`） */
export function currentBrowserPath(): string {
  if (typeof window === 'undefined' || !window.location) return '/'
  return `${window.location.pathname}${window.location.search}`
}

/**
 * 通知「登录态失效」：清 token 的职责在调用方（client 先 `clearSession()`），
 * 这里只负责把用户送到登录页并带上 returnTo（已是 /login 时不再重复跳，避免循环）。
 */
export function notifyUnauthorized(returnTo: string = currentBrowserPath()): void {
  const target = returnTo.startsWith('/login') ? '/' : returnTo
  if (handler) {
    handler(target)
    return
  }
  if (typeof window !== 'undefined' && window.location) {
    const query = target && target !== '/' ? `?returnTo=${encodeURIComponent(target)}` : ''
    window.location.assign(`/login${query}`)
  }
}
