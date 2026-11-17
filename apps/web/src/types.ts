/**
 * W1 本地类型定义。
 *
 * 说明：`packages/shared` 由后端并行创建，W1 前端**不依赖** `@property/shared`。
 * W2 接入 shared 后，本文件只保留 UI 侧类型（导航/角色/token 相关），
 * 业务 DTO（账单/工单/知识等）改为从 `@property/shared` 导入。
 */

/** 6 类演示角色（仅可见性占位，W1 不做真实鉴权） */
export type Role = 'owner' | 'steward' | 'agent' | 'supervisor' | 'it' | 'admin'

export interface RoleMeta {
  key: Role
  /** 角色切换器与导航提示里显示的名字 */
  label: string
  /** 头像等紧凑场景的短名 */
  short: string
  description: string
}

export type ProjectStatus = 'online' | 'gray' | 'planned'

export interface ProjectMeta {
  id: string
  name: string
  status: ProjectStatus
  /** 副标题：户数与 ERP，来自原型演示项目「云栖苑」 */
  meta: string
}

/** 内联图标名；与 `components/ui/icons.ts` 一一对应 */
export type IconName =
  | 'overview'
  | 'chat'
  | 'list-check'
  | 'sparkles'
  | 'headset'
  | 'wallet'
  | 'plug'
  | 'mapping'
  | 'workflow'
  | 'activity'
  | 'book'
  | 'chart'
  | 'search'
  | 'bell'
  | 'chevron-down'
  | 'chevron-right'
  | 'close'
  | 'check'
  | 'copy'
  | 'plus'
  | 'menu'
  | 'user'
  | 'lock'
  | 'users'
  | 'report'
  | 'clock'
  | 'edit'
  | 'idcard'
  | 'info'
  | 'filter'
  | 'download'
  | 'refresh'
  | 'arrow-right'
  | 'arrow-up'
  | 'mic'
  | 'shield'

/** 导航分组（左侧栏的小标题）。分组顺序与 12 项顺序保持一致：
 *  业务工作台（01—06）→ 适配与运营（07—12）。 */
export type NavGroup = 'core' | 'platform'

export interface NavItem {
  key: string
  path: string
  label: string
  icon: IconName
  group: NavGroup
  roles: Role[]
  badge?: string
}

/** 12 屏之一的 key（Copilot 屏为 8 工作区的容器） */
export type ScreenKey =
  | 'overview'
  | 'owner'
  | 'progress'
  | 'copilot'
  | 'desk'
  | 'billing'
  | 'erp'
  | 'mapping'
  | 'orchestration'
  | 'trace'
  | 'knowledge'
  | 'dashboard'

/** Copilot 8 工作区 */
export type CopilotWorkspaceKey =
  | 'knowledge'
  | 'script'
  | 'summary'
  | 'profile'
  | 'daily'
  | 'group'
  | 'build'
  | 'history'

export interface ScreenPanelMeta {
  title: string
  /** W2 要填充的内容说明（占位文案，不是业务数据） */
  note: string
  icon: IconName
}

export interface ScreenMeta {
  key: ScreenKey
  path: string
  /** 左侧导航文案 */
  navLabel: string
  icon: IconName
  group: NavGroup
  roles: Role[]
  badge?: string
  eyebrow: string
  title: string
  description: string
  /** W2 交付重点，用于骨架页提示 */
  w2Focus: string[]
  panels: ScreenPanelMeta[]
}

export interface CopilotWorkspaceMeta {
  key: CopilotWorkspaceKey
  path: string
  label: string
  icon: IconName
  description: string
  w2Focus: string
  panels: ScreenPanelMeta[]
}

/** 页面级加载状态（W1 组件占位用，W2 接 mock API 后复用） */
export type LoadState = 'idle' | 'loading' | 'ready' | 'empty' | 'error' | 'degraded'

export interface TableColumn {
  key: string
  title: string
  width?: string
  align?: 'left' | 'center' | 'right'
}

export type TimelineStatus = 'done' | 'active' | 'pending' | 'error'

export interface TimelineItem {
  title: string
  time?: string
  description?: string
  status?: TimelineStatus
}

export interface ToastItem {
  id: number
  message: string
  tone: 'info' | 'success' | 'warning' | 'error'
  duration: number
}
