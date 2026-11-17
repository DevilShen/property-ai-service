import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import type { ProjectMeta, Role } from '@/types'
import { DEFAULT_ROLE, roleMeta } from '@/app/roles'
import { useAuthStore } from '@/stores/auth'
import { useProjectStore } from '@/stores/project'

/** 通知类型：决定通知中心图标/色调与跳转口径（演示维度） */
export type NotificationType =
  | 'ticket-timeout'
  | 'knowledge-expired'
  | 'probe-failed'
  | 'collection'
  | 'gray-release'
  | 'system'

/** 通知筛选：全部 / 未读（通知中心 Tab 状态） */
export type NotificationFilter = 'all' | 'unread'

/**
 * 通知条目（W3 演示数据，`mock: true` 标注）。
 * 字段口径：type 决定图标/跳转语义，targetRoute 为点击后跳转页面，read 为已读状态。
 */
export interface AppNotification {
  id: string
  type: NotificationType
  title: string
  description: string
  /** 相对时间文案（演示） */
  time: string
  read: boolean
  /** 点击通知跳转的站内路由（按 type 关联对应屏） */
  targetRoute: string
  /** 演示数据标注：不接真实事件推送 */
  mock?: boolean
}

/**
 * 8 条演示通知（6 类事件：工单超时 / 知识过期 / 能力探测 / 催缴任务 / 灰度发布 / 系统公告）。
 * 数据口径与页面保持一致：工单号/房号来自 mock/data/tickets.json，K-009 停水通知在知识库为已过期，
 * 312 户欠费来自费用与催缴页，字段映射当前版本 v1.8.2 来自字段与状态映射页。
 * 不接真实事件流，也不含真实业主手机号/金额明细。
 */
const INITIAL_NOTIFICATIONS: AppNotification[] = [
  {
    id: 'n-ticket-timeout-1',
    type: 'ticket-timeout',
    title: '工单已超时 · WO-20261114-0028',
    description: '2-1-301 阳台地漏堵塞已派单，超过「上门时间」承诺，建议催办并回写 ERP。',
    time: '10 分钟前',
    read: false,
    targetRoute: '/progress',
    mock: true,
  },
  {
    id: 'n-knowledge-expired-1',
    type: 'knowledge-expired',
    title: '知识已过期 · K-009 停水通知',
    description: 'V1.0 已过生效期，命中时按「过期降级」转人工，请责任人复核或下线。',
    time: '32 分钟前',
    read: false,
    targetRoute: '/knowledge',
    mock: true,
  },
  {
    id: 'n-probe-failed-1',
    type: 'probe-failed',
    title: '能力探测降级 · 新视窗连接器',
    description: 'ticket.status 能力缺失（文件/RPA 接入），已按降级策略回退到「工单页跳转」，不假支持。',
    time: '1 小时前',
    read: false,
    targetRoute: '/erp',
    mock: true,
  },
  {
    id: 'n-collection-1',
    type: 'collection',
    title: '催缴任务已生成 · 312 户分层',
    description: '按逾期分层生成 AI 话术任务；只生成话术与渠道建议，不外呼、不碰钱。',
    time: '2 小时前',
    read: false,
    targetRoute: '/billing',
    mock: true,
  },
  {
    id: 'n-gray-release-1',
    type: 'gray-release',
    title: '灰度发布 · 字段映射 v1.8.2',
    description: '修正 urgency 映射、补录 3 个房号别名；灰度 30% 观察后再全量，异常可回滚。',
    time: '昨天 17:40',
    read: true,
    targetRoute: '/mapping',
    mock: true,
  },
  {
    id: 'n-system-1',
    type: 'system',
    title: '系统公告 · 演示环境',
    description: '全局搜索与通知中心为演示索引（mock），不接真实搜索/事件推送，也不做真实鉴权。',
    time: '昨天 09:00',
    read: true,
    targetRoute: '/overview',
    mock: true,
  },
  {
    id: 'n-ticket-timeout-2',
    type: 'ticket-timeout',
    title: '工单超时已恢复 · WO-20261112-0024',
    description: '土建班已重新派单，本次超时 12 分钟记入 SLA 周报，供主管复盘。',
    time: '前天 18:12',
    read: true,
    targetRoute: '/progress',
    mock: true,
  },
  {
    id: 'n-system-2',
    type: 'system',
    title: '多项目隔离公告 · 云栖苑 / 观澜府',
    description: '切换项目只改变演示视图；项目级知识隔离与路由在 P2/P5 落地，当前不做真实隔离。',
    time: '前天 09:30',
    read: true,
    targetRoute: '/overview',
    mock: true,
  },
]

