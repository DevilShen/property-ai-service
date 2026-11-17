import type { IconName } from '@/types'

/**
 * 业主端动作弹层 · 统一契约（P1-032）
 * ------------------------------------------------------------------
 * 对应原型说明 §2.3「操作弹层（17 类）」中的业主侧 7 类：
 *   缴费 / 电子发票 / 语音入口 / 手机号绑定 / 改约 / 服务评价 / 催办。
 *
 * 全目录统一的 props / emits 约定（7 个弹层逐条遵守，便于页面接线与 e2e）：
 *   props:  open（必填，配合 v-model:open）+ 内容 props（可选，带默认值）
 *   emits:  update:open(value)  —— 关闭/确认后由父级收口；父级用 v-model:open 绑定
 *           confirm(payload)    —— 仅「确认」动作触发，payload 类型见下方 *Confirm
 *   埋点:   data-testid="action-modal-<name>"（内容根，用于断言弹层已打开）
 *           data-testid="action-error-<name>"（校验错误，用于断言必填拦截）
 *           data-testid="action-confirm-<name>" / "action-cancel-<name>"（底部固定按钮，
 *           避免与右上角 aria-label="关闭" 的同名按钮冲突）
 *
 * 注意：确认按钮位于 WModal 的 footer 插槽（不在内容根内），
 * e2e 需要按按钮定位时请用 action-confirm-<name>，或
 * `.w-modal__panel:has([data-testid=action-modal-<name>])` 限定作用域。
 */

export type ActionModalName = 'pay' | 'invoice' | 'voice' | 'bindphone' | 'reschedule' | 'rate' | 'urge'

/** 7 类弹层的稳定顺序（演示脚本/自检按此遍历） */
export const ACTION_MODAL_NAMES = [
  'pay',
  'invoice',
  'voice',
  'bindphone',
  'reschedule',
  'rate',
  'urge',
] as const satisfies readonly ActionModalName[]

/** 弹层内容根埋点：data-testid */
export const actionModalTestId = (name: ActionModalName): string => `action-modal-${name}`

/** 手机号 B 策略：三选一，一般咨询免绑定 */
export type BindMethodKey = 'wechat' | 'sms' | 'offline'

/**
 * 绑定方式选项。
 * key 用 string：选项可由页面把演示数据（`views/owner/data.ts` 的 BIND_METHODS）直接传入，
 * 组件内部归一成 BindMethodKey 后分派（'sms' 走手机号 + 验证码，其余按已授权 / 线下预约处理）。
 */
export interface BindMethodOption {
  key: string
  title: string
  desc: string
  icon: IconName
}

export type InvoiceType = '个人' | '单位'

export interface PayConfirm {
  /** 应缴金额（展示值，来自 ERP 演示数据） */
  amount: string
  /** 收款方 */
  target: string
}

export interface InvoiceConfirm {
  type: InvoiceType
  title: string
  taxId: string
}

export interface VoiceConfirm {
  /** 识别出的自然语言原话，确认后进入统一会话 */
  text: string
}

export interface BindConfirm {
  method: BindMethodKey
  /** 手动 + 短信方式下的手机号；微信授权/线下核验为空（演示不取真实号码） */
  phone: string
}

export interface RescheduleConfirm {
  slot: string
  note: string
}

export interface RateConfirm {
  /** 1—5 星 */
  score: number
  tags: string[]
  note: string
}

export interface UrgeConfirm {
  reason: string
  note: string
}
