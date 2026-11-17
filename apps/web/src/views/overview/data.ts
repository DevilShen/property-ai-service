import type { IconName } from '@/types'

/** 01 方案总览 · 演示数据（来源：原型 + _design/00-项目基线.md，标注“演示数据”） */

export const HERO_STATS = [
  { value: '18', label: '标准能力' },
  { value: '5', label: '已接 ERP' },
  { value: '6.5 天', label: '平均适配周期' },
  { value: '0', label: '业务数据落库' },
]

export interface ArchCell {
  label: string
  icon: IconName
}

export interface ArchLayer {
  key: string
  label: string
  caption: string
  tone: 'plain' | 'ai' | 'hot' | 'data' | 'erp'
  cells: ArchCell[]
  detail: string
}

/** 六层架构：触点 → 交互 → AI 能力 → 适配解耦 → 数据与知识 → ERP 业务系统 */
export const ARCH_LAYERS: ArchLayer[] = [
  {
    key: 'touch',
    label: '触点层',
    caption: '多渠道统一入口',
    tone: 'plain',
    cells: [
      { label: '微信服务号', icon: 'chat' },
      { label: '小程序 / H5', icon: 'overview' },
      { label: '企业微信', icon: 'users' },
      { label: '400 电话', icon: 'headset' },
      { label: '企微客户群', icon: 'users' },
    ],
    detail: '五个入口共用一套会话与身份体系；群聊只做存档摘要与人工确认群发，不做群机器人。',
  },
  {
    key: 'dialog',
    label: '交互与会话层',
    caption: '多轮追问 · 转人工 · 语音',
    tone: 'plain',
    cells: [
      { label: '会话编排', icon: 'workflow' },
      { label: '5 问结构化', icon: 'chat' },
      { label: '情绪识别', icon: 'activity' },
      { label: '转人工交接', icon: 'headset' },
      { label: '语音 / 大字兜底', icon: 'idcard' },
    ],
    detail: '一次报修由 5 个必答要素收敛成工单字段；超置信度阈值或命中红线时转人工，带摘要一起交接。',
  },
  {
    key: 'ai',
    label: 'AI 能力层',
    caption: '一套大脑，先对内再对外',
    tone: 'ai',
    cells: [
      { label: '对话编排', icon: 'workflow' },
      { label: '项目知识库', icon: 'book' },
      { label: '查 / 办 / 催 工具', icon: 'sparkles' },
      { label: '安全护栏', icon: 'shield' },
      { label: '运营分析', icon: 'chart' },
    ],
    detail: 'AI 不直连 ERP、不存业务数据；答案必须带知识 ID、版本、生效期与责任人。',
  },
  {
    key: 'adapter',
    label: '适配解耦层',
    caption: '解耦核心：AI 只认 18 项能力',
    tone: 'hot',
    cells: [
      { label: '标准能力契约 18 项', icon: 'mapping' },
      { label: '连接器运行时', icon: 'plug' },
      { label: '字段 / 状态映射', icon: 'mapping' },
      { label: '能力探测', icon: 'search' },
      { label: '灰度路由', icon: 'workflow' },
    ],
    detail: '换 ERP 只换连接器：用友 / 金蝶 / 明源 / 自研 / 新视窗的差异全部收敛在连接器与映射配置内。',
  },
  {
    key: 'data',
    label: '数据与知识层',
    caption: '知识、存档、审计，不存业务主数据',
    tone: 'data',
    cells: [
      { label: '项目知识库（版本/生效期）', icon: 'book' },
      { label: '会话存档', icon: 'lock' },
      { label: '调用链审计', icon: 'activity' },
      { label: '指标口径', icon: 'chart' },
      { label: 'badcase 池', icon: 'info' },
    ],
    detail: '知识按项目隔离、按版本生效与过期降级；每次回答可回溯知识版本与接口调用（trace）。',
  },
  {
    key: 'erp',
    label: 'ERP 业务系统层',
    caption: '业务唯一真相源',
    tone: 'erp',
    cells: [
      { label: '用友 NC Cloud', icon: 'plug' },
      { label: '金蝶 EAS', icon: 'workflow' },
      { label: '明源云', icon: 'mapping' },
      { label: '自研 ERP', icon: 'overview' },
      { label: '新视窗', icon: 'refresh' },
    ],
    detail: '房档、账单、工单、投诉、通知的全部业务逻辑仍在 ERP；AI 侧数字均为实时读取。',
  },
]

export type CapabilityGroup = 'read' | 'operate' | 'notify'

export interface Capability {
  code: string
  name: string
  level: 'L1' | 'L2'
  group: CapabilityGroup
}

