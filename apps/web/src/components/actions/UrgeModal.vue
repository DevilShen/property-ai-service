<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import ActionModal from './ActionModal.vue'
import type { UrgeConfirm } from './shared'

/**
 * 催办弹层（工单进度 / 业主端工单卡）
 * 产品口径：催办只生成提醒给班组主管，不做 AI 语音外呼；紧急安全事件走 400 人工兜底。
 */
const BUILTIN_REASONS = ['尽快安排上门', '家中仍在漏水 / 积水', '老人小孩独自在家', '已超过约定上门时间', '其他原因']

const props = withDefaults(
  defineProps<{
    open: boolean
    reasons?: string[]
    note?: string
  }>(),
  { note: '' },
)

const emit = defineEmits<{ 'update:open': [value: boolean]; confirm: [payload: UrgeConfirm] }>()

/** 催办原因：宿主不传时用内置选项 */
const reasonList = computed<string[]>(() => props.reasons ?? BUILTIN_REASONS)

const reason = ref('')
const remark = ref('')
const error = ref('')

watch(
  () => props.open,
  (open) => {
    if (!open) return
    reason.value = reasonList.value[0] ?? ''
    remark.value = ''
    error.value = ''
  },
)

function setOpen(value: boolean): void {
  emit('update:open', value)
}

function pick(item: string): void {
  reason.value = item
  error.value = ''
}

function onConfirm(): void {
  if (!reason.value) {
    error.value = '请选择催办原因'
    return
  }
  error.value = ''
  emit('confirm', { reason: reason.value, note: remark.value.trim() })
}
</script>

<template>
  <ActionModal
    :open="open"
    name="urge"
    title="催办工单"
    width="480px"
    lead="催办会生成一条提醒给班组主管，并记录在工单时间轴上；不做 AI 语音外呼。"
    :hint="note || '演示环境不发送真实短信/推送；紧急安全事件请拨 400 人工热线（不由 AI 外呼）。'"
    confirm-text="提交催办"
    :error="error"
    @update:open="setOpen"
    @confirm="onConfirm"
  >
    <div class="act-list" role="radiogroup" aria-label="催办原因">
      <button
        v-for="(item, index) in reasonList"
        :key="item"
        type="button"
        role="radio"
        class="act-opt"
        :class="{ 'is-on': reason === item }"
        :aria-checked="reason === item"
        :data-testid="`urge-reason-${index}`"
        @click="pick(item)"
      >
        <span class="act-opt__body">
          <b>{{ item }}</b>
        </span>
      </button>
    </div>

    <div class="act-field act-field--stack">
      <label for="act-urge-note">补充说明（选填）</label>
      <textarea
        id="act-urge-note"
        v-model="remark"
        class="act-input act-textarea"
        rows="2"
        maxlength="60"
        placeholder="例如：家里有积水，希望 1 小时内上门"
        data-testid="urge-note"
      />
    </div>
  </ActionModal>
</template>
