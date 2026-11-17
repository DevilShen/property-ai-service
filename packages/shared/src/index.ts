/**
 * @property/shared —— 物业 AI 智能客服平台 · 前后端共享契约
 *
 * 唯一事实源：`docs/openapi/openapi.yaml`（**P2 v0.3.3 = W8 向后兼容增量（登录/安全方案/审计）**；
 * v0.3.0 已于 P2 W5 冻结、v0.3.1 = W6 增量、v0.3.2 = W7 增量）。
 * 辅助口径：`_design/02-架构设计.md`（契约通则 §3.1 / 能力清单 §3.2 / 统一错误码 §3.4）、
 * `_design/01-产品设计.md`（5.1 报修闭环、5.2 账单解释）、`_mvp/01-MVP需求.md`。
 *
 * 约定：
 * - 字段小驼峰；枚举用标准字典编码（英文 code），中文文案放 `*_LABELS`，前端不得硬编码中文枚举。
 * - 金额单位「元」，定点两位，币种默认 CNY；AI 侧不自算金额，以 ERP/台账返回为准。
 * - 时间为 ISO 8601 字符串，业务时区 Asia/Shanghai（`YYYY-MM-DDTHH:mm:ss+08:00` 或 UTC `Z`）。
 * - §1—§8 为 P1 领域类型（12 屏 mock 口径）：`Legacy*` 前缀者为 P2 已迁移的旧形态，仅供 P1 页面过渡，
 *   新代码一律使用 §11 的 P2 契约类型（`Ticket` / `TicketStatus` / `BillItem` 等规范名归 P2）。
 * - §9 统一信封：成功 `{ok,data,traceId,degraded?}`，失败 `{ok:false,error:<17 类 ErrorCode>,message?,...}`。
 * - §11 为 P2 契约（owner/room/bill/ticket/recon/handoff），与 Java DTO `bff/dto/*` 一一对应。
 */

/* ============================================================================
 * 1. 基础标量
 * ========================================================================== */

/** 金额（元），定点两位。例：408 表示 ¥408.00 */
export type Money = number;
/** 币种，平台默认人民币 */
export type CurrencyCode = 'CNY';
/** 日期时间，ISO 8601 字符串（业务时区 Asia/Shanghai） */
export type IsoDateTime = string;
/** 日期，`YYYY-MM-DD` */
export type IsoDate = string;
/** 账期，`YYYY-MM` */
export type Period = string;

/* ============================================================================
 * 2. 角色（Role）
 * ========================================================================== */

/**
 * 平台角色。
 * - owner 业主（C 端，仅本户数据）
 * - steward 管家（B 端，本片区）
 * - agent 坐席（B 端，本项目会话）
 * - supervisor 主管（B 端，本项目/跨项目只读，含项目经理、知识运营、质检口径）
 * - it IT/集成（适配中心、映射、编排）
 * - admin 系统管理员（平台管理、权限、审计）
 */
export type Role = 'owner' | 'steward' | 'agent' | 'supervisor' | 'it' | 'admin';

export const ROLES: readonly Role[] = ['owner', 'steward', 'agent', 'supervisor', 'it', 'admin'];

export const ROLE_LABELS: Readonly<Record<Role, string>> = {
  owner: '业主',
  steward: '管家',
  agent: '坐席',
  supervisor: '主管',
  it: 'IT/集成',
  admin: '系统管理员',
};

/* ============================================================================
 * 3. 12 屏（ScreenKey）
 * ========================================================================== */

/** 导航分组：业主服务 / 员工工作台 / 适配与集成 / 运营与治理 / 平台管理 */
export type NavGroup = 'owner-service' | 'workbench' | 'integration' | 'governance' | 'platform';

/**
 * 12 屏原型对应的路由 key（与 `apps/web/src/views/<key>` 目录一一对应）。
 * 13 新增连接器向导、14 平台管理为原型外页面，不进 ScreenKey（P1 由 07 页内入口承载）。
 */
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
  | 'dashboard';

export interface ScreenMeta {
  key: ScreenKey;
  /** 原型屏号 01—12 */
  no: number;
  title: string;
  /** 路由路径（前端 router 以此为默认值） */
  path: string;
  navGroup: NavGroup;
  /** 可见角色（P1 仅占位，真实权限模型见 P1-027 / FR-ADM-02） */
  roles: readonly Role[];
}

export const SCREENS: readonly ScreenMeta[] = [
  {
    key: 'overview',
    no: 1,
    title: '方案总览',
    path: '/overview',
    navGroup: 'governance',
    roles: ['steward', 'agent', 'supervisor', 'it', 'admin'],
  },
  {
    key: 'owner',
    no: 2,
    title: '业主端 AI 客服',
    path: '/owner',
    navGroup: 'owner-service',
    roles: ['owner', 'steward'],
  },
  {
    key: 'progress',
    no: 3,
    title: '工单进度闭环',
    path: '/progress',
    navGroup: 'owner-service',
    roles: ['owner', 'steward', 'supervisor'],
  },
  {
    key: 'copilot',
    no: 4,
    title: '管家 Copilot',
    path: '/copilot',
    navGroup: 'workbench',
    roles: ['steward', 'agent', 'supervisor'],
  },
  {
    key: 'desk',
    no: 5,
    title: '坐席工作台',
    path: '/desk',
    navGroup: 'workbench',
    roles: ['agent', 'supervisor'],
  },
  {
    key: 'billing',
    no: 6,
    title: '费用与催缴',
    path: '/billing',
    navGroup: 'workbench',
    roles: ['steward', 'supervisor'],
  },
  {
    key: 'erp',
    no: 7,
    title: 'ERP 适配中心',
    path: '/erp',
    navGroup: 'integration',
    roles: ['it', 'supervisor'],
  },
  {
    key: 'mapping',
    no: 8,
    title: '字段与状态映射',
    path: '/mapping',
    navGroup: 'integration',
    roles: ['it', 'steward'],
  },
  {
    key: 'orchestration',
    no: 9,
    title: '能力编排运行时',
    path: '/orchestration',
    navGroup: 'integration',
    roles: ['it'],
  },
  {
    key: 'trace',
    no: 10,
    title: '全链路回溯',
    path: '/trace',
    navGroup: 'governance',
    roles: ['supervisor', 'it'],
  },
  {
    key: 'knowledge',
    no: 11,
    title: '知识库运营',
    path: '/knowledge',
    navGroup: 'governance',
    roles: ['steward', 'supervisor'],
  },
  {
    key: 'dashboard',
    no: 12,
    title: '运营看板',
    path: '/dashboard',
    navGroup: 'governance',
    roles: ['supervisor', 'admin'],
  },
];

export const SCREEN_KEYS: readonly ScreenKey[] = SCREENS.map((screen) => screen.key);

export const SCREEN_BY_KEY: Readonly<Record<ScreenKey, ScreenMeta>> = SCREENS.reduce(
  (acc, screen) => {
    acc[screen.key] = screen;
    return acc;
  },
  {} as Record<ScreenKey, ScreenMeta>,
);

/* ============================================================================
 * 4. 管家 Copilot 8 个工作区（CopilotWorkspace）
 * ========================================================================== */

/**
 * 8 个工作区（同级切换，不嵌套二级菜单；对齐 FR-COP-01—09）。
 * FR-COP-08「采纳与减负度量」是口径，不是独立工作区。
 */
export type CopilotWorkspace =
  | 'knowledge'
  | 'script'
  | 'summary'
  | 'profile'
  | 'report'
  | 'contribute'
  | 'history'
  | 'group';

export interface CopilotWorkspaceMeta {
  key: CopilotWorkspace;
  /** 需求编号（_design/01-产品设计.md §7.2） */
  fr: string;
  title: string;
  /** 一句话职责，供工作区空态/说明文案复用 */
  purpose: string;
  /** 路由路径 */
  path: string;
}

export const COPILOT_WORKSPACES: readonly CopilotWorkspaceMeta[] = [
  {
    key: 'knowledge',
    fr: 'FR-COP-01',
    title: '知识秒查',
    purpose: '输入问题返回答案 + 出处四要素 + 相关条款原文',
    path: '/copilot/knowledge',
  },
  {
    key: 'script',
    fr: 'FR-COP-02',
    title: '话术生成',
    purpose: '场景 × 情绪 × 渠道，生成温和/标准/坚定 3 版话术并过禁区扫描',
    path: '/copilot/script',
  },
  {
    key: 'summary',
    fr: 'FR-COP-03',
    title: '工单摘要',
    purpose: '群消息或通话转写压成 5 行摘要 + 3 条待办',
    path: '/copilot/summary',
  },
  {
    key: 'profile',
    fr: 'FR-COP-04',
    title: '业主画像',
    purpose: '基础档案、服务历史、缴费与合同、风险与沟通禁忌',
    path: '/copilot/profile',
  },
  {
    key: 'report',
    fr: 'FR-COP-05',
    title: '日报周报',
    purpose: '汇总当日/当周诉求量、工单、投诉、欠费变动与异常',
    path: '/copilot/report',
  },
  {
    key: 'contribute',
    fr: 'FR-COP-06',
    title: '知识共建',
    purpose: '提交 AI 答错/答不上的问题、正确答案与来源，跟踪采纳状态',
    path: '/copilot/contribute',
  },
  {
    key: 'history',
    fr: 'FR-COP-07',
    title: '历史会话',
    purpose: '按业主、意图、结果筛选近 7 天会话，可跳全链路回溯',
    path: '/copilot/history',
  },
  {
    key: 'group',
    fr: 'FR-COP-09',
    title: '群服务',
    purpose: '企微客户群存档摘要/草稿 + 个人群入口（B+C，群内不自动回复）',
    path: '/copilot/group',
  },
];

export const COPILOT_WORKSPACE_KEYS: readonly CopilotWorkspace[] = COPILOT_WORKSPACES.map(
  (workspace) => workspace.key,
);

/* ============================================================================
 * 5. 房档（Household）
 * ========================================================================== */

/** 绑定状态：未绑定 / 已绑定 / 核验中 */
export type BindState = 'unbound' | 'bound' | 'pending';

/** 账户状态（P1 mock/data 扩展字段，P2 契约纳入 `RoomInfo`） */
export type AccountStatus = 'normal' | 'arrears' | 'prepaid' | 'unverified';

/** 关系人类型：产权人 / 家属 / 租户 / 其他 */
export type RelationType = 'owner' | 'family' | 'tenant' | 'other';

export interface HouseholdRelation {
  relationType: RelationType;
  /** 掩码姓名，如「王**」 */
  nameMasked: string;
  /** 掩码手机号，如「138****6688」；仅测试数据，不进 AI 上下文 */
  phoneMasked?: string;
  isPrimary?: boolean;
}

/** 房档（字段对齐 _mvp/02-技术方案.md §3 mock rooms + _dev/plan/tasks.json P1-007） */
export interface Household {
  householdId: string;
  projectId: string;
  /** 归一房号，如「3-1-801」 */
  roomNo: string;
  building: string;
  unit: string;
  room: string;
  /** 建筑面积（㎡） */
  areaM2: number;
  /** 户型，如「3室2厅」 */
  layout?: string;
  ownerId: string;
  /** 业主掩码姓名（153 规则：AI 只给掩码） */
  ownerNameMasked: string;
  /** 手机后四位（测试环境绑定因子，仅存后四位） */
  phoneTail4: string;
  bindState: BindState;
  relations: HouseholdRelation[];
  /** 欠费合计（元，两位小数）；无欠费为 0 */
  arrearsAmount?: Money;
  /** 风险/偏好标签，如「老年业主」「已起诉」 */
  tags?: string[];
  /** 车位号；null = 无车位（账单不含车位费）。P1 mock/data 扩展字段，P2 纳入 `RoomInfo` */
  parkingNo?: string | null;
  /** 账户状态（正常/欠费/预缴/待核验）。P1 mock/data 扩展字段，P2 纳入 `RoomInfo` */
  accountStatus?: AccountStatus;
  /** 逾期天数（0 = 未逾期）。P1 mock/data 扩展字段，P2 纳入 `RoomInfo` */
  overdueDays?: number;
  updatedAt: IsoDateTime;
}

/* ============================================================================
 * 6. 账单（Bill）
 * ========================================================================== */

/** 账单状态：未缴 / 部分缴 / 已缴清 / 逾期 / 作废 */
export type BillStatus = 'unpaid' | 'partial' | 'paid' | 'overdue' | 'void';

/** 数据来源：真实 ERP / mock / 缓存降级 */
export type BillSource = 'erp' | 'mock' | 'cache';

/**
 * 账单费项（P1 领域口径：itemCode/name）。
 * @deprecated P2 契约费项使用 §11 `BillItem`（label/amount/note 展示口径）；本类型仅供 P1 页面过渡。
 */
export interface LegacyBillItem {
  /** 标准费项编码，如「PROPERTY_FEE」 */
  itemCode: string;
  /** 费项名称，如「物业费」 */
  name: string;
  amount: Money;
  quantity?: number;
  unitPrice?: Money;
  /** 说明本次计费口径的知识条目，如「K-002」 */
  knowledgeId?: string;
  note?: string;
}

/** 账单（对齐 _mvp/02-技术方案.md §2 C1 与 _design/02-架构设计.md CAP-03） */
export interface Bill {
  billNo: string;
  projectId: string;
  householdId: string;
  roomNo: string;
  /** 账期 `YYYY-MM`，如「2026-11」 */
  period: Period;
  /** 本期应缴合计（= items 金额之和，由 ERP 保证） */
  total: Money;
  /** 上期应缴合计，用于差额解释 */
  prevTotal?: Money;
  /** 与上期差额（= total - prevTotal），由 ERP 给出，AI 不重算 */
  diff?: Money;
  /** 差额原因文案（引用知识条目，如「公摊电费改为按月分摊」） */
  diffReason?: string;
  items: LegacyBillItem[];
  /** 实缴金额 */
  paid?: Money;
  /** 欠费金额（含历史欠费口径以 ERP 为准） */
  outstanding?: Money;
  /** 滞纳金 */
  lateFee?: Money;
  currency: CurrencyCode;
  status: BillStatus;
  /** 缴费截止日 `YYYY-MM-DD` */
  dueDate?: IsoDate;
  source: BillSource;
  /** 数据时点，降级/缓存时前端需标注「数据时间」 */
  dataAsOf: IsoDateTime;
}

/* ============================================================================
 * 7. 工单（Ticket）
 * ========================================================================== */

/** 报修分类（标准字典编码；中文文案见 TICKET_CATEGORY_LABELS） */
export type TicketCategory =
  | 'plumbing'
  | 'electrical'
  | 'elevator'
  | 'door_window'
  | 'public_facility'
  | 'other';

export const TICKET_CATEGORY_LABELS: Readonly<Record<TicketCategory, string>> = {
  plumbing: '水暖/给排水',
  electrical: '电气/照明',
  elevator: '电梯',
  door_window: '门窗/入户',
  public_facility: '公共设施',
  other: '其他',
};

/** 紧急程度：一般 / 紧急 / 应急（漏到楼下、燃气、电梯困人、停电、消防隐患为应急） */
export type TicketUrgency = 'normal' | 'urgent' | 'emergency';

/** 现象影响范围（是否漏到楼下等，独立字段便于班组分级） */
export type IssueScope = 'none' | 'downstairs' | 'unknown';

/**
 * 工单节点编码（P1 小写口径）：覆盖产品 7 节点时间轴 + 已闭环时间轴细分。
 * MVP（V6）只展示 4 节点子集：accepted → assigned → onsite → completed。
 * @deprecated P2 契约状态机使用 §11 `TicketStatus`（大写 7 节点 CREATED→…→ARCHIVED）。
 */
export type TicketNodeCode =
  | 'accepted'
  | 'assigned'
  | 'engineer_accepted'
  | 'departed'
  | 'onsite'
  | 'completed'
  | 'revisit'
  | 'closed';

/**
 * 工单状态 = 节点状态 + 已取消（P1 小写口径）。
 * @deprecated P2 契约状态机使用 §11 `TicketStatus`。
 */
export type LegacyTicketStatus = TicketNodeCode | 'cancelled';

/** @deprecated P1 小写状态文案；P2 用 §11 `TICKET_STATUS_LABELS`。 */
export const LEGACY_TICKET_STATUS_LABELS: Readonly<Record<LegacyTicketStatus, string>> = {
  accepted: '已受理',
  assigned: '已派单',
  engineer_accepted: '已接单',
  departed: '已出发',
  onsite: '处理中',
  completed: '已完工',
  revisit: '待回访',
  closed: '已闭环',
  cancelled: '已取消',
};

export interface TicketNode {
  code: TicketNodeCode;
  /** 节点中文名（服务端给出，前端不硬编码） */
  name: string;
  /** 节点发生时间；未发生则为空 */
  at?: IsoDateTime;
  /** 处理人（掩码姓名或班组） */
  who?: string;
  /** 业主可见文案，如「水电二班 · 预计今天 17:00 前上门」 */
  ownerText?: string;
}

/** 报修人类型 */
export type ReporterType = 'owner' | 'family' | 'tenant' | 'steward';

/**
 * 工单（P1 领域口径，对齐 _mvp/02-技术方案.md §2 C2/C3 与 CAP-06/07/08/09）。
 * @deprecated P2 契约使用 §11 `Ticket`（大写 7 节点 + erp 引用 + version 乐观锁）。
 */
export interface LegacyTicket {
  /** ERP 工单号，如「WO-20261114-0031」；降级待人工建单时为临时号「TMP-0031」 */
  ticketNo: string;
  projectId: string;
  householdId: string;
  roomNo: string;
  category: TicketCategory;
  /** 现象描述（业主原话结构化后） */
  symptom: string;
  issueScope: IssueScope;
  urgency: TicketUrgency;
  /** 预约上门时段文案，如「今天 14:00—18:00」 */
  expectTime?: string;
  /** 联系人手机后四位（仅后四位，掩码展示） */
  contactTail4: string;
  reporterType: ReporterType;
  status: LegacyTicketStatus;
  /** 状态中文文案（冗余给出，避免前端字典不同步） */
  statusText: string;
  nodes: TicketNode[];
  /** 受理/处理班组 */
  team?: string;
  /** 预计上门文案 */
  etaText?: string;
  /** 下次主动推送时间（三次推送：接单/出发/完工） */
  nextPushAt?: IsoDateTime;
  /** 是否 AI 创建（必须可追溯） */
  aiCreated: boolean;
  /** 降级落「待人工建单」时为 true */
  degraded?: boolean;
  createdAt: IsoDateTime;
  updatedAt: IsoDateTime;
}

/* ============================================================================
 * 8. 知识条目（KnowledgeItem）
 * ========================================================================== */

/** 知识状态：草稿 / 复核中 / 已发布 / 已过期 / 已下线 */
export type KnowledgeStatus = 'draft' | 'reviewing' | 'published' | 'expired' | 'withdrawn';

/** 知识分类 */
export type KnowledgeCategory =
  | 'fee'
  | 'utility'
  | 'parking'
  | 'renovation'
  | 'pet'
  | 'visitor'
  | 'noise'
  | 'notice'
  | 'repair'
  | 'complaint'
  | 'other';

export const KNOWLEDGE_CATEGORY_LABELS: Readonly<Record<KnowledgeCategory, string>> = {
  fee: '物业费',
  utility: '公共能耗',
  parking: '车位',
  renovation: '装修',
  pet: '宠物',
  visitor: '访客',
  noise: '噪音',
  notice: '通知公告',
  repair: '报修',
  complaint: '投诉',
  other: '其他',
};

/** 出处（用于「依据 K-018 V1.0 · 装修管理规定 §3.1」） */
export interface KnowledgeSource {
  /** 制度/文件名称 */
  doc: string;
  /** 条款位置，如「§3.1」 */
  clause?: string;
  page?: string;
}

/** 回答引用四要素：knowledgeId / version / effectiveDate / owner（_mvp/01-MVP需求.md 待确认 #5） */
export interface KnowledgeCite {
  knowledgeId: string;
  version: string;
  effectiveDate: IsoDate;
  /** 责任人 */
  owner: string;
  title?: string;
  source?: KnowledgeSource;
}

/**
 * 知识条目（P1 设计口径演示类型，对齐 K-001—K-020 与 _design/02-架构设计.md §10.1）。
 *
 * **v1.2.0 更名**：对外契约把 `KnowledgeItem` 固化为 API 形状（见本文件 §13），
 * 本类型（projectId / keywords / answerable / effectiveDate 等设计口径字段）改名为 `KnowledgeDoc`；
 * 字段与注释未变、仓内无引用，保留导出以免破坏既有外部引用。
 */
