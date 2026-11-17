/**
 * 业主端动作弹层统一出口（components/actions）
 * ------------------------------------------------------------------
 * 严格限定在本目录内聚合，不并进 `components/index.ts`（共享出口由 P1-011 owner 维护）。
 * 页面用法：
 *   import { PayModal, InvoiceModal, ... } from '@/components/actions'
 *   <PayModal v-model:open="payOpen" :amount="PAY.amount" @confirm="confirmPay" />
 */
import './actions.css'

export { default as ActionModal } from './ActionModal.vue'
export { default as PayModal } from './PayModal.vue'
export { default as InvoiceModal } from './InvoiceModal.vue'
export { default as VoiceModal } from './VoiceModal.vue'
export { default as BindPhoneModal } from './BindPhoneModal.vue'
export { default as RescheduleModal } from './RescheduleModal.vue'
export { default as RateModal } from './RateModal.vue'
export { default as UrgeModal } from './UrgeModal.vue'

export { ACTION_MODAL_NAMES, actionModalTestId } from './shared'
export type {
  ActionModalName,
  BindConfirm,
  BindMethodKey,
  BindMethodOption,
  InvoiceConfirm,
  InvoiceType,
  PayConfirm,
  RateConfirm,
  RescheduleConfirm,
  UrgeConfirm,
  VoiceConfirm,
} from './shared'
