import type { NavGroup, NavItem, Role } from '@/types'
import { NAV_GROUP_LABELS, NAV_GROUP_ORDER, NAV_ITEMS, SCREENS, SCREEN_ORDER } from './screens'
import { ROLES } from './roles'

/**
 * 角色可见性 + 动作级权限占位（P1-027）。
 *
 * 分两层，都是**演示占位**，不做真实鉴权：
 * 1) 页面级：12 屏的可见角色写在 SCREENS[key].roles；导航只渲染可见项；
 *    路由守卫对不可见路径跳 /forbidden。
 * 2) 动作级：敏感动作（导出 / 催缴 / 接管 / 新增连接器 / 发布映射 / 证据包…）
 *    用 CAPABILITIES 声明允许角色，页面调用 can(role, capability) 灰置或拦截。
 *
 * 边界：真实鉴权在 P2 由登录态 + 后端接口校验完成；这里只保证“演示时不会
 * 出现越权入口”，并在 /forbidden 明确提示这是占位。
 */

function normalizePath(path: string): string {
  const clean = path.split('?')[0]?.split('#')[0] ?? '/'
  return clean === '' ? '/' : clean
}

/** 路径 → 所属导航项（支持 Copilot 子路由按前缀匹配） */
export function navItemForPath(path: string): NavItem | undefined {
  const target = normalizePath(path)
  return NAV_ITEMS.find((item) => target === item.path || target.startsWith(`${item.path}/`))
}

export function canAccess(role: Role, path: string): boolean {
  const item = navItemForPath(path)
  // 非导航路径（组件预览、403 等）不做角色限制
  if (!item) return true
  return item.roles.includes(role)
}

export function visibleNav(role: Role): NavItem[] {
  return NAV_ITEMS.filter((item) => item.roles.includes(role))
}

export interface NavGroupBlock {
  key: NavGroup
  label: string
  items: NavItem[]
}

/** 左侧导航：按分组输出当前角色可见项（跳过空分组，如业主看不到治理分组） */
export function visibleNavGroups(role: Role): NavGroupBlock[] {
  const items = visibleNav(role)
  return NAV_GROUP_ORDER.map((group) => ({
    key: group,
    label: NAV_GROUP_LABELS[group],
    items: items.filter((item) => item.group === group),
  })).filter((block) => block.items.length > 0)
}

/** 切换角色后若当前路径不可见，回落到该角色第一个可见页 */
export function firstAllowedPath(role: Role): string {
  return visibleNav(role)[0]?.path ?? '/overview'
}

/* ------------------------------ 动作级权限占位 ------------------------------ */

export type Capability =
  | 'owner.pay_jump'
  | 'owner.service_rate'
  | 'desk.takeover'
  | 'billing.export'
  | 'billing.collect_task'
  | 'erp.connector_create'
  | 'mapping.publish'
  | 'orchestration.degrade'
  | 'trace.evidence_export'
  | 'knowledge.upsert'
  | 'dashboard.export'
  | 'ai.boundary_config'
  | 'role.demo_switch'

export interface CapabilityMeta {
  key: Capability
  label: string
  desc: string
  /** 允许执行该动作的角色（演示矩阵） */
  roles: Role[]
}

/**
 * 动作级权限矩阵（与 P1-032/P1-033 的弹层动作一一对应）。
 * 演示版只用它做入口灰置与说明，后端不做校验。
 */
