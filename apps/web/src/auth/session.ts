import type { Actor } from '@/api/contract'

/**
 * 前端登录态本地持久化（P2 W8，契约 v0.3.3 §11.9.1）
 * ===========================================================================
 * 边界（AGENTS.md NEVER #11 / 契约 demo 口径）：
 * - 这里存的是**演示登录态**（演示 JWT + 演示主体 `mock: true`），不是真实 SSO/微信/短信鉴权；
 * - 只存 `Authorization: Bearer` 需要的 token 与 `Actor`，**不存口令**（演示口令只来自 shared
 *   `DEMO_PASSWORD`，页面提示用，不落 localStorage），也不打 token 明文日志；
 * - 真实安全边界在 Java BFF `SecurityFilter`（401/403）；前端的 `hasCapability` 只用于隐藏入口/说明。
 *
 * 存储键：`property.auth`（README / 契约文档里与 `?role=` 深链同口径，改动属契约变更）。
 */
export const AUTH_STORAGE_KEY = 'property.auth'

/** 登录响应（契约 `LoginResult`）里前端需要持久化的部分（不含口令与日志明文） */
export interface AuthSession {
  token: string
  tokenType: 'Bearer'
  /** 过期时间（契约 `LoginResult.expiresAt`，ISO 8601） */
  expiresAt: string
  /** 演示主体（契约 `Actor`；`mock` 恒 true） */
  actor: Actor
  /** 演示标记（恒 true，页面据此标注「演示账号（demo）」） */
  mock: boolean
  /** 本地记录的本端登录时间（仅展示/排障，不含敏感信息） */
  signedInAt: string
}

function storage(): Storage | null {
  // vitest(node 环境) / SSR 下没有 localStorage：返回 null，调用方按未登录处理
  try {
    if (typeof globalThis.localStorage === 'undefined') return null
    return globalThis.localStorage
  } catch {
    return null
  }
}

function isActor(value: unknown): value is Actor {
  if (!value || typeof value !== 'object') return false
  const actor = value as Record<string, unknown>
  return (
    typeof actor.actorId === 'string' &&
    typeof actor.role === 'string' &&
    typeof actor.name === 'string' &&
    typeof actor.mock === 'boolean'
  )
}

/** 结构校验：只接受完整登录态；损坏/旧格式一律视为未登录（不猜、不补） */
export function isAuthSession(value: unknown): value is AuthSession {
  if (!value || typeof value !== 'object') return false
  const session = value as Record<string, unknown>
  return (
    typeof session.token === 'string' &&
    session.token.length > 0 &&
    session.tokenType === 'Bearer' &&
    typeof session.expiresAt === 'string' &&
    isActor(session.actor) &&
    typeof session.mock === 'boolean'
  )
}

/** 持久化登录态变化订阅者（store 用它保持内存态与 localStorage 一致，401 清态后立刻失效） */
type SessionListener = (session: AuthSession | null) => void
const listeners = new Set<SessionListener>()

/** 订阅登录态变化（返回取消订阅函数）；写入/清除都会回调最新登录态 */
export function onSessionChange(listener: SessionListener): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

function notifySessionChange(): void {
  const current = readSession()
  for (const listener of listeners) listener(current)
}

/** 读取持久化登录态（损坏返回 null，不抛错） */
export function readSession(): AuthSession | null {
  const store = storage()
  if (!store) return null
  const raw = store.getItem(AUTH_STORAGE_KEY)
  if (!raw) return null
  try {
    const parsed: unknown = JSON.parse(raw)
    return isAuthSession(parsed) ? parsed : null
  } catch {
    return null
  }
}

/** 写入登录态（localStorage 不可用时静默降级为内存态，页面仍可用但当次刷新会掉登录） */
export function writeSession(session: AuthSession): void {
  const store = storage()
  if (!store) return // 无 localStorage：由调用方持有内存态，不回调（否则会把内存态清成 null）
  try {
    store.setItem(AUTH_STORAGE_KEY, JSON.stringify(session))
  } catch {
    // 隐私模式/配额不足：忽略，调用方仍持有内存态
    return
  }
  notifySessionChange()
}

/** 清除登录态（401、登出、切换账号前调用） */
export function clearSession(): void {
  const store = storage()
  if (store) {
    try {
      store.removeItem(AUTH_STORAGE_KEY)
    } catch {
      // 忽略
    }
  }
  notifySessionChange()
}

/** 是否已过期（`expiresAt` 无法解析时按过期处理，避免用坏 token 反复请求） */
export function sessionExpired(session: AuthSession | null, now: number = Date.now()): boolean {
  if (!session) return true
  const expiresAt = new Date(session.expiresAt).getTime()
  if (Number.isNaN(expiresAt)) return true
  return expiresAt <= now
}

/** 从登录响应（契约 `LoginResult`）构造本地登录态 */
export function toAuthSession(result: {
  token: string
  tokenType: 'Bearer'
  expiresAt: string
  actor: Actor
  mock: boolean
}): AuthSession {
  return {
    token: result.token,
    tokenType: result.tokenType,
    expiresAt: result.expiresAt,
    actor: result.actor,
    mock: result.mock,
    signedInAt: new Date().toISOString(),
  }
}
