import type { ErrorCode } from '../../../../packages/shared/src/index'
import type { ToastItem } from '@/types'

/**
 * 操作回执与错误码人话映射（P1-036）
 * ---------------------------------------------------------------------------
 * 1) 回执句式：成功 / 失败 / 中性说明 / 演示补充，全站统一；
 * 2) Toast 语气：success（动作已生效）/ error（需用户处理）/ warning（部分完成）/
 *    info（状态变化或边界说明，不代表成功）；
 * 3) 17 类错误码 → 人话：**只做映射，不新造错误码**。
 *
 * 错误码唯一事实源：`packages/shared/src/index.ts` §9 `ERROR_CODES`（与
 * `docs/openapi/openapi.yaml` 的 ErrorResponse 同源）。此处以 `import type` 引入
 * `ErrorCode` 联合类型（仅类型、构建时擦除），因此：
 * - 若契约侧新增/删除错误码，本文件的 `Record<ErrorCode, …>` 会直接编译失败（防漏）；
 * - 禁止在本文件出现契约外的错误码字符串（单测会做集合比对）。
 */

/* ============================================================================
 * 1. 回执句式
 * ========================================================================== */

/**
 * 回执句式模板（唯一口径）：
 * - 成功：`已{动作}：{结果}`
 * - 失败：`{动作}未完成：{原因}，请{下一步}`
 * - 说明：`{主题}：{说明}`
 * - 演示：`（演示：{说明}）`，追加在成功/失败回执末尾，明确“不是真实业务结果”
 */
export const RECEIPT_PATTERNS: Readonly<Record<'success' | 'failure' | 'info' | 'demo', string>> = {
  success: '已{action}：{detail}',
  failure: '{action}未完成：{reason}，请{next}',
  info: '{topic}：{note}',
  demo: '（演示：{note}）',
}

/** 演示回执统一后缀说明（页面标注与 Toast 后缀共用，避免“演示 / mock / 示例”三种叫法混用） */
export const DEMO_NOTE = '不代表真实业务结果'

/** 演示回执后缀：成功/失败回执统一追加这一句 */
export const DEMO_SUFFIX = `（演示：${DEMO_NOTE}）`

/** 演示环境的统一提示（不接真实支付 / 短信 / 微信 / ERP 写操作） */
export const DEMO_BOUNDARY_NOTE = '演示环境：不接真实支付、短信、微信与 ERP 写操作'

/** Toast 语气使用规则（写报告与 Code Review 共用） */
export const TOAST_TONE_POLICY: Readonly<Record<ToastItem['tone'], string>> = {
  success: '动作已生效或已受理（含演示回执）',
  error: '动作失败且需要用户处理：缺必填、越权、上游不可用',
  warning: '动作部分完成，或需要用户二次确认',
  info: '状态变化、边界说明、入口跳转；不代表动作成功',
}

export interface ReceiptOptions {
  /** 是否演示回执（默认 true：P1 全站均为演示环境） */
  demo?: boolean
  /** 演示补充说明（默认 DEMO_NOTE） */
  demoNote?: string
}

function withDemo(text: string, options?: ReceiptOptions): string {
  const demo = options?.demo ?? true
  if (!demo) return text
  return `${text}（演示：${options?.demoNote ?? DEMO_NOTE}）`
}

/** 成功回执：`已导出欠费台账：10 行（演示：…）` */
export function successReceipt(action: string, detail?: string, options?: ReceiptOptions): string {
  const body = detail ? `已${action}：${detail}` : `已${action}`
  return withDemo(body, options)
}

/** 失败回执：`催缴任务未完成：上游系统超时，请稍后重试或转人工` */
export function failureReceipt(action: string, reason: string, next = '稍后重试或转人工'): string {
  return `${action}未完成：${reason}，请${next}`
}

/** 中性说明：`入口说明：完整回放见「全链路回溯」` */
export function infoReceipt(topic: string, note: string): string {
  return `${topic}：${note}`
}

/** 演示补充说明：`（演示：仅本地状态，未写入后端）` */
export function demoNote(note: string = DEMO_NOTE): string {
  return `（演示：${note}）`
}

/* ============================================================================
 * 2. 17 类错误码 → 人话
 * ========================================================================== */

/** 契约错误码（与 shared `ErrorCode` 严格一致，禁止扩展） */
export type PlatformErrorCode = ErrorCode

export interface ErrorCodeCopy {
  code: PlatformErrorCode
  /** 中文语义：与 shared `ERROR_CODES[code].label` 逐字一致（单测校验） */
  label: string
  /** 面向用户的人话（不出现内部码、金额、工单号等敏感结果） */
  userMessage: string
  /** 下一步该做什么（人工兜底 / 重试 / 补齐信息） */
  nextStep: string
  /** 是否可重试（与 shared 一致：读 1 次 / 写 0 次） */
  retryable: boolean
  /** 建议 Toast 语气 */
  tone: 'error' | 'warning'
}

/**
 * 17 类错误码人话表。顺序与 shared 定义一致（E-1xxx → E-9xxx）。
 * 文案原则：只描述“发生了什么 + 下一步”，不暴露堆栈、上游报文与业务数字。
 */
