<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { WIcon } from '@/components'
import ActionModal from './ActionModal.vue'
import type { RescheduleConfirm } from './shared'

/**
 * 改约上门时间弹层
 * 产品口径：改约只生成申请，由管家与班组重新确认；节点推送时间会重新计算（演示不接真实排班）。
 */
const BUILTIN_SLOTS = ['今天 17:30—19:00', '明天上午 09:00—11:00', '明天下午 14:00—16:00']

const props = withDefaults(
  defineProps<{
    open: boolean
    slots?: string[]
    /** 当前已约时段（用于默认选中） */
    current?: string
    note?: string
  }>(),
  { current: '', note: '' },
)

const emit = defineEmits<{ 'update:open': [value: boolean]; confirm: [payload: RescheduleConfirm] }>()

/** 可选时段：宿主不传时用内置演示时段 */
const slotList = computed<string[]>(() => props.slots ?? BUILTIN_SLOTS)

const selected = ref('')
const remark = ref('')
const error = ref('')

watch(
  () => props.open,
  (open) => {
    if (!open) return
    selected.value = props.current || slotList.value[0] || ''
    remark.value = ''
    error.value = ''
  },
)

function setOpen(value: boolean): void {
  emit('update:open', value)
}

function pick(item: string): void {
  selected.value = item
  error.value = ''
}

function onConfirm(): void {
  if (!selected.value) {
    error.value = '请选择一个上门时段'
    return
  }
  error.value = ''
  emit('confirm', { slot: selected.value, note: remark.value.trim() })
}
</script>

<template>
  <ActionModal
    :open="open"
    name="reschedule"
    title="改约上门时间"
    width="480px"
    lead="选择新的上门时段，管家会与班组确认；接单 / 出发 / 完工三次推送会按新时间重算。"
    :hint="note || '演示环境不接真实排班与短信通知；改约后仍需管家确认才算生效。'"
    confirm-text="提交改约"
    :error="error"
    @update:open="setOpen"
    @confirm="onConfirm"
  >
    <div class="act-list" role="radiogroup" aria-label="改约上门时间">
      <button
        v-for="(item, index) in slotList"
        :key="item"
        type="button"
        role="radio"
        class="act-opt"
        :class="{ 'is-on': selected === item }"
        :aria-checked="selected === item"
        :data-testid="`reschedule-slot-${index}`"
        @click="pick(item)"
      >
        <span class="act-opt__icon"><WIcon name="clock" :size="14" /></span>
        <span class="act-opt__body">
          <b>{{ item }}</b>
          <span>改约后由管家与班组确认，节点推送会重新计算</span>
        </span>
      </button>
    </div>

    <div class="act-field act-field--stack">
      <label for="act-reschedule-note">补充说明（选填）</label>
      <textarea
        id="act-reschedule-note"
        v-model="remark"
        class="act-input act-textarea"
        rows="2"
        maxlength="60"
        placeholder="例如：请提前 10 分钟电话联系，家中有老人"
        data-testid="reschedule-note"
      />
    </div>
  </ActionModal>
</template>
