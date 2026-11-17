<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { WButton, WTag, useToast } from '@/components'
import AdminModal from './AdminModal.vue'
import type { TrialDiff, TrialRunPayload } from './types'

/**
 * 200 条样本试跑弹层（P1-033）
 * ----------------------------------------------------------------
 * - 打开即按「逐条校验」动画推进进度条与百分比（不假装瞬时完成）；
 * - 结束后给出 通过 / 差异 统计与差异行清单，差异未清零前调用方禁止发布；
 * - 支持「重跑」：清零进度重新跑一遍，并 emit('complete') 供调用方刷新统计。
 */
const props = withDefaults(
  defineProps<{
    open: boolean
    name?: string
    title?: string
    total?: number
    passed?: number
    diffs?: TrialDiff[]
    itemLabel?: string
    hint?: string
  }>(),
  {
    name: 'trial-run',
    title: '试运行结果',
    total: 200,
    passed: 198,
    diffs: () => [],
    itemLabel: '条',
    hint: '',
  },
)

const emit = defineEmits<{
  'update:open': [value: boolean]
  complete: [payload: TrialRunPayload]
  close: []
}>()

const toast = useToast()

const TICK_MS = 28
const TICKS = 40

const progress = ref(0)
const running = ref(false)
const finished = ref(false)
let timer: number | undefined

const percentText = computed(() => `${Math.round(progress.value)}%`)
const doneCount = computed(() => Math.round((progress.value / 100) * props.total))
const diffCount = computed(() => props.diffs.length)
const passedCount = computed(() => Math.min(props.passed, props.total))

function clearTimer() {
  if (timer !== undefined) {
    window.clearInterval(timer)
    timer = undefined
  }
}

function finish() {
  clearTimer()
  running.value = false
  progress.value = 100
  finished.value = true
  emit('complete', { passed: passedCount.value, total: props.total, diffCount: diffCount.value })
}

function start() {
  clearTimer()
  progress.value = 0
  finished.value = false
  running.value = true
  let tick = 0
  timer = window.setInterval(() => {
    tick += 1
    // 前 80% 快、后 20% 慢，贴近真实批量校验的观感
    const ratio = tick / TICKS
    progress.value = Math.min(100, Math.round((ratio < 0.8 ? ratio * 1.08 : 0.864 + (ratio - 0.8) * 0.68) * 100))
    if (tick >= TICKS) finish()
  }, TICK_MS)
}

watch(
  () => props.open,
  (open) => {
    if (open) start()
    else clearTimer()
  },
  { immediate: true },
)

onBeforeUnmount(clearTimer)

function rerun() {
  start()
  toast.info('已重新试跑 200 条样本（只读，不写 ERP）')
}

function createTodo() {
  toast.success(`已把 ${diffCount.value} 条差异加入待办：分配责任人后修复并重跑，差异清零才可发布（mock）`)
}
</script>

