import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import AppShell from '@/layouts/AppShell.vue'
import { COPILOT_WORKSPACES, COPILOT_WORKSPACE_ORDER } from '@/app/copilot'
import { canAccess, firstAllowedPath } from '@/app/permissions'
import { isRole } from '@/app/roles'
import { SCREENS, SCREEN_ORDER } from '@/app/screens'
import { roleHasCapability } from '@/auth/capabilities'
import { useAppStore } from '@/stores/app'
import { useAuthStore } from '@/stores/auth'
import type { CopilotWorkspaceKey, Role, ScreenKey } from '@/types'

declare module 'vue-router' {
  interface RouteMeta {
    /** 顶栏面包屑与 document.title */
    title?: string
    eyebrow?: string
    /** 组件预览等开发页，不进导航 */
    devOnly?: boolean
    /** 公开路由（登录页）：无需登录态，且在已登录时回跳 returnTo */
    public?: boolean
    /**
     * 能力门槛（前端隐藏入口/兜底跳转用，**不是安全边界**；真实 403 由 Java BFF 判定）：
     * 用于「非 12 屏导航页」按能力码守卫（如 `/rag` 需要 `rag.query`）。
     */
    requiresCapability?: string
  }
}

/** 12 屏视图：按屏懒加载独立 chunk，业务内容在各 views 内实现 */
const SCREEN_COMPONENTS: Record<ScreenKey, () => Promise<unknown>> = {
  overview: () => import('@/views/overview/OverviewView.vue'),
  owner: () => import('@/views/owner/OwnerView.vue'),
  progress: () => import('@/views/progress/ProgressView.vue'),
  copilot: () => import('@/views/copilot/CopilotView.vue'),
  desk: () => import('@/views/desk/DeskView.vue'),
  billing: () => import('@/views/billing/BillingView.vue'),
  erp: () => import('@/views/erp/ErpView.vue'),
  mapping: () => import('@/views/mapping/MappingView.vue'),
  orchestration: () => import('@/views/orchestration/OrchestrationView.vue'),
  trace: () => import('@/views/trace/TraceView.vue'),
  knowledge: () => import('@/views/knowledge/KnowledgeView.vue'),
  dashboard: () => import('@/views/dashboard/DashboardView.vue'),
}

/** Copilot 8 工作区视图 */
const WORKSPACE_COMPONENTS: Record<CopilotWorkspaceKey, () => Promise<unknown>> = {
  knowledge: () => import('@/views/copilot/workspaces/KnowledgeWorkspace.vue'),
  script: () => import('@/views/copilot/workspaces/ScriptWorkspace.vue'),
  summary: () => import('@/views/copilot/workspaces/SummaryWorkspace.vue'),
  profile: () => import('@/views/copilot/workspaces/ProfileWorkspace.vue'),
  daily: () => import('@/views/copilot/workspaces/DailyWorkspace.vue'),
  group: () => import('@/views/copilot/workspaces/GroupWorkspace.vue'),
  build: () => import('@/views/copilot/workspaces/BuildWorkspace.vue'),
  history: () => import('@/views/copilot/workspaces/HistoryWorkspace.vue'),
}

const workspaceRoutes: RouteRecordRaw[] = COPILOT_WORKSPACE_ORDER.map((key) => ({
  path: key,
  name: `copilot-${key}`,
  component: WORKSPACE_COMPONENTS[key],
  meta: { title: COPILOT_WORKSPACES[key].label },
}))

/** 12 屏路由：由 SCREENS 单一数据源生成，避免路径与导航漂移 */
const screenRoutes: RouteRecordRaw[] = SCREEN_ORDER.flatMap<RouteRecordRaw>((key) => {
  const screen = SCREENS[key]
  if (key === 'copilot') {
    return [
      {
        path: screen.path,
        name: 'copilot',
        component: SCREEN_COMPONENTS.copilot,
        meta: { title: screen.title, eyebrow: screen.eyebrow },
        children: [
          { path: '', name: 'copilot-index', redirect: '/copilot/knowledge' },
          ...workspaceRoutes,
        ],
      },
    ]
  }
  return [
    {
      path: screen.path,
      name: screen.key,
      component: SCREEN_COMPONENTS[key],
      meta: { title: screen.title, eyebrow: screen.eyebrow },
    },
  ]
})