export interface KnowledgeDoc {
  /** 知识 ID，如「K-002」 */
  knowledgeId: string;
  /** 项目域；「*」表示平台共享知识（I-0） */
  projectId: string;
  title: string;
  category: KnowledgeCategory;
  /** 答案正文（结构化条款原文，不含自由发挥） */
  content: string;
  /** 版本号，如「V1.1」 */
  version: string;
  status: KnowledgeStatus;
  /** 生效日 `YYYY-MM-DD` */
  effectiveDate: IsoDate;
  /** 失效日；过期知识降级为「以最新公示为准」并转管家 */
  expireDate?: IsoDate;
  /** 责任人 */
  owner: string;
  source: KnowledgeSource;
  /** 检索关键词 */
  keywords: string[];
  /** 是否允许对外回答（红线/内部口径为 false） */
  answerable: boolean;
  updatedAt: IsoDateTime;
}

/* ============================================================================
 * 9. 统一错误码与统一信封（ApiError / ApiResponse / DegradedInfo）
 * ========================================================================== */

/**
 * OpenAPI 契约版本（唯一事实源 `docs/openapi/openapi.yaml`）。
 * P2 v1.0.0 = 契约冻结（P2-036，形状等价 v0.3.3）；P3 v1.1.0 = W10 向后兼容增量
 * （费用/催缴/投诉/数据范围/对账导出）；P4 v1.2.0 = W15 向后兼容增量
 * （知识 / Copilot 8 工作区 / 坐席 / 全链路回溯 / P3 遗留补项）；P5 v1.3.0 = W19 向后兼容增量
 * （多项目隔离 / 连接器 SDK / 映射中心多连接器 / 灰度双跑 / 事件契约）；
 * **P5 v1.3.1 = W24 向后兼容增量（冻结前收口）**——observability 两个只读端点 + 3 schema、
 * 知识/映射/灰度 16 个端点补声明可选 `?projectId=`、审计单条作用域说明（R1）；
 * **P6 v1.4.0 = W30 向后兼容增量（知识库 RAG）**——`/rag/query`（混合检索 + LLM 重排 + 引用校验/拒答）、
 * `/rag/health`（编排/模型健康，仅 it/admin）、`/knowledge/{id}/reindex`（重建索引 + 审计 `knowledge.reindex`）
 * 共 3 path / 3 operation / 12 schema；`AuditAction` 末尾追加 `knowledge.reindex`；
 * 见 `docs/openapi/README.md` §1.8 / §6.10 / §7.8（新基线 83 path / 87 operation / 301 schema）。
 */
export const OPENAPI_VERSION = '1.4.0';

/** 契约通则版本（_design/02-架构设计.md §3.1，设计口径版本，非 OpenAPI 文件版本） */
export const CONTRACT_VERSION = 'Canonical API v1.4';

/** 降级档位：0 正常 / 1 替代 / 2 缓存或部分 */
export type DegradeLevel = 0 | 1 | 2;

/** 统一错误码（_design/02-架构设计.md §3.4） */
export type ErrorCode =
  | 'E-1001'
  | 'E-1002'
  | 'E-1003'
  | 'E-2001'
  | 'E-2002'
  | 'E-2003'
  | 'E-3001'
  | 'E-3002'
  | 'E-3003'
  | 'E-4001'
  | 'E-4002'
  | 'E-4003'
  | 'E-5001'
  | 'E-5002'
  | 'E-6001'
  | 'E-6002'
  | 'E-9001';

export interface ErrorCodeMeta {
  code: ErrorCode;
  /** 中文语义 */
  label: string;
  /** 是否可重试（读 1 次 / 写 0 次） */
  retryable: boolean;
  /** 责任方 */
  responsible: 'session' | 'mapping' | 'data' | 'security' | 'connector' | 'erp' | 'adapter' | 'ai' | 'platform';
  /** AI 侧动作 */
  aiAction: string;
}

export const ERROR_CODES: Readonly<Record<ErrorCode, ErrorCodeMeta>> = {
  'E-1001': { code: 'E-1001', label: '参数缺失或非法', retryable: false, responsible: 'session', aiAction: '追问补齐或转人工' },
  'E-1002': { code: 'E-1002', label: '枚举/字典未匹配', retryable: false, responsible: 'mapping', aiAction: '记缺口，降级或转人工' },
  'E-1003': { code: 'E-1003', label: '房号无法归一', retryable: false, responsible: 'data', aiAction: '请用户确认，二次失败转人工' },
  'E-2001': { code: 'E-2001', label: '身份未核验或核验失败', retryable: false, responsible: 'security', aiAction: '要求核验，3 次失败转人工' },
  'E-2002': { code: 'E-2002', label: '越权访问（主体与房号不匹配）', retryable: false, responsible: 'security', aiAction: '拒绝并记录，转人工' },
  'E-2003': { code: 'E-2003', label: '连接器凭据失效', retryable: true, responsible: 'connector', aiAction: '切备用凭据，失败降级' },
  'E-3001': { code: 'E-3001', label: '数据不存在', retryable: false, responsible: 'erp', aiAction: '如实告知，不构造答案' },
  'E-3002': { code: 'E-3002', label: '状态不允许（写前置不满足）', retryable: false, responsible: 'erp', aiAction: '说明当前状态，给替代路径' },
  'E-3003': { code: 'E-3003', label: '幂等冲突（同键不同载荷）', retryable: false, responsible: 'adapter', aiAction: '返回首次结果并提示已提交' },
  'E-4001': { code: 'E-4001', label: '上游超时', retryable: true, responsible: 'erp', aiAction: '进降级阶梯 L1→L2' },
  'E-4002': { code: 'E-4002', label: '上游错误（5xx）', retryable: true, responsible: 'erp', aiAction: '熔断 + 降级 + 告警' },
  'E-4003': { code: 'E-4003', label: '上游限流', retryable: true, responsible: 'erp', aiAction: '排队或降级，避免重试风暴' },
  'E-5001': { code: 'E-5001', label: '能力未开通/不支持', retryable: false, responsible: 'adapter', aiAction: '隐藏入口，改替代通道' },
  'E-5002': { code: 'E-5002', label: '降级返回（字段缺失）', retryable: false, responsible: 'adapter', aiAction: '只答已确认部分，其余转人工' },
  'E-6001': { code: 'E-6001', label: '护栏拦截（L3 命中）', retryable: false, responsible: 'security', aiAction: '替换标准话术并转人工' },
  'E-6002': { code: 'E-6002', label: '引用缺失（拒答）', retryable: false, responsible: 'ai', aiAction: '拒答 + 转人工 + 记缺口' },
  'E-9001': { code: 'E-9001', label: '内部错误', retryable: true, responsible: 'platform', aiAction: '兜底话术 + 转人工 + 告警' },
};

/** 降级替代路径（OpenAPI v0.3 `DegradedFallback`） */
export type DegradedFallback = 'alt_connector' | 'cache' | 'mock' | 'human' | 'partial' | 'none';

/**
 * 降级标记（OpenAPI v0.3 `DegradedInfo` / Java `bff/dto/DegradedInfo`）。
 * 降级不是错误：仍返回 2xx + `degraded`，前端展示已确认部分并给出替代路径；
 * 与 17 类错误码的关系见 `reason`（可带 `code`）。
 */
export interface DegradedInfo {
  /** 降级原因（人话；可同时给 `code`） */
  reason: string;
  fallback: DegradedFallback;
  /** 是否值得让用户稍后重试 */
  retryable: boolean;
  /** 触发降级的错误码（可选，取 17 类） */
  code?: ErrorCode;
  /** 降级数据时点（缓存/替代数据） */
  dataAsOf?: IsoDateTime;
}

/** 统一错误体（P1 结构化形态）。
 * @deprecated HTTP 信封已改为扁平 `ApiFailure.error`（OpenAPI v0.3 ErrorResponse）；
 * 本接口保留供内部编排/审计记录结构化错误，禁止用于对外响应。
 */
export interface ApiError {
  code: ErrorCode | string;
  /** 可读文案。禁止携带金额/工单号等敏感结果 */
  message: string;
  traceId?: string;
  /** @deprecated 降级改用 `DegradedInfo`（ApiFailure.degraded） */
  degraded?: boolean;
  retryable?: boolean;
  /** 结构化补充，如缺失字段列表 */
  details?: Record<string, unknown>;
}

/**
 * 成功响应（OpenAPI v0.3 统一信封：`{ok:true,data,traceId,degraded?}`）。
 * `degradeLevel` 等连接器可观测字段为 P5 预留，P2 信封**未建模**（不要在新代码依赖）。
 */
export interface ApiSuccess<T> {
  ok: true;
  data: T;
  traceId?: string;
  /** 降级标记（P2 契约）：未出现即未降级 */
  degraded?: DegradedInfo;
  /** @deprecated P5 连接器元数据，P2 契约未建模 */
  degradeLevel?: DegradeLevel;
  /** @deprecated P5 连接器元数据，P2 契约未建模 */
  sourceConnector?: string;
  /** @deprecated P5 连接器元数据，P2 契约未建模 */
  connectorVersion?: string;
  /** @deprecated P5 连接器元数据，P2 契约未建模 */
  mappingVersion?: string;
  /** @deprecated P5 连接器元数据，P2 契约未建模 */
  latencyMs?: number;
  /** @deprecated P5 连接器元数据，P2 契约未建模 */
  cacheHit?: boolean;
  /** @deprecated P5 连接器元数据，P2 契约未建模 */
  citeRequired?: boolean;
}

/**
 * 失败响应（OpenAPI v0.3 统一错误信封）。
 * `error` 为机器可读错误码：P2 起必须是 17 类 `ErrorCode`；P1 屏 mock 遗留业务码（如
 * SCREEN_NOT_FOUND）仅为兼容保留，新端点禁止使用。
 */
export interface ApiFailure {
  ok: false;
  error: ErrorCode | string;
  /** 人话文案（前端展示/回执映射）；不含金额、工单号等敏感结果 */
  message?: string;
  retryable?: boolean;
  /** 结构化补充，如 allowed 动作、缺失字段、idempotencyKey、reason */
  details?: Record<string, unknown>;
  degraded?: DegradedInfo;
  traceId?: string;
}

/** 统一响应：`{ok,data,...}` / `{ok:false,error,...}`（对齐 _mvp/02-技术方案.md §2 通用约定） */
export type ApiResponse<T> = ApiSuccess<T> | ApiFailure;

/* ============================================================================
 * 10. 健康检查（Health）
 * ========================================================================== */

/** 服务标识，与 apps/server 的 package name 对应 */
export const SERVICE_NAME = 'property-ai-server' as const;

/** GET /healthz 返回体（W1 最小契约：ok / service / mock / now） */
export interface Health {
  ok: true;
  service: typeof SERVICE_NAME;
  /** 是否 mock 模式（W1 恒为 true：无真实 ERP/DB/支付/短信） */
  mock: boolean;
  /** 服务端当前时间 ISO 8601 */
  now: IsoDateTime;
  /** 可选：版本与运行时长，便于前端状态条/排障展示 */
  version?: string;
  uptimeMs?: number;
}

/* ============================================================================
 * 11. P2 契约（OpenAPI v0.3.1：W5 冻结 + W6 向后兼容增量）—— owner / room / bill / ticket / push / recon / handoff
 *
 * 与 Java DTO 一一对应：`apps/server-java/src/main/java/com/property/ai/bff/dto/*`。
 * 命名约定：`*Result` / `*Info` / `*Summary` 是 `data` 载荷；`*Response` 是统一信封别名
 * （`ApiResponse<载荷>`），与 OpenAPI 的 `*Response`（信封）同名同义。
 * 字段名 / 必填 / 枚举严格以 `docs/openapi/openapi.yaml` 为准，禁止两端各写一套。
 * ========================================================================== */

/* ---- 11.1 分页约定 ---- */

export const DEFAULT_PAGE = 1;
export const DEFAULT_PAGE_SIZE = 20;
export const MAX_PAGE_SIZE = 100;

/** 分页入参（page 从 1 开始；pageSize ≤ 100） */
export interface PageQuery {
  page?: number;
  pageSize?: number;
}

/** 分页返回（OpenAPI 分页约定：`{items,page,pageSize,total}`） */
export interface PageResult<T> {
  items: T[];
  page: number;
  pageSize: number;
  total: number;
}

/* ---- 11.2 业主核验（CAP-02 owner.verify） ---- */

export interface OwnerVerifyRequest {
  /** 归一房号；省略时按手机号后四位在项目内匹配（多户命中 → E-1001） */
  roomCode?: string;
  /** 手机号后四位（仅 4 位数字；P2 演示因子，不做真实短信/微信授权） */
  phoneTail: string;
}

export interface OwnerVerifyResult {
  verified: boolean;
  /** 回显房号；未提供时为匹配到的房号，不匹配时为空串 */
  roomCode: string;
  /** 掩码姓名（如「王*」）；未通过时为空串，不提示错因 */
  ownerNameMasked: string;
  /** 项目名（公开信息） */
  projectName: string;
  /** 剩余核验次数（连续 3 次失败转人工） */
  attemptsLeft?: number;
  locked?: boolean;
  mock: boolean;
}

export type OwnerVerifyResponse = ApiResponse<OwnerVerifyResult>;

/* ---- 11.3 房档只读（CAP-01 room.query） ---- */

/** 房档数据来源 */
export type RoomSource = 'erp' | 'mock' | 'cache';

/** 房档（字段对齐 `mock/data/households.json`；`roomNo` → 契约 `roomCode`） */
export interface RoomInfo {
  householdId: string;
  projectId: string;
  /** 归一房号（= mock/data 的 roomNo，如「1-1-101」） */
  roomCode: string;
  building: string;
  unit: string;
  room: string;
  /** 建筑面积（㎡），决定物业费与公摊电费 */
  areaM2: number;
  layout?: string;
  ownerNameMasked: string;
  bindState: BindState;
  /** 车位号；null = 无车位（账单不含车位费） */
  parkingNo: string | null;
  accountStatus: AccountStatus;
  overdueDays: number;
  /** 已过应缴截止日仍未缴的本金合计 */
  arrearsAmount?: Money;
  /** 本房脱敏关系人（不返回他人房档明细） */
  relations?: HouseholdRelation[];
  tags?: string[];
  source: RoomSource;
  mock: boolean;
  dataAsOf?: IsoDateTime;
  updatedAt: IsoDateTime;
  /** v0.3.2 增量（可选）：数据新鲜度；命中缓存 / source=cache / 降级 fallback=cache 时必带 */
  freshness?: DataFreshness;
}

export type RoomQueryResponse = ApiResponse<RoomInfo>;

/* ---- 11.4 账单只读（CAP-03 bill.query） ---- */

/** 知识引用（差额解释必须可追溯；对应 `KnowledgeCite` 的四要素子集） */
export interface Citation {
  /** 知识条目 ID，如「K-002」 */
  kbId: string;
  /** 版本号，如「V1.1」 */
  version: string;
  title?: string;
  effectiveDate?: IsoDate;
  /** 责任人（岗位/掩码名） */
  owner?: string;
}

/** 账单费项（展示口径；金额由 ERP 给出，AI 不自算） */
export interface BillItem {
  /** 展示名，如「公摊电费」 */
  label: string;
  amount: Money;
  /** 计费口径/算式，如「100 ㎡ × 0.38 元/㎡·月」 */
  note?: string;
  /** 标准费项编码（PROPERTY_FEE / PUBLIC_ENERGY / PARKING_FEE） */
  itemCode?: string;
  /** 解释该费项的知识条目，如「K-002」 */
  knowledgeId?: string;
}

/** 账单（CAP-03；`total`/`due`/`prevTotal`/`diff`/`items` 以 ERP 返回为准） */
export interface BillInfo {
  /** 账期 `YYYY-MM` */
  period: Period;
  /** 本期应缴合计 */
  total: Money;
  /** 本期待缴（= total - paid，由 ERP 给出） */
  due: Money;
  items: BillItem[];
  /** 上期应缴合计 */
  prevTotal: Money;
  /** 与上期差额（= total - prevTotal，由 ERP 给出，AI 不重算） */
  diff: Money;
  /** 差额解释引用 */
  cite: Citation;
  diffReason?: string;
  billNo?: string;
  householdId?: string;
  projectId?: string;
  roomCode?: string;
  /** 实缴金额 */
  paid?: Money;
  /** 欠费金额（含历史欠费口径以 ERP 为准） */
  outstanding?: Money;
  /** 滞纳金 */
  lateFee?: Money;
  status?: BillStatus;
  dueDate?: IsoDate;
  currency?: CurrencyCode;
  source: BillSource;
  /** 数据时点（降级/缓存时前端必须标注「数据时间」） */
  dataAsOf: IsoDateTime;
  mock: boolean;
  /** v0.3.2 增量（可选）：数据新鲜度；命中缓存 / source=cache / 降级 fallback=cache 时必带 */
  freshness?: DataFreshness;
  /** v1.1.0 增量（可选）：逾期天数（0 = 未逾期；由 ERP 账单 dueDate 与服务日计算） */
  overdueDays?: number;
  /** v1.1.0 增量（可选）：连续欠费天数（ERP 欠费台账口径，跨账期累计，用于分层策略） */
  arrearsDays?: number;
  /** v1.1.0 增量（可选）：账单解释引用（前端点击「为什么这么多」调 `GET /api/v1/fees/explain`） */
  explainRef?: string;
}

export type BillQueryResponse = ApiResponse<BillInfo>;

/* ---- 11.5 报修/工单闭环（CAP-06/07/08/09/13/18） ---- */

/**
 * 工单状态机 7 节点（P2 冻结，大写）：
 * `CREATED → ACCEPTED → DISPATCHED → ON_SITE → COMPLETED → REVIEWED → ARCHIVED`。
 * `ARCHIVED` 由归档作业完成（P2-018），不在 `TICKET_TRANSITIONS` 中开放。
 */
export type TicketStatus =
  | 'CREATED'
  | 'ACCEPTED'
  | 'DISPATCHED'
  | 'ON_SITE'
  | 'COMPLETED'
  | 'REVIEWED'
  | 'ARCHIVED';

export const TICKET_STATUSES: readonly TicketStatus[] = [
  'CREATED',
  'ACCEPTED',
  'DISPATCHED',
  'ON_SITE',
  'COMPLETED',
  'REVIEWED',
  'ARCHIVED',
];

export const TICKET_STATUS_LABELS: Readonly<Record<TicketStatus, string>> = {
  CREATED: '待受理',
  ACCEPTED: '已受理',
  DISPATCHED: '已派单',
  ON_SITE: '处理中',
  COMPLETED: '已完工',
  REVIEWED: '已回访',
  ARCHIVED: '已归档',
};

/** 状态迁移动作（`POST /api/v1/tickets/{ticketNo}/transition`） */
export type TicketTransitionAction = 'dispatch' | 'on_site' | 'complete' | 'reschedule' | 'urge';

export const TICKET_TRANSITION_ACTIONS: readonly TicketTransitionAction[] = [
  'dispatch',
  'on_site',
  'complete',
  'reschedule',
  'urge',
];

/** 单个迁移动作的允许前置状态与目标状态（供 Java P2-007 状态机 / 前端按钮显隐复用） */
export interface TicketTransitionRule {
  /** 允许的 from 状态 */
  from: readonly TicketStatus[];
  /** to 状态；null 表示状态不变（改约/催办） */
  to: TicketStatus | null;
  /** payload 关键字段说明（校验口径以 OpenAPI 为准） */
  payload: string;
}

export const TICKET_TRANSITIONS: Readonly<Record<TicketTransitionAction, TicketTransitionRule>> = {
  dispatch: { from: ['CREATED', 'ACCEPTED'], to: 'DISPATCHED', payload: 'team 必填；eta 可选' },
  on_site: { from: ['DISPATCHED'], to: 'ON_SITE', payload: 'arrivedAt 可选' },
  complete: { from: ['ON_SITE'], to: 'COMPLETED', payload: 'note / finishedAt 可选' },
  reschedule: {
    from: ['ACCEPTED', 'DISPATCHED', 'ON_SITE'],
    to: null,
    payload: 'slot 必填；reason 可选',
  },
  urge: {
    from: ['CREATED', 'ACCEPTED', 'DISPATCHED', 'ON_SITE'],
    to: null,
    payload: 'reason / channel 可选',
  },
};

/** 回访评价允许的 from 状态（成功后 → `REVIEWED`） */
export const TICKET_FEEDBACK_FROM: readonly TicketStatus[] = ['COMPLETED'];

/** 归档作业允许的 from 状态（P2-018，不开放 HTTP action） */
export const TICKET_ARCHIVE_FROM: readonly TicketStatus[] = ['REVIEWED'];

/** 工单节点/审计事件（按时间升序） */
export interface TicketAuditNode {
  status: TicketStatus;
  /** 节点中文文案（服务端给出，前端不硬编码） */
  statusText: string;
  at: IsoDateTime;
  /** 处理人/班组（掩码或岗位名） */
  who?: string;
  /** 业主可见文案 */
  ownerText?: string;
}

/** ERP 侧参考（AI 建单与真实 ERP 单号的可追溯映射） */
export interface ErpRef {
  /** ERP 厂商/连接器标识（P2 第 1 周为 yonyou-nc-mock） */
  vendor: string;
  /** ERP 侧单号/引用 */
  ref: string;
}