/** 18 项标准能力（Canonical API v1.4，与 _design/00-项目基线.md §4 一致） */
export const CAPABILITIES: Capability[] = [
  { code: 'room.query', name: '房档查询', level: 'L1', group: 'read' },
  { code: 'owner.verify', name: '业主身份核验', level: 'L1', group: 'read' },
  { code: 'bill.query', name: '账单查询', level: 'L1', group: 'read' },
  { code: 'ticket.status', name: '工单进度', level: 'L1', group: 'read' },
  { code: 'notice.receipt', name: '通知回执', level: 'L1', group: 'read' },
  { code: 'kb.search', name: '知识检索', level: 'L1', group: 'read' },
  { code: 'pay.jump', name: '缴费跳转', level: 'L2', group: 'operate' },
  { code: 'invoice.apply', name: '开票申请', level: 'L2', group: 'operate' },
  { code: 'ticket.create', name: '工单创建', level: 'L2', group: 'operate' },
  { code: 'ticket.assign', name: '工单派单 / 改派', level: 'L2', group: 'operate' },
  { code: 'ticket.close', name: '完工回写', level: 'L2', group: 'operate' },
  { code: 'complaint.create', name: '投诉单创建', level: 'L2', group: 'operate' },
  { code: 'visitor.auth', name: '访客授权', level: 'L2', group: 'operate' },
  { code: 'parking.auth', name: '停车授权', level: 'L2', group: 'operate' },
  { code: 'dunning.task', name: '催缴任务', level: 'L2', group: 'notify' },
  { code: 'notice.push', name: '通知推送', level: 'L2', group: 'notify' },
  { code: 'visit.record', name: '回访记录', level: 'L2', group: 'notify' },
  { code: 'handoff.human', name: '转人工', level: 'L2', group: 'notify' },
]

export const CAP_GROUPS: Array<{ key: 'all' | CapabilityGroup; label: string; badge?: string }> = [
  { key: 'all', label: '全部能力', badge: '18' },
  { key: 'read', label: '只读查询 L1', badge: '6' },
  { key: 'operate', label: '业务办理 L2', badge: '9' },
  { key: 'notify', label: '触达与协同', badge: '3' },
]

export interface ErpConnector {
  key: string
  name: string
  access: string
  coverage: string
  coveragePct: number
  state: '生产' | '灰度 30%' | '联调'
  tone: 'g' | 'a' | 'n'
  degraded: string[]
}

/** 5 套 ERP（覆盖数与状态与基线 §5 一致） */
export const ERP_CONNECTORS: ErpConnector[] = [
  {
    key: 'yonyou',
    name: '用友 NC Cloud',
    access: 'REST API · V6.5',
    coverage: '18/18',
    coveragePct: 100,
    state: '生产',
    tone: 'g',
    degraded: ['无降级项：P0 八项能力全部可用'],
  },
  {
    key: 'kingdee',
    name: '金蝶 EAS',
    access: 'ESB 中间件 · V8.2',
    coverage: '16/18',
    coveragePct: 89,
    state: '生产',
    tone: 'g',
    degraded: ['invoice.apply 降级：跳转 ERP 自助开票页', 'visit.record 降级：导出 CSV，次日人工回填'],
  },
  {
    key: 'mingyuan',
    name: '明源云',
    access: 'REST API · 天际 3.0',
    coverage: '17/18',
    coveragePct: 94,
    state: '灰度 30%',
    tone: 'a',
    degraded: ['complaint.create 不支持：落 AI 侧投诉池，人工补录到明源'],
  },
  {
    key: 'self',
    name: '自研 ERP',
    access: '只读视图 + API',
    coverage: '15/18',
    coveragePct: 83,
    state: '生产',
    tone: 'g',
    degraded: [
      'invoice.apply 降级：跳转 ERP 自助开票页',
      'notice.push 降级：改用企微群发 + 短信',
      'visit.record 降级：导出 CSV，次日人工回填',
    ],
  },
  {
    key: 'xinshichuang',
    name: '新视窗',
    access: '文件交换 / RPA',
    coverage: '9/18',
    coveragePct: 50,
    state: '联调',
    tone: 'n',
    degraded: ['仅只读：建单 / 开票 / 推送类写能力暂不支持，界面入口自动隐藏（不做“假支持”）'],
  },
]

export interface LoopCard {
  no: number
  title: string
  desc: string
  chain: string[]
  ai: string
  human: string
}

export const LOOPS: LoopCard[] = [
  {
    no: 1,
    title: '报修闭环',
    desc: 'AI 结构化受理，ERP 建单派单，三次节点自动推送，完工回访。',
    chain: ['受理', '派单', '接单', '出发', '到场', '完工', '回访', '沉淀'],
    ai: '5 问结构化、自动建单、接单/出发/完工三次推送、回访发起',
    human: '上门维修、现场验收、处理结论（AI 不代写定责）',
  },
  {
    no: 2,
    title: '投诉闭环',
    desc: 'AI 只做首响、情绪安抚、要素采集与分级，处理与谈判由人完成。',
    chain: ['首响', '分级', '转办', '时限跟踪', '处理', '回访', '复盘'],
    ai: '首响、情绪安抚、要素采集、分级建议、时限提醒',
    human: '谈判、责任认定、赔付方案、复盘结论',
  },
  {
    no: 3,
    title: '催缴闭环',
    desc: '账单可视、分层触达、缴费直达；AI 不碰减免与承诺，人工兜底。',
    chain: ['账单可视', '分层触达', '缴费', '对账', '困难帮扶'],
    ai: '账单解释、分层触达话术、缴费跳转、回执统计',
    human: '减免审批、滞纳金处理、困难帮扶、争议对账',
  },
]

