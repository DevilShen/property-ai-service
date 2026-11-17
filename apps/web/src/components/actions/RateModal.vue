<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { WIcon } from '@/components'
import ActionModal from './ActionModal.vue'
import type { RateConfirm } from './shared'

/**
 * 服务评价弹层（1—5 星 + 标签多选 + 备注）
 * 产品口径：回访只收集评价与遗留问题，不涉及赔偿与责任认定。
 * 已评价后再次打开会显示「reviewed 回执」，提交由父级把页面切到已回访态并 Toast 回执。
 */
const SCORES = [1, 2, 3, 4, 5]
const SCORE_LABELS = ['非常不满意', '不满意', '一般', '满意', '非常满意']
const BUILTIN_TAGS = ['上门准时', '师傅专业', '态度友好', '问题当场解决', '现场清理干净']

const props = withDefaults(
  defineProps<{
    open: boolean
    /** 是否已评价过（决定回执展示与按钮文案） */
    reviewed?: boolean
    /** 已评价回执文案，如「已评价回执：5 分 · 今天 17:31」 */
    receipt?: string
    /** 回访话术 */
    script?: string
    /** 合规说明 */
    policy?: string
    tags?: string[]
    defaultScore?: number
  }>(),
  {
    reviewed: false,
    receipt: '',
    script: '',
    policy: '',
    defaultScore: 5,
  },
)

const emit = defineEmits<{ 'update:open': [value: boolean]; confirm: [payload: RateConfirm] }>()

/** 可多选的标签：宿主不传时用内置演示标签 */
const tagList = computed<string[]>(() => props.tags ?? BUILTIN_TAGS)

const score = ref(props.defaultScore)
const selected = ref<string[]>([])
const remark = ref('')

watch(
  () => props.open,
  (open) => {
    if (!open) return
    score.value = props.defaultScore
    selected.value = []
    remark.value = ''
  },
)

const legend = (value: number): string => SCORE_LABELS[value - 1] ?? ''

function setOpen(value: boolean): void {
  emit('update:open', value)
}

function toggleTag(tag: string): void {
  const index = selected.value.indexOf(tag)
  if (index >= 0) selected.value.splice(index, 1)
  else selected.value.push(tag)
}

function onConfirm(): void {
  emit('confirm', { score: score.value, tags: [...selected.value], note: remark.value.trim() })
}
</script>

<template>
  <ActionModal
    :open="open"
    name="rate"
    title="评价本次服务"
    width="480px"
    :hint="policy || '回访只收集评价与遗留问题，不涉及赔偿与责任认定（演示不写真实满意度库）。'"
    :confirm-text="reviewed ? '更新评价' : '提交评价'"
    @update:open="setOpen"
    @confirm="onConfirm"
  >
    <blockquote v-if="script" class="act-quote">{{ script }}</blockquote>

    <div v-if="reviewed && receipt" class="act-receipt" data-testid="rate-receipt">
      <WIcon name="check" :size="13" />
      <span>{{ receipt }}</span>
    </div>

    <div class="act-stars" role="group" aria-label="服务评分（1—5 星）">
      <button
        v-for="value in SCORES"
        :key="value"
        type="button"
        class="act-star"
        :class="{ 'is-on': value <= score }"
        :aria-pressed="value <= score"
        :aria-label="`${value} 星 · ${legend(value)}`"
        :data-testid="`rate-star-${value}`"
        @click="score = value"
      >
        ★
      </button>
      <span class="act-star__legend" data-testid="rate-legend">{{ score }} 星 · {{ legend(score) }}</span>
    </div>

    <div>
      <p class="act-sub" style="margin-bottom: 4px">哪些地方做得好？（可多选）</p>
      <div class="act-chips">
        <button
          v-for="(tag, index) in tagList"
          :key="tag"
          type="button"
          class="act-chip"
          :class="{ 'is-on': selected.includes(tag) }"
          :aria-pressed="selected.includes(tag)"
          :data-testid="`rate-tag-${index}`"
          @click="toggleTag(tag)"
        >
          {{ tag }}
        </button>
      </div>
    </div>

    <div class="act-field act-field--stack">
      <label for="act-rate-remark">补充说明（选填）</label>
      <textarea
        id="act-rate-remark"
        v-model="remark"
        class="act-input act-textarea"
        rows="2"
        maxlength="80"
        placeholder="哪里做得好，或还有什么没处理好？"
        data-testid="rate-remark"
      />
    </div>
  </ActionModal>
</template>