<template>
  <AdminModal :open="open" :name="name" :title="title" width="560px" @update:open="emit('update:open', $event)" @close="emit('close')">
    <div class="adm-trial">
      <div class="adm-trial__bar" role="progressbar" :aria-valuenow="Math.round(progress)" aria-valuemin="0" aria-valuemax="100">
        <i data-testid="admin-trial-progress" :style="{ width: `${progress}%` }" />
      </div>
      <div class="adm-trial__head">
        <b class="num" data-testid="admin-trial-percent">{{ percentText }}</b>
        <span v-if="running">正在校验第 {{ doneCount }} / {{ total }} {{ itemLabel }}…</span>
        <span v-else>试跑完成 · {{ total }} {{ itemLabel }}样本（只读）</span>
        <WTag :tone="diffCount ? 'a' : 'g'" class="adm-trial__tag" data-testid="admin-trial-status">
          {{ diffCount ? `${diffCount} 条差异待处理` : '全部通过' }}
        </WTag>
      </div>

      <div class="adm-trial__stats">
        <div>
          <span>通过</span>
          <b class="num is-ok" data-testid="admin-trial-stat-passed">{{ finished ? passedCount : doneCount }}</b>
        </div>
        <div>
          <span>差异</span>
          <b class="num is-warn" data-testid="admin-trial-stat-diff">{{ finished ? diffCount : 0 }}</b>
        </div>
        <div>
          <span>样本总数</span>
          <b class="num" data-testid="admin-trial-stat-total">{{ total }}</b>
        </div>
        <div>
          <span>通过率</span>
          <b class="num">{{ finished ? `${((passedCount / total) * 100).toFixed(1)}%` : '—' }}</b>
        </div>
      </div>

      <div v-if="finished && diffs.length" class="adm-trial__diffs">
        <div v-for="(diff, index) in diffs" :key="diff.title" class="adm-trial__diff" data-testid="admin-trial-diff">
          <b>{{ String(index + 1).padStart(2, '0') }} {{ diff.title }}</b>
          <span>{{ diff.detail }}</span>
          <em v-if="diff.expected || diff.actual">期望 {{ diff.expected ?? '—' }} · 实际 {{ diff.actual ?? '—' }}</em>
        </div>
      </div>
      <p v-else-if="finished" class="adm-ok">200 条样本全部通过，可发布映射。</p>

      <p v-if="hint" class="adm-note">{{ hint }}</p>
    </div>

    <template #footer>
      <WButton size="sm" variant="ghost" @click="emit('update:open', false)">关闭</WButton>
      <WButton size="sm" @click="createTodo" :disabled="!finished || !diffs.length" data-testid="admin-trial-todo">
        生成差异待办
      </WButton>
      <WButton size="sm" variant="primary" icon="refresh" :loading="running" data-testid="admin-trial-rerun" @click="rerun">
        重新试跑
      </WButton>
    </template>
  </AdminModal>
</template>

<style scoped>
.adm-trial {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.adm-trial__bar {
  height: 8px;
  border-radius: var(--rd-pill);
  background: var(--c-line-2);
  overflow: hidden;
}

.adm-trial__bar i {
  display: block;
  height: 100%;
  border-radius: var(--rd-pill);
  background: var(--c-brand-gradient);
  transition: width 60ms linear;
}

.adm-trial__head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  flex-wrap: wrap;
}

.adm-trial__head b {
  font-size: var(--fs-2xl);
  color: var(--c-brand);
  line-height: 1.1;
}

.adm-trial__head span {
  font-size: var(--fs-xs);
  color: var(--c-muted);
}

.adm-trial__tag {
  margin-left: auto;
}

.adm-trial__stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 5px;
}

.adm-trial__stats div {
  display: flex;
  flex-direction: column;
  gap: 1px;
  padding: 7px 9px;
  border-radius: var(--rd-sm);
  background: var(--c-bg-2);
}

.adm-trial__stats span {
  font-size: 9px;
  color: var(--c-faint);
}

.adm-trial__stats b {
  font-size: var(--fs-base);
  color: var(--c-ink-2);
}

.adm-trial__stats b.is-ok {
  color: var(--c-brand);
}

.adm-trial__stats b.is-warn {
  color: var(--c-amber);
}

.adm-trial__diffs {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.adm-trial__diff {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 7px 9px;
  border: 1px solid var(--c-line-2);
  border-radius: var(--rd-sm);
}

.adm-trial__diff b {
  font-size: var(--fs-xs);
  color: var(--c-ink-2);
}

.adm-trial__diff span {
  font-size: var(--fs-2xs);
  color: var(--c-muted);
  line-height: var(--lh-base);
}

.adm-trial__diff em {
  font-style: normal;
  font-family: var(--font-mono);
  font-size: 9px;
  color: var(--c-faint);
}

.adm-ok {
  padding: 7px 9px;
  border-radius: var(--rd-sm);
  background: var(--c-success-soft);
  color: #0a725f;
  font-size: var(--fs-2xs);
}

.adm-note {
  font-size: var(--fs-2xs);
  color: var(--c-faint);
  line-height: var(--lh-base);
}
</style>