/** 工单详情（7 节点时间轴） */
export interface Ticket {
  ticketNo: string;
  projectId: string;
  householdId: string;
  /** 归一房号（OpenAPI `roomCode`） */
  roomCode: string;
  category: TicketCategory;
  urgency: TicketUrgency;
  /** 报修位置（五问之一） */
  location: string;
  /** 是否漏到楼下等影响范围 */
  downstairs: IssueScope;
  /** 现象持续时长 */
  duration: string;
  /** 预约上门时段 */
  slot: string;
  note?: string;
  /** 结构化现象摘要（供摘要/转人工使用） */
  symptom?: string;
  status: TicketStatus;
  statusText: string;
  team?: string;
  eta?: string;
  nodes: TicketAuditNode[];
  /** 乐观锁版本号（transition 必须回传） */
  version: number;
  aiCreated: boolean;
  mock: boolean;
  erp: ErpRef;
  /** 建单时 traceId（可回溯） */
  traceId?: string;
  /** v0.3.1 增量：回访评价（落库，刷新后仍在；未回访时不返回该字段） */
  feedback?: TicketFeedbackSummary;
  createdAt: IsoDateTime;
  updatedAt: IsoDateTime;
  /**
   * v1.1.0 增量（可选）：业主主体 ID（数据范围归属，如 `owner-3-2-1802`）。
   * 用于 owner 数据级权限（架构 §14.2：业主只能看本户/本人单）；staff 端点可不返回。
   */
  ownerId?: string;
}

/** 工单列表项（不含 nodes；详情走 GET /tickets/{ticketNo}） */
export interface TicketListItem {
  ticketNo: string;
  roomCode: string;
  category: TicketCategory;
  urgency: TicketUrgency;
  status: TicketStatus;
  statusText: string;
  team?: string;
  eta?: string;
  version: number;
  aiCreated: boolean;
  mock: boolean;
  createdAt: IsoDateTime;
  updatedAt: IsoDateTime;
}

/** 工单列表分页结果（OpenAPI `TicketListResult`） */
export type TicketListResult = PageResult<TicketListItem>;

/** 工单列表排序（v0.3.1 增量；默认 `updatedAt_desc`） */
export type TicketListSort = 'updatedAt_desc' | 'createdAt_asc' | 'createdAt_desc';

/** 工单列表默认排序（与 OpenAPI `TicketListSort.default` 一致） */
export const DEFAULT_TICKET_LIST_SORT: TicketListSort = 'updatedAt_desc';

/** 工单列表查询参数（GET /api/v1/tickets；v0.3.1 增量：category/dateFrom/dateTo/sort） */
export interface TicketListQuery extends PageQuery {
  status?: TicketStatus;
  team?: string;
  /** 报修分类（v0.3.1 增量） */
  category?: TicketCategory;
  /** 起始时间（含，按 updatedAt 过滤；v0.3.1 增量） */
  dateFrom?: IsoDateTime;
  /** 结束时间（含，按 updatedAt 过滤；v0.3.1 增量） */
  dateTo?: IsoDateTime;
  /** 排序（默认 updatedAt_desc；v0.3.1 增量） */
  sort?: TicketListSort;
  /**
   * v1.1.0 增量（可选，默认 false）：只看与我相关。
   * owner → 本户工单（服务端强制数据范围）；steward/agent → 分配给自己的工单；supervisor/it/admin → 不受限。
   */
  mine?: boolean;
}

/** 报修建单请求（五问 + 预约时段；写操作，必须带 Idempotency-Key） */
export interface TicketCreateRequest {
  roomCode: string;
  category: TicketCategory;
  urgency: TicketUrgency;
  /** 报修位置，如「卫生间吊顶」 */
  location: string;
  /** 是否已漏到楼下 */
  downstairs: IssueScope;
  /** 现象持续时长，如「约 2 小时」 */
  duration: string;
  /** 预约上门时段，如「今天 14:00—18:00」 */
  slot: string;
  note?: string;
  /** 报修人类型（默认 owner） */
  reporterType?: ReporterType;
  /** 联系人手机后四位 */
  contactTail4?: string;
  /** 客户端 traceId（服务端优先沿用） */
  traceId?: string;
}

/** 建单受理结果（真实工单号来自 ERP；降级待人工建单时不得编造 ticketNo） */
export interface TicketCreateResult {
  ticketNo: string;
  status: TicketStatus;
  /** 受理/派单班组（未派单为空串） */
  team: string;
  /** 预计上门文案（未派单为空串） */
  eta: string;
  erp: ErpRef;
  mock: boolean;
}

/* ---- 11.5.1 W6 增量（OpenAPI v0.3.1）：派单 / 完工结案 / ERP 回写 ---- */

/**
 * 派单动作参数（v0.3.1 有名字段；产品设计 §5.1 派单）。
 * 兼容：W5 客户端仍可只传 `{team, eta}`，服务端按 `team` ≈ `teamName` 映射。
 */
export interface DispatchPayload {
  /** 班组 ID（ERP 侧主数据，优先于 teamName） */
  teamId?: string;
  /** 班组名（展示口径，如「水电二班」；W5 旧字段 `team` 语义等同本字段） */
  teamName?: string;
  /** 预计上门时间（ISO 8601）或人话文案（如「预计今天 17:00 前」，W5 兼容） */
  eta?: IsoDateTime | string;
  /** 技能标签（派单匹配用，如「水暖」「强电」） */
  skillTag?: string;
  /** 接单人（工程师姓名/工号掩码；空表示班组待接单） */
  assignee?: string;
}

/** 完工结案参数（v0.3.1，CAP-09 ticket.close 完工回写） */
export interface CompletePayload {
  /** 结案说明（业主可见处理结果；必填） */
  resolution: string;
  /** 内部备注（不进业主文案） */
  note?: string;
  /** ERP 侧结案单号/引用（可选；真实单号来自 ERP，不得编造） */
  erpCloseRef?: string;
}

/**
 * ERP 回写状态（架构 §24.6）：`pending` = 30 秒确认窗口内；`confirmed` = ERP 已确认闭环；
 * `failed` = 回写失败，已进人工待办（**不自动重写**）。
 */
export type ErpWritebackStatus = 'confirmed' | 'pending' | 'failed';

/** ERP 写操作回写结果（v0.3.1；dispatch / complete 均返回） */
export interface ErpWriteback {
  status: ErpWritebackStatus;
  /** 已尝试回写次数（0 = 尚未发出；不自动重试，超限即进人工待办） */
  attempts: number;
  /** 下次确认/查询时间（仅 pending 时有意义；服务端 30 秒后确认） */
  nextRetryAt?: IsoDateTime;
  /** ERP 侧回写单号/引用 */
  erpRef?: string;
  /** 回写结果人话说明（失败原因，不含 ERP 凭据/敏感信息） */
  message?: string;
}

/** 状态迁移请求（写操作，必须带 Idempotency-Key） */
export interface TicketTransitionRequest {
  action: TicketTransitionAction;
  /**
   * 动作参数（v0.3.1 有名字段）：dispatch → `DispatchPayload`；complete → `CompletePayload`；
   * on_site/reschedule/urge 沿用 W5 自由字段（`arrivedAt?` / `slot` / `reason?` / `channel?`）。
   * 保留 `Record<string, unknown>` 分支以兼容 W5 客户端，不破坏既有调用。
   */
  payload?: DispatchPayload | CompletePayload | Record<string, unknown>;
  /** 乐观锁版本号（取自工单详情；不匹配 → E-3002 version_conflict） */
  version: number;
}

/** 状态迁移结果（新状态 + 本次新增审计节点 + v0.3.1 ERP 回写/派单回显） */
export interface TicketTransitionResult {
  ticketNo: string;
  status: TicketStatus;
  statusText: string;
  version: number;
  /** 本次新增的审计节点（reschedule/urge 状态不变） */
  nodes: TicketAuditNode[];
  /** 审计事件 ID（可回溯） */
  auditEventId: string;
  /**
   * v0.3.1 增量（dispatch / complete 都会带）：ERP 回写结果。
   * `pending` 表示 30 秒确认窗口内（前端展示「回写确认中」），`failed` 已进人工待办。
   */
  erpWriteback?: ErpWriteback;
  /** v0.3.1 增量（仅 dispatch）：回显本次派单班组/ETA/接单人，供前端即时刷新 */
  dispatch?: DispatchPayload;
  mock: boolean;
}

/**
 * 回访评价摘要（v0.3.1 增量）。同时用于 `Ticket.feedback`（落库后随详情返回，刷新不丢评分）
 * 与 `TicketFeedbackResult.feedback`（回访成功即时回显）。
 */
export interface TicketFeedbackSummary {
  /** 满意度 1—5 分 */
  score: number;
  /** 评价标签，如「响应快」「处理干净」 */
  tags?: string[];
  /** 评价备注（不含敏感信息） */
  note?: string;
  /** 回访时间（ISO 8601） */
  reviewedAt: IsoDateTime;
}

/** 回访评价请求（写操作，必须带 Idempotency-Key） */
export interface TicketFeedbackRequest {
  /** 满意度 1—5 分（≤2 触发主管提醒；不得改写工单结论） */
  score: number;
  tags?: string[];
  note?: string;
}

/** 回访评价结果（成功后工单进入 REVIEWED） */
export interface TicketFeedbackResult {
  ticketNo: string;
  status: TicketStatus;
  statusText: string;
  score: number;
  tags?: string[];
  /** 是否触发主管提醒（score ≤ 2） */
  supervisorAlert: boolean;
  reviewedAt: IsoDateTime;
  /** v0.3.1 增量：回访结果回显（与 `Ticket.feedback` 同构，便于前端统一刷新） */
  feedback?: TicketFeedbackSummary;
  mock: boolean;
}

/** 统一信封别名（与 OpenAPI `*Response` 同名同义） */
export type TicketCreateResponse = ApiResponse<TicketCreateResult>;
export type TicketGetResponse = ApiResponse<Ticket>;
export type TicketListResponse = ApiResponse<TicketListResult>;
export type TicketTransitionResponse = ApiResponse<TicketTransitionResult>;
export type TicketFeedbackResponse = ApiResponse<TicketFeedbackResult>;

/* ---- 11.5.2 推送记录（P2 W6 增量 / OpenAPI v0.3.1 `GET /tickets/{ticketNo}/pushes`） ---- */

/** 推送通道（P2 W6 全部为 mock 测试替身，不接真实企微/短信） */
export type TicketPushChannel = 'wecom' | 'sms' | 'inapp';

/** 推送状态（`mocked` = 已由 mock 通道受理并留痕，未真实外发） */
export type TicketPushStatus = 'sent' | 'pending' | 'failed' | 'mocked';

/** 单条推送记录（产品设计 §5.1 建单/派单/完工三次推送；`contentMasked` 只回脱敏正文） */
export interface TicketPush {
  /** 推送记录 ID（可回溯） */
  pushId: string;
  ticketNo: string;
  channel: TicketPushChannel;
  /** 模板编码/名称（服务端给出，前端不硬编码），如 `ticket_created` / `ticket_dispatched` / `ticket_completed` */
  template: string;
  status: TicketPushStatus;
  /** 受理/发出时间（`pending` 时为空） */
  sentAt?: IsoDateTime;
  /** 脱敏正文（手机号只留后四位、地址隐藏） */
  contentMasked?: string;
  /** P2 W6 推送为 mock 通道，恒为 true */
  mock: boolean;
}

/** 推送记录列表（按 `sentAt` 倒序；无记录返回空数组，不造数） */
export interface TicketPushListResult {
  items: TicketPush[];
}

/** 统一信封别名（与 OpenAPI `TicketPushListResponse` 同名同义） */
export type TicketPushListResponse = ApiResponse<TicketPushListResult>;

/* ---- 11.6 转人工同屏摘要（CAP-18 handoff.human） ---- */

/** 历史来源渠道 */
export type HandoffChannel = 'chat' | 'phone' | 'wechat' | 'system';

/** 脱敏历史片段（最近 7 天，不返回完整手机号/地址） */
export interface HandoffHistoryItem {
  at: IsoDateTime;
  channel: HandoffChannel;
  text: string;
}

/** 转人工摘要（摘要 + 要素 + 情绪 + 历史 + 建议动作） */
export interface HandoffSummary {
  ticketNo: string;
  roomCode: string;
  ownerNameMasked: string;
  /** 一句话标题 */
  title: string;
  status: TicketStatus;
  statusText: string;
  /** 摘要（≤5 行） */
  summary: string[];
  /** 已采集要素键值（category/urgency/location/downstairs/duration/slot 等） */
  elements: Record<string, string>;
  /** 情绪判断（如「着急」） */
  emotion?: string;
  history: HandoffHistoryItem[];
  suggestedActions: string[];
  pendingActions?: string[];
  generatedAt: IsoDateTime;
  mock: boolean;
}

export type HandoffSummaryResponse = ApiResponse<HandoffSummary>;

/* ---- 11.7 只读对账（P2-013） ---- */

/** 对账范围：房档 / 账单 / 工单 */
export type ReconScope = 'room' | 'bill' | 'ticket';

export type ReconRunStatus = 'queued' | 'running' | 'completed' | 'failed';

/** 差异类型 */
export type ReconDiffKind = 'missing' | 'field_drift' | 'amount_mismatch' | 'status_mismatch';

export interface ReconRunRequest {
  scope: ReconScope;
  /** 抽样条数（默认 200，上限 1000） */
  sampleSize?: number;
}

export interface ReconRunResult {
  runId: string;
  scope: ReconScope;
  sampleSize: number;
  status: ReconRunStatus;
  startedAt: IsoDateTime;
  mock: boolean;
}

/** 单条差异（不含真实业主信息） */
export interface ReconDiffItem {
  /** 差异对象主键（billNo / ticketNo / householdId） */
  key: string;
  kind: ReconDiffKind;
  field?: string;
  expected?: string;
  actual?: string;
  roomCode?: string;
  billNo?: string;
  ticketNo?: string;
}

/** 对账报告（P2 门禁：200 条，差异 ≤2%） */
export interface ReconReport {
  runId: string;
  scope: ReconScope;
  status: ReconRunStatus;
  sampleSize: number;
  /** 实际对账条数 */
  checked: number;
  matched: number;
  diffCount: number;
  /** 差异率 = diffCount / checked */
  diffRate: number;
  /** 阈值（P2 为 0.02） */
  threshold: number;
  passed: boolean;
  items: ReconDiffItem[];
  startedAt: IsoDateTime;
  finishedAt?: IsoDateTime;
  mock: boolean;
}

/** P2 对账默认抽样条数与门禁阈值 */
export const DEFAULT_RECON_SAMPLE_SIZE = 200;
export const RECON_DIFF_THRESHOLD = 0.02;

export type ReconRunResponse = ApiResponse<ReconRunResult>;
export type ReconReportResponse = ApiResponse<ReconReport>;

/* ============================================================================
 * 11.8 P2 W7 增量（OpenAPI v0.3.2）—— 只读列表/新鲜度 / 映射版本化 / 连接器与能力探测 /
 *      降级台账 / 对账历史
 *
 * 兼容策略：新增端点独立 path、新增字段全部可选；W5/W6 既有响应形状与必填集不变。
 * 对应 Java DTO 同样在 `bff/dto/*`；枚举顺序与 OpenAPI 严格一致（check-contract 硬锁）。
 * ========================================================================== */

/* ---- 11.8.1 只读列表 + 数据新鲜度（P2-023） ---- */

/**
 * 只读数据新鲜度（OpenAPI `DataFreshness`，v0.3.2）。
 * 命中展示缓存 / `source=cache` / 降级 `degraded.fallback=cache` 时**必带**；非缓存直读可不带。
 * 与 `DegradedInfo.dataAsOf` 同源口径（架构 §24.5：展示缓存必须带来源与 TTL，超时即重新查询）。
 */
export interface DataFreshness {
  /** 数据来源（erp / mock / cache，与 RoomInfo.source / BillInfo.source 同口径） */
  source: string;
  /** 数据时点（缓存写入或 ERP 返回时间）；前端据此标注「数据时间」 */
  dataAsOf: IsoDateTime;
  /** 展示缓存 TTL（秒）；0 = 非缓存直读 */
  ttlSeconds: number;
  /** 是否命中缓存（true = 本次返回缓存数据，非实时 ERP） */
  hit: boolean;
}

/** 房档列表排序（v0.3.2；默认 `roomCode_asc`，保证稳定分页） */
export type RoomListSort = 'roomCode_asc' | 'updatedAt_desc';

export const ROOM_LIST_SORTS: readonly RoomListSort[] = ['roomCode_asc', 'updatedAt_desc'];

export const DEFAULT_ROOM_LIST_SORT: RoomListSort = 'roomCode_asc';

/** 房档列表查询参数（GET /api/v1/rooms；v0.3.2 增量，与单条 `GET /rooms/{roomCode}` 并存） */
export interface RoomListQuery extends PageQuery {
  /** 楼栋名筛选（精确匹配，如「1栋」） */
  building?: string;
  bindState?: BindState;
  sort?: RoomListSort;
}

/** 房档列表分页结果（OpenAPI `RoomListResult`） */
export type RoomListResult = PageResult<RoomInfo>;
export type RoomListResponse = ApiResponse<RoomListResult>;

/** 账单列表排序（v0.3.2；默认 `updatedAt_desc`） */
export type BillListSort = 'total_desc' | 'due_asc' | 'updatedAt_desc';

export const BILL_LIST_SORTS: readonly BillListSort[] = ['total_desc', 'due_asc', 'updatedAt_desc'];

export const DEFAULT_BILL_LIST_SORT: BillListSort = 'updatedAt_desc';

/**
 * 账单列表查询参数（GET /api/v1/bills **列表模式**：省略 `roomCode` 时进入列表模式）。
 * 提供 `roomCode` 时仍为 W5 单账单模式（`period` 必填），见 `BillQueryOrListResponse`。
 */
export interface BillListQuery extends PageQuery {
  /** 账期 `YYYY-MM`（列表模式筛选，默认当前账期） */
  period?: Period;
  status?: BillStatus;
  /** 楼栋名筛选（精确匹配，如「1栋」） */
  building?: string;
  sort?: BillListSort;
}

/** 账单列表分页结果（OpenAPI `BillListResult`） */
export type BillListResult = PageResult<BillInfo>;
export type BillListResponse = ApiResponse<BillListResult>;

/**
 * `GET /api/v1/bills` 双模式响应（v0.3.2）：
 * 提供 `roomCode` → `BillQueryResponse`（W5 单账单，形状不变）；
 * 省略 `roomCode` → `BillListResponse`（分页列表）。
 */
export type BillQueryOrListResponse = BillQueryResponse | BillListResponse;

/* ---- 11.8.2 映射版本化（P2-024） ---- */

/**
 * 映射版本状态机（架构 §5.2）：`draft` 草稿 → `trial_passed` 试跑通过 → `published` 已发布 →
 * `rolled_back` 已回滚；保留 ≥2 个可回滚版本，发布需试跑通过（否则 409 `E-3002`）。
 */
export type MappingVersionStatus = 'draft' | 'trial_passed' | 'published' | 'rolled_back';

export const MAPPING_VERSION_STATUSES: readonly MappingVersionStatus[] = [
  'draft',
  'trial_passed',
  'published',
  'rolled_back',
];

/** 单条字段映射（标准字段 ↔ ERP 字段；转换规则只允许白名单类型，不含脚本，架构 §5.1） */
export interface MappingField {
  /** ERP 侧源字段（厂商字段名，如 `pk_ticket`） */
  sourceField: string;
  /** 标准字段（契约字段名，如 `ticketNo`） */
  targetField: string;
  /** 字段类型（string / number / enum / datetime / array / text） */
  type: string;
  /** 是否必填（必填缺失 → E-1001，阻断发布） */
  required: boolean;
  /** 转换规则说明（归一/截断/单位换算/时区/枚举/默认值/拆分/拼接/脱敏） */
  transform?: string;
}

/** 映射整体版本（字段/状态码/字典映射，架构 §5.2；`fields` 仅详情端点返回） */
export interface MappingVersion {
  /** 映射版本 ID（稳定主键，如 `MAP-NC-TICKET`） */
  id: string;
  name: string;
  /** 绑定的连接器 ID（映射与连接器版本解耦但绑定） */
  connectorId: string;
  /** 版本号（形如 v1.8.2） */
  version: string;
  status: MappingVersionStatus;
  /** 字段映射条数 */
  fieldCount: number;
  fields?: MappingField[];
  updatedAt: IsoDateTime;
  /** 发布/生效时间（未发布不返回） */
  publishedAt?: IsoDateTime;
  /** 变更人/发布人（岗位名或掩码名） */
  operator?: string;
}

/** 映射试跑差异类型 */
export type MappingDiffKind = 'missing' | 'type_mismatch' | 'required_missing';

export const MAPPING_DIFF_KINDS: readonly MappingDiffKind[] = ['missing', 'type_mismatch', 'required_missing'];

