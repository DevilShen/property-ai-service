<script setup lang="ts">
import { computed } from 'vue'
import type { IconName } from '@/types'
import WIcon from './Icon.vue'

/**
 * 共享按钮。必带 hover / active / focus-visible / disabled 四态，
 * 覆盖原型 V1.2 的"91 个按钮全部有动作、统一按压与焦点态"要求。
 */
const props = withDefaults(
  defineProps<{
    variant?: 'primary' | 'secondary' | 'ghost' | 'ai' | 'danger'
    size?: 'sm' | 'md' | 'lg'
    type?: 'button' | 'submit' | 'reset'
    disabled?: boolean
    loading?: boolean
    block?: boolean
    icon?: IconName
  }>(),
  {
    variant: 'secondary',
    size: 'md',
    type: 'button',
    disabled: false,
    loading: false,
    block: false,
    icon: undefined,
  },
)

const emit = defineEmits<{ click: [event: MouseEvent] }>()

const isDisabled = computed(() => props.disabled || props.loading)

function onClick(event: MouseEvent) {
  if (isDisabled.value) {
    event.preventDefault()
    return
  }
  emit('click', event)
}
</script>

<template>
  <button
    class="w-btn"
    :class="[`w-btn--${variant}`, `w-btn--${size}`, { 'is-block': block, 'is-loading': loading }]"
    :type="type"
    :disabled="isDisabled"
    :aria-busy="loading || undefined"
    @click="onClick"
  >
    <span v-if="loading" class="w-btn__spinner" aria-hidden="true" />
    <WIcon v-else-if="icon" :name="icon" :size="size === 'sm' ? 13 : 15" />
    <span class="w-btn__label"><slot /></span>
    <slot name="suffix" />
  </button>
</template>

<style scoped>
.w-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-md);
  background: var(--c-card);
  color: var(--c-ink-2);
  font-weight: var(--fw-medium);
  white-space: nowrap;
  user-select: none;
  transition:
    background-color var(--tr-fast),
    border-color var(--tr-fast),
    color var(--tr-fast),
    box-shadow var(--tr-fast),
    transform var(--tr-fast);
}

.w-btn--md {
  height: 32px;
  padding: 0 12px;
  font-size: var(--fs-sm);
}

.w-btn--sm {
  height: 27px;
  padding: 0 9px;
  font-size: var(--fs-xs);
  border-radius: var(--rd-sm);
}

.w-btn--lg {
  height: 38px;
  padding: 0 18px;
  font-size: var(--fs-md);
  border-radius: var(--rd-lg);
}

/* hover / active / focus：原型统一按压位移 + 焦点环 */
.w-btn:hover:not(:disabled) {
  border-color: #cbd7de;
  background: var(--c-bg-2);
}

.w-btn:active:not(:disabled) {
  transform: translateY(1px);
  background: #f1f5f7;
}

.w-btn:focus-visible {
  outline: none;
  border-color: var(--c-brand);
  box-shadow: var(--sh-ring);
}

.w-btn:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}

.w-btn.is-block {
  width: 100%;
}

.w-btn--primary {
  border-color: #0a725f;
  background: linear-gradient(180deg, #0c846f, #0a725f);
  color: #fff;
  box-shadow: 0 2px 6px rgba(10, 107, 91, 0.22);
}

.w-btn--primary:hover:not(:disabled) {
  border-color: #086553;
  background: #086553;
}

.w-btn--primary:active:not(:disabled) {
  background: #074d40;
}

.w-btn--primary:focus-visible {
  border-color: var(--c-brand);
  box-shadow: var(--sh-ring);
}

.w-btn--ai {
  border-color: #ddd9fb;
  background: var(--c-ai-soft);
  color: #4338ca;
  font-weight: var(--fw-semibold);
}

.w-btn--ai:hover:not(:disabled) {
  border-color: #cfc9fa;
  background: #e4e2fc;
}

.w-btn--ghost {
  border-color: transparent;
  background: transparent;
  color: var(--c-muted);
}

.w-btn--ghost:hover:not(:disabled) {
  border-color: transparent;
  background: rgba(14, 29, 43, 0.04);
  color: var(--c-ink-2);
}

.w-btn--danger {
  border-color: #f6d5d3;
  background: var(--c-red-soft);
  color: var(--c-red);
}

.w-btn--danger:hover:not(:disabled) {
  border-color: #f0c3c0;
  background: #fbe2e0;
}

.w-btn__spinner {
  width: 12px;
  height: 12px;
  border: 1.6px solid currentColor;
  border-right-color: transparent;
  border-radius: 50%;
  animation: w-btn-spin 0.8s linear infinite;
}

@keyframes w-btn-spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
