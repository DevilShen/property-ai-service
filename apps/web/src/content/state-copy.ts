import type { IconName, ScreenKey } from '@/types'

/**
 * 四态统一文案（P1-036）
 * ---------------------------------------------------------------------------
 * 全站「空态 / 加载 / 错误 / 降级」的唯一句式来源。页面只引用这里，不再各自硬编码。
 *
 * 句式约定（W4 文案规范，报告见 `_dev/reports/W4-文案与状态统一.md`）：
 * - 空态：`暂无{对象}` + 一句「下一步怎么办」，不写“敬请期待”这类空话；
 * - 加载：`正在{动作}…`，只描述正在读什么，不预估时间；
 * - 错误：`暂时无法加载` + 「稍后重试 / 转人工」，**不编造数字、不写内部错误码**；
 * - 降级：`已降级：数据来自缓存，仅供参考` + 「哪些写操作已暂停、转人工兜底」。
 */

/** 四态类型 */
export type StateKind = 'empty' | 'loading' | 'error' | 'degraded'

export interface StateCopy {
  /** 状态标题（第一行，尽量 ≤ 16 字） */
  title: string
  /** 状态说明（一句话讲清“发生了什么 + 下一步”） */
  description: string
  /** 状态图标 */
  icon: IconName
  /** 状态标签色调（WTag tone） */
  tone: 'g' | 'b' | 'a' | 'r' | 'v' | 'n'
}

/** 四态中文名（状态演示区 / 报告 / 测试共用，避免各页面自造叫法） */
export const STATE_LABELS: Readonly<Record<StateKind, string>> = {
  empty: '空态',
  loading: '加载',
  error: '错误',
  degraded: '降级',
}

/** 四态展示顺序 */
export const STATE_ORDER: readonly StateKind[] = ['empty', 'loading', 'error', 'degraded']

/** 四态统一句式（全站唯一口径） */
export const STATE_COPY: Readonly<Record<StateKind, StateCopy>> = {
  empty: {
    title: '暂无数据',
    description: '当前条件下没有记录；换个筛选条件，或稍后再看。',
    icon: 'info',
    tone: 'n',
  },
  loading: {
    title: '加载中…',
    description: '正在读取数据，请稍候；读完后本区块自动展示结果。',
    icon: 'refresh',
    tone: 'b',
  },
  error: {
    title: '暂时无法加载',
    description: '请稍后重试；多次失败可转人工处理，已保存的数据不受影响。',
    icon: 'shield',
    tone: 'r',
  },
  degraded: {
    title: '已降级：数据来自缓存，仅供参考',
    description: '上游系统暂不可用，写操作已暂停；需要即时结果请转人工。',
    icon: 'refresh',
    tone: 'a',
  },
}

/** 每屏的数据对象名：用于「{对象}读取失败 / 已降级为只读快照」句式，避免各页面自己拼 */
export const SCREEN_OBJECT: Readonly<Record<ScreenKey, string>> = {
  overview: '方案总览数据',
  owner: '会话与账单数据',
  progress: '工单进度数据',
  copilot: '工作区结果',
  desk: '会话队列数据',
  billing: '欠费台账',
  erp: '连接器与能力探测结果',
  mapping: '映射配置与试跑结果',
  orchestration: '编排链路与路由表',
  trace: 'trace 与会话回放',
  knowledge: '知识清单',
  dashboard: '运营指标',
}

/** 每屏加载文案：描述“正在读什么”，不写开发期口径 */
export const SCREEN_LOADING_LABEL: Readonly<Record<ScreenKey, string>> = {
  overview: '正在加载方案总览与验收指标…',
  owner: '正在加载会话、账单与工单数据…',
  progress: '正在加载 7 节点时间轴、推送与回执…',
  copilot: '正在加载当前工作区结果…',
  desk: '正在加载会话队列、AI 摘要与只读的 ERP 数据…',
  billing: '正在加载欠费台账与催缴漏斗…',
  erp: '正在加载 5 套连接器与能力探测结果…',
  mapping: '正在加载映射配置与 200 条试跑结果…',
  orchestration: '正在加载 7 跳链路与路由表…',
  trace: '正在加载 trace、会话回放与调用瀑布…',
  knowledge: '正在加载知识清单、缺口与 badcase 复盘…',
  dashboard: '正在加载指标卡、趋势与漏斗…',
}