export const ERROR_CODE_COPY: Readonly<Record<PlatformErrorCode, ErrorCodeCopy>> = {
  'E-1001': {
    code: 'E-1001',
    label: '参数缺失或非法',
    userMessage: '还有信息没填完整，请补充后再提交',
    nextStep: '按提示补齐必填项后重试',
    retryable: false,
    tone: 'error',
  },
  'E-1002': {
    code: 'E-1002',
    label: '枚举/字典未匹配',
    userMessage: '这个选项在系统里对不上，已记入映射缺口',
    nextStep: '先选标准选项，或转人工确认口径',
    retryable: false,
    tone: 'error',
  },
  'E-1003': {
    code: 'E-1003',
    label: '房号无法归一',
    userMessage: '房号没识别出来，请确认楼栋、单元与房号',
    nextStep: '按「楼栋-单元-房号」重新填写',
    retryable: false,
    tone: 'error',
  },
  'E-2001': {
    code: 'E-2001',
    label: '身份未核验或核验失败',
    userMessage: '需要先核验身份才能继续',
    nextStep: '重新核验，连续 3 次失败转人工',
    retryable: false,
    tone: 'warning',
  },
  'E-2002': {
    code: 'E-2002',
    label: '越权访问（主体与房号不匹配）',
    userMessage: '该房号不在当前账号的授权范围内，已记录并转人工',
    nextStep: '由管家核验归属后人工处理',
    retryable: false,
    tone: 'error',
  },
  'E-2003': {
    code: 'E-2003',
    label: '连接器凭据失效',
    userMessage: '连接器凭据失效，正在切换备用凭据',
    nextStep: '稍后重试；仍失败联系系统管理员',
    retryable: true,
    tone: 'warning',
  },
  'E-3001': {
    code: 'E-3001',
    label: '数据不存在',
    userMessage: '没有查到这条记录，请确认编号后再试',
    nextStep: '核对编号，或转人工核实',
    retryable: false,
    tone: 'warning',
  },
  'E-3002': {
    code: 'E-3002',
    label: '状态不允许（写前置不满足）',
    userMessage: '当前状态不支持这个操作',
    nextStep: '按页面给出的替代路径操作，或转人工',
    retryable: false,
    tone: 'error',
  },
  'E-3003': {
    code: 'E-3003',
    label: '幂等冲突（同键不同载荷）',
    userMessage: '这次提交之前已受理过，以首次结果为准',
    nextStep: '刷新查看首次结果，不要重复提交',
    retryable: false,
    tone: 'warning',
  },
  'E-4001': {
    code: 'E-4001',
    label: '上游超时',
    userMessage: '上游系统超时，已进入降级流程',
    nextStep: '稍后重试；需要即时结果请转人工',
    retryable: true,
    tone: 'warning',
  },
  'E-4002': {
    code: 'E-4002',
    label: '上游错误（5xx）',
    userMessage: '上游系统异常，已熔断并降级',
    nextStep: '稍后重试；已通知运维排查',
    retryable: true,
    tone: 'error',
  },
  'E-4003': {
    code: 'E-4003',
    label: '上游限流',
    userMessage: '上游限流中，请求已排队',
    nextStep: '稍后重试，避免连续点击',
    retryable: true,
    tone: 'warning',
  },
  'E-5001': {
    code: 'E-5001',
    label: '能力未开通/不支持',
    userMessage: '该能力在当前 ERP 未开通，已隐藏入口并给出替代通道',
    nextStep: '使用替代通道，或转人工办理',
    retryable: false,
    tone: 'warning',
  },
  'E-5002': {
    code: 'E-5002',
    label: '降级返回（字段缺失）',
    userMessage: '部分字段暂缺，只展示已确认部分',
    nextStep: '其余内容转人工确认，不做推测',
    retryable: false,
    tone: 'warning',
  },
  'E-6001': {
    code: 'E-6001',
    label: '护栏拦截（L3 命中）',
    userMessage: '该问题超出可承诺范围，已转人工处理',
    nextStep: '由管家按标准口径人工答复',
    retryable: false,
    tone: 'error',
  },
  'E-6002': {
    code: 'E-6002',
    label: '引用缺失（拒答）',
    userMessage: '没有可引用的知识出处，按规则不回答',
    nextStep: '已转人工并记录知识缺口',
    retryable: false,
    tone: 'error',
  },
  'E-9001': {
    code: 'E-9001',
    label: '内部错误',
    userMessage: '系统内部错误，请稍后重试',
    nextStep: '多次失败请联系管理员并附 trace id',
    retryable: true,
    tone: 'error',
  },
}

/** 错误码顺序（表格渲染 / 测试用） */
export const ERROR_CODE_ORDER: readonly PlatformErrorCode[] = [
  'E-1001',
  'E-1002',
  'E-1003',
  'E-2001',
  'E-2002',
  'E-2003',
  'E-3001',
  'E-3002',
  'E-3003',
  'E-4001',
  'E-4002',
  'E-4003',
  'E-5001',
  'E-5002',
  'E-6001',
  'E-6002',
  'E-9001',
]

/** 错误码人话表（按契约顺序，便于表格 / 测试遍历） */
export const ERROR_CODE_LIST: readonly ErrorCodeCopy[] = ERROR_CODE_ORDER.map((code) => ERROR_CODE_COPY[code])

/** 按错误码取人话；未知码不新造语义，统一回落到 E-9001 口径 */
export function errorCodeCopy(code: string): ErrorCodeCopy {
  return (ERROR_CODE_COPY as Record<string, ErrorCodeCopy>)[code] ?? ERROR_CODE_COPY['E-9001']
}

/** 把错误码转成一条可直接 Toast 的回执（人话 + 下一步） */
export function errorReceipt(code: string): { message: string; tone: ErrorCodeCopy['tone'] } {
  const copy = errorCodeCopy(code)
  return {
    message: `${copy.userMessage}；${copy.nextStep}`,
    tone: copy.tone,
  }
}
