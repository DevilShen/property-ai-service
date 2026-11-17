import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { api, ApiError, DEMO_ACCOUNTS, DEMO_PASSWORD, errorNextStep, errorText } from '@/api'
import type { Actor, ActorRole, DemoAccount } from '@/api/contract'
import { capabilitiesOfRole, roleHasCapability } from '@/auth/capabilities'
import {
  clearSession,
  onSessionChange,
  readSession,
  sessionExpired,
  toAuthSession,
  writeSession,
  type AuthSession,
} from '@/auth/session'

/**
 * 演示登录态 store（P2 W8，契约 v0.3.3 §11.9.1 / P2-029）
 * ===========================================================================
 * 职责：
 * - `login(account)` / `loginAsRole(role)`：调 `POST /api/v1/auth/login` 拿演示 JWT + `Actor`，
 *   持久化到 `localStorage['property.auth']`（token/actor/expiresAt，**不存口令**）；
 * - `logout()`：调 `POST /api/v1/auth/logout`（撤销 token + 落审计），失败也清本地态；
 * - `isAuthed` / `hasCapability(cap)`：前者决定路由守卫，后者**只用于隐藏入口与说明**，
 *   真实鉴权在 Java BFF（`SecurityFilter` 角色 × 能力矩阵）。
 *
 * demo 口径（AGENTS.md NEVER #11）：6 个演示账号 + 固定口令 + 本地演示 JWT，
 * **不是真实 SSO/微信/短信鉴权**，页面必须标注「演示账号（demo）」。
 */

/** 登录失败的人话（401 是「账号或口令不正确」，不区分账号是否存在——防枚举口径） */
export interface AuthFailure {
  code: string
  message: string
  next: string
}

/** 登录页展示的演示账号卡片（账号 + 角色 + 能力说明；数据源 shared `DEMO_ACCOUNTS`） */
export interface DemoAccountCard extends DemoAccount {
  capabilities: readonly string[]
}

export const useAuthStore = defineStore('auth', () => {
  /** 当前登录态（初始化时从 localStorage 恢复；e2e/深链在页面加载前写入同一键） */
  const session = ref<AuthSession | null>(readSession())
  const pending = ref(false)
  const failure = ref<AuthFailure | null>(null)
  /** 401 失效原因（missing_token/invalid_token/expired_token），登录页据此给说明 */
  const unauthorizedReason = ref<string>('')

  /**
   * 持久化登录态变化 → 同步内存态。
   * 关键场景：`api/client.ts` 收到 401 时直接 `clearSession()`（请求层不依赖 store），
   * 这里必须立刻把 `session` 置空，否则路由守卫会以为「还登录着」并把用户弹回原页（401 死循环）。
   */
  onSessionChange((next) => {
    session.value = next
  })

  const token = computed<string>(() => {
    const current = session.value
    if (!current || sessionExpired(current)) return ''
    return current.token
  })
  const actor = computed<Actor | null>(() => session.value?.actor ?? null)
  const role = computed<ActorRole | null>(() => actor.value?.role ?? null)
  const expiresAt = computed<string>(() => session.value?.expiresAt ?? '')
  const isAuthed = computed<boolean>(() => Boolean(token.value && actor.value))
  const isDemo = computed<boolean>(() => session.value?.mock === true)
  const capabilities = computed<readonly string[]>(() => capabilitiesOfRole(role.value))
  /** 演示账号卡片（6 个，顺序与 shared `DEMO_ACCOUNTS` 一致） */
  const demoAccounts = computed<DemoAccountCard[]>(() =>
    DEMO_ACCOUNTS.map((account) => ({ ...account, capabilities: capabilitiesOfRole(account.role) })),
  )

  /** 前端能力判断（隐藏入口/说明用，**不是安全边界**） */
  function hasCapability(capability: string): boolean {
    return roleHasCapability(role.value, capability)
  }

  /** 已登录角色是否就是这个演示角色（顶栏/深链避免重复登录） */
  function isRole(accountRole: ActorRole): boolean {
    return isAuthed.value && role.value === accountRole
  }

  /**
   * 演示登录（账号 + 固定口令）。成功写入持久化登录态并返回 true；
   * 失败写入 `failure`（401 → 「账号或密码不正确」人话）并返回 false，不抛异常给页面。
   */
  async function login(account: string, password: string = DEMO_PASSWORD): Promise<boolean> {
    if (!account) {
      failure.value = { code: 'E-1001', message: '请选择一个演示账号', next: '从下方 6 个演示账号中选择' }
      return false
    }
    pending.value = true
    failure.value = null
    unauthorizedReason.value = ''
    try {
      const { data } = await api.auth.login({ account, password })
      session.value = toAuthSession(data)
      writeSession(session.value)
      return true
    } catch (caught) {
      const apiError = caught instanceof ApiError ? caught : null
      const code = apiError?.code ?? 'E-9001'
      const status = apiError?.httpStatus ?? 0
      const reason = typeof apiError?.details?.reason === 'string' ? apiError.details.reason : ''
      if (status === 401 || reason === 'invalid_credentials') {
        unauthorizedReason.value = reason || 'invalid_credentials'
        failure.value = {
          code: 'E-2001',
          message: '账号或密码不正确（演示账号口令统一为 demo123，不区分账号是否存在）',
          next: '点选下方演示账号卡片重新登录；连续失败请检查账号是否在 README §2.1 列表内',
        }
      } else {
        failure.value = { code, message: errorText(code), next: errorNextStep(code) }
      }
      return false
    } finally {
      pending.value = false
    }
  }

  /** 按演示角色一键登录（登录页卡片 / 顶栏演示账号切换 / `?role=` 深链共用） */
  async function loginAsRole(accountRole: ActorRole): Promise<boolean> {
    const account = DEMO_ACCOUNTS.find((item) => item.role === accountRole)
    if (!account) {
      failure.value = { code: 'E-1001', message: `没有「${accountRole}」的演示账号`, next: '使用 README §2.1 的 6 个演示账号' }
      return false
    }
    return login(account.account, DEMO_PASSWORD)
  }

  /** 演示登出：先撤销 token（幂等），再清本地态；接口失败不阻塞前端退出 */
  async function logout(): Promise<void> {
    if (token.value) {
      try {
        await api.auth.logout()
      } catch {
        // 契约：token 已失效/已注销时登出仍视为完成，前端继续清态
      }
    }
    clear()
  }

  /** 清本地登录态（401 出口、登出、切换账号前调用） */
  function clear(): void {
    session.value = null
    clearSession()
  }

  /** 重新从 localStorage 读（多标签页/外部写入后同步；e2e 深链写入同一键） */
  function restore(): void {
    session.value = readSession()
  }

  /** 记录 401 原因（路由守卫把 `details.reason` 带到登录页展示） */
  function setUnauthorizedReason(reason: string): void {
    unauthorizedReason.value = reason
  }

  function clearFailure(): void {
    failure.value = null
  }

  return {
    session,
    actor,
    role,
    token,
    expiresAt,
    isAuthed,
    isDemo,
    pending,
    failure,
    unauthorizedReason,
    capabilities,
    demoAccounts,
    hasCapability,
    isRole,
    login,
    loginAsRole,
    logout,
    clear,
    restore,
    setUnauthorizedReason,
    clearFailure,
  }
})