/** 每屏空态文案：标题（暂无什么）+ 描述（下一步怎么办） */
export const SCREEN_EMPTY_COPY: Readonly<Record<ScreenKey, StateCopy>> = {
  overview: {
    title: '暂无可展示的能力覆盖',
    description: '连接器能力探测结果尚未生成；到「ERP 适配中心」执行一次探测后再看。',
    icon: 'plug',
    tone: 'n',
  },
  owner: {
    title: '还没有对话',
    description: '说一句话就能开始：查账单、报修、查进度或转人工。',
    icon: 'chat',
    tone: 'n',
  },
  progress: {
    title: '该视角下没有工单进度',
    description: '换一张工单，或从业主端重新发起报修；已归档工单可在「全链路回溯」查看。',
    icon: 'list-check',
    tone: 'n',
  },
  copilot: {
    title: '当前工作区暂无结果',
    description: '换个问法，或切换左侧 8 个工作区；答案必须带出处（知识 ID + 版本）。',
    icon: 'sparkles',
    tone: 'n',
  },
  desk: {
    title: '当前队列没有待处理会话',
    description: '保持在线，新会话会实时进入；首响 SLA 为 30 秒。',
    icon: 'headset',
    tone: 'n',
  },
  billing: {
    title: '该筛选下没有欠费记录',
    description: '调整楼栋或逾期分层，或清空关键词后重试。',
    icon: 'wallet',
    tone: 'n',
  },
  erp: {
    title: '还没有接入连接器',
    description: '从「新增连接器」开始 4 步接入；能力矩阵由探测结果生成。',
    icon: 'plug',
    tone: 'n',
  },
  mapping: {
    title: '该能力暂无差异字段',
    description: '复用「标准物业模板」即可；如需新增字段请走变更单。',
    icon: 'mapping',
    tone: 'n',
  },
  orchestration: {
    title: '还没有编排记录',
    description: '发起一次模拟请求后，7 跳链路与路由命中会显示在这里。',
    icon: 'workflow',
    tone: 'n',
  },
  trace: {
    title: '没有匹配的 trace 记录',
    description: '换 trace id 或时间范围；证据包含知识引用与护栏判断。',
    icon: 'activity',
    tone: 'n',
  },
  knowledge: {
    title: '该分类暂无知识条目',
    description: '切换分类或清空搜索；新增知识需指定责任人与生效期。',
    icon: 'book',
    tone: 'n',
  },
  dashboard: {
    title: '当前项目暂无可统计指标',
    description: '指标按日结算（次日更新）；口径未校验前不展示，避免误读。',
    icon: 'chart',
    tone: 'n',
  },
}

