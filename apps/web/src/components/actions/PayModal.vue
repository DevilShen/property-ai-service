<script setup lang="ts">
import { computed } from 'vue'
import ActionModal from './ActionModal.vue'
import type { PayConfirm } from './shared'

/**
 * 缴费弹层（业主端「去缴费」）
 * 产品口径：AI 只做跳转，不读取/不写入支付与财务数据；退款、减免、滞纳金豁免一律转人工。
 * 这里不接任何真实支付通道（P1 阶段门禁），按钮只产生「跳转回执」。
 */
const props = withDefaults(
  defineProps<{
    open: boolean
    amount?: string
    target?: string
    month?: string
    due?: string
    /** 底部合规说明；不传则用默认文案 */
    disclaimer?: string
  }>(),
  {
    amount: '0.00',
    target: '微信支付（物业收费系统）',
    month: '',
    due: '',
    disclaimer: '',
  },
)

const emit = defineEmits<{ 'update:open': [value: boolean]; confirm: [payload: PayConfirm] }>()

const hint = computed(
  () => props.disclaimer || 'AI 只做跳转，不读取、不写入支付与财务数据；退款、减免、滞纳金豁免一律转人工。',
)

function setOpen(value: boolean): void {
  emit('update:open', value)
}

function onConfirm(): void {
  emit('confirm', { amount: props.amount, target: props.target })
}
</script>

<template>
  <ActionModal
    :open="open"
    name="pay"
    title="去缴费（跳转支付系统）"
    width="480px"
    lead="点击「跳转支付」将打开物业收费系统收银台，由收费系统完成收款与开票。"
    :hint="hint"
    confirm-text="跳转支付"
    @update:open="setOpen"
    @confirm="onConfirm"
  >
    <div class="act-kv act-kv--lg">
      <span>应缴金额</span>
      <b class="act-amount" data-testid="pay-amount">¥{{ amount }}</b>
    </div>
    <div class="act-kv">
      <span>收款方</span>
      <b>{{ target }}</b>
    </div>
    <div v-if="month" class="act-kv">
      <span>账单月份</span>
      <b>{{ month }}{{ due ? ` · ${due}` : '' }}</b>
    </div>
    <div class="act-kv">
      <span>支付方式</span>
      <b>微信支付 · 由收费系统收银台完成（演示不发起支付）</b>
    </div>
  </ActionModal>
</template>