/** 单条试跑差异（样本主键用 roomCode / ticketNo / billNo，不含敏感信息） */
export interface MappingDiffItem {
  /** 样本主键（roomCode / ticketNo / billNo） */
  key: string;
  kind: MappingDiffKind;
  /** 差异字段的 ERP 源字段 */
  sourceField?: string;
  /** 差异字段的标准字段 */
  targetField?: string;
  /** 期望值（标准字段口径，字符串化） */
  expected?: string;
  /** 实际值（映射后结果，字符串化） */
  actual?: string;
  roomCode?: string;
}

/** 映射试跑请求（默认 200 样本；非法 → 400 `E-1001`） */
export interface MappingTrialRequest {
  /** 试跑样本数（默认 200，上限 1000） */
  sampleSize?: number;
}

/**
 * 映射试跑报告（OpenAPI `MappingTrialReport`，v0.3.2）。
 * `diffRate = diffCount / checked`；`passed = diffRate <= threshold`（P2 门禁 0.02）。
 */
export interface MappingTrialReport {
  /** 试跑 ID（可回溯） */
  runId: string;
  mappingId: string;
  /** 实际比对样本数 */
  checked: number;
  /** 完全一致样本数 */
  matched: number;
  diffCount: number;
  /** 差异率 = diffCount / checked */
  diffRate: number;
  /** 通过阈值（P2 为 0.02） */
  threshold: number;
  passed: boolean;
  items: MappingDiffItem[];
  startedAt: IsoDateTime;
  finishedAt?: IsoDateTime;
}

/** 映射回滚请求（`toVersion` 省略 = 回滚到上一个已发布版本） */
export interface MappingRollbackRequest {
  toVersion?: string;
  reason?: string;
  /** v1.3.0（P5 W19）可选：连接器作用域（省略 = 映射自身 `connectorId`；不匹配 → 403 `E-2002`） */
  connectorId?: string;
}

/** P2 映射试跑默认样本数与门禁阈值（与 OpenAPI `MappingTrialRequest.sampleSize.default` 一致） */
export const DEFAULT_MAPPING_TRIAL_SAMPLE = 200;
export const MAPPING_TRIAL_THRESHOLD = 0.02;

export type MappingVersionListResponse = ApiResponse<MappingVersion[]>;
export type MappingVersionResponse = ApiResponse<MappingVersion>;
export type MappingTrialResponse = ApiResponse<MappingTrialReport>;

/* ---- 11.8.3 连接器与能力探测（P2-025） ---- */

/**
 * 18 项标准能力编码（架构 §3.2 CAP-01…CAP-18，顺序固定）。
 * 与 OpenAPI `CapabilityCode`、Java `CapabilityCode` 一一对应；禁止新增未经设计确认的编码。
 */
export type CapabilityCode =
  | 'room.query'
  | 'owner.verify'
  | 'bill.query'
  | 'pay.jump'
  | 'invoice.apply'
  | 'ticket.create'
  | 'ticket.assign'
  | 'ticket.status'
  | 'ticket.close'
  | 'complaint.create'
  | 'notice.push'
  | 'notice.receipt'
  | 'visit.record'
  | 'dunning.task'
  | 'kb.search'
  | 'visitor.auth'
  | 'parking.auth'
  | 'handoff.human';

/** 18 项能力编码（顺序 = CAP-01…CAP-18） */
export const CAPABILITY_CODES: readonly CapabilityCode[] = [
  'room.query',
  'owner.verify',
  'bill.query',
  'pay.jump',
  'invoice.apply',
  'ticket.create',
  'ticket.assign',
  'ticket.status',
  'ticket.close',
  'complaint.create',
  'notice.push',
  'notice.receipt',
  'visit.record',
  'dunning.task',
  'kb.search',
  'visitor.auth',
  'parking.auth',
  'handoff.human',
];

/** 能力级别（架构 §3.1）：L1 只能说（只读）/ L2 可以做 / L3 不提供（必须拦截转人工） */
export type CapabilityLevel = 'L1' | 'L2' | 'L3';

export const CAPABILITY_LEVELS: readonly CapabilityLevel[] = ['L1', 'L2', 'L3'];

/**
 * 能力状态三态（架构 §4.3/§6，探测结果覆盖声明）：
 * `supported` 支持 / `degraded` 降级（入口保留 + 能力受限提示 + 替代通道）/
 * `unsupported` 不支持（隐藏入口、话术不承诺、走替代或转人工）。
 */
export type CapabilityStatus = 'supported' | 'degraded' | 'unsupported';

export const CAPABILITY_STATUSES: readonly CapabilityStatus[] = ['supported', 'degraded', 'unsupported'];

/**
 * 单能力探测态（OpenAPI `CapabilityState`，v0.3.2）。
 * 矩阵是能力事实的唯一来源（入口显隐/话术承诺/看板统计全部读矩阵，禁止页面硬编码）。
 * 与前端 P1-025 向导 `ProbeResult`、mock-erp `/api/v1/meta/capabilities` 同口径。
 */
export interface CapabilityState {
  /** 设计编号 CAP-01…CAP-18（可选，便于回溯设计条目与 mock-erp 元数据） */
  capabilityCode?: string;
  code: CapabilityCode;
  /** 能力中文名（服务端给出，前端不硬编码） */
  name: string;
  level: CapabilityLevel;
  state: CapabilityStatus;
  /** 本次探测时间（探测有效期 30 天，过期按未探测处理） */
  checkedAt: IsoDateTime;
  /** 探测证据（方法/样本/通过率/P95；不含 ERP 凭据与敏感信息） */
  evidence?: string;
  /** 缺能力时的替代通道文案（降级/不支持项必给） */
  fallback?: string;
}

/** 连接器健康度四态（架构 §4.3：健康/降级/异常/未知） */
export type ConnectorHealth = 'healthy' | 'degraded' | 'error' | 'unknown';

/** 连接器生命周期状态（架构 §4.1：注册草稿/灰度/已发布/已退役） */
export type ConnectorStatus = 'draft' | 'gray' | 'published' | 'retired';

/** 连接器摘要（OpenAPI `ConnectorSummary`；能力计数取最近一次探测/声明结果） */
export interface ConnectorSummary {
  /** 连接器 ID（注册表主键，如 `yonyou-nc-mock`） */
  connectorId: string;
  name: string;
  vendor: string;
  vendorVersion: string;
  status: ConnectorStatus;
  health: ConnectorHealth;
  /** 是否 mock-erp 测试替身（P2 W7 仅用友 NC 为 true） */
  mock: boolean;
  /** 能力总数（标准 18 项） */
  capabilityTotal: number;
  supportedCount: number;
  degradedCount: number;
  unsupportedCount: number;
  /** 最近一次探测完成时间（未探测不返回） */
  lastProbeAt?: IsoDateTime;
  /** 当前生效映射版本号（未发布不返回） */
  mappingVersion?: string;
  updatedAt: IsoDateTime;
}

/** 连接器详情（摘要 + 18 项能力探测态；未探测时 `capabilities: []`，不造数） */
export interface ConnectorDetail {
  connector: ConnectorSummary;
  capabilities: CapabilityState[];
  /** v1.3.0（P5 W19）可选：连接器 SDK 声明（声明先于探测、探测覆盖声明） */
  sdk?: ConnectorSdk;
}

/** 探测汇总（三态计数 + drift = 与上一次探测相比状态变化的能力数） */
export interface ProbeSummary {
  supported: number;
  degraded: number;
  unsupported: number;
  drift: number;
}

/** 能力探测日报（OpenAPI `ProbeReport`，P2-025；每日 06:00 + 每次发布后执行） */
export interface ProbeReport {
  probeId: string;
  connectorId: string;
  startedAt: IsoDateTime;
  finishedAt: IsoDateTime;
  /** 18 项能力探测态（顺序 CAP-01…CAP-18） */
  states: CapabilityState[];
  summary: ProbeSummary;
}

/** 最近一次探测 + 近 7 日历史（`latest=null` / `history=[]` 表示无历史，不造数） */
export interface ProbeLatestResult {
  latest: ProbeReport | null;
  /** 近 7 日探测日报（按 startedAt 升序） */
  history: ProbeReport[];
}

/** 探测日报分页结果（按 startedAt 倒序） */
export type ProbeListResult = PageResult<ProbeReport>;

/** 探测历史保留天数（OpenAPI `ProbeLatestResult.history.maxItems`） */
export const PROBE_HISTORY_DAYS = 7;

export type ConnectorListResponse = ApiResponse<ConnectorSummary[]>;
export type ConnectorDetailResponse = ApiResponse<ConnectorDetail>;
export type ProbeResponse = ApiResponse<ProbeReport>;
export type ProbeLatestResponse = ApiResponse<ProbeLatestResult>;
export type ProbeListResponse = ApiResponse<ProbeListResult>;

/* ---- 11.8.4 降级台账（P2-025） ---- */

/**
 * 降级台账条目（OpenAPI `DegradeLedgerItem`）：一条记录 = 「连接器 × 能力 × 替代路径」命中聚合。
 * 降级必须可解释（架构 §8）：`fallback` 与信封 `DegradedInfo.fallback` 同口径。
 */
export interface DegradeLedgerItem {
  id: string;
  connectorId: string;
  capability: CapabilityCode;
  /** 降级原因（人话 + 错误码，不含敏感信息） */
  reason: string;
  fallback: DegradedFallback;
  /** 命中次数（同一聚合维度累计） */
  count: number;
  firstHitAt: IsoDateTime;
  lastHitAt: IsoDateTime;
}

/** 降级台账分页结果（按 lastHitAt 倒序） */
export type DegradeLedgerResult = PageResult<DegradeLedgerItem>;

/** 降级台账查询参数（GET /api/v1/degrade/ledger） */
export interface DegradeLedgerQuery extends PageQuery {
  connectorId?: string;
  /** 起始时间（含，按 lastHitAt 过滤） */
  from?: IsoDateTime;
  /** 结束时间（含，按 lastHitAt 过滤） */
  to?: IsoDateTime;
}

export type DegradeLedgerResponse = ApiResponse<DegradeLedgerResult>;

/* ---- 11.8.5 对账历史（P2-027） ---- */

/** 对账历史分页结果（按 startedAt 倒序；明细走 `GET /recon/report/{runId}`） */
export type ReconRunListResult = PageResult<ReconRunResult>;

export type ReconRunListResponse = ApiResponse<ReconRunListResult>;

/* ============================================================================
 * 11.9 P2 W8 增量（OpenAPI v0.3.3）—— 登录 / 安全方案 / 审计（P2-029 / P2-030 / P2-031）
 *
 * 与 Java DTO 一一对应：`bff/dto/Actor.java` / `LoginRequest.java` / `LoginResult.java` /
 * `AuditEvent.java` 等。**demo 口径**：P2 登录是演示身份（演示账号 + 固定口令 `DEMO_PASSWORD`
 * + 本地演示 JWT），**不是真实 SSO/微信/短信鉴权**，不得把 `mock: true` 身份说成真实鉴权。
 * 401 = 未登录/凭证无效（复用 `E-2001` + `details.reason`）；403 = 无权限（`E-2001` +
 * `details.requiredCapability/role`）；17 类错误码不新增。
 * ========================================================================== */

/** 演示 JWT 有效期（秒）；对应 OpenAPI / README `AUTH_TOKEN_TTL_SECONDS`（v0.3.3） */
export const AUTH_TOKEN_TTL_SECONDS = 7200;

/** 演示登录固定口令（P2 demo；不是真实口令策略/多因子，不得用于生产） */
export const DEMO_PASSWORD = 'demo123';

/** 演示角色（OpenAPI `ActorRole`；与 P1 `Role` 同值，角色 × 数据范围见架构 §14.2） */
export type ActorRole = 'owner' | 'steward' | 'agent' | 'supervisor' | 'it' | 'admin';

/** 演示角色全集（顺序与 OpenAPI `ActorRole.enum` 一致） */
export const ACTOR_ROLES: readonly ActorRole[] = ['owner', 'steward', 'agent', 'supervisor', 'it', 'admin'];

/** 演示主体（OpenAPI `Actor`）：`projectId` 缺省 = 平台级（IT/管理员跨项目）；`mock` 恒 true */
export interface Actor {
  /** 演示账号 ID（如 steward-01） */
  actorId: string;
  role: ActorRole;
  /** 演示显示名（非真实姓名） */
  name: string;
  /** 所属项目（缺省 = 平台级） */
  projectId?: string;
  /** 演示标记（恒 true） */
  mock: boolean;
}

/** 演示账号登记（P2 demo；**不含真实手机号/工号**） */
export interface DemoAccount {
  /** 登录账号（`LoginRequest.account`） */
  account: string;
  role: ActorRole;
  /** 登录后 `Actor.actorId`（P2 与 account 同值） */
  actorId: string;
  /** 演示显示名（非真实姓名） */
  name: string;
  /** 所属项目（缺省 = 平台级：IT/管理员） */
  projectId?: string;
}

/**
 * 6 个演示账号（P2 W8 demo；与 OpenAPI `POST /api/v1/auth/login` description、README §2.1 同口径）。
 * 口令统一 `DEMO_PASSWORD = 'demo123'`；**不是真实账号体系**（不接 SSO/微信/短信，不含真实手机号）。
 */
export const DEMO_ACCOUNTS: readonly DemoAccount[] = [
  { account: 'owner-3-2-1802', role: 'owner', actorId: 'owner-3-2-1802', name: '王女士', projectId: 'P-YQY' },
  { account: 'steward-01', role: 'steward', actorId: 'steward-01', name: '李管家', projectId: 'P-YQY' },
  { account: 'agent-01', role: 'agent', actorId: 'agent-01', name: '陈坐席', projectId: 'P-YQY' },
  { account: 'supervisor-01', role: 'supervisor', actorId: 'supervisor-01', name: '赵主管', projectId: 'P-YQY' },
  { account: 'it-01', role: 'it', actorId: 'it-01', name: 'IT·孙' },
  { account: 'admin-01', role: 'admin', actorId: 'admin-01', name: '管理员·周' },
];

/** 登录入参（OpenAPI `LoginRequest`）：`password` 可缺省（demo 仍按固定口令校验） */
export interface LoginRequest {
  account: string;
  password?: string;
}

/** 登录结果（OpenAPI `LoginResult`）：演示 JWT + 主体；`tokenType` 固定 `Bearer`、`mock` 恒 true */
export interface LoginResult {
  token: string;
  tokenType: 'Bearer';
  /** 过期时间 = 签发时间 + `AUTH_TOKEN_TTL_SECONDS` */
  expiresAt: IsoDateTime;
  actor: Actor;
  /** 演示标记（恒 true） */
  mock: boolean;
}

/** 演示登出结果（OpenAPI `LogoutResult`）：data 内 `ok: true` 表示 token 已撤销（幂等） */
export interface LogoutResult {
  ok: boolean;
}

/** 401 的 `details.reason`（OpenAPI `components/responses/Unauthorized`；demo 复用 `E-2001`） */
export type UnauthorizedReason = 'missing_token' | 'invalid_token' | 'expired_token' | 'invalid_credentials';

/**
 * 403 的 `details.reason`（OpenAPI `components/responses/Forbidden` / `ForbiddenDataScope` /
 * `ForbiddenProjectScope`；demo 复用 `E-2001` 表达能力拒绝、`E-2002` 表达数据范围/项目越权，
 * **不新造错误码**）。
 * - `capability_denied`：角色 × 能力矩阵不通过（`E-2001`）
 * - `out_of_scope`：主体与房号/单据归属不匹配（`E-2002`，v1.1.0 P3 W10 数据范围）
 * - `cross_project`：跨项目访问（`E-2002`，v1.3.0 P5 W19 多项目隔离）
 */
export type ForbiddenReason = 'capability_denied' | 'out_of_scope' | 'cross_project';

export type AuthLoginResponse = ApiResponse<LoginResult>;
export type AuthMeResponse = ApiResponse<Actor>;
export type AuthLogoutResponse = ApiResponse<LogoutResult>;

/* ---- 11.9.2 审计（P2-031，架构 §11.3：谁在何时对什么资源做了什么 + traceId） ---- */

/** 审计动作（OpenAPI `AuditAction`；写操作必须落审计） */
export type AuditAction =
  | 'ticket.create'
  | 'ticket.transition'
  | 'ticket.feedback'
  | 'mapping.publish'
  | 'mapping.rollback'
  | 'connector.probe'
  | 'recon.run'
  | 'owner.verify'
  | 'auth.login'
  | 'auth.logout'
  | 'access.deny'
  | 'complaint.create'
  | 'complaint.transition'
  | 'dunning.script'
  | 'dunning.send'
  | 'dunning.close'
  | 'recon.export'
  // v1.2.0（P4 W15）末尾追加
  | 'knowledge.change'
  | 'knowledge.review'
  | 'copilot.broadcast'
  | 'desk.takeover'
  | 'desk.qa.score'
  | 'fee.catalog.change'
  | 'fee.catalog.review'
  | 'trace.export'
  // v1.3.0（P5 W19）末尾追加
  | 'gray.policy.create'
  | 'gray.policy.update'
  // v1.4.0（P6 W30，知识库 RAG）末尾追加：知识重建索引（仅 it/admin；rag 问答/健康为只读，不落写审计）
  | 'knowledge.reindex';

/** 审计动作全集（顺序与 OpenAPI `AuditAction.enum` 一致） */
export const AUDIT_ACTIONS: readonly AuditAction[] = [
  'ticket.create',
  'ticket.transition',
  'ticket.feedback',
  'mapping.publish',
  'mapping.rollback',
  'connector.probe',
  'recon.run',
  'owner.verify',
  'auth.login',
  'auth.logout',
  'access.deny',
  'complaint.create',
  'complaint.transition',
  'dunning.script',
  'dunning.send',
  'dunning.close',
  'recon.export',
  'knowledge.change',
  'knowledge.review',
  'copilot.broadcast',
  'desk.takeover',
  'desk.qa.score',
  'fee.catalog.change',
  'fee.catalog.review',
  'trace.export',
  'gray.policy.create',
  'gray.policy.update',
  // v1.4.0（P6 W30）末尾追加
  'knowledge.reindex',
];

/** 审计资源类型（OpenAPI `AuditResourceType`；`export` = 导出/下载类动作） */
export type AuditResourceType =
  | 'ticket'
  | 'mapping'
  | 'connector'
  | 'recon'
  | 'owner'
  | 'auth'
  | 'export'
  | 'complaint'
  | 'dunning'
  // v1.2.0（P4 W15）末尾追加
  | 'knowledge'
  | 'fee_catalog'
  | 'conversation'
  | 'trace'
  // v1.3.0（P5 W19）末尾追加
  | 'gray_policy'
  | 'project';

/** 审计资源类型全集（顺序与 OpenAPI `AuditResourceType.enum` 一致） */
export const AUDIT_RESOURCE_TYPES: readonly AuditResourceType[] = [
  'ticket',
  'mapping',
  'connector',
  'recon',
  'owner',
  'auth',
  'export',
  'complaint',
  'dunning',
  'knowledge',
  'fee_catalog',
  'conversation',
  'trace',
  'gray_policy',
  'project',
];

/** 审计结果（OpenAPI `AuditResult`：success 成功 / denied 越权拒绝 / failed 执行失败） */
export type AuditResult = 'success' | 'denied' | 'failed';

/** 审计结果全集（顺序与 OpenAPI `AuditResult.enum` 一致） */
export const AUDIT_RESULTS: readonly AuditResult[] = ['success', 'denied', 'failed'];

/**
 * 审计事件（OpenAPI `AuditEvent`）。只记脱敏信息：`maskedFields` 列已脱敏字段名，
 * **不含手机号/姓名/金额原文**（架构 §11.3）。
 */
export interface AuditEvent {
  eventId: string;
  /** 事件时间 */
  at: IsoDateTime;
  actorId: string;
  actorRole: ActorRole;
  action: AuditAction;
  resourceType: AuditResourceType;
  resourceId: string;
  /** 链路 traceId（与响应信封 traceId 同源） */
  traceId: string;
  result: AuditResult;
  /** 客户端 IP（演示环境可为 127.0.0.1） */
  ip?: string;
  /** 已脱敏字段名清单（如 ownerName / phoneTail） */
  maskedFields?: string[];
}

/** 审计事件分页结果（OpenAPI `AuditEventListResult`，按 `at` 倒序） */
export type AuditEventListResult = PageResult<AuditEvent>;

/** 审计查询参数（GET /api/v1/audit/events；全部可选，`from`/`to` 按 `at` 过滤且含端点） */
export interface AuditEventQuery extends PageQuery {
  actorId?: string;
  action?: AuditAction;
  resourceType?: AuditResourceType;
  resourceId?: string;
  from?: IsoDateTime;
  to?: IsoDateTime;
}

export type AuditEventListResponse = ApiResponse<AuditEventListResult>;
export type AuditEventResponse = ApiResponse<AuditEvent>;

