import type { ActorRole } from '@/api/contract'

/**
 * 角色 × 能力（前端侧，P2 W8）
 * ===========================================================================
 * 口径来源：Java `com.property.ai.security.PermissionMatrix`（契约 v0.3.3 §8 / 架构 §14.2）。
 * 用途**仅限前端隐藏入口、展示「需要什么能力」与登录页能力说明**；
 * **这不是安全边界**——真正的 401/403 由 Java BFF `SecurityFilter` 判定。
 *
 * 平台级权限（`mapping.*` / `connector.*` / `degrade.read` / `recon.*` / `audit.read`）
 * 是权限标识，不是错误码（README §2.2）。
 */

/** 审计读取能力（平台级；仅 supervisor/it/admin） */
export const AUDIT_READ = 'audit.read'

/**
 * 角色 → 能力集合（逐条对齐 Java `PermissionMatrix#buildMatrix`，禁止前端另写一套语义）
 *
 * P4 W15 增量（task-52 同步，跨任务一致性要求；Java 侧由 p4-w15-java-knowledge /
 * p4-java-copilot-desk-trace 追加，本文件只跟随、不改语义）：
 * - `knowledge.read/change/review/stats`：owner 仅 read（且只 published+生效期）；
 *   staff 可 read/change/stats；**review 仅 it/admin**；
 * - `copilot.use`（8 工作区，staff+it/admin）、`copilot.broadcast`（群服务人工确认，仅 staff）；
 * - `desk.*`（队列/接管/释放/SLA/质检：agent+supervisor，it/admin 平台只读子集）；
 * - `trace.read/export`（全链路回溯，仅 supervisor/it/admin）。
 */
