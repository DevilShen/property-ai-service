<script setup lang="ts">
import { onBeforeUnmount, watch } from 'vue'
import WIcon from './Icon.vue'

/** 共享弹窗：遮罩 + 居中面板，支持 ESC / 点击遮罩关闭。 */
const props = withDefaults(
  defineProps<{
    open: boolean
    title?: string
    width?: string
    closeOnMask?: boolean
    showClose?: boolean
  }>(),
  { title: '', width: '520px', closeOnMask: true, showClose: true },
)

const emit = defineEmits<{ 'update:open': [value: boolean]; close: [] }>()

function close() {
  emit('update:open', false)
  emit('close')
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') close()
}

watch(
  () => props.open,
  (open) => {
    if (typeof document === 'undefined') return
    if (open) {
      document.addEventListener('keydown', onKeydown)
      document.body.style.overflow = 'hidden'
    } else {
      document.removeEventListener('keydown', onKeydown)
      document.body.style.overflow = ''
    }
  },
  { immediate: true },
)

onBeforeUnmount(() => {
  if (typeof document === 'undefined') return
  document.removeEventListener('keydown', onKeydown)
  document.body.style.overflow = ''
})
</script>

<template>
  <Teleport to="body">
    <Transition name="w-modal">
      <div v-if="open" class="w-modal" role="dialog" aria-modal="true" :aria-label="title || undefined">
        <div class="w-modal__mask" @click="closeOnMask && close()" />
        <div class="w-modal__panel" :style="{ width }">
          <header class="w-modal__head">
            <h3 v-if="title || $slots.title" class="w-modal__title">
              <slot name="title">{{ title }}</slot>
            </h3>
            <div v-if="$slots.headExtra" class="w-modal__extra"><slot name="headExtra" /></div>
            <button v-if="showClose" type="button" class="w-modal__close" aria-label="关闭" @click="close">
              <WIcon name="close" :size="16" />
            </button>
          </header>
          <div class="w-modal__body"><slot /></div>
          <footer v-if="$slots.footer" class="w-modal__foot"><slot name="footer" /></footer>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.w-modal {
  position: fixed;
  inset: 0;
  z-index: var(--z-modal);
  display: grid;
  place-items: center;
  padding: var(--sp-6);
}

.w-modal__mask {
  position: absolute;
  inset: 0;
  background: rgba(14, 29, 43, 0.42);
  backdrop-filter: blur(1.5px);
}

.w-modal__panel {
  position: relative;
  display: flex;
  flex-direction: column;
  max-width: calc(100vw - 48px);
  max-height: calc(100vh - 96px);
  background: var(--c-card);
  border-radius: var(--rd-xl);
  box-shadow: var(--sh-lg);
  overflow: hidden;
}

.w-modal__head {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  padding: 14px 18px;
  border-bottom: 1px solid var(--c-line-2);
  flex: none;
}

.w-modal__title {
  font-size: var(--fs-md);
  font-weight: var(--fw-semibold);
  color: var(--c-ink);
}

.w-modal__extra {
  margin-left: auto;
}

.w-modal__close {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  margin-left: auto;
  border-radius: var(--rd-sm);
  color: var(--c-muted);
  transition: background-color var(--tr-fast), color var(--tr-fast);
}

.w-modal__close:hover {
  background: var(--c-bg-2);
  color: var(--c-ink-2);
}

.w-modal__close:focus-visible {
  outline: none;
  box-shadow: var(--sh-ring);
}

.w-modal__body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 16px 18px;
}

.w-modal__foot {
  flex: none;
  display: flex;
  justify-content: flex-end;
  gap: var(--sp-2);
  padding: 12px 18px;
  border-top: 1px solid var(--c-line-2);
  background: var(--c-bg-2);
}

.w-modal-enter-active,
.w-modal-leave-active {
  transition: opacity var(--tr-base);
}

.w-modal-enter-active .w-modal__panel,
.w-modal-leave-active .w-modal__panel {
  transition: transform var(--tr-base), opacity var(--tr-base);
}

.w-modal-enter-from,
.w-modal-leave-to {
  opacity: 0;
}

.w-modal-enter-from .w-modal__panel,
.w-modal-leave-to .w-modal__panel {
  transform: translateY(8px) scale(0.99);
  opacity: 0;
}
</style>