/* ============================================================================
 * 12. P3 W10 增量（OpenAPI v1.1.0）—— 费用 / 催缴 / 投诉 / 数据范围 / 对账导出
 *
 * 与 Java DTO 一一对应：`bff/dto/ArrearsItem.java` / `DunningTask.java` / `Complaint.java` 等。
 * 兼容策略：全部为**新增类型/枚举**，既有 P2 类型仅在末尾追加可选字段（`BillInfo` / `Ticket`）；
 * **17 类错误码一个不新增**：跨房越权复用 `E-2002`（+ `details.reason = out_of_scope`）、
 * 状态/版本冲突复用 `E-3002`、频次红线/幂等冲突复用 `E-3003`、未人工确认 400 `E-1001`。
 * 权限矩阵（角色 × 能力）见 `docs/openapi/README.md` §7.2；owner 数据范围见架构 §14.2。
 * ========================================================================== */

/* ---- 12.1 费用：欠费台账 + 账单解释（P3-002/005/006） ---- */

/** 欠费逾期分层（OpenAPI `ArrearsBucket`）：D1 1—7 天 / D7 8—15 天 / D15 16—30 天 / D30 30 天以上 */
export type ArrearsBucket = 'D1' | 'D7' | 'D15' | 'D30';

/** 欠费分层全集（顺序与 OpenAPI `ArrearsBucket.enum` 一致：D1 → D30） */
export const ARREARS_BUCKETS: readonly ArrearsBucket[] = ['D1', 'D7', 'D15', 'D30'];

/** 欠费分层中文文案（前端不硬编码；与产品 §7.4 分层策略同口径） */
export const ARREARS_BUCKET_LABELS: Readonly<Record<ArrearsBucket, string>> = {
  D1: '逾期 1—7 天',
  D7: '逾期 8—15 天',
  D15: '逾期 16—30 天',
  D30: '逾期 30 天以上',
};

/** 单个欠费分层统计（户数 + 金额合计；ERP 口径，不由前端求和） */
export interface ArrearsBucketStat {
  bucket: ArrearsBucket;
  /** 该分层欠费户数 */
  rooms: number;
  /** 该分层欠费金额合计（元） */
  amount: Money;
}

/** 欠费台账分层统计（`buckets` 顺序固定 `D1 → D7 → D15 → D30`） */
export interface ArrearsSummary {
  buckets: ArrearsBucketStat[];
  /** 当前筛选条件下的欠费户数合计 */
  totalRooms: number;
  /** 当前筛选条件下的欠费金额合计（元） */
  totalAmount: Money;
}

/** 欠费台账行（金额/滞纳金/逾期天数以 ERP 为准；业主姓名只回掩码） */
export interface ArrearsItem {
  roomCode: string;
  /** 业主姓名掩码（如「王**」；不返回手机号/身份证/地址） */
  ownerNameMasked: string;
  /** 账期 `YYYY-MM` */
  period: Period;
  /** 该账期应缴合计 */
  total: Money;
  /** 欠费金额（ERP 口径，含历史欠费） */
  outstanding: Money;
  /** 逾期天数（0 = 未逾期） */
  overdueDays: number;
  bucket: ArrearsBucket;
  /** 滞纳金（ERP 口径；AI 不减免也不承诺减免） */
  lateFee: Money;
  /** 最近一次触达时间（从未触达不返回） */
  lastContactAt?: IsoDateTime;
}

/** 欠费台账分页结果（分页约定与契约 §5 一致；`summary` 为当前筛选条件下的分层统计） */
export interface ArrearsListResult {
  items: ArrearsItem[];
  page: number;
  pageSize: number;
  total: number;
  summary: ArrearsSummary;
}

/** 欠费台账查询参数（`GET /api/v1/fees/arrears`；全部可选） */
export interface ArrearsQuery extends PageQuery {
  /** 楼栋名筛选（如「1栋」）；steward 本片区范围由服务端数据范围叠加 */
  building?: string;
  bucket?: ArrearsBucket;
}

export type ArrearsListResponse = ApiResponse<ArrearsListResult>;

/** 账单解释的单个费项明细（分摊基数 + 计费规则可追溯；金额 ERP 口径） */
export interface BillExplanationLine {
  /** 标准费项编码（PROPERTY_FEE / PUBLIC_ENERGY / PARKING_FEE / LATE_FEE） */
  itemCode: string;
  /** 费项展示名 */
  name: string;
  amount: Money;
  /** 分摊基数 / 计价依据（建筑面积、车位号、公摊电量等） */
  base: string;
  /** 计费规则 / 算式（展示口径） */
  rule: string;
}

/** 与上期账单的差异（`amount` 由 ERP 给出，AI 不重算；`reason` 必须可追溯到 knowledgeRefs） */
export interface BillExplanationDiff {
  /** 与上期差额（= 本期 - 上期；负数为下降） */
  amount: Money;
  reason: string;
}

/** 解释引用的知识条目（可追溯：条目 + 标题 + 版本；无出处不得编造） */
export interface BillExplanationRef {
  /** 知识条目 ID，如 `K-002` */
  knowledgeId: string;
  title: string;
  /** 知识版本，如 `V1.1` */
  version: string;
}

/** 账单解释（公式 + 分摊明细 + 与上期差异 + 知识引用；先解释再提醒缴费） */
export interface BillExplanation {
  roomCode: string;
  /** 账期 `YYYY-MM` */
  period: Period;
  /** 只解释单个费项时回带 */
  itemCode?: string;
  /** 本期应缴合计（ERP 口径） */
  total: Money;
  formula: string;
  breakdown: BillExplanationLine[];
  diffVsPrev?: BillExplanationDiff;
  /** 解释引用的知识条目（至少 1 条；为空表示无出处，服务端应拒答而不是编造） */
  knowledgeRefs: BillExplanationRef[];
  dataAsOf: IsoDateTime;
  mock: boolean;
}

/** 账单解释查询参数（`GET /api/v1/fees/explain`） */
export interface BillExplanationQuery {
  roomCode: string;
  period: Period;
  itemCode?: string;
}

export type BillExplanationResponse = ApiResponse<BillExplanation>;

/* ---- 12.2 催缴任务 / 话术 / 人工确认触达 / 漏斗（P3-011…015） ---- */

/** 催缴任务状态（OpenAPI `DunningStatus`）：待触达 → 话术就绪 → 已触达 → 响应 → 承诺 → 缴费 → 关闭 */
export type DunningStatus =
  | 'pending'
  | 'script_ready'
  | 'sent'
  | 'responded'
  | 'promised'
  | 'paid'
  | 'closed';

/** 催缴任务状态全集（顺序与 OpenAPI `DunningStatus.enum` 一致） */
export const DUNNING_STATUSES: readonly DunningStatus[] = [
  'pending',
  'script_ready',
  'sent',
  'responded',
  'promised',
  'paid',
  'closed',
];

/** 催缴任务状态中文文案（前端不硬编码） */
export const DUNNING_STATUS_LABELS: Readonly<Record<DunningStatus, string>> = {
  pending: '待触达',
  script_ready: '话术待确认',
  sent: '已触达（mock）',
  responded: '业主已响应',
  promised: '已承诺缴费',
  paid: '已缴费',
  closed: '已关闭',
};

/** 催缴触达渠道（OpenAPI `DunningChannel`）；P3 触达走 mock-channel，不接真实企微/短信 */
export type DunningChannel = 'wecom' | 'sms' | 'inapp';

/** 催缴渠道全集（顺序与 OpenAPI `DunningChannel.enum` 一致） */
export const DUNNING_CHANNELS: readonly DunningChannel[] = ['wecom', 'sms', 'inapp'];

/** 催缴渠道中文文案 */
export const DUNNING_CHANNEL_LABELS: Readonly<Record<DunningChannel, string>> = {
  wecom: '企微 1v1',
  sms: '短信',
  inapp: '站内信',
};

/** 频次红线统计窗口（天）：单户每周口径（产品 FR-BILL-04） */
export const DUNNING_FREQUENCY_WINDOW_DAYS = 7;

/** 频次红线外呼上限（单户每窗口 ≤2 次，产品 FR-BILL-04） */
export const DUNNING_FREQUENCY_CALL_LIMIT = 2;

/** 频次红线校验（`allowed=false` 时 `send` 必须 409 `E-3003` + `details.reason=frequency_redline`） */
export interface DunningFrequencyCheck {
  /** 统计窗口天数（单户每周口径 = 7） */
  windowDays: number;
  /** 窗口内已触达次数 */
  used: number;
  /** 窗口内允许上限（外呼 ≤2） */
  limit: number;
  /** 当前是否允许触达 */
  allowed: boolean;
}

/** 催缴任务（隐私：业主姓名只回掩码；`assignedTo` 用岗位名/掩码） */
export interface DunningTask {
  taskId: string;
  roomCode: string;
  ownerNameMasked: string;
  /** 账期 `YYYY-MM` */
  period: Period;
  /** 欠费金额（ERP 口径） */
  outstanding: Money;
  bucket: ArrearsBucket;
  status: DunningStatus;
  channel?: DunningChannel;
  /** 最近一次生成的话术版本（`send` 必须回传；不一致 → 409 `E-3002`） */
  scriptVersion?: string;
  /** 负责人（岗位名/掩码，如「李管家」） */
  assignedTo?: string;
  createdAt: IsoDateTime;
  /** 下一次应触达时间（分层节奏） */
  dueAt?: IsoDateTime;
  lastActionAt?: IsoDateTime;
  frequencyCheck: DunningFrequencyCheck;
}

/** 催缴任务分页结果（分页约定与契约 §5 一致） */
export interface DunningTaskListResult {
  items: DunningTask[];
  page: number;
  pageSize: number;
  total: number;
}

/** 催缴任务列表查询参数（`GET /api/v1/dunning/tasks`；全部可选） */
export interface DunningTaskListQuery extends PageQuery {
  status?: DunningStatus;
  bucket?: ArrearsBucket;
  assignee?: string;
}

export type DunningTaskListResponse = ApiResponse<DunningTaskListResult>;
export type DunningTaskResponse = ApiResponse<DunningTask>;

/** 催缴话术语气（OpenAPI `DunningTone`）：friendly 温和提醒 / firm 明确期限 */
export type DunningTone = 'friendly' | 'firm';

/** 催缴话术语气全集（顺序与 OpenAPI `DunningTone.enum` 一致） */
export const DUNNING_TONES: readonly DunningTone[] = ['friendly', 'firm'];

/** 生成催缴话术入参（字段全部可选；缺省 `tone=friendly`、`channel=wecom`） */
export interface DunningScriptRequest {
  tone?: DunningTone;
  channel?: DunningChannel;
}

/** 催缴话术（生成不等于外发：`forbiddenHits` 非空不得发送，发送前必须人工确认） */
export interface DunningScript {
  /** 话术正文（已脱敏，不含完整手机号/身份证） */
  content: string;
  /** 命中的禁区规则 code 列表（空数组 = 未命中可发送；非空 = 不得发送） */
  forbiddenHits: string[];
  /** 话术版本（如 `v1`）；`send` 必须回传 */
  version: string;
  tone: DunningTone;
  channel: DunningChannel;
}

export type DunningScriptResponse = ApiResponse<DunningScript>;

/** 发送催缴入参（`confirmed` 必须为 true：人工确认后才触达；P3 无自动发送/群发） */
export interface DunningSendRequest {
  /** 待发送话术版本（`DunningScript.version`） */
  scriptVersion: string;
  channel: DunningChannel;
  /** 人工确认标记（必须为 true；缺失/false → 400 `E-1001` + `details.reason=confirmation_required`） */
  confirmed: boolean;
}

/** 发送结果（P3 由 `services/mock-channel` 受理并留痕，`status` 恒为 `mocked`，未真实外发） */
export interface DunningSendResult {
  /** mock-channel 消息 ID */
  pushId: string;
  /** P3 恒为 `mocked`（不得声称已送达业主） */
  status: 'mocked';
  sentAt: IsoDateTime;
  frequencyAfter: DunningFrequencyCheck;
}

export type DunningSendResponse = ApiResponse<DunningSendResult>;

/** 关闭催缴任务入参（`reason` 必填并落审计；只改任务状态，不写财务数据） */
export interface DunningCloseRequest {
  reason: string;
}

/** 关闭结果 */
export interface DunningCloseResult {
  taskId: string;
  status: DunningStatus;
  closedAt: IsoDateTime;
}

export type DunningCloseResponse = ApiResponse<DunningCloseResult>;

/** 漏斗转化率（均为 `分子/分母` 小数，保留 4 位；分母为 0 时记 0） */
export interface DunningFunnelConversion {
  reachedToResponded: number;
  respondedToPromised: number;
  promisedToPaid: number;
  reachedToPaid: number;
}

/** 漏斗统计时间窗（业务日，含端点） */
export interface DunningFunnelPeriod {
  from: IsoDate;
  to: IsoDate;
}

/** 催缴转化漏斗（触达 → 响应 → 承诺 → 缴费，产品 FR-BILL-06） */
export interface DunningFunnel {
  /** 触达户数 */
  reached: number;
  /** 有响应户数 */
  responded: number;
  /** 登记承诺户数 */
  promised: number;
  /** 窗口内缴费户数（以 ERP 实收为准） */
  paid: number;
  conversion: DunningFunnelConversion;
  period: DunningFunnelPeriod;
  generatedAt: IsoDateTime;
}

/** 漏斗查询参数（`GET /api/v1/dunning/funnel`；省略时默认最近 7 天） */
export interface DunningFunnelQuery {
  from?: IsoDate;
  to?: IsoDate;
}

export type DunningFunnelResponse = ApiResponse<DunningFunnel>;

/* ---- 12.3 投诉受理 / 分级 / SLA / 流转 / 统计（P3-021…023） ---- */

/** 投诉级别（OpenAPI `ComplaintLevel`，产品 §8.3）：L1 一级 / L2 二级 / L3 三级 */
export type ComplaintLevel = 'L1' | 'L2' | 'L3';

/** 投诉级别全集（顺序与 OpenAPI `ComplaintLevel.enum` 一致） */
export const COMPLAINT_LEVELS: readonly ComplaintLevel[] = ['L1', 'L2', 'L3'];

/** 投诉级别中文文案 */
export const COMPLAINT_LEVEL_LABELS: Readonly<Record<ComplaintLevel, string>> = {
  L1: '一级（立即接管 + 30 分钟上报）',
  L2: '二级（24 小时首复 + 48 小时方案）',
  L3: '三级（2 小时首响 + 48 小时闭环）',
};

/** 投诉首响 SLA（分钟，与 OpenAPI `Complaint.slaDueAt` 描述同口径） */
export const COMPLAINT_SLA_MINUTES: Readonly<Record<ComplaintLevel, number>> = {
  L1: 30,
  L2: 1440,
  L3: 120,
};

/** 投诉状态（OpenAPI `ComplaintStatus`）：已提交 → 已受理 → 处理中 → 已升级 → 已结案 → 已重开 */
export type ComplaintStatus =
  | 'submitted'
  | 'accepted'
  | 'processing'
  | 'escalated'
  | 'closed'
  | 'reopened';

/** 投诉状态全集（顺序与 OpenAPI `ComplaintStatus.enum` 一致） */
export const COMPLAINT_STATUSES: readonly ComplaintStatus[] = [
  'submitted',
  'accepted',
  'processing',
  'escalated',
  'closed',
  'reopened',
];

/** 投诉状态中文文案 */
export const COMPLAINT_STATUS_LABELS: Readonly<Record<ComplaintStatus, string>> = {
  submitted: '已提交',
  accepted: '已受理',
  processing: '处理中',
  escalated: '已升级',
  closed: '已结案',
  reopened: '已重开',
};

/** 投诉流转动作（OpenAPI `ComplaintTransitionAction`） */
export type ComplaintTransitionAction = 'accept' | 'first_response' | 'escalate' | 'close' | 'reopen';

/** 投诉流转动作全集（顺序与 OpenAPI `ComplaintTransitionAction.enum` 一致） */
export const COMPLAINT_TRANSITION_ACTIONS: readonly ComplaintTransitionAction[] = [
  'accept',
  'first_response',
  'escalate',
  'close',
  'reopen',
];

/** 投诉列表排序（默认 `createdAt_desc`；SLA 视角用 `slaDueAt_asc`） */
export type ComplaintListSort = 'createdAt_desc' | 'slaDueAt_asc';

/** 投诉排序全集（顺序与 OpenAPI `ComplaintListSort.enum` 一致） */
export const COMPLAINT_LIST_SORTS: readonly ComplaintListSort[] = ['createdAt_desc', 'slaDueAt_asc'];

/** 投诉列表默认排序（与 OpenAPI `ComplaintListSort.default` 一致） */
export const DEFAULT_COMPLAINT_LIST_SORT: ComplaintListSort = 'createdAt_desc';

/** 投诉动作留痕（按时间升序） */
export interface ComplaintAuditEntry {
  at: IsoDateTime;
  /** 动作/状态 code（submitted / accept / first_response / escalate / close / reopen / note） */
  action: string;
  /** 操作人（岗位名/掩码；不返回真实手机号/工号） */
  who: string;
  note?: string;
}

/** 投诉单据（`slaDueAt` 为首响 SLA 截止；`version` 为 transition 乐观锁） */
export interface Complaint {
  complaintId: string;
  roomCode: string;
  ownerNameMasked: string;
  /** 投诉分类 code（字典项，如 noise / parking / fee / attitude / public_area / other；P3 不锁死枚举） */
  category: string;
  level: ComplaintLevel;
  status: ComplaintStatus;
  /** 投诉内容（已脱敏） */
  content: string;
  /** 首次响应时间（first_response 后回带；未响应不返回） */
  firstResponseAt?: IsoDateTime;
  /** 首响 SLA 截止（L1 30 分钟 / L2 24 小时 / L3 2 小时） */
  slaDueAt: IsoDateTime;
  /** 升级后受理层级/岗位（escalate 后回带） */
  escalatedTo?: string;
  /** 联动工单号（投诉升级为工单时回带，P3-022；未联动不返回） */
  ticketNo?: string;
  createdAt: IsoDateTime;
  updatedAt: IsoDateTime;
  /** 乐观锁版本号（transition 必须回传） */
  version: number;
  audit: ComplaintAuditEntry[];
}

/** 提交投诉入参（服务端只强制 roomCode + category + content；contactTail4 仅手机号后四位） */
export interface ComplaintCreateRequest {
  roomCode: string;
  category: string;
  level?: ComplaintLevel;
  content: string;
  /** 联系手机号后四位（仅回访用；不接收完整号码，不回传） */
  contactTail4?: string;
  /** 附件数量（P3 只计数，不传文件） */
  attachments?: number;
}

/** 投诉列表查询参数（`GET /api/v1/complaints`；全部可选） */
export interface ComplaintListQuery extends PageQuery {
  status?: ComplaintStatus;
  level?: ComplaintLevel;
  roomCode?: string;
  sort?: ComplaintListSort;
}

/** 投诉分页结果（分页约定与契约 §5 一致） */
export interface ComplaintListResult {
  items: Complaint[];
  page: number;
  pageSize: number;
  total: number;
}

/** 投诉流转入参（`version` 乐观锁必填；不匹配 → 409 `E-3002`） */
export interface ComplaintTransitionRequest {
  action: ComplaintTransitionAction;
  note?: string;
  /** 当前投诉版本号（乐观锁） */
  version: number;
  /**
   * `escalate` 升级目标岗位（v1.2.0 末尾追加可选；省略沿用默认「客服主管」）。
   * 仅在 `action=escalate` 下生效，其它 action 传了忽略（后端不报错，便于前端复用表单）。
   */
  target?: string;
  /**
   * 显式关联工单号（v1.2.0 末尾追加可选；省略按「同房未结案工单」自动关联）。
   * 指向不存在/非本房工单 → 400 `E-1001`。
   */
  ticketNo?: string;
}

/** 投诉统计（`avgFirstResponseMinutes` 无数据为 0；`slaBreachCount` 为首响超 SLA 数） */
export interface ComplaintStats {
  total: number;
  /** 各状态投诉数（键取 `ComplaintStatus`，缺失状态记 0） */
  byStatus: Record<string, number>;
  /** 各级别投诉数（键取 `ComplaintLevel`，缺失级别记 0） */
  byLevel: Record<string, number>;
  /** 平均首响分钟（无数据为 0） */
  avgFirstResponseMinutes: number;
  slaBreachCount: number;
  /** 统计周期（`YYYY-MM` 或 `YYYY-MM-DD~YYYY-MM-DD`） */
  period: string;
}

/** 投诉统计查询参数（`GET /api/v1/complaints/stats`；全部可选） */
export interface ComplaintStatsQuery {
  from?: IsoDate;
  to?: IsoDate;
  level?: ComplaintLevel;
}

export type ComplaintCreateResponse = ApiResponse<Complaint>;
export type ComplaintListResponse = ApiResponse<ComplaintListResult>;
export type ComplaintResponse = ApiResponse<Complaint>;
export type ComplaintStatsResponse = ApiResponse<ComplaintStats>;

/* ---- 12.4 owner 数据范围与自有单能力（P3-028/029 契约前置；架构 §14.2） ---- */