const routes: RouteRecordRaw[] = [
  {
    // P2 W8 演示登录页：**独立于 12 屏导航**（不挂 AppShell，无侧边栏/顶栏）
    path: '/login',
    name: 'login',
    component: () => import('@/views/login/LoginView.vue'),
    meta: { title: '演示登录', public: true },
  },
  {
    path: '/',
    component: AppShell,
    children: [
      { path: '', name: 'root', redirect: '/overview' },
      ...screenRoutes,
      {
        // P3 W12：投诉页（**非 12 屏导航屏**，lead 条件授权 +1 条非导航路由）。
        // 用途：`/desk` 的「投诉处理台」页签对 agent/supervisor/admin 可见，而 desk 可见角色不含
        // steward（W1 可见性矩阵不在本次改动范围）→ steward 用本路由进入处理台；owner 进入时
        // 页面自动切换为「我的投诉」。不写死角色：能力入口按 auth.hasCapability 显隐，
        // 接口 403 统一走 PermissionDenied + requiredCapability（it/admin 进入即「投诉无权限态」）。
        path: '/complaints',
        name: 'complaints',
        component: () => import('@/views/complaint/ComplaintsPage.vue'),
        meta: { title: '投诉受理与处理', eyebrow: 'P3 · 投诉' },
      },
      {
        // P5 W19：事件流页（**非 12 屏导航屏**，沿用 P3 W12 `/complaints` 的非导航路由先例）。
        // 用途：supervisor/it/admin 查看 outbox 事件（GET /api/v1/events）；入口在 ERP 适配中心
        // 「事件」页签与全链路回溯页头按钮，按 `event.read` 能力显隐；其它角色进入即无权限态（接口 403）。
        path: '/events',
        name: 'events',
        component: () => import('@/views/events/EventsView.vue'),
        meta: { title: '事件流', eyebrow: 'P5 · 事件' },
      },
      {
        // P1-025 新增连接器 4 步向导（独立页，不进 12 屏导航契约）
        path: '/erp/wizard',
        name: 'erp-wizard',
        component: () => import('@/views/erp/wizard/ConnectorWizardView.vue'),
        meta: { title: '新增连接器向导', eyebrow: '07 · 适配层' },
      },
      {
        // P6 W33 知识秒查页（**非 12 屏导航屏**，沿用 `/complaints`、`/events` 的非导航路由先例；
        // lead 授权 task-81 +1 条路由，不改 `app/screens.ts` 的 12 屏基线）。
        // 用途：owner / steward / agent / supervisor / it / admin 都能直接问知识（能力 `rag.query`），
        // 解决 `/knowledge` 只对 staff/it/admin 开放时 **owner 无问答 UI 入口** 的问题；
        // 知识页「智能问答」页签、Copilot 知识工作区入口按既有可见性矩阵不变（同一 RagAskPanel 组件）。
        path: '/rag',
        name: 'rag',
        component: () => import('@/views/rag/RagAskView.vue'),
        meta: { title: '知识秒查', eyebrow: 'P6 · RAG', requiresCapability: 'rag.query' },
      },
      {
        path: '/ui-kit',
        name: 'ui-kit',
        component: () => import('@/views/system/UiKitView.vue'),
        meta: { title: '组件预览', eyebrow: '演示 · 组件与状态', devOnly: true },
      },
      {
        path: '/forbidden',
        name: 'forbidden',
        component: () => import('@/views/system/ForbiddenView.vue'),
        meta: { title: '无权访问' },
      },
      { path: '/:pathMatch(.*)*', name: 'not-found', redirect: '/overview' },
    ],
  },
]

export const router = createRouter({
  history: createWebHistory(),
  routes,
})

/* ------------------------- 登录态 + 可见性路由守卫（P2 W8） ------------------------- */

/** 只接受站内相对路径作为 returnTo（防开放重定向；/login 自身不来回跳） */
export function safeReturnTo(value: unknown): string | null {
  if (typeof value !== 'string' || !value.startsWith('/') || value.startsWith('//')) return null
  if (value.startsWith('/login')) return null
  return value
}

/** 登录后落点：returnTo 优先（且当前角色可见），否则该角色第一个可见页 */
export function landingAfterLogin(role: Role, returnTo: unknown): string {
  const target = safeReturnTo(returnTo)
  if (target && canAccess(role, target)) return target
  return firstAllowedPath(role)
}

/**
 * P2 W8 守卫（契约 v0.3.3 §8）：
 * 1) 未登录访问业务页 → `/login?returnTo=<原路径>`（不白屏）；401 出口也走这条；
 * 2) `?role=<Role>` 深链 → 自动调用登录 API 切到对应演示账号（保持既有 e2e/演示语义）；
 * 3) 登录后仍沿用 P1 的「12 屏 × 6 角色」可见性矩阵（演示口径，不是安全边界）。
 */
router.beforeEach(async (to) => {
  const auth = useAuthStore()

  if (to.meta.public || to.name === 'login') {
    if (!auth.isAuthed) return true
    const role = (auth.role ?? 'steward') as Role
    return landingAfterLogin(role, to.query.returnTo)
  }

  // `?role=` 深链（含 /progress?ticket=…&role=owner 这类调试/演示链接）：按 demo 账号自动登录
  const roleQuery = typeof to.query.role === 'string' ? to.query.role : ''
  if (roleQuery && isRole(roleQuery) && !auth.isRole(roleQuery)) {
    await auth.loginAsRole(roleQuery)
  }

  if (!auth.isAuthed) {
    return { name: 'login', query: { returnTo: to.fullPath } }
  }

  // 非导航页的能力门槛（如 `/rag` 需要 `rag.query`）：前端只做入口兜底，
  // 缺失能力 → /forbidden（说明所需能力），真实数据由 Java BFF 403 拦截。
  const requiredCapability = to.meta.requiresCapability
  if (requiredCapability && !roleHasCapability(auth.role, requiredCapability)) {
    return { name: 'forbidden', query: { from: to.fullPath } }
  }

  const app = useAppStore()
  if (!canAccess(app.currentRole, to.path)) {
    return { name: 'forbidden', query: { from: to.fullPath } }
  }
  return true
})

router.afterEach((to) => {
  const title = to.meta.title
  document.title = title ? `${title} · 物业 AI 智能客服` : '物业 AI 智能客服 · 工作台'
})

export default router
