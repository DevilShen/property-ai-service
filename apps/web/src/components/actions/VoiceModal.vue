<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { WButton } from '@/components'
import ActionModal from './ActionModal.vue'
import type { VoiceConfirm } from './shared'

/**
 * 语音入口弹层
 * 产品口径：不接真实语音服务（P1 门禁），演示「波形动画 → 识别中 → 已识别」三态；
 * 识别结果确认后进入同一个智能管家会话，不新增第二个入口。
 */
type VoiceState = 'idle' | 'listening' | 'recognized'

const BUILTIN_LINES = ['查一下这个月物业费', '卫生间漏水了，帮我报修', '转人工']
const WAVE = [10, 18, 26, 14, 30, 22, 36, 16, 28, 20, 32, 12, 24, 18, 30, 14, 22, 10]
/** 演示用识别耗时（毫秒） */
const RECOGNIZE_MS = 900

const props = withDefaults(
  defineProps<{
    open: boolean
    hint?: string
    /** 示例语句（也作为演示识别结果来源） */
    lines?: string[]
  }>(),
  {
    hint: '对着手机说需求即可，识别后直接进入同一个智能管家会话；也可让家人代说。',
  },
)

const emit = defineEmits<{ 'update:open': [value: boolean]; confirm: [payload: VoiceConfirm] }>()

/** 示例语句：宿主不传时用内置演示句子 */
const samples = computed<string[]>(() => props.lines ?? BUILTIN_LINES)

const state = ref<VoiceState>('idle')
const text = ref('')
let timer: ReturnType<typeof setTimeout> | undefined

const stateLabel = computed(() => {
  if (state.value === 'listening') return '识别中…'
  if (state.value === 'recognized') return '已识别'
  return '待开始'
})

const sample = computed(() => samples.value[0] ?? BUILTIN_LINES[0])

function clearTimer(): void {
  if (timer !== undefined) {
    clearTimeout(timer)
    timer = undefined
  }
}

function reset(): void {
  clearTimer()
  state.value = 'idle'
  text.value = ''
}

function listen(): void {
  clearTimer()
  state.value = 'listening'
  text.value = ''
  timer = setTimeout(() => {
    timer = undefined
    state.value = 'recognized'
    text.value = sample.value
  }, RECOGNIZE_MS)
}

/** 停止并立刻给出识别结果（演示：取第一条示例语句） */
function stop(): void {
  clearTimer()
  state.value = 'recognized'
  text.value = sample.value
}

function pick(line: string): void {
  clearTimer()
  state.value = 'recognized'
  text.value = line
}

function setOpen(value: boolean): void {
  emit('update:open', value)
}

function onConfirm(): void {
  if (state.value !== 'recognized' || !text.value) return
  emit('confirm', { text: text.value })
}

watch(
  () => props.open,
  (open) => {
    if (open) reset()
    else clearTimer()
  },
)

onBeforeUnmount(clearTimer)
</script>

<template>
  <ActionModal
    :open="open"
    name="voice"
    title="语音入口"
    width="480px"
    :hint="hint"
    :lead="'演示环境不调用真实语音服务：波形与识别结果均为演示，确认后只把这句话放进会话。'"
    @update:open="setOpen"
  >
    <div class="act-wave" :class="{ 'is-live': state === 'listening' }" aria-hidden="true">
      <i v-for="(height, index) in WAVE" :key="index" :style="{ height: `${height}px`, animationDelay: `${index * 55}ms` }" />
    </div>

    <div class="act-state">
      <span class="act-state__dot" :class="`is-${state}`" />
      <b data-testid="voice-state">{{ stateLabel }}</b>
      <span v-if="state === 'listening'" class="act-sub">正在识别…（演示，不调用真实语音服务）</span>
      <span v-else-if="state === 'recognized'" class="act-sub">识别完成，确认后进入智能管家会话</span>
      <span v-else class="act-sub">点击「开始说话」体验语音识别，或直接点下方示例</span>
    </div>

    <blockquote v-if="state === 'recognized'" class="act-quote" data-testid="voice-text">“{{ text }}”</blockquote>

    <div class="act-chips">
      <button
        v-for="(line, index) in samples"
        :key="line"
        type="button"
        class="act-chip"
        :class="{ 'is-on': state === 'recognized' && text === line }"
        :data-testid="`voice-sample-${index}`"
        @click="pick(line)"
      >
        {{ line }}
      </button>
    </div>

    <p class="act-sub">示例语句即演示识别结果；真实语音识别由编排层在后续阶段接入，需业主单独授权。</p>

    <template #footer>
      <WButton v-if="state === 'idle'" size="sm" variant="ghost" @click="setOpen(false)">取消</WButton>
      <WButton v-if="state === 'idle'" size="sm" variant="primary" icon="mic" @click="listen">开始说话</WButton>
      <WButton v-if="state === 'listening'" size="sm" variant="ghost" @click="reset">取消说话</WButton>
      <WButton v-if="state === 'listening'" size="sm" variant="primary" @click="stop">停止并识别</WButton>
      <WButton v-if="state === 'recognized'" size="sm" variant="ghost" @click="reset">重新说</WButton>
      <WButton v-if="state === 'recognized'" size="sm" variant="primary" @click="onConfirm">确认并发送</WButton>
    </template>
  </ActionModal>
</template>