export const ROLE_CAPABILITIES: Record<ActorRole, readonly string[]> = {
  owner: [
    'room.query',
    'bill.query',
    'owner.verify',
    'ticket.create',
    'ticket.status',
    'visit.record',
    // P3 W13：owner 自有单改约/催办（数据范围由 BFF 强制）
    'ticket.reschedule.own',
    'ticket.urge.own',
    // P3 W12：投诉（本户）；数据范围由 BFF 领域服务强制
    'complaint.create',
    'complaint.read',
    // P4 W15：知识只读（仅 published + 生效期，服务端强制）
    'knowledge.read',
    // P5 W19：项目只读（本项目；跨项目 403 cross_project）
    'project.read',
    'rag.query',
  ],
  steward: [
    'room.query',
    'bill.query',
    // P3 W13 修正（task-43）：staff 可代业主报修（项目内任意房号）；owner 仅本户房号
    'ticket.create',
    'ticket.status',
    'ticket.assign',
    'visit.record',
    'handoff.human',
    'complaint.create',
    'complaint.read',
    'complaint.handle',
    'complaint.stats',
    'dunning.task',
    // P4 W15：知识（staff 可读全部状态 + 提交变更 + 读统计；无审核权）
    'knowledge.read',
    'knowledge.change',
    'knowledge.stats',
    // P4 W16 增量：Copilot 8 工作区 + 群服务人工确认群发
    'copilot.use',
    'copilot.broadcast',
    'project.read',
    'rag.query',
  ],
  agent: [
    'room.query',
    'bill.query',
    // P3 W13 修正（task-43）：staff 可代业主报修（项目内任意房号）；owner 仅本户房号
    'ticket.create',
    'ticket.status',
    'ticket.assign',
    'visit.record',
    'handoff.human',
    'complaint.create',
    'complaint.read',
    'complaint.handle',
    'complaint.stats',
    'dunning.task',
    // P4 W15：知识（同 staff；无审核权）
    'knowledge.read',
    'knowledge.change',
    'knowledge.stats',
    // P4 W16 增量：Copilot + 群服务 + 坐席工作台（接管/释放/SLA/质检）
    'copilot.use',
    'copilot.broadcast',
    'desk.queue',
    'desk.takeover',
    'desk.release',
    'desk.sla',
    'desk.qa.score',
    'project.read',
    'rag.query',
  ],
  supervisor: [
    'room.query',
    'bill.query',
    // P3 W13 修正（task-43）：staff 可代业主报修（项目内任意房号）；owner 仅本户房号
    'ticket.create',
    'ticket.status',
    'ticket.assign',
    'visit.record',
    'handoff.human',
    AUDIT_READ,
    'recon.run',
    'recon.report',
    'complaint.create',
    'complaint.read',
    'complaint.handle',
    'complaint.escalate',
    'complaint.stats',
    'dunning.task',
    // P4 W15：知识（同 staff；无审核权）
    'knowledge.read',
    'knowledge.change',
    'knowledge.stats',
    // P4 W16 增量：Copilot + 群服务 + 坐席（含质检统计）+ 全链路回溯/证据导出
    'copilot.use',
    'copilot.broadcast',
    'desk.queue',
    'desk.takeover',
    'desk.release',
    'desk.sla',
    'desk.qa.score',
    'desk.qa.stats',
    'trace.read',
    'trace.export',
    'project.read',
    'event.read',
    'rag.query',
  ],
  it: [
    'mapping.read',
    'mapping.trial',
    'mapping.publish',
    'mapping.rollback',
    'connector.read',
    'connector.probe',
    'degrade.read',
    AUDIT_READ,
    'recon.run',
    'recon.report',
    'fee.catalog.change',
    'fee.catalog.review',
    // P4 W15：知识（平台只读 + 可提交变更 + **唯一审核角色** + 统计）
    'knowledge.read',
    'knowledge.change',
    'knowledge.review',
    'knowledge.stats',
    // P4 W16 增量：Copilot 只读 + 坐席平台只读子集（无接管/释放）+ 回溯/证据导出
    'copilot.use',
    'desk.queue',
    'desk.sla',
    'desk.qa.score',
    'desk.qa.stats',
    'trace.read',
    'trace.export',
    'project.read',
    'connector.sdk.read',
    'gray.policy.read',
    'gray.policy.manage',
    'event.read',
    'observability.read',
    'rag.query',
    'rag.health',
    'knowledge.reindex',
  ],
  admin: [
    'mapping.read',
    'mapping.trial',
    'mapping.publish',
    'mapping.rollback',
    'connector.read',
    'connector.probe',
    'degrade.read',
    AUDIT_READ,
    'recon.run',
    'recon.report',
    'fee.catalog.change',
    'fee.catalog.review',
    // P4 W15：知识（同 it：平台只读 + 提交变更 + 审核 + 统计）
    'knowledge.read',
    'knowledge.change',
    'knowledge.review',
    'knowledge.stats',
    // P4 W16 增量：同 it（平台只读口径）
    'copilot.use',
    'desk.queue',
    'desk.sla',
    'desk.qa.score',
    'desk.qa.stats',
    'trace.read',
    'trace.export',
    'project.read',
    'connector.sdk.read',
    'gray.policy.read',
    'gray.policy.manage',
    'event.read',
    'observability.read',
    'rag.query',
    'rag.health',
    'knowledge.reindex',
  ],
}