/**
 * owner 自有单专用能力码（OpenAPI `OwnerTicketCapability`）。
 * **不属于 18 项标准 `CapabilityCode`**（后者是 ERP 能力矩阵），这是数据范围内的动作级权限标识，
 * 与 Java `PermissionMatrix` 对齐：owner 可对**自有单**执行 `transition action=reschedule/urge`；
 * staff 仍用 `ticket.assign`；owner 其它动作 → 403 `E-2001`（`details.requiredCapability`）。
 */
export type OwnerTicketCapability = 'ticket.reschedule.own' | 'ticket.urge.own';

/** owner 自有单能力码全集（顺序与 OpenAPI `OwnerTicketCapability.enum` 一致） */
export const OWNER_TICKET_CAPABILITIES: readonly OwnerTicketCapability[] = [
  'ticket.reschedule.own',
  'ticket.urge.own',
];

/** owner 可执行的 transition 动作 → 专用能力码（其余动作 owner 一律 403） */
export const OWNER_TRANSITION_CAPABILITIES: Readonly<
  Record<'reschedule' | 'urge', OwnerTicketCapability>
> = {
  reschedule: 'ticket.reschedule.own',
  urge: 'ticket.urge.own',
};

/** owner 允许的 transition 动作（数据范围内；跨房 → 403 `E-2002` + `details.reason=out_of_scope`） */
export const OWNER_ALLOWED_TRANSITION_ACTIONS: readonly TicketTransitionAction[] = ['reschedule', 'urge'];

/** 数据范围越权的 `details.reason`（403 `E-2002`；与 OpenAPI `components/responses/ForbiddenDataScope` 同口径） */
export const DATA_SCOPE_DENIED_REASON = 'out_of_scope' as const;

/** 数据范围越权的 `details`（`E-2002`；`resourceType`/`resourceId` 为被拒绝的资源） */
export interface DataScopeDeniedDetails {
  reason: typeof DATA_SCOPE_DENIED_REASON;
  resourceType: string;
  resourceId: string;
  roomCode?: string;
}

/**
 * 判断某角色对某工单是否可用该 transition 动作（前端隐藏入口的**辅助**口径，
 * 安全边界仍在服务端：能力矩阵 + 数据范围双校验；架构 §14.2 / README §7.2）。
 */
export function canTransitionTicket(role: ActorRole, action: TicketTransitionAction): boolean {
  if (role === 'owner') {
    return OWNER_ALLOWED_TRANSITION_ACTIONS.includes(action);
  }
  if (role === 'it' || role === 'admin') {
    return false;
  }
  return true;
}

/* ============================================================================
 * 13. P4 W15 增量（OpenAPI v1.2.0）—— 知识 / Copilot 8 工作区 / 坐席工作台 / 全链路回溯
 *     + P3 遗留补项（投诉 escalate 目标 / fee.catalog.* / PushTemplate.on_site）
 * ----------------------------------------------------------------------------
 * 口径（README §6.7 / §7.4）：本段全部类型是 v1.2.0 契约的**唯一 TS 事实源**，
 * 由 `pnpm check:contract` 与 OpenAPI / Java DTO 三方对拍；新增导出不改既有导出。
 * P1 设计口径的 `KnowledgeItem`（含 projectId/keywords/answerable 等演示字段）在
 * v1.2.0 更名为 `KnowledgeDoc`（字段未变、仓内无引用），`KnowledgeItem` 让位给契约形状；
 * `KnowledgeStatus`（draft/reviewing/published/expired/withdrawn）保留给 `KnowledgeDoc`，
 * 契约状态用 `KnowledgeItemStatus`。
 * ========================================================================== */

/** 知识条目状态（v1.2.0 契约枚举；检索只返回 `published` 且在生效期内的条目） */
/**
 * P4 知识状态（v1.2.0 契约枚举）。`published` 生效中 / `expired` 已过期 / `draft` 草稿 /
 * `pending_review` 待审核 / `rejected` 审核驳回。**只读接口只返回 `published` 且在生效期内的条目**；
 * 过期/草稿/待审/驳回一律不进入检索结果（检索不分叉、不编造）。
 * 与 shared P1 设计口径枚举 `KnowledgeStatus`（draft/reviewing/published/expired/withdrawn，用于
 * `KnowledgeDoc` 演示类型）**并列保留**，本枚举是 P4 契约唯一取值来源。
 */
export type KnowledgeItemStatus =
  | 'published'
  | 'expired'
  | 'draft'
  | 'pending_review'
  | 'rejected';

/**
 * 知识变更单状态（P4 v1.2.0）：`pending_review` 待审核 / `approved` 已通过（生效）/ `rejected` 已驳回。
 * 状态机单向：`pending_review → approved | rejected`；同一知识同时只允许一个待审变更单（409 `E-3002`）。
 */
export type KnowledgeChangeStatus =
  | 'pending_review'
  | 'approved'
  | 'rejected';

/**
 * P4 Copilot 8 工作区 ID（v1.2.0 契约枚举，**顺序固定**）：`summary` 工单摘要 / `script` 话术生成 /
 * `portrait` 业主画像 / `digest` 日报摘要 / `group` 群服务 / `knowledge` 知识秒查 / `report` 报表 /
 * `handoff` 转人工交接。与 P1 设计口径 `CopilotWorkspace`（knowledge/script/summary/profile/report/
 * contribute/history/group，前端路由用）的映射见 README §7.4 表；**契约以本枚举为准**。
 */
export type CopilotWorkspaceId =
  | 'summary'
  | 'script'
  | 'portrait'
  | 'digest'
  | 'group'
  | 'knowledge'
  | 'report'
  | 'handoff';

/**
 * 工作区可用状态（P4 v1.2.0）：`available` 已接真实数据 / `degraded` 部分降级（标注 mock 或缺数据）/
 * `planned` 未上线（入口置灰，不得展示编造数据）。前端入口显隐读本状态，**不得页面硬编码**。
 */
export type CopilotWorkspaceStatus =
  | 'available'
  | 'degraded'
  | 'planned';

/**
 * 业主情绪（P4 v1.2.0 话术入参）：`calm` 平静 / `anxious` 焦虑 / `angry` 激动 / `neutral` 未知。
 */
export type CopilotEmotion =
  | 'calm'
  | 'anxious'
  | 'angry'
  | 'neutral';

/**
 * 话术语气（P4 v1.2.0 三版话术）：`gentle` 温和 / `standard` 标准 / `firm` 坚定。
 * 与催缴 `DunningTone`（friendly/firm，催缴专用）**分列**，互不复用。
 */
export type CopilotTone =
  | 'gentle'
  | 'standard'
  | 'firm';

/**
 * 坐席会话状态（P4 v1.2.0）：`waiting` 排队等待 / `taken_over` 已接管 / `closed` 已结束。
 * 状态机：`waiting → taken_over → closed`；同一会话同时只允许一个坐席接管（409 `E-3002`）。
 */
export type DeskConversationStatus =
  | 'waiting'
  | 'taken_over'
  | 'closed';

/**
 * 质检抽样状态（P4 v1.2.0）：`pending` 待评分 / `scored` 已评分。
 */
export type DeskQaSampleStatus =
  | 'pending'
  | 'scored';

/**
 * 审核决定（P4 v1.2.0，知识与费用口径共用）：`approved` 通过 / `rejected` 驳回。
 * **只允许 it/admin 审核**（权限矩阵 README §7.4）；审核写操作必须落审计。
 */
export type ReviewDecision =
  | 'approved'
  | 'rejected';

/**
 * 费项口径变更单状态（P4 v1.2.0）：`pending_review` 待审核 / `approved` 已通过（生效）/ `rejected` 已驳回。
 * 状态机单向 `pending_review → approved | rejected`；通过后新口径生效并落审计 `fee.catalog.review`。
 */
export type FeeCatalogChangeStatus =
  | 'pending_review'
  | 'approved'
  | 'rejected';

/**
 * 推送模板（P4 v1.2.0 首次固化为契约枚举）：`accepted` 接单 / `dispatched` 派单 /
 * `completed` 完工 / `dunning` 催缴（mock-channel 自定义模板）/ `on_site` 上门（**v1.2.0 末尾追加**）。
 * 既有 4 个模板取值 **原样保留**，实际生产值集合不变；`on_site` 用于上门前提醒，仍为 mock 通道留痕。
 */
export type PushTemplate =
  | 'accepted'
  | 'dispatched'
  | 'completed'
  | 'dunning'
  | 'on_site';

/**
 * 8 个工作区 ID（顺序与 OpenAPI `CopilotWorkspaceId` 一致；**v1.2.0 契约口径**）。
 *
 * 与 P1 设计口径的 `COPILOT_WORKSPACES`（8 个工作区元数据：key/fr/title/purpose/path，前端路由用）
 * **并列存在**：本常量是 `GET /copilot/workspaces` 返回的契约 ID 顺序，二者映射见
 * `COPILOT_WORKSPACE_ID_BY_DESIGN_KEY`（knowledge/script/summary/profile/report/contribute/history/group
 * → summary/script/portrait/digest/group/knowledge/report/handoff）。
 */
export const COPILOT_WORKSPACE_IDS: readonly CopilotWorkspaceId[] = [
  'summary',
  'script',
  'portrait',
  'digest',
  'group',
  'knowledge',
  'report',
  'handoff',
];

/** `KnowledgeItemStatus` 取值全集（顺序与 OpenAPI 枚举一致） */
export const KNOWLEDGE_STATUSES: readonly KnowledgeItemStatus[] = [
  'published',
  'expired',
  'draft',
  'pending_review',
  'rejected',
];

/** `KnowledgeChangeStatus` 取值全集（顺序与 OpenAPI 枚举一致） */
export const KNOWLEDGE_CHANGE_STATUSES: readonly KnowledgeChangeStatus[] = [
  'pending_review',
  'approved',
  'rejected',
];

/** `ReviewDecision` 取值全集（顺序与 OpenAPI 枚举一致） */
export const REVIEW_DECISIONS: readonly ReviewDecision[] = [
  'approved',
  'rejected',
];

/** `PushTemplate` 取值全集（顺序与 OpenAPI 枚举一致） */
export const PUSH_TEMPLATES: readonly PushTemplate[] = [
  'accepted',
  'dispatched',
  'completed',
  'dunning',
  'on_site',
];

/** `FeeCatalogChangeStatus` 取值全集（顺序与 OpenAPI 枚举一致） */
export const FEE_CATALOG_CHANGE_STATUSES: readonly FeeCatalogChangeStatus[] = [
  'pending_review',
  'approved',
  'rejected',
];

/** `DeskConversationStatus` 取值全集（顺序与 OpenAPI 枚举一致） */
export const DESK_CONVERSATION_STATUSES: readonly DeskConversationStatus[] = [
  'waiting',
  'taken_over',
  'closed',
];

/** `DeskQaSampleStatus` 取值全集（顺序与 OpenAPI 枚举一致） */
export const DESK_QA_SAMPLE_STATUSES: readonly DeskQaSampleStatus[] = [
  'pending',
  'scored',
];

/** `CopilotEmotion` 取值全集（顺序与 OpenAPI 枚举一致） */
export const COPILOT_EMOTIONS: readonly CopilotEmotion[] = [
  'calm',
  'anxious',
  'angry',
  'neutral',
];

/** `CopilotTone` 取值全集（顺序与 OpenAPI 枚举一致） */
export const COPILOT_TONES: readonly CopilotTone[] = [
  'gentle',
  'standard',
  'firm',
];

/** 质检评分区间（0—100，端点入参校验与前端滑杆共用） */
export const DEFAULT_QA_SCORE_RANGE = { min: 0, max: 100 } as const;

/** 知识检索默认返回条数（`GET /knowledge/search?limit=` 默认值，上限 50） */
export const KNOWLEDGE_SEARCH_DEFAULT_LIMIT = 10;

/** Copilot 工作区固定数量（`GET /copilot/workspaces` 恒 8 项） */
export const COPILOT_WORKSPACE_TOTAL = 8;

/** 证据包导出格式（`GET /trace/{traceId}/evidence?format=`；csv 为文件流例外） */
export type TraceEvidenceFormat = 'json' | 'csv';

/** P1 设计口径工作区 key → v1.2.0 契约 `CopilotWorkspaceId` 映射（前端路由迁移用） */
export const COPILOT_WORKSPACE_ID_BY_DESIGN_KEY: Readonly<Record<CopilotWorkspace, CopilotWorkspaceId>> = {
  knowledge: 'knowledge',
  script: 'script',
  summary: 'summary',
  profile: 'portrait',
  report: 'report',
  contribute: 'knowledge',
  history: 'handoff',
  group: 'group',
};

/**
 * 知识出处（P1 设计口径 `KnowledgeSource` 的契约化，v1.2.0 首次进 OpenAPI）。
 * 引用展示口径「依据 K-018 V1.2 · 装修管理规定 §3.1」：`doc` 必填，`clause`/`page` 可选。
 */
export interface KnowledgeSource {
  /** 制度/文件名称（如「装修管理规定」） */
  doc: string;
  /** 条款位置，如「§3.1」 */
  clause?: string;
  /** 页码/条目号（可选） */
  page?: string;
}

/**
 * 知识条目（P4 v1.2.0 新增独立 schema）。**回答引用四要素** = `knowledgeId` + `version` +
 * `effectiveFrom`/`effectiveTo` + `owner`，引用必须带版本；过期条目不得作为答案依据。
 * 隐私：`owner` 只回岗位/掩码名，不回手机号/工号明文。
 */
export interface KnowledgeItem {
  /** 知识 ID（稳定主键，可回溯到知识库条目） */
  knowledgeId: string;
  /** 条目标题 */
  title: string;
  /** 答案正文（条款原文结构化，不含自由发挥；已脱敏） */
  content: string;
  /** 版本号（引用必须带版本，如 V1.2） */
  version: string;
  /** 生命周期状态（检索只返回 published） */
  status: KnowledgeItemStatus;
  /** 责任人（岗位/掩码名） */
  owner: string;
  /** 生效日 `YYYY-MM-DD` */
  effectiveFrom: IsoDate;
  /** 失效日 `YYYY-MM-DD`（过期降级为「以最新公示为准」并转管家） */
  effectiveTo: IsoDate;
  /** 出处（制度/条款） */
  source: KnowledgeSource;
  /** 分类 */
  category: KnowledgeCategory;
  /** 是否来自 badcase 回流（答错/答不上 → 回流共建） */
  badcase: boolean;
  /** 关联引用（条款原文/附件/关联知识 ID；可空） */
  refs?: string[];
}

/**
 * 知识版本历史项（P4 v1.2.0）。每次 `change` 提交/审核产生一条版本；`submittedAt` 升序由服务端保证，
 * 客户端按 `version` 展示「当前 / 历史」。
 */
export interface KnowledgeVersion {
  /** 知识 ID */
  knowledgeId: string;
  /** 版本号 */
  version: string;
  /** 该版本状态 */
  status: KnowledgeItemStatus;
  /** 生效日 */
  effectiveFrom: IsoDate;
  /** 失效日 */
  effectiveTo: IsoDate;
  /** 提交人（账号/岗位，掩码） */
  submittedBy: string;
  /** 提交时间 */
  submittedAt: IsoDateTime;
  /** 关联变更单 ID（P4 变更审核链路） */
  changeId?: string;
  /** 审核意见（驳回原因/生效说明） */
  reviewNote?: string;
}

/**
 * 知识条目详情（P4 v1.2.0）：`KnowledgeItem` 全字段 + `versions[]` 版本历史。
 * 不做「详情/列表」字段分叉：列表用 `KnowledgeItem`，详情在其上追加 `versions`。
 */
export interface KnowledgeDetail {
  /** 知识 ID */
  knowledgeId: string;
  /** 条目标题 */
  title: string;
  /** 答案正文（已脱敏） */
  content: string;
  /** 当前版本号 */
  version: string;
  /** 当前状态 */
  status: KnowledgeItemStatus;
  /** 责任人（岗位/掩码名） */
  owner: string;
  /** 生效日 */
  effectiveFrom: IsoDate;
  /** 失效日 */
  effectiveTo: IsoDate;
  /** 出处 */
  source: KnowledgeSource;
  /** 分类 */
  category: KnowledgeCategory;
  /** 是否来自 badcase 回流 */
  badcase: boolean;
  /** 关联引用（可空） */
  refs?: string[];
  /** 版本历史（按提交时间升序） */
  versions: KnowledgeVersion[];
}

/**
 * 知识分页结果（分页约定与 README §2 一致）。`status`/`category`/`owner`/生效期过滤由服务端执行，
 * **owner 角色只返回 `published`**（服务端强制，前端隐藏入口不是安全边界）。
 */
export interface KnowledgeListResult {
  /** 知识条目 */
  items: KnowledgeItem[];
  /** 页码（从 1 开始） */
  page: number;
  /** 每页条数（默认 20，上限 100） */
  pageSize: number;
  /** 总条数 */
  total: number;
}

/**
 * 知识版本历史结果（P4 v1.2.0）。版本数量有限（不做分页包装），`total` 便于前端判断是否已全量。
 */
export interface KnowledgeVersionListResult {
  /** 知识 ID */
  knowledgeId: string;
  /** 版本历史（按提交时间升序） */
  items: KnowledgeVersion[];
  /** 版本数 */
  total: number;
}

/**
 * 知识变更单（P4 v1.2.0）。提交后为 `pending_review`，由 it/admin 审核；通过后生成新版本并生效，
 * 驳回需带 `note`。审计口径 `knowledge.change` / `knowledge.review`（资源类型 `knowledge`）。
 */
export interface KnowledgeChange {
  /** 变更单 ID */
  changeId: string;
  /** 知识 ID */
  knowledgeId: string;
  /** 变更单状态 */
  status: KnowledgeChangeStatus;
  /** 提交人（账号/岗位，掩码） */
  submittedBy: string;
  /** 提交时间 */
  submittedAt: IsoDateTime;
  /** 变更原因（审核依据，必读） */
  reason?: string;
  /** 审核人（仅审核后返回） */
  reviewedBy?: string;
  /** 审核时间（仅审核后返回） */
  reviewedAt?: IsoDateTime;
}

/**
 * 知识变更提交入参（P4 v1.2.0）。`version` 不在此入参：服务端按当前版本自动递增并回带新版本号。
 * `effectiveFrom` 必须 ≤ `effectiveTo`（否则 400 `E-1001`）。
 */
export interface KnowledgeChangeRequest {
  /** 新标题（不传沿用原标题） */
  title?: string;
  /** 新正文（必填，条款原文；服务端落库前脱敏） */
  content: string;
  /** 新生效日 */
  effectiveFrom: IsoDate;
  /** 新失效日 */
  effectiveTo: IsoDate;
  /** 变更原因（必填，落审计与版本历史） */
  reason: string;
}

/**
 * 知识变更审核入参（P4 v1.2.0）。`changeId` 必须属于该 `knowledgeId` 且为 `pending_review`，
 * 否则 409 `E-3002`（`details.reason=invalid_transition`）；驳回时 `note` 必填（否则 400 `E-1001`）。
 */
export interface KnowledgeReviewRequest {
  /** 待审核变更单 ID */
  changeId: string;
  /** 审核决定 */
  decision: ReviewDecision;
  /** 审核意见（驳回时必填） */
  note?: string;
}

/**
 * 知识检索命中项（P4 v1.2.0）。**只返回 `published` 且在生效期内的条目**；`cite` 是可直接展示的
 * 引用串（`K-018 V1.2 · 装修管理规定 §3.1`），前端不得自行拼接。
 */
export interface KnowledgeSearchHit {
  /** 知识 ID */
  knowledgeId: string;
  /** 条目标题 */
  title: string;
  /** 版本号 */
  version: string;
  /** 责任人（岗位/掩码名） */
  owner: string;
  /** 生效日 */
  effectiveFrom: IsoDate;
  /** 失效日 */
  effectiveTo: IsoDate;
  /** 相关度（0—1，服务端给出，前端不参与排序） */
  score: number;
  /** 引用串（可直接展示，含 ID/版本/出处） */
  cite: string;
}

/**
 * 知识检索结果（P4 v1.2.0）。无命中返回 `items: []` + 200（不是错误）：AI 必须**拒答并转人工**，
 * 不得编造答案（产品 §7.2 知识秒查红线）；缺口由 `/knowledge/stats` 的 `gaps` 聚合。
 */
export interface KnowledgeSearchResult {
  /** 命中项（按 score 降序） */
  items: KnowledgeSearchHit[];
  /** 原始查询词（回带回显，便于「无命中」反馈） */
  query: string;
}

/**
 * 知识缺口项（P4 v1.2.0）：某主题被问到但未命中知识（`unanswered`）的次数，用于缺口 TopN 运营。
 */
export interface KnowledgeGap {
  /** 缺口主题（意图/问题聚类） */
  topic: string;
  /** 被问次数 */
  asked: number;
  /** 未命中（答不上/拒答）次数 */
  unanswered: number;
}