export const useAppStore = defineStore('app', () => {
  const auth = useAuthStore()
  const project = useProjectStore()

  /**
   * 当前角色：**从登录态派生**（P2 W8）。
   * 登录/切换演示账号后 `auth.role` 变化，导航与路由可见性自动跟随，不再有第二套角色状态。
   * 未登录时回落默认「管家」仅为渲染兜底（路由守卫在未登录时已跳到 /login）。
   * `?role=` 深链的自动登录在 `router` 守卫里完成（调 `POST /api/v1/auth/login`）。
   */
  const currentRole = computed<Role>(() => (auth.role as Role | null) ?? DEFAULT_ROLE)

  /**
   * 当前项目（P5 W19）：**从 `stores/project` 单一来源派生**（`GET /api/v1/projects`）。
   * 保留 P1 的 `ProjectMeta{id,name,status,meta}` 形状给侧栏/顶栏/面包屑复用；
   * `status` 做契约枚举（active/gray/archived）→ P1 视觉枚举（online/gray/planned）的展示映射，
   * 不改变任何可见性/权限语义（项目隔离由 BFF 强制）。
   */
  const currentProjectId = computed<string>(() => project.activeProjectId)
  const currentProject = computed<ProjectMeta>(() => {
    const item = project.currentProjectOrDemo
    const status: ProjectMeta['status'] =
      item?.status === 'gray' ? 'gray' : item?.status === 'archived' ? 'planned' : 'online'
    return {
      id: item?.projectId ?? '',
      name: item?.name ?? '演示项目',
      status,
      meta: project.currentMetaText,
    }
  })
  /** 全局搜索 / 通知中心浮层状态 */
  const searchOpen = ref(false)
  const notificationOpen = ref(false)
  /** 通知中心筛选 Tab（全部 / 未读） */
  const notificationFilter = ref<NotificationFilter>('all')
  const notifications = ref<AppNotification[]>(INITIAL_NOTIFICATIONS.map((item) => ({ ...item })))

  const currentRoleMeta = computed(() => roleMeta(currentRole.value))
  const unreadCount = computed(() => notifications.value.filter((item) => !item.read).length)

  /** 切换项目：只接受「当前角色可见项目」内的切换（越权由服务端 403 兜底，前端不提供入口） */
  function setProject(projectId: string): boolean {
    return project.select(projectId)
  }

  function setSearchOpen(open: boolean) {
    searchOpen.value = open
  }

  function setNotificationOpen(open: boolean) {
    notificationOpen.value = open
  }

  function setNotificationFilter(filter: NotificationFilter) {
    notificationFilter.value = filter
  }

  /** 单条标记已读（通知中心点击条目时调用） */
  function markRead(id: string) {
    notifications.value = notifications.value.map((item) => (item.id === id ? { ...item, read: true } : item))
  }

  function markAllRead() {
    notifications.value = notifications.value.map((item) => ({ ...item, read: true }))
  }

  function closeOverlays() {
    searchOpen.value = false
    notificationOpen.value = false
  }

  return {
    currentRole,
    currentProjectId,
    currentProject,
    currentRoleMeta,
    notifications,
    notificationFilter,
    unreadCount,
    searchOpen,
    notificationOpen,
    setProject,
    setSearchOpen,
    setNotificationOpen,
    setNotificationFilter,
    markRead,
    markAllRead,
    closeOverlays,
  }
})