export const CAPABILITIES: CapabilityMeta[] = [
  {
    key: 'owner.pay_jump',
    label: '缴费跳转',
    desc: '业主端点“去缴费”只跳转收费系统，AI 不读取、不写财务数据',
    roles: ['owner', 'admin'],
  },
  {
    key: 'owner.service_rate',
    label: '服务评价',
    desc: '工单完工后业主/管家可提交评价，评价影响班组考核',
    roles: ['owner', 'steward', 'supervisor', 'admin'],
  },
  {
    key: 'desk.takeover',
    label: '接管会话',
    desc: '坐席从 AI 接管会话并回复；接管后 AI 只做辅助建议',
    roles: ['agent', 'supervisor', 'admin'],
  },
  {
    key: 'billing.export',
    label: '导出欠费台账',
    desc: '导出当前筛选结果 CSV；含手机号等敏感字段需脱敏',
    roles: ['steward', 'supervisor', 'admin'],
  },
  {
    key: 'billing.collect_task',
    label: '创建催缴任务',
    desc: '按分层触达创建催缴任务；不做群内自动回复、不碰钱',
    roles: ['steward', 'supervisor', 'admin'],
  },
  {
    key: 'erp.connector_create',
    label: '新增连接器',
    desc: '走 4 步向导新增一套 ERP 连接器（演示只生成草稿）',
    roles: ['it', 'admin'],
  },
  {
    key: 'mapping.publish',
    label: '发布映射版本',
    desc: '字段/状态映射版本发布与回滚，需要试跑通过',
    roles: ['it', 'admin'],
  },
  {
    key: 'orchestration.degrade',
    label: '调整降级策略',
    desc: '缺能力时改走跳转 ERP / 企微 / 人工池，不允许“界面上假支持”',
    roles: ['it', 'admin'],
  },
  {
    key: 'trace.evidence_export',
    label: '导出证据包',
    desc: '导出 trace + 知识引用 + 护栏判断的证据 CSV，字段脱敏',
    roles: ['supervisor', 'it', 'admin'],
  },
  {
    key: 'knowledge.upsert',
    label: '新增/变更知识',
    desc: '知识新增与变更单提交，需版本、生效期与责任人',
    roles: ['steward', 'supervisor', 'it', 'admin'],
  },
  {
    key: 'dashboard.export',
    label: '导出周报',
    desc: '按附录 C 口径导出 KPI 周报 CSV',
    roles: ['supervisor', 'it', 'admin'],
  },
  {
    key: 'ai.boundary_config',
    label: 'AI 边界配置',
    desc: 'L1/L2/L3 能力边界与脱敏规则配置；演示版只读占位，不接模型',
    roles: ['it', 'admin'],
  },
  {
    key: 'role.demo_switch',
    label: '演示角色切换',
    desc: '顶栏角色切换器，仅演示用，不是登录鉴权',
    roles: ROLES.map((role) => role.key),
  },
]

const CAPABILITY_MAP = new Map(CAPABILITIES.map((item) => [item.key, item]))

export function can(role: Role, capability: Capability): boolean {
  return CAPABILITY_MAP.get(capability)?.roles.includes(role) ?? false
}

export function capabilitiesOf(role: Role): CapabilityMeta[] {
  return CAPABILITIES.filter((item) => item.roles.includes(role))
}

/* ------------------------------ 权限矩阵（给 /forbidden 与文档用） ------------------------------ */

export interface MatrixRow {
  key: string
  label: string
  /** 该行允许的角色；空数组表示演示版对所有角色开放 */
  roles: Role[]
}

/** 12 屏 × 6 角色 */
export function screenMatrix(): MatrixRow[] {
  return SCREEN_ORDER.map((key) => ({ key: `screen:${key}`, label: SCREENS[key].title, roles: SCREENS[key].roles }))
}

/** 动作 × 6 角色 */
export function actionMatrix(): MatrixRow[] {
  return CAPABILITIES.map((item) => ({ key: `action:${item.key}`, label: item.label, roles: item.roles }))
}

/** 全角色（顺序固定：业主 → 管家 → 坐席 → 主管 → IT → 管理员） */
export const MATRIX_ROLES: Role[] = ROLES.map((role) => role.key)

/** 越权提示文案：说明缺什么、谁能看、以及这是占位 */
export function denyReason(role: Role, path: string): string {
  const item = navItemForPath(path)
  const roleLabel = ROLES.find((entry) => entry.key === role)?.label ?? role
  if (!item) return `「${roleLabel}」访问 ${path} 被拦截：该路径不在演示可见性矩阵内。`
  const allowedLabels = MATRIX_ROLES.filter((entry) => item.roles.includes(entry))
    .map((entry) => ROLES.find((roleMeta) => roleMeta.key === entry)?.label ?? entry)
    .join(' / ')
  return `「${roleLabel}」不包含在「${item.label}」的可见角色内；该页仅对 ${allowedLabels} 开放。本次为 P1 可见性占位，真实鉴权在 P2 由登录态与后端接口校验完成。`
}
