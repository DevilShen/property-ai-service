import type { Role, RoleMeta } from '@/types'

/**
 * 6 类演示角色。W1 只做可见性占位：
 * 角色切换 = 导航与路由可见性变化，不做真实鉴权/接口鉴权。
 */
export const ROLES: RoleMeta[] = [
  { key: 'owner', label: '业主', short: '业', description: '业主视角：业主端、工单进度' },
  { key: 'steward', label: '管家', short: '管', description: '管家视角：业主服务、Copilot、费用与知识' },
  { key: 'agent', label: '坐席', short: '坐', description: '坐席视角：坐席工作台与业主服务' },
  { key: 'supervisor', label: '主管', short: '主', description: '主管视角：全业务视图与回溯，不含适配配置' },
  { key: 'it', label: 'IT/集成', short: 'IT', description: 'IT 视角：适配中心、映射、编排与回溯' },
  { key: 'admin', label: '管理员', short: 'AD', description: '管理员视角：全部路由（演示占位）' },
]

export const ROLE_LABELS: Record<Role, string> = ROLES.reduce(
  (acc, role) => ({ ...acc, [role.key]: role.label }),
  {} as Record<Role, string>,
)

export const ROLE_SHORTS: Record<Role, string> = ROLES.reduce(
  (acc, role) => ({ ...acc, [role.key]: role.short }),
  {} as Record<Role, string>,
)

/** 默认角色：管家，便于演示最完整的员工侧路径 */
export const DEFAULT_ROLE: Role = 'steward'

export function isRole(value: string): value is Role {
  return ROLES.some((role) => role.key === value)
}

export function roleMeta(role: Role): RoleMeta {
  return ROLES.find((item) => item.key === role) ?? ROLES[1]
}