export interface MethodCard {
  icon: IconName
  title: string
  tone: 'g' | 'b' | 'a'
  tag: string
  desc: string
  meta: string
}

export const METHODS: MethodCard[] = [
  {
    icon: 'plug',
    title: '标准 API 对接',
    tone: 'g',
    tag: '推荐',
    desc: '用友、金蝶、明源等已有开放接口的 ERP，按标准契约逐项映射，查询类 3 天可通。',
    meta: '适配周期 5—10 天 · 实时性最高',
  },
  {
    icon: 'book',
    title: '只读数据库视图',
    tone: 'b',
    tag: '常用',
    desc: '接口不开放或改造成本高时，以只读视图 + 增量同步接入，先跑查询与进度类能力。',
    meta: '适配周期 3—5 天 · 不写生产库',
  },
  {
    icon: 'refresh',
    title: '中间件 / 文件 / RPA',
    tone: 'a',
    tag: '兜底',
    desc: '老系统、私有协议走 ESB、定时文件或 RPA；能力降级但业务不断，缺口自动隐藏入口。',
    meta: '适配周期 1—3 周 · 允许降级',
  },
]

export const ROADMAP = [
  { phase: 'P0 · 0—2 周', title: '准备与数据体检', desc: '房号唯一性、手机号、收费标准版本' },
  { phase: 'P1 · 2—6 周', title: '内部 Copilot 上线', desc: '管家先用，零对客风险，沉淀知识' },
  { phase: 'P2 · 6—10 周', title: '对客灰度 10% → 30%', desc: '报修、进度、规则问答，零严重事故' },
  { phase: 'P3 · 10—16 周', title: '三条闭环扩面', desc: '费用、催缴、投诉首响，放开至 100%' },
  { phase: 'P4 · 4—12 月', title: '多项目复制', desc: '连接器复用，单项目边际成本降 30%' },
]

export const METRICS = [
  { name: '自助解决率', target: '≥ 40%', note: '上线 6 个月口径' },
  { name: '问答准确率', target: '≥ 95%', note: '人工抽检' },
  { name: '工单字段完整率', target: '≥ 98%', note: '结构化受理' },
  { name: '一次解决率', target: '≥ 75%', note: '7 日内无重复报修' },
  { name: '首次响应', target: '≤ 1 分钟', note: '对客会话' },
  { name: '7 日缴费转化', target: '≥ 15%', note: '催缴触达后' },
  { name: '满意度', target: '≥ 4.3 / 5', note: '回访评分' },
  { name: '错误承诺事件', target: '0', note: '一票否决' },
  { name: '收缴率', target: '提升 1—3 pt', note: '同比口径' },
]

export const PRINCIPLES = [
  { label: '边界', text: 'ERP 是唯一数据源，AI 只做对话层 + 调度层' },
  { label: '解耦', text: '换 ERP 只换连接器，AI 侧不改' },
  { label: '能力', text: '只读 → 可写 → 可发起流程，逐级放开' },
  { label: '安全', text: '进模型前脱敏，三级权限 + 硬拦截' },
  { label: '可复制', text: '项目级知识隔离，一套平台管多项目' },
  { label: '可审计', text: '每条回答可回溯知识版本与接口调用' },
]

/** 汇报要点：Modal 预览 + Markdown 下载（演示） */
export const REPORT_MARKDOWN = [
  '# 物业 AI 智能客服 · 汇报要点（演示数据）',
  '',
  '## 一句话',
  'AI 能力层与物业 ERP 解耦：一次建设、多系统复用；换 ERP 只换连接器。',
  '',
  '## 六层架构',
  ...ARCH_LAYERS.map((layer) => `- ${layer.label}：${layer.caption}`),
  '',
  '## 18 项标准能力',
  ...CAPABILITIES.map((cap) => `- ${cap.code} · ${cap.name} · ${cap.level}`),
  '',
  '## 5 套 ERP 连接器',
  ...ERP_CONNECTORS.map((erp) => `- ${erp.name} · ${erp.access} · ${erp.coverage} · ${erp.state}`),
  '',
  '## 三条闭环',
  ...LOOPS.map((loop) => `- ${loop.title}：${loop.chain.join(' → ')}`),
  '',
  '## 90 天路线',
  ...ROADMAP.map((step) => `- ${step.phase} ${step.title}：${step.desc}`),
  '',
  '## 关键指标（验收口径）',
  ...METRICS.map((metric) => `- ${metric.name}：${metric.target}（${metric.note}）`),
].join('\n')

export function downloadText(filename: string, content: string): void {
  const blob = new Blob([content], { type: 'text/markdown;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  URL.revokeObjectURL(url)
}