/**
 * 知识责任人分布项（P4 v1.2.0）：用于「过期/待审知识追到人」。
 */
export interface KnowledgeOwnerStat {
  /** 责任人（岗位/掩码名） */
  owner: string;
  /** 名下知识总数 */
  total: number;
  /** 其中生效中 */
  published: number;
  /** 其中已过期（需责任人复核） */
  expired: number;
}

/**
 * 知识采纳与缺口统计（P4 v1.2.0）。口径：`adoptionRate = adopted / (adopted + rejected)`
 * （无引用时为 0，不编造）；`gaps` 为未命中 TopN；`expiredSoon` = 30 天内到期条目数；
 * `owners` 为责任人分布（过期/待审追到人）。统计读权限 = staff / it / admin（owner 403）。
 */
export interface KnowledgeStats {
  /** 知识总数 */
  total: number;
  /** 各状态条目数（键取 KnowledgeItemStatus，缺失状态记 0） */
  byStatus: Record<string, number>;
  /** 采纳率 = adopted/(adopted+rejected) */
  adoptionRate: number;
  /** 被采纳（引用且未被驳回）次数 */
  adopted: number;
  /** 被驳回（答案不可用/badcase）次数 */
  rejected: number;
  /** 未命中缺口 TopN */
  gaps: KnowledgeGap[];
  /** 30 天内到期条目数 */
  expiredSoon: number;
  /** 责任人分布 */
  owners: KnowledgeOwnerStat[];
}

/**
 * Copilot 工作区元数据项（P4 v1.2.0）。`description` 为一句话职责，供空态/说明文案复用。
 */
export interface CopilotWorkspaceInfo {
  /** 工作区 ID */
  workspaceId: CopilotWorkspaceId;
  /** 工作区名称（中文） */
  name: string;
  /** 可用状态 */
  status: CopilotWorkspaceStatus;
  /** 一句话职责 */
  description: string;
}

/**
 * Copilot 工作区列表（P4 v1.2.0，固定 8 项、顺序与 `CopilotWorkspaceId` 一致，不分页）。
 */
export interface CopilotWorkspaceListResult {
  /** 8 个工作区（顺序固定） */
  items: CopilotWorkspaceInfo[];
  /** 工作区数（恒为 8） */
  total: number;
}

/**
 * 工单摘要入参（P4 v1.2.0）。`ticketNo` 与 `messages` 二选一（都缺 → 400 `E-1001`）：
 * 给 `ticketNo` 由服务端读工单/会话；给 `messages` 直接压摘要。**入参不得含手机号/地址明文**。
 */
export interface CopilotSummaryRequest {
  /** 工单号（读工单与会话上下文） */
  ticketNo?: string;
  /** 会话/群消息原文（已脱敏；与 ticketNo 二选一） */
  messages?: string[];
}

/**
 * 回答引用项（P4 v1.2.0）。四要素 = `knowledgeId` + `version` + `owner` + `title`；
 * **无引用则 AI 必须拒答**（不得编造出处），前端逐条渲染「依据」。
 */
export interface KnowledgeRef {
  /** 知识 ID */
  knowledgeId: string;
  /** 条目标题 */
  title: string;
  /** 版本号 */
  version: string;
  /** 责任人（岗位/掩码名） */
  owner: string;
}

/**
 * 工单摘要结果（P4 v1.2.0）：`summary` 为 5 行摘要（≤5 条）、`todos` 为 3 条待办（≤3 条）；
 * `cites` 为知识引用（可空，空则前端提示「无出处，需人工确认」）。模型调用走 Python 编排层，
 * Java 只编排与落库；`mock: true` 表示演示编排未接真实模型。
 */
export interface CopilotSummary {
  /** 摘要（≤5 行） */
  summary: string[];
  /** 待办（≤3 条） */
  todos: string[];
  /** 知识引用（可空；空则提示无出处） */
  cites: KnowledgeRef[];
  /** 演示编排标记（未接真实模型时为 true） */
  mock: boolean;
}

/**
 * 话术生成入参（P4 v1.2.0）：场景 × 情绪 × 渠道 → 3 版话术 + 禁区扫描。
 * `channel` 复用推送通道枚举（wecom/sms/inapp），**只生成不外发**（触达需人工确认）。
 */
export interface CopilotScriptRequest {
  /** 场景（如「装修噪声」「催缴首响」「投诉安抚」「上门预约」） */
  scenario: string;
  /** 业主情绪（影响语气与开场） */
  emotion?: CopilotEmotion;
  /** 拟用渠道（只影响文案长度/格式，不触达） */
  channel?: TicketPushChannel;
  /** 关联工单号（可选，便于引用上下文） */
  ticketNo?: string;
}

/**
 * 单版话术（P4 v1.2.0）。`forbiddenHits` 为禁区词命中（命中非空时前端必须提示改写，
 * 不得直接复制外发：禁承诺/禁威胁/禁泄露他人信息）。
 */
export interface CopilotScriptVersion {
  /** 语气档位 */
  tone: CopilotTone;
  /** 话术正文（可直接复制） */
  content: string;
  /** 禁区词命中（空数组 = 通过扫描） */
  forbiddenHits: string[];
}

/**
 * 话术生成结果（P4 v1.2.0）：固定 3 版（gentle/standard/firm）。**只生成不外发**；
 * 真实触达必须人工确认（AGENTS.md NEVER #4/#7）。
 */
export interface CopilotScript {
  /** 3 版话术（按 CopilotTone 顺序） */
  versions: CopilotScriptVersion[];
  /** 演示编排标记 */
  mock: boolean;
}

/**
 * 画像服务历史计数（P4 v1.2.0）：只回计数，不回明细/金额，避免泄露欠费与投诉原文。
 */
export interface CopilotPortraitHistory {
  /** 工单数（近 12 个月） */
  tickets: number;
  /** 账单/缴费交互次数 */
  bills: number;
  /** 投诉次数 */
  complaints: number;
}

/**
 * 业主画像（P4 v1.2.0，**脱敏**）：`ownerNameMasked` 只回掩码名；`roomCode` 为归一房号；
 * `tags`/`risks` 为运营标签与沟通禁忌（如「夜间沟通敏感」）；不回手机号/身份证/欠费金额明文。
 * 数据范围：staff 限本项目/本片区（owner 403 `E-2001`），越权拒绝落 `access.deny` 审计。
 */
export interface CopilotPortrait {
  /** 归一房号 */
  roomCode: string;
  /** 业主姓名（掩码） */
  ownerNameMasked: string;
  /** 运营标签 */
  tags: string[];
  /** 服务历史计数 */
  history: CopilotPortraitHistory;
  /** 风险与沟通禁忌 */
  risks: string[];
  /** 演示标记（画像为派生数据） */
  mock: boolean;
}

/**
 * 日报/摘要（P4 v1.2.0）：`summary` 为条目化摘要，`metrics` 为当日指标（键值数字，口径见 README §7.4）。
 * 数字必须来自 API/数据库聚合，**不得前端编造**；无数据日期回空数组与 0 值。
 */
export interface CopilotDigest {
  /** 日报日期 `YYYY-MM-DD` */
  date: IsoDate;
  /** 日报摘要（条目化） */
  summary: string[];
  /** 当日指标（诉求量/工单/投诉/欠费变动/首响均值） */
  metrics: Record<string, number>;
  /** 演示标记 */
  mock: boolean;
}

/**
 * 群服务群发入参（P4 v1.2.0，**人工确认红线**）：`confirmed` 必须为 `true`（否则 400 `E-1001`）；
 * `targetRooms` 省略 = 不指定房号（由服务端按会话群成员解析）。**不做群内自动回复**，
 * 只做人工确认后经 `services/mock-channel` 留痕（`status: mocked`），不触达真实微信。
 */
export interface CopilotBroadcastRequest {
  /** 群发正文（已审核文案；禁止含他人房号/欠费信息） */
  content: string;
  /** 目标房号（可选；省略=按群成员解析） */
  targetRooms?: string[];
  /** 人工确认（必须 true；未确认 400 `E-1001`） */
  confirmed: boolean;
}

/**
 * 群服务群发结果（P4 v1.2.0）：`pushIds` 为 mock 通道受理 ID 列表（可回溯），
 * `status` 恒为 `mocked`（未真实外发），`sentAt` 为受理时间。**不得对客户声称已送达**。
 */
export interface CopilotBroadcastResult {
  /** mock 通道受理 ID 列表 */
  pushIds: string[];
  /** 受理状态（恒为 mocked） */
  status: TicketPushStatus;
  /** 受理时间 */
  sentAt: IsoDateTime;
}

/**
 * 报表分组项（P4 v1.2.0）：按维度（场景/渠道/坐席）分组的计数与占比。
 */
export interface CopilotReportBreakdown {
  /** 分组名（场景/渠道/坐席） */
  name: string;
  /** 计数 */
  value: number;
  /** 占比（0—1，可选） */
  ratio?: number;
}

/**
 * Copilot 报表（P4 v1.2.0）：`metrics` 为周期指标（诉求量/工单/投诉/自助解决率/采纳率），
 * `breakdown` 为分组明细。口径与看板一致（README §7.4），数字全部来自 API。
 */
export interface CopilotReport {
  /** 周期指标（键值数字） */
  metrics: Record<string, number>;
  /** 分组明细（按场景） */
  breakdown: CopilotReportBreakdown[];
  /** 生成时间（数据截止） */
  generatedAt: IsoDateTime;
  /** 演示标记 */
  mock: boolean;
}

/**
 * 坐席队列项（P4 v1.2.0）。`waitingSeconds`/`slaDueAt` 由服务端按 SLA 规则计算，前端只做倒计时展示；
 * `preview` 为脱敏后的最后一句（不含手机号/地址）。
 */
export interface DeskQueueItem {
  /** 会话 ID（可回溯 traceId 关联） */
  conversationId: string;
  /** 归一房号 */
  roomCode: string;
  /** 业主姓名（掩码） */
  ownerNameMasked: string;
  /** 来源通道 */
  channel: TicketPushChannel;
  /** 已等待秒数 */
  waitingSeconds: number;
  /** 首响 SLA 截止时间 */
  slaDueAt: IsoDateTime;
  /** 会话状态 */
  status: DeskConversationStatus;
  /** 最后一句脱敏预览 */
  preview: string;
}

/**
 * 队列汇总（P4 v1.2.0）：`waiting` 排队数 / `avgWaitSeconds` 平均等待秒数 / `slaBreach` 超 SLA 数。
 */
export interface DeskQueueSummary {
  /** 排队会话数 */
  waiting: number;
  /** 平均等待秒数 */
  avgWaitSeconds: number;
  /** 超 SLA 会话数 */
  slaBreach: number;
}

/**
 * 坐席队列（P4 v1.2.0）：`items` 按等待时长降序；`summary` 为顶部指标卡数据。
 * 权限：agent / supervisor（本项目）；steward / owner / it / admin → 403 `E-2001`。
 */
export interface DeskQueue {
  /** 队列项（按 waitingSeconds 降序） */
  items: DeskQueueItem[];
  /** 汇总指标 */
  summary: DeskQueueSummary;
}

/**
 * 接管入参（P4 v1.2.0）：`note` 可选（如「业主重复来电，优先处理」），落审计。
 */
export interface DeskTakeoverRequest {
  /** 接管备注（可选，落审计） */
  note?: string;
}

/**
 * 接管结果（P4 v1.2.0）：返回接管坐席与状态，前端据此切到会话面板。
 */
export interface DeskTakeoverResult {
  /** 会话 ID */
  conversationId: string;
  /** 接管坐席（账号/工号，掩码） */
  agentId: string;
  /** 接管时间 */
  takenAt: IsoDateTime;
  /** 接管后状态 */
  status: DeskConversationStatus;
}

/**
 * 释放结果（P4 v1.2.0）：释放后会话回队列或结束，`status` 回带新状态。
 */
export interface DeskReleaseResult {
  /** 会话 ID */
  conversationId: string;
  /** 释放时间 */
  releasedAt: IsoDateTime;
  /** 释放后状态（waiting=回队列 / closed=结束） */
  status: DeskConversationStatus;
}

/**
 * SLA 分时项（P4 v1.2.0）：按小时聚合的首响均值与超时数（0—23 时）。
 */
export interface DeskSlaHour {
  /** 小时（0—23） */
  hour: number;
  /** 该小时平均首响秒数 */
  avgFirstResponseSeconds: number;
  /** 该小时超 SLA 数 */
  breaches: number;
}

/**
 * 坐席 SLA（P4 v1.2.0）：`avgFirstResponseSeconds` 平均首响秒数、`breachRate` 超时率（0—1）、
 * `byHour[]` 分时明细。口径：首响 = 坐席第一条人工回复；SLA 超时以 `slaDueAt` 判定。
 */
export interface DeskSla {
  /** 平均首响秒数 */
  avgFirstResponseSeconds: number;
  /** SLA 超时率（0—1） */
  breachRate: number;
  /** 分时明细（0—23 时，缺数据小时记 0） */
  byHour: DeskSlaHour[];
}

/**
 * 质检抽样项（P4 v1.2.0）。规则抽样（SLA 超时/投诉升级/低分回访优先），`score` 为 0—100，
 * `pending` 时不返回 `score`；评分后 `status=scored`。
 */
export interface DeskQaSample {
  /** 抽样 ID */
  sampleId: string;
  /** 会话 ID */
  conversationId: string;
  /** 被抽检坐席（掩码） */
  agentId: string;
  /** 质检得分 0—100（pending 不返回） */
  score?: number;
  /** 状态 */
  status: DeskQaSampleStatus;
}

/**
 * 质检抽样分页结果（P4 v1.2.0，分页约定同 README §2）。
 */
export interface DeskQaSampleListResult {
  /** 抽样项 */
  items: DeskQaSample[];
  /** 页码 */
  page: number;
  /** 每页条数 */
  pageSize: number;
  /** 总条数 */
  total: number;
}

/**
 * 质检评分入参（P4 v1.2.0）：`score` 0—100 整数（超范围/非整数 → 400 `E-1001`）；`note` 可选。
 * 评分是写操作，落审计 `desk.qa.score`（资源类型 `conversation`）。
 */
export interface DeskQaScoreRequest {
  /** 质检得分（0—100 整数） */
  score: number;
  /** 评分说明/扣分项（可选） */
  note?: string;
}

/**
 * 坐席质检分布项（P4 v1.2.0）：用于主管看「谁需要辅导」。
 */
export interface DeskQaAgentStat {
  /** 坐席（掩码） */
  agentId: string;
  /** 抽检数 */
  samples: number;
  /** 平均得分（0—100） */
  avgScore: number;
}

/**
 * 质检统计（P4 v1.2.0）：`samples` 抽样数、`scored` 已评分数、`avgScore` 平均分（0—100，
 * 无评分记 0）、`byAgent[]` 坐席分布。权限：supervisor / it / admin（agent 只能提交自己的评分）。
 */
export interface DeskQaStats {
  /** 抽样数 */
  samples: number;
  /** 已评分数 */
  scored: number;
  /** 平均分（0—100） */
  avgScore: number;
  /** 坐席分布 */
  byAgent: DeskQaAgentStat[];
}

/**
 * 回溯时间线节点（P4 v1.2.0）。一条 trace 由多个节点组成（AI 路由/工具调用/写操作/拒绝/推送），
 * `role` 复用 `ActorRole`，`result` 复用 `AuditResult`（success/denied/failed）；
 * `durationMs` 为节点耗时（可选，非关键节点不返回）。
 */
export interface TraceEntry {
  /** 事件时间 */
  at: IsoDateTime;
  /** 操作主体（账号/工号，掩码） */
  actorId: string;
  /** 主体角色 */
  role: ActorRole;
  /** 动作（与审计 action 同口径，如 ticket.create / knowledge.search） */
  action: string;
  /** 资源类型（ticket/bill/knowledge/conversation/export…） */
  resourceType: string;
  /** 资源 ID */
  resourceId: string;
  /** 结果 */
  result: AuditResult;
  /** 节点耗时（毫秒，可选） */
  durationMs?: number;
}

/**
 * 全链路回溯详情（P4 v1.2.0）：`entries[]` 按时间升序；`firstAt`/`lastAt` 为链路首尾时间，
 * 前端可直接渲染时间线/瀑布。`mock` 表示含演示数据（P4 仍以进程内审计为源）。
 */
export interface TraceDetail {
  /** 链路 ID（贯穿 AI/工具/写操作/推送） */
  traceId: string;
  /** 时间线节点（按 at 升序） */
  entries: TraceEntry[];
  /** 链路首个节点时间 */
  firstAt: IsoDateTime;
  /** 链路最后一个节点时间 */
  lastAt: IsoDateTime;
  /** 演示标记（含演示数据时为 true） */
  mock?: boolean;
}

/**
 * 证据包（P4 v1.2.0，JSON 形态）。**导出前必须脱敏**：`maskedFields` 列出已脱敏字段，
 * `checksum` 为内容校验和（SHA-256 前 16 位，便于举证一致性）。
 * 同端点 `format=csv` 时 2xx 为 `text/csv` 文件流（**非 JSON 信封**，契约例外见 README §6.2）。
 */
export interface TraceEvidence {
  /** 链路 ID */
  traceId: string;
  /** 生成时间 */
  generatedAt: IsoDateTime;
  /** 内容校验和（SHA-256 前 16 位） */
  checksum: string;
  /** 证据节点（与时间线一致） */
  entries: TraceEntry[];
  /** 已脱敏字段清单 */
  maskedFields: string[];
  /** 演示标记 */
  mock: boolean;
}

/**
 * 回溯检索项（P4 v1.2.0）：每个 `traceId` 一行，`entries` 为节点数（不展开节点内容，避免大响应）。
 */
export interface TraceSearchItem {
  /** 链路 ID */
  traceId: string;
  /** 首个节点时间 */
  firstAt: IsoDateTime;
  /** 最后一个节点时间 */
  lastAt: IsoDateTime;
  /** 主要操作主体（掩码） */
  actorId: string;
  /** 首个动作（与审计 action 同口径） */
  action: string;
  /** 节点数 */
  entries: number;
}

/**
 * 回溯检索结果（P4 v1.2.0，分页约定同 README §2）。筛选条件全部可选，按 `lastAt` 降序。
 */
export interface TraceSearchResult {
  /** 命中链路 */
  items: TraceSearchItem[];
  /** 页码 */
  page: number;
  /** 每页条数 */
  pageSize: number;
  /** 总条数 */
  total: number;
}

/**
 * 费项口径变更单（P4 v1.2.0，P3-032 服务层 `FeeCatalogChangeService` 的 REST 契约化）。
 * **不改已有账单**：只对后续计费生效；审计专用动作 `fee.catalog.change`/`fee.catalog.review`
 * （资源类型 `fee_catalog`），不再复用 `mapping.publish`。
 */
export interface FeeCatalogChange {
  /** 变更单 ID */
  changeId: string;
  /** 费项编码 */
  feeItemCode: string;
  /** 新计费公式（口径原文，服务端做语法校验） */
  newFormula: string;
  /** 变更原因（必填，落审计） */
  reason: string;
  /** 变更单状态 */
  status: FeeCatalogChangeStatus;
  /** 提交人（账号/岗位，掩码） */
  submittedBy: string;
  /** 审核人（仅审核后返回） */
  reviewedBy?: string;
}

/**
 * 费项口径变更提交入参（P4 v1.2.0）：`newFormula`/`reason` 必填；提交后 `pending_review`，
 * 只有 it/admin 审核通过才生效（**不直接改计费**，避免误改账单）。
 */
export interface FeeCatalogChangeRequest {
  /** 费项编码（字典项） */
  feeItemCode: string;
  /** 新计费公式 */
  newFormula: string;
  /** 变更原因 */
  reason: string;
}

/**
 * 费项口径变更审核入参（P4 v1.2.0）：仅 it/admin；驳回需 `note`（否则 400 `E-1001`）。
 */
export interface FeeCatalogChangeReviewRequest {
  /** 审核决定 */
  decision: ReviewDecision;
  /** 审核意见（驳回时必填） */
  note?: string;
}

/**
 * 费项口径变更分页结果（P4 v1.2.0，分页约定同 README §2）。
 */
export interface FeeCatalogChangeListResult {
  /** 变更单 */
  items: FeeCatalogChange[];
  /** 页码 */
  page: number;
  /** 每页条数 */
  pageSize: number;
  /** 总条数 */
  total: number;
}

