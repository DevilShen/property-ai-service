import type { NavGroup, NavItem, Role, ScreenKey, ScreenMeta } from '@/types'

/**
 * 12 屏的唯一数据源：导航、路由 meta、骨架页占位都从这里读取。
 * W2 各屏业务实现时只改 views，本文件保持"一屏一条"。
 *
 * roles 仅用于可见性占位（P1-027 会在 W3 细化），不代表真实鉴权。
 */
const ALL_ROLES: Role[] = ['owner', 'steward', 'agent', 'supervisor', 'it', 'admin']
const STAFF_ROLES: Role[] = ['steward', 'agent', 'supervisor', 'it', 'admin']

export const SCREEN_ORDER: ScreenKey[] = [
  'overview',
  'owner',
  'progress',
  'copilot',
  'desk',
  'billing',
  'erp',
  'mapping',
  'orchestration',
  'trace',
  'knowledge',
  'dashboard',
]

export const NAV_GROUP_LABELS: Record<NavGroup, string> = {
  core: '业务工作台',
  platform: '适配与运营',
}

export const NAV_GROUP_ORDER: NavGroup[] = ['core', 'platform']

export const SCREENS: Record<ScreenKey, ScreenMeta> = {
  overview: {
    key: 'overview',
    path: '/overview',
    navLabel: '方案总览',
    icon: 'overview',
    group: 'core',
    roles: ['steward', 'agent', 'supervisor', 'it', 'admin'],
    eyebrow: '01 · 总览',
    title: '方案总览',
    description: '一套 AI + 一个适配层 + N 套 ERP：六层架构、三条闭环、18 项能力与 5 套 ERP 适配路线。',
    w2Focus: ['六层架构与三条闭环', '18 项能力 × 5 套 ERP 矩阵', '90 天路线与验收指标'],
    panels: [
      { title: '六层架构', note: '接入层 / 编排层 / 能力层 / 适配层 / 数据层 / 运营层，W2 画架构图。', icon: 'overview' },
      { title: '三条闭环', note: '报修闭环、费用催缴、投诉分级，W2 用链路示意 + 状态卡呈现。', icon: 'workflow' },
      { title: '适配与路线', note: '5 套 ERP 卡片、能力矩阵入口、90 天里程碑，W2 填充。', icon: 'plug' },
    ],
  },
  owner: {
    key: 'owner',
    path: '/owner',
    navLabel: '业主端',
    icon: 'chat',
    group: 'core',
    roles: ALL_ROLES,
    eyebrow: '02 · 业主端',
    title: '业主端 AI 客服',
    description: '业主侧单一智能管家会话：一句话说需求，AI 自动识别账单 / 报修 / 查进度 / 转人工意图，再做身份核验、实时账单解释与 5 问报修建单。',
    w2Focus: ['统一会话 + AI 意图识别（不分费用 / 报修入口）', '身份核验与手机号绑定（三种方式）', '实时账单卡与报修建单卡'],
    panels: [
      { title: '统一会话主区', note: '手机壳内单入口 AI 会话：输入框、答案出处与快捷 chip，不区分费用查询 / 报修入口。', icon: 'chat' },
      { title: '身份核验 / 手机号绑定', note: '微信授权、手动 + 短信、线下核验三种绑定方式弹层，W3 做动作弹层。', icon: 'shield' },
      { title: '实时账单卡 / 工单卡', note: '账单明细来自 ERP、只读展示；缴费仅跳转，不碰钱，W2 填充。', icon: 'wallet' },
    ],
  },
  progress: {
    key: 'progress',
    path: '/progress',
    navLabel: '工单进度',
    icon: 'list-check',
    group: 'core',
    roles: ALL_ROLES,
    eyebrow: '03 · 闭环',
    title: '工单进度 · 闭环',
    description: '7 个节点谁在做、系统如何回写、业主看到什么；三次推送、回访与知识沉淀。',
    w2Focus: ['7 节点时间轴与责任人', '三次推送文案与触发点', '回访、评价与证据留存'],
    panels: [
      { title: '7 节点时间轴', note: '受理 → 派单 → 上门 → 完工 → 回访 → 归档 → 沉淀，W2 用 Timeline 呈现。', icon: 'list-check' },
      { title: '推送与回写', note: '企微/短信三次推送节点与 ERP 回写结果，W2 填充。', icon: 'bell' },
      { title: '回访与证据', note: '服务评价、照片证据、知识沉淀入口，W2/W3 填充。', icon: 'report' },
    ],
  },
  copilot: {
    key: 'copilot',
    path: '/copilot',
    navLabel: '管家 Copilot',
    icon: 'sparkles',
    group: 'core',
    roles: STAFF_ROLES,
    badge: 'AI',
    eyebrow: '04 · Copilot',
    title: '管家 Copilot',
    description: '左侧 8 个工作区独立面板：知识秒查、话术生成、工单摘要、业主画像、日报周报、群服务、知识共建、历史会话。',
    w2Focus: ['8 工作区独立 pane（各自路由）', '知识/话术/摘要 mock 结果与出处', '画像脱敏、群服务 B+C 边界'],
    panels: [
      { title: '8 个工作区', note: '左栏切换，每个工作区独立路由与面板，W2 填充。', icon: 'sparkles' },
      { title: '边界', note: 'AI 不存业务数据、不碰钱、不认定责任、答案必须带出处。', icon: 'shield' },
      { title: '群服务', note: '只做 B（人工确认群发/存档）+ C（个人群 1v1 入口），不做群内自动回复。', icon: 'users' },
    ],
  },
  desk: {
    key: 'desk',
    path: '/desk',
    navLabel: '坐席工作台',
    icon: 'headset',
    group: 'core',
    roles: ['agent', 'supervisor', 'admin'],
    eyebrow: '05 · 员工端',
    title: '坐席工作台',
    description: '三队列、上下文同屏、分级与 SLA、质检；转人工不断线。',
    w2Focus: ['三队列与分级/SLA', '会话上下文与 ERP 数据同屏', '质检与转人工摘要'],
    panels: [
      { title: '三队列', note: '待接入 / 进行中 / 待质检，W2 填充队列与筛选。', icon: 'headset' },
      { title: '同屏上下文', note: 'AI 摘要、要素、情绪、建议与 ERP 数据同屏，W2 填充。', icon: 'idcard' },
      { title: 'SLA 与质检', note: '首响/解决时长、质检抽检与回溯入口，W2 填充。', icon: 'clock' },
    ],
  },
  billing: {
    key: 'billing',
    path: '/billing',
    navLabel: '费用与催缴',
    icon: 'wallet',
    group: 'core',
    roles: ['steward', 'supervisor', 'admin'],
    eyebrow: '06 · 费用',
    title: '费用与催缴',
    description: '欠费台账、分层触达、AI 话术、渠道红线与 7 日转化漏斗。',
    w2Focus: ['欠费台账与逾期分层', 'AI 催缴话术与渠道红线', '7 日转化漏斗与导出'],
    panels: [
      { title: '欠费台账', note: '按楼栋/逾期分层筛选，行选中联动右侧详情，W2 填充。', icon: 'wallet' },
      { title: '话术与红线', note: '催缴话术、时间/频次红线、不碰钱边界提示，W2 填充。', icon: 'shield' },
      { title: '转化漏斗', note: '触达 → 响应 → 承诺 → 缴费 7 日漏斗，W2 填充。', icon: 'chart' },
    ],
  },
  erp: {
    key: 'erp',
    path: '/erp',
    navLabel: 'ERP 适配中心',
    icon: 'plug',
    group: 'platform',
    roles: ['it', 'admin'],
    badge: '5 套',
    eyebrow: '07 · 适配层',
    title: 'ERP 适配中心',
    description: '5 套 ERP、18 项标准能力矩阵、能力探测、降级策略与灰度；新增连接器 4 步向导入口。',
    w2Focus: ['5 套 ERP 连接器卡片', '18 项能力矩阵与探测结果', '降级策略与灰度对比'],
    panels: [
      { title: '连接器卡片', note: '用友 / 金蝶 / 明源 / 自研 / 新视窗，W2 填充卡片与状态。', icon: 'plug' },
      { title: '能力矩阵', note: '18 项能力 × 连接器矩阵，由探测生成，W2 填充。', icon: 'mapping' },
      { title: '新增连接器入口', note: '4 步向导（接入方式→连通→探测→映射灰度）W3 实现。', icon: 'plus' },
    ],
  },
  mapping: {
    key: 'mapping',
    path: '/mapping',
    navLabel: '字段与状态映射',
    icon: 'mapping',
    group: 'platform',
    roles: ['it', 'admin'],
    eyebrow: '08 · 适配层',
    title: '字段与状态映射',
    description: '字段翻译、状态与字典映射、200 条试跑、版本发布与回滚。',
    w2Focus: ['字段 / 状态 / 字典映射表', '200 条试跑与差异校验', '版本发布与回滚'],
    panels: [
      { title: '映射表', note: '字段 Tab、状态 Tab、字典 Tab，W2 填充映射表。', icon: 'mapping' },
      { title: '试跑校验', note: '200 条样本试跑结果与差异说明，W2 填充。', icon: 'list-check' },
      { title: '版本与回滚', note: '版本发布记录与一键回滚，W2/W3 填充。', icon: 'refresh' },
    ],
  },
  orchestration: {
    key: 'orchestration',
    path: '/orchestration',
    navLabel: '能力编排',
    icon: 'workflow',
    group: 'platform',
    roles: ['it', 'admin'],
    eyebrow: '09 · 运行时',
    title: '能力编排 · 运行时',
    description: '一次请求的 7 跳链路、项目 → ERP 路由表、规则与降级台账。',
    w2Focus: ['7 跳链路图与耗时', '项目 → 连接器路由表', '降级规则与台账'],
    panels: [
      { title: '7 跳链路', note: '意图 → 身份 → 能力 → 适配 → 回写 → 校验 → 回复，W2 填充链路图。', icon: 'workflow' },
      { title: '路由表', note: '按 projectId 路由到连接器，支持灰度并行，W2 填充。', icon: 'mapping' },
      { title: '降级台账', note: '缺能力时的降级路径与命中记录，W2 填充。', icon: 'shield' },
    ],
  },
  trace: {
    key: 'trace',
    path: '/trace',
    navLabel: '全链路回溯',
    icon: 'activity',
    group: 'platform',
    roles: ['supervisor', 'it', 'admin'],
    eyebrow: '10 · 可追溯',
    title: '全链路回溯',
    description: '会话回放、接口调用瀑布、知识引用与版本、护栏判断、trace id 与证据包。',
    w2Focus: ['会话回放与调用瀑布', '知识引用（ID/版本/生效期/责任人）', '护栏判断与证据包导出'],
    panels: [
      { title: '会话回放', note: '按 trace id 回放会话与调用时间线，W2 填充。', icon: 'activity' },
      { title: '调用瀑布', note: '接口请求/返回、耗时、脱敏字段，W2 填充。', icon: 'workflow' },
      { title: '证据与护栏', note: '知识引用、护栏判断、证据包导出入口，W2/W3 填充。', icon: 'shield' },
    ],
  },
  knowledge: {
    key: 'knowledge',
    path: '/knowledge',
    navLabel: '知识库运营',
    icon: 'book',
    group: 'platform',
    roles: ['steward', 'supervisor', 'it', 'admin'],
    eyebrow: '11 · 知识',
    title: '知识库运营',
    description: '知识清单、版本与生效期、责任人、过期降级、缺口与 badcase、变更卡点。',
    w2Focus: ['知识清单与分类筛选', '版本/生效期/责任人/过期降级', '缺口、badcase 与变更卡点'],
    panels: [
      { title: '知识清单', note: '分类筛选、版本、状态、责任人，W2 填充表格。', icon: 'book' },
      { title: '缺口与 badcase', note: '答不上/答错的问题池与采纳状态，W2 填充。', icon: 'info' },
      { title: '变更与卡点', note: '制度变更单与审核卡点，W2 填充。', icon: 'edit' },
    ],
  },
  dashboard: {
    key: 'dashboard',
    path: '/dashboard',
    navLabel: '运营看板',
    icon: 'chart',
    group: 'platform',
    roles: ['supervisor', 'it', 'admin'],
    eyebrow: '12 · 看板',
    title: '运营看板',
    description: '自助率、工单质量、缴费转化、收缴率与 ROI；按业务指标验收，不看对话量。',
    w2Focus: ['指标卡（口径参照附录 C）', '趋势与漏斗', '周报导出'],
    panels: [
      { title: '指标卡', note: '自助率 / 工单质量 / 缴费转化 / 收缴率 / ROI，W2 填充。', icon: 'chart' },
      { title: '趋势与漏斗', note: '周/月趋势、转化漏斗，W2 填充。', icon: 'activity' },
      { title: '导出', note: '周报 CSV 导出（W3 动作弹层统一接）。', icon: 'download' },
    ],
  },
}

/** 导航项由 SCREENS 派生，保证"一屏一条" */
export const NAV_ITEMS: NavItem[] = SCREEN_ORDER.map((key) => {
  const screen = SCREENS[key]
  return {
    key: screen.key,
    path: screen.path,
    label: screen.navLabel,
    icon: screen.icon,
    group: screen.group,
    roles: screen.roles,
    badge: screen.badge,
  }
})