/** 每屏降级说明：讲清“哪部分只读 / 哪些写操作转人工”，不编造数字 */
export const SCREEN_DEGRADED_COPY: Readonly<Record<ScreenKey, StateCopy>> = {
  overview: {
    title: '已降级：能力覆盖来自缓存，仅供参考',
    description: '能力探测暂不可用；以「ERP 适配中心」最新探测结果为准，本页不新造覆盖率。',
    icon: 'refresh',
    tone: 'a',
  },
  owner: {
    title: '已降级：账单暂不可查',
    description: '账单查询超时，不展示缓存金额；已自动转人工，由管家确认后再答复。',
    icon: 'refresh',
    tone: 'a',
  },
  progress: {
    title: '已降级：进度来自上一次回写，仅供参考',
    description: 'ERP 回写暂不可用；节点以人工确认为准，催办与改约转人工受理。',
    icon: 'refresh',
    tone: 'a',
  },
  copilot: {
    title: '已降级：结果来自缓存，仅供参考',
    description: '知识检索或生成暂不可用；可直接转人工，草稿不会写入知识库。',
    icon: 'refresh',
    tone: 'a',
  },
  desk: {
    title: '已降级：ERP 数据为只读快照，仅供参考',
    description: '查询超时后自动降级为只读快照；写操作一律走人工，不在本页提交。',
    icon: 'refresh',
    tone: 'a',
  },
  billing: {
    title: '已降级：台账来自只读快照，仅供参考',
    description: '账单实时查询不可用；催缴任务只登记不发送，缴费与开票转人工。',
    icon: 'refresh',
    tone: 'a',
  },
  erp: {
    title: '已降级：能力矩阵来自缓存，仅供参考',
    description: '探测暂不可用；缺能力入口保持隐藏，不做“界面有、实际不能用”的假支持。',
    icon: 'refresh',
    tone: 'a',
  },
  mapping: {
    title: '已降级：试跑结果来自缓存，仅供参考',
    description: '试跑服务不可用；差异未清零前禁止发布映射，回滚仍可执行。',
    icon: 'refresh',
    tone: 'a',
  },
  orchestration: {
    title: '已降级：链路耗时来自缓存，仅供参考',
    description: '编排运行态不可用；降级路径仍按台账执行并留痕，写操作转人工。',
    icon: 'refresh',
    tone: 'a',
  },
  trace: {
    title: '已降级：回放来自本地缓存，仅供参考',
    description: 'trace 服务不可用；证据包只导出已落盘部分，缺项单独标注、不推测补齐。',
    icon: 'refresh',
    tone: 'a',
  },
  knowledge: {
    title: '已降级：知识清单来自缓存，仅供参考',
    description: '知识库暂不可用；过期知识自动降级为「以最新公示为准」并转管家。',
    icon: 'refresh',
    tone: 'a',
  },
  dashboard: {
    title: '已降级：指标来自缓存，仅供参考',
    description: '统计服务不可用；不补数、不外推环比，周报导出前请先确认口径。',
    icon: 'refresh',
    tone: 'a',
  },
}

/** 取四态文案：传屏 key 时优先用该屏的空态 / 降级文案，其余用统一句式 */
export function stateCopy(kind: StateKind, screen?: ScreenKey): StateCopy {
  if (screen) {
    if (kind === 'empty') return SCREEN_EMPTY_COPY[screen]
    if (kind === 'degraded') return SCREEN_DEGRADED_COPY[screen]
    if (kind === 'error') return errorCopy(screen)
    if (kind === 'loading') return loadingCopy(screen)
  }
  return STATE_COPY[kind]
}

/** 空态文案（带屏级覆盖） */
export function emptyCopy(screen?: ScreenKey): StateCopy {
  return screen ? SCREEN_EMPTY_COPY[screen] : STATE_COPY.empty
}

/** 加载文案（带屏级覆盖） */
export function loadingCopy(screen?: ScreenKey): StateCopy {
  return screen ? { ...STATE_COPY.loading, description: SCREEN_LOADING_LABEL[screen] } : STATE_COPY.loading
}

/** 错误文案（带屏级覆盖）：标题统一，描述指明“哪个数据读取失败 + 下一步” */
export function errorCopy(screen?: ScreenKey): StateCopy {
  if (!screen) return STATE_COPY.error
  return {
    ...STATE_COPY.error,
    description: `「${SCREEN_OBJECT[screen]}」读取失败；可稍后重试或转人工，已保存的配置不受影响。`,
  }
}

/** 降级文案（带屏级覆盖） */
export function degradedCopy(screen?: ScreenKey): StateCopy {
  return screen ? SCREEN_DEGRADED_COPY[screen] : STATE_COPY.degraded
}