/** 能力 → 人话（403 说明与登录页能力卡片用；未知能力原样展示，不编造含义） */
export const CAPABILITY_LABELS: Record<string, string> = {
  'room.query': '房档只读',
  'bill.query': '账单只读',
  'owner.verify': '业主身份核验',
  'ticket.create': '报修建单',
  'ticket.status': '工单进度查询',
  'ticket.assign': '派单 / 改约 / 催办',
  'visit.record': '回访评价记录',
  'handoff.human': '转人工摘要',
  'mapping.read': '映射版本只读',
  'mapping.trial': '映射试跑',
  'mapping.publish': '映射发布',
  'mapping.rollback': '映射回滚',
  'connector.read': '连接器与能力矩阵只读',
  'connector.probe': '能力探测',
  'degrade.read': '降级台账只读',
  'recon.run': '只读对账触发',
  'recon.report': '对账报告 / 历史',
  'complaint.create': '投诉提交',
  'complaint.read': '投诉查看',
  'complaint.handle': '投诉受理 / 首响 / 结案',
  'complaint.escalate': '投诉升级（主管）',
  'complaint.stats': '投诉统计看板',
  'dunning.task': '催缴任务（生成/话术/触达/关闭）',
  'ticket.reschedule.own': '自有单改约（业主）',
  'ticket.urge.own': '自有单催办（业主）',
  'fee.catalog.change': '费项口径变更提交',
  'fee.catalog.review': '费项口径变更审核',
  // ---- P4 W15/W16 增量的能力人话（403 说明与登录页能力卡片用；与 Java 能力码一一对应） ----
  'knowledge.read': '知识只读（owner 仅 published+生效期）',
  'knowledge.change': '知识变更提交（进入待审核）',
  'knowledge.review': '知识变更审核（仅 it/admin）',
  'knowledge.stats': '知识与采纳统计',
  'copilot.use': 'Copilot 8 工作区',
  'copilot.broadcast': '群服务人工确认群发',
  'desk.queue': '坐席队列',
  'desk.takeover': '接管会话',
  'desk.release': '释放会话',
  'desk.sla': '坐席 SLA 看板',
  'desk.qa.score': '质检评分',
  'desk.qa.stats': '质检统计',
  'trace.read': '全链路回溯只读',
  'trace.export': '证据包导出',
  'project.read': '项目只读（本项目；跨项目 403）',
  'connector.sdk.read': '连接器 SDK 元数据只读',
  'gray.policy.read': '灰度策略只读',
  'gray.policy.manage': '灰度策略管理',
  'event.read': '事件（outbox）只读',
  'observability.read': '可观测性指标/健康（平台运维）',
  'rag.query': '知识库 RAG 问答（带引用/拒答）',
  'rag.health': 'RAG 链路健康（平台运维）',
  'knowledge.reindex': '知识索引重建（平台运维）',
  [AUDIT_READ]: '审计事件读取',
}

export function capabilitiesOfRole(role: ActorRole | null | undefined): readonly string[] {
  if (!role) return []
  return ROLE_CAPABILITIES[role] ?? []
}

/** 角色是否具备能力（前端隐藏入口用，不是安全边界） */
export function roleHasCapability(role: ActorRole | null | undefined, capability: string): boolean {
  if (!role || !capability) return false
  return (ROLE_CAPABILITIES[role] ?? []).includes(capability)
}

/** 能力 → 展示文案（如 `audit.read（审计事件读取）`） */
export function capabilityLabel(capability?: string | null): string {
  if (!capability) return '（未提供）'
  const label = CAPABILITY_LABELS[capability]
  return label ? `${capability}（${label}）` : capability
}

/**
 * 路径 → 后端所需能力（/forbidden 页与页面内 403 态说明用）。
 * 只覆盖「有后端能力门槛」的页面；未命中的路径说明为「页面可见性（演示矩阵）」。
 */
const PATH_CAPABILITIES: readonly { prefix: string; capability: string }[] = [
  { prefix: '/erp', capability: 'connector.read' },
  { prefix: '/mapping', capability: 'mapping.read' },
  { prefix: '/orchestration', capability: 'degrade.read' },
  { prefix: '/audit', capability: AUDIT_READ },
]

export function capabilityForPath(path: string | null | undefined): string | null {
  if (!path) return null
  const clean = path.split('?')[0]?.split('#')[0] ?? ''
  const hit = PATH_CAPABILITIES.find((item) => clean === item.prefix || clean.startsWith(`${item.prefix}/`))
  return hit?.capability ?? null
}