export type KnowledgeListResponse = ApiResponse<KnowledgeListResult>;
export type KnowledgeDetailResponse = ApiResponse<KnowledgeDetail>;
export type KnowledgeVersionListResponse = ApiResponse<KnowledgeVersionListResult>;
export type KnowledgeChangeResponse = ApiResponse<KnowledgeChange>;
export type KnowledgeSearchResponse = ApiResponse<KnowledgeSearchResult>;
export type KnowledgeStatsResponse = ApiResponse<KnowledgeStats>;
export type CopilotWorkspaceListResponse = ApiResponse<CopilotWorkspaceListResult>;
export type CopilotSummaryResponse = ApiResponse<CopilotSummary>;
export type CopilotScriptResponse = ApiResponse<CopilotScript>;
export type CopilotPortraitResponse = ApiResponse<CopilotPortrait>;
export type CopilotDigestResponse = ApiResponse<CopilotDigest>;
export type CopilotBroadcastResponse = ApiResponse<CopilotBroadcastResult>;
export type CopilotReportResponse = ApiResponse<CopilotReport>;
export type DeskQueueResponse = ApiResponse<DeskQueue>;
export type DeskTakeoverResponse = ApiResponse<DeskTakeoverResult>;
export type DeskReleaseResponse = ApiResponse<DeskReleaseResult>;
export type DeskSlaResponse = ApiResponse<DeskSla>;
export type DeskQaSamplesResponse = ApiResponse<DeskQaSampleListResult>;
export type DeskQaScoreResponse = ApiResponse<DeskQaSample>;
export type DeskQaStatsResponse = ApiResponse<DeskQaStats>;
export type TraceDetailResponse = ApiResponse<TraceDetail>;
export type TraceEvidenceResponse = ApiResponse<TraceEvidence>;
export type TraceSearchResponse = ApiResponse<TraceSearchResult>;
export type FeeCatalogChangeListResponse = ApiResponse<FeeCatalogChangeListResult>;
export type FeeCatalogChangeResponse = ApiResponse<FeeCatalogChange>;

/* ============================================================================
 * 14. P5 W19 增量（OpenAPI v1.3.0）—— 多项目隔离 / 连接器 SDK / 映射中心多连接器 /
 *     灰度与双跑 / 事件契约（outbox）
 * ----------------------------------------------------------------------------
 * 口径（README §1.6 / §6.9 / §7.6）：本段全部类型是 v1.3.0 契约的**唯一 TS 事实源**，
 * 由 `pnpm check:contract`（§21 P5 断言）与 OpenAPI / Java DTO 三方对拍；新增导出不改既有导出。
 * 边界：项目 / 灰度 / 事件为**契约级演示口径**（`mock: true` / demo 数据），不接真实组织架构与真实
 * Kafka（P5 W21—W22）；真实 ERP / 微信 / 短信 / 支付仍未接入（AGENTS.md NEVER #4/#11）。
 * 兼容：v1.2.0 既有导出一个未删；仅 `ConnectorDetail.sdk?` / `MappingRollbackRequest.connectorId?`
 * 末尾追加可选字段（P2/P3/P4 调用方无感）。
 * ========================================================================== */

/**
 * 项目生命周期状态（v1.3.0 契约枚举）：`active` 正常运营 / `gray` 灰度中（新连接器按比例分流）/
 * `archived` 已归档（只读）。与连接器 `ConnectorStatus`（draft/gray/published/retired）**分列**，不复用。
 */
export type ProjectStatus = 'active' | 'gray' | 'archived';

/** 项目状态全集（顺序与 OpenAPI `ProjectStatus.enum` 一致） */
export const PROJECT_STATUSES: readonly ProjectStatus[] = ['active', 'gray', 'archived'];

/** 项目 ID（契约 `Project.projectId` 稳定主键，如 `P-YQY`；演示 3 项目见 `DEMO_PROJECTS`） */
export type ProjectId = string;

/**
 * 项目（v1.3.0，多项目隔离资源，架构 §24.7）：`projectId` 稳定主键；`roomCount` 房档数量（ERP 口径，
 * BFF 不自算）；`connectorId` 项目当前主连接器（灰度期可为新连接器）。**演示数据，非真实组织架构**。
 */
export interface Project {
  projectId: string;
  name: string;
  status: ProjectStatus;
  roomCount: number;
  connectorId: string;
  createdAt: IsoDateTime;
}

/**
 * 演示项目（3 个，与 OpenAPI `Project` 示例 / README §7.6 一致）：
 * `P-YQY` 云栖苑 / `P-JZY` 金竹苑 / `P-LVY` 绿苑。**演示数据，不是真实组织架构/租户**（AGENTS.md NEVER #11）。
 */
export const DEMO_PROJECTS: readonly Project[] = [
  {
    projectId: 'P-YQY',
    name: '云栖苑',
    status: 'active',
    roomCount: 20,
    connectorId: 'yonyou-nc-mock',
    createdAt: '2026-09-01T09:00:00+08:00',
  },
  {
    projectId: 'P-JZY',
    name: '金竹苑',
    status: 'gray',
    roomCount: 18,
    connectorId: 'kingdee-eas',
    createdAt: '2026-10-15T09:00:00+08:00',
  },
  {
    projectId: 'P-LVY',
    name: '绿苑',
    status: 'active',
    roomCount: 16,
    connectorId: 'yonyou-nc-mock',
    createdAt: '2026-11-01T09:00:00+08:00',
  },
];

/** 连接器 SDK 运行限制（v1.3.0）：超时 / 分页上限 / 每分钟限流，由连接器运行时强制 */
export interface ConnectorSdkLimits {
  /** 单次调用超时（毫秒，100—120000） */
  timeoutMs: number;
  /** 分页上限（条，1—1000） */
  pageSizeMax: number;
  /** 每分钟限流（次/连接器） */
  rateLimitPerMin: number;
}

/**
 * 连接器 SDK 声明（v1.3.0，架构 §24.4）：`sdkVersion` 语义化版本、声明的标准能力（⊆ 18 项
 * `CapabilityCode`）、映射字段数、运行限制。**声明先于探测、探测覆盖声明**（以 `/probe` 为准）。
 */
export interface ConnectorSdk {
  sdkVersion: string;
  connectorId: string;
  capabilities: CapabilityCode[];
  fieldCount: number;
  limits: ConnectorSdkLimits;
}

/**
 * 灰度作用域（v1.3.0）：`connector` 按连接器整体分流 / `capability` 按单能力分流。
 * 与 `AuditResourceType` 分列（灰度策略资源类型为 `gray_policy`）。
 */
export type GrayScope = 'connector' | 'capability';

/** 灰度作用域全集（顺序与 OpenAPI `GrayScope.enum` 一致） */
export const GRAY_SCOPES: readonly GrayScope[] = ['connector', 'capability'];

/** 灰度比例区间（0—100，单位 %；入参与滑杆共用，禁止前端硬编码边界） */
export const GRAY_PERCENT_RANGE = { min: 0, max: 100 } as const;

/**
 * 灰度策略（v1.3.0，架构 §24.4）：`targetId` 在 `scope=connector` 时为连接器 ID、`scope=capability`
 * 时为 `CapabilityCode`；`percent` 为新版本流量比例（0 = 全量旧版，100 = 全量新版）；
 * `enabled=false` 暂停策略（等价全量回旧版，回滚 ≤5 分钟）。同一 `scope`+`targetId` 唯一。
 */
export interface GrayPolicy {
  policyId: string;
  scope: GrayScope;
  targetId: string;
  percent: number;
  enabled: boolean;
  createdAt: IsoDateTime;
  updatedAt: IsoDateTime;
}

/** 创建灰度策略入参（v1.3.0，`percent` 0—100；重复 scope+targetId → 409 `E-3002`） */
export interface GrayPolicyCreateRequest {
  scope: GrayScope;
  targetId: string;
  percent: number;
  enabled: boolean;
}

/** 更新灰度策略入参（v1.3.0，**至少一项**；`enabled=false` 即暂停/回滚） */
export interface GrayPolicyUpdateRequest {
  percent?: number;
  enabled?: boolean;
}

/** 单能力双跑比对项（v1.3.0）：`diffRate = diffCount / checked`；只读比对，不含敏感信息 */
export interface GrayCompareItem {
  capability: CapabilityCode;
  oldConnector: string;
  newConnector: string;
  checked: number;
  matched: number;
  diffCount: number;
  diffRate: number;
}

/** 灰度双跑比对结果（v1.3.0）：逐能力差异 + 生成时间；差异只作发布决策证据 */
export interface GrayCompareResult {
  items: GrayCompareItem[];
  generatedAt: IsoDateTime;
}

/**
 * 事件类型（v1.3.0，outbox / 事件总线口径，**顺序固定**）：建单 / 工单流转 / 回访落库 / 投诉受理 /
 * 催缴触达 / 知识变更生效 / 对账完成 / 灰度策略变更。新增事件类型属**向后兼容增量**（末尾追加）。
 */
export type EventType =
  | 'ticket.created'
  | 'ticket.transitioned'
  | 'ticket.feedback'
  | 'complaint.created'
  | 'dunning.sent'
  | 'knowledge.changed'
  | 'recon.completed'
  | 'gray.policy.updated';

/** 事件类型全集（顺序与 OpenAPI `EventType.enum` 一致） */
export const EVENT_TYPES: readonly EventType[] = [
  'ticket.created',
  'ticket.transitioned',
  'ticket.feedback',
  'complaint.created',
  'dunning.sent',
  'knowledge.changed',
  'recon.completed',
  'gray.policy.updated',
];

/**
 * 事件投递状态（v1.3.0，架构 §24.6）：`pending` 待投递 / `published` 已投递 / `failed` 投递失败
 * （可重试）/ `dead` 死信队列（超重试上限，运维待办，**不静默丢失**）。
 */
export type EventStatus = 'pending' | 'published' | 'failed' | 'dead';

/** 事件投递状态全集（顺序与 OpenAPI `EventStatus.enum` 一致） */
export const EVENT_STATUSES: readonly EventStatus[] = ['pending', 'published', 'failed', 'dead'];

/**
 * 事件信封（v1.3.0）：`payload` 为脱敏业务载荷（不含真实手机号/证件号）；至少一次投递 + 消费幂等 +
 * 死信队列；`dead` 事件必须可查、可重放（架构 §24.6）。
 */
export interface EventEnvelope {
  eventId: string;
  type: EventType;
  projectId: string;
  payload: Record<string, unknown>;
  occurredAt: IsoDateTime;
  status: EventStatus;
}

/** 事件分页结果（v1.3.0，按 `occurredAt` 倒序；分页约定与其他列表一致） */
export interface EventListResult {
  items: EventEnvelope[];
  page: number;
  pageSize: number;
  total: number;
}

/**
 * 映射发布请求（v1.3.0，**可选** body）：`connectorId` 为连接器作用域（省略 = 映射自身 `connectorId`；
 * 不匹配 → 403 `E-2002` `cross_connector`）。省略 body 时与 v0.3.2 行为完全一致。
 */
export interface MappingPublishRequest {
  connectorId?: string;
}

/* ---- P5 v1.3.0 响应信封别名（统一信封 `ApiResponse<T>`；小集合 `data` 直接是数组） ---- */

export type ProjectListResponse = ApiResponse<Project[]>;
export type ProjectResponse = ApiResponse<Project>;
export type ConnectorSdkResponse = ApiResponse<ConnectorSdk>;
export type GrayPolicyListResponse = ApiResponse<GrayPolicy[]>;
export type GrayPolicyResponse = ApiResponse<GrayPolicy>;
export type GrayCompareResponse = ApiResponse<GrayCompareResult>;
export type EventListResponse = ApiResponse<EventListResult>;

/* ============================================================================
 * 10. P5 v1.3.1 增量（P5 W24 冻结前收口）：可观测性 observability 指标 / 健康
 * ========================================================================== */

/**
 * 可观测性指标快照（v1.3.1，`GET /api/v1/observability/metrics`；权限 `observability.read` 仅 it/admin）。
 *
 * 口径（契约 `ObservabilityMetrics`，Java `bff/dto/ObservabilityMetrics`）：
 * - `requests`：进程内 HTTP 响应计数（含 401/403/5xx；重启清零）；`byStatus` 键 = 状态码字符串；
 * - `errors.total` = 状态 ≥400 的响应数；`errors.rate` = total / requests.total（4 位小数，无请求时 0）；
 * - `connectors`：**仅已接入 mock 测试替身（SPI 实现存在）**的连接器；`health` = `up|down|unknown`
 *   （从未调用 = unknown）；`lastProbeAt` 仅探测过的连接器有值（未探测为 null / 省略，不伪造）；
 *   `cacheHitRate` 取只读缓存全局命中率（当前未按连接器分桶，同值）；
 * - `events`：进程内 outbox 状态计数（demo 无 dispatcher，未投递一律 pending，不伪造 published）。
 *
 * 隐私：不含房号 / 手机号 / 姓名 / 请求路径，只有计数与连接器 ID（AGENTS.md NEVER #8）。
 */
export interface ObservabilityMetrics {
  uptimeSeconds: number;
  requests: { total: number; byStatus: Record<string, number> };
  errors: { total: number; rate: number };
  connectors: Array<{ connectorId: string; health: 'up' | 'down' | 'unknown'; lastProbeAt?: IsoDateTime | null; cacheHitRate: number }>;
  cache: { hits: number; misses: number; hitRate: number };
  events: { pending: number; published: number; failed: number; dead: number };
  generatedAt: IsoDateTime;
}

/**
 * 可观测性单组件健康（v1.3.1）：`name` = `erp` / `erp.telemetry` / `connector:<id>` / `channel` /
 * `cache` / `event`；`status` = `up|down|unknown|disabled|degraded`（`disabled` = 该能力被配置关闭）；
 * `detail` = 人话说明（只含计数、连接器 ID 与配置项，不含业主隐私）。
 */
export interface ObservabilityHealthComponent {
  name: string;
  status: 'up' | 'down' | 'unknown' | 'disabled' | 'degraded';
  detail: string;
}

/**
 * 可观测性组件健康汇总（v1.3.1，`GET /api/v1/observability/health`；权限 `observability.read` 仅 it/admin）。
 * `status` = `ok`（全部 up）/ `degraded`（存在 down/unknown/disabled/degraded）；`degradations[]` 必须显式
 * 写明降级原因与 demo 边界（进程内计数、无 Micrometer/Prometheus 导出；不得声称是生产监控，NEVER #11）。
 */
export interface ObservabilityHealth {
  status: 'ok' | 'degraded';
  components: ObservabilityHealthComponent[];
  degradations: string[];
  generatedAt: IsoDateTime;
}

/* ---- P5 v1.3.1 响应信封别名（统一信封 `ApiResponse<T>`） ---- */

export type ObservabilityMetricsResponse = ApiResponse<ObservabilityMetrics>;
export type ObservabilityHealthResponse = ApiResponse<ObservabilityHealth>;

/* ============================================================================
 * 11. P6 v1.4.0 增量（P6 W30 知识库 RAG）：问答 / 来源 / 拒答 / 健康 / 重建索引
 * ========================================================================== */

/**
 * RAG 链路模式（v1.4.0，OpenAPI `RagMode`；`RagQueryResult.mode`）。
 * - `generative` 标准生成（LLM 生成 + 引用校验）
 * - `retrieval_only` 仅检索模式（无 LLM Key / 生成超时降级：原文片段 + `[n]` 编号，前端徽标「仅检索模式」）
 * - `rules` 规则检索兜底（沿用 `GET /knowledge/search`）
 * **不得把 `retrieval_only` / `rules` 说成生成答案**（AGENTS.md NEVER #11）。
 */
export type RagMode =
  | 'generative'
  | 'retrieval_only'
  | 'rules';

/** RAG 模式全集（顺序与 OpenAPI `RagMode.enum` 一致） */
export const RAG_MODES: readonly RagMode[] = ['generative', 'retrieval_only', 'rules'];

/**
 * 拒答原因（v1.4.0，OpenAPI `RagRefusalReason`；`RagQueryResult.refusalReason`）。
 * `no_evidence` 召回为空 / `low_confidence` 最高分低于阈值 / `citation_invalid` 引用越界或缺失 /
 * `out_of_scope` 越权或跨项目。拒答一律**转人工（handoff）**，不编造（错误码口径 E-6002）。
 */
export type RagRefusalReason =
  | 'no_evidence'
  | 'low_confidence'
  | 'citation_invalid'
  | 'out_of_scope';

/** 拒答原因全集（顺序与 OpenAPI `RagRefusalReason.enum` 一致） */
export const RAG_REFUSAL_REASONS: readonly RagRefusalReason[] = [
  'no_evidence',
  'low_confidence',
  'citation_invalid',
  'out_of_scope',
];

/**
 * RAG `topK` 取值范围（v1.4.0，OpenAPI `RagQueryRequest.topK`：1—10，缺省 5）。
 * 前端只做提示与滑杆边界，**服务端强制校验**（越界 400 `E-1001`）。
 */
export const RAG_TOP_K_RANGE = { min: 1, max: 10 } as const;

/** RAG `topK` 缺省值（OpenAPI `RagQueryRequest.topK.default = 5`） */
export const RAG_DEFAULT_TOP_K = 5;

/**
 * 知识问答请求（v1.4.0，OpenAPI `RagQueryRequest` / Java `bff/dto/RagQueryRequest`）：
 * `question` 必填 1—500 字符；`projectId` / `category` 为可选作用域过滤（服务端强制，前端传参不是安全边界）；
 * `topK` 1—10（默认 5）。
 */
export interface RagQueryRequest {
  question: string;
  projectId?: string;
  category?: KnowledgeCategory;
  topK?: number;
}

/**
 * RAG 来源卡片（v1.4.0，OpenAPI `RagSource`）。**答案中的 `[n]` 引用必须 ⊆ `sources[].index`**：
 * `index` 为上下文编号（1 起），`knowledgeId`/`version` 保证引用可追溯到知识版本；
 * `snippet` 为脱敏后的入选片段（非全文）；`score` 为服务端给的相似度展示分（前端不参与排序）。
 */
export interface RagSource {
  index: number;
  knowledgeId: string;
  title: string;
  version: string;
  owner: string;
  category: KnowledgeCategory;
  score: number;
  snippet: string;
}

/**
 * RAG 用量（v1.4.0，OpenAPI `RagUsage`，**全部字段可选**）：只用于成本观测与评测，不参与业务判定；
 * 无 LLM 调用（`mode=retrieval_only|rules`）时可省略或仅给 `rerankCalls=0`。
 */
export interface RagUsage {
  promptTokens?: number;
  completionTokens?: number;
  rerankCalls?: number;
}

/**
 * RAG 问答结果（v1.4.0，OpenAPI `RagQueryResult` / Java `bff/dto/RagQueryResult`）：
 * - **引用红线**：`answer` 中的 `[n]` 编号必须 ⊆ `sources[].index`，否则 `citation_invalid` 拒答；
 * - **拒答不是错误**：`answerable=false` 仍 200，`refusalReason` 说明原因并**转人工（handoff）**；
 * - `mode`/`reranked` 必须如实反映实际链路（重排失败 = `false` 原序透传）。
 */
export interface RagQueryResult {
  answer: string;
  sources: RagSource[];
  confidence: number;
  answerable: boolean;
  refusalReason?: RagRefusalReason;
  mode: RagMode;
  reranked: boolean;
  degraded?: DegradedInfo;
  usage?: RagUsage;
  traceId: string;
}

/**
 * RAG 单组件健康（v1.4.0，OpenAPI `RagComponent`）：`name` = `orchestrator` / `llm` / `embedding` /
 * `vectorStore` / `reranker`；`status` = `ok|degraded|down`；`detail` 只含模型名/计数/开关（不含密钥与内网地址）。
 */
export interface RagComponent {
  name: string;
  status: 'ok' | 'degraded' | 'down';
  detail: string;
}

/**
 * RAG 编排健康与模型信息（v1.4.0，OpenAPI `RagHealth`；`GET /api/v1/rag/health`，仅 it/admin）。
 * `status` = ok（全部组件 ok）/ degraded（存在 degraded/down）/ down（编排层或向量库不可用）；
 * **demo 边界**：`llm.mode` 必须如实标注 `relay|mock|retrieval_only`，无真实中转凭据时 `degraded=true`，
 * 不得把 PoC 模型/本地向量库说成生产可用（AGENTS.md NEVER #11）。
 */
export interface RagHealth {
  status: 'ok' | 'degraded' | 'down';
  components: RagComponent[];
  llm: { provider: string; model: string; mode: 'relay' | 'mock' | 'retrieval_only' };
  embedding: { model: string; dim: number };
  vectorStore: { backend: string; count: number };
  reranked: boolean;
  degraded: boolean;
  generatedAt: IsoDateTime;
}

/**
 * 知识重建索引结果（v1.4.0，OpenAPI `RagReindexResult`；
 * `POST /api/v1/knowledge/{knowledgeId}/reindex`，仅 it/admin，审计 `knowledge.reindex`）：
 * `chunks` = 重建后片段数（幂等，不产生重复片段）；**不修改**知识正文/版本/生效期/状态，只重建检索索引。
 */
export interface RagReindexResult {
  knowledgeId: string;
  chunks: number;
  indexedAt: IsoDateTime;
}

/* ---- P6 v1.4.0 响应信封别名（统一信封 `ApiResponse<T>`） ---- */

export type RagQueryResponse = ApiResponse<RagQueryResult>;
export type RagHealthResponse = ApiResponse<RagHealth>;
export type RagReindexResponse = ApiResponse<RagReindexResult>;
