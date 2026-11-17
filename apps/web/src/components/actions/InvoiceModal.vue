<script setup lang="ts">
import { ref, watch } from 'vue'
import ActionModal from './ActionModal.vue'
import type { InvoiceConfirm, InvoiceType } from './shared'

/**
 * 电子发票弹层
 * 产品口径：发票由 ERP 收费系统开具，AI 仅转交申请；抬头（个人）或抬头+税号（单位）需业主确认。
 * 校验：抬头必填（个人填姓名 / 单位填公司全称）；税号为演示占位，不做真实校验与开具。
 */
const TYPES: InvoiceType[] = ['个人', '单位']

const props = withDefaults(
  defineProps<{
    open: boolean
    /** 个人抬头默认值（演示数据：业主姓名） */
    defaultTitle?: string
    /** 底部说明；不传则用默认文案 */
    note?: string
  }>(),
  { defaultTitle: '', note: '' },
)

const emit = defineEmits<{ 'update:open': [value: boolean]; confirm: [payload: InvoiceConfirm] }>()

const type = ref<InvoiceType>('个人')
const title = ref('')
const taxId = ref('')
const error = ref('')

watch(
  () => props.open,
  (open) => {
    if (!open) return
    type.value = '个人'
    title.value = props.defaultTitle
    taxId.value = ''
    error.value = ''
  },
)

function setOpen(value: boolean): void {
  emit('update:open', value)
}

function chooseType(next: InvoiceType): void {
  if (type.value === next) return
  type.value = next
  error.value = ''
  if (next === '个人' && title.value.trim() === '') title.value = props.defaultTitle
  if (next === '单位' && title.value === props.defaultTitle) title.value = ''
}

function onConfirm(): void {
  const value = title.value.trim()
  if (!value) {
    error.value =
      type.value === '个人'
        ? '请填写发票抬头：个人需填写业主姓名'
        : '请填写发票抬头：单位需填写公司全称'
    return
  }
  error.value = ''
  emit('confirm', { type: type.value, title: value, taxId: taxId.value.trim() })
}
</script>

<template>
  <ActionModal
    :open="open"
    name="invoice"
    title="电子发票"
    width="480px"
    lead="发票由 ERP 收费系统开具，AI 仅转交申请；提交后可在收费系统查询开票结果。"
    :hint="note || '抬头与税号需业主确认；演示环境不真实开具、不推送税务系统。'"
    confirm-text="提交申请"
    :error="error"
    @update:open="setOpen"
    @confirm="onConfirm"
  >
    <div class="act-field">
      <label>发票类型</label>
      <div class="act-chips">
        <button
          v-for="item in TYPES"
          :key="item"
          type="button"
          class="act-chip"
          :class="{ 'is-on': type === item }"
          :aria-pressed="type === item"
          :data-testid="`invoice-type-${item}`"
          @click="chooseType(item)"
        >
          {{ item }}
        </button>
      </div>
    </div>

    <div class="act-field">
      <label for="act-invoice-title">发票抬头 <em>必填</em></label>
      <input
        id="act-invoice-title"
        v-model="title"
        class="act-input"
        type="text"
        maxlength="40"
        :placeholder="type === '个人' ? '例如 王女士' : '例如 某某物业服务有限公司'"
        data-testid="invoice-title"
        @input="error = ''"
      />
    </div>

    <div v-if="type === '单位'" class="act-field">
      <label for="act-invoice-taxid">税号</label>
      <input
        id="act-invoice-taxid"
        v-model="taxId"
        class="act-input"
        type="text"
        maxlength="24"
        placeholder="单位税号（演示占位，不做校验）"
        data-testid="invoice-taxid"
      />
    </div>

    <p class="act-sub">发票内容为本次应缴账单；抬头填写错误需在收费系统作废重开（演示不做真实作废）。</p>
  </ActionModal>
</template>
