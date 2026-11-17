<script setup lang="ts">
import { onBeforeUnmount, watch } from 'vue'
import WIcon from './Icon.vue'

/** 共享抽屉：从右侧滑出，ESC / 点击遮罩关闭。 */
const props = withDefaults(
  defineProps<{
    open: boolean
    title?: string
    width?: string
    placement?: 'right' | 'left'
  }>(),
  { title: '', width: '420px', placement: 'right' },
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
    <Transition :name="`w-drawer-${placement}`">
      <div v-if="open" class="w-drawer" role="dialog" aria-modal="true" :aria-label="title || undefined">
        <div class="w-drawer__mask" @click="close" />
        <aside class="w-drawer__panel" :class="`is-${placement}`" :style="{ width }">
          <header class="w-drawer__head">
            <h3 v-if="title || $slots.title" class="w-drawer__title">
              <slot name="title">{{ title }}</slot>
            </h3>
            <div v-if="$slots.extra" class="w-drawer__extra"><slot name="extra" /></div>
            <button type="button" class="w-drawer__close" aria-label="关闭" @click="close">
              <WIcon name="close" :size="16" />
            </button>
          </header>
          <div class="w-drawer__body"><slot /></div>
          <footer v-if="$slots.footer" class="w-drawer__foot"><slot name="footer" /></footer>
        </aside>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.w-drawer {
  position: fixed;
  inset: 0;
  z-index: var(--z-modal);
  display: flex;
}

.w-drawer__mask {
  position: absolute;
  inset: 0;
  background: rgba(14, 29, 43, 0.42);
  backdrop-filter: blur(1.5px);
}

.w-drawer__panel {
  position: relative;
  display: flex;
  flex-direction: column;
  height: 100%;
  max-width: calc(100vw - 48px);
  background: var(--c-card);
  box-shadow: var(--sh-lg);
}

.w-drawer__panel.is-right {
  margin-left: auto;
}

.w-drawer__head {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  padding: 14px 18px;
  border-bottom: 1px solid var(--c-line-2);
  flex: none;
}

.w-drawer__title {
  font-size: var(--fs-md);
  font-weight: var(--fw-semibold);
  color: var(--c-ink);
}

.w-drawer__extra {
  margin-left: auto;
}

.w-drawer__close {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  margin-left: auto;
  border-radius: var(--rd-sm);
  color: var(--c-muted);
  transition: background-color var(--tr-fast), color var(--tr-fast);
}

.w-drawer__close:hover {
  background: var(--c-bg-2);
  color: var(--c-ink-2);
}

.w-drawer__close:focus-visible {
  outline: none;
  box-shadow: var(--sh-ring);
}

.w-drawer__body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 16px 18px;
}

.w-drawer__foot {
  flex: none;
  display: flex;
  justify-content: flex-end;
  gap: var(--sp-2);
  padding: 12px 18px;
  border-top: 1px solid var(--c-line-2);
  background: var(--c-bg-2);
}

.w-drawer-right-enter-active,
.w-drawer-right-leave-active,
.w-drawer-left-enter-active,
.w-drawer-left-leave-active {
  transition: opacity var(--tr-base);
}

.w-drawer-right-enter-active .w-drawer__panel,
.w-drawer-right-leave-active .w-drawer__panel,
.w-drawer-left-enter-active .w-drawer__panel,
.w-drawer-left-leave-active .w-drawer__panel {
  transition: transform var(--tr-base);
}

.w-drawer-right-enter-from,
.w-drawer-right-leave-to,
.w-drawer-left-enter-from,
.w-drawer-left-leave-to {
  opacity: 0;
}

.w-drawer-right-enter-from .w-drawer__panel,
.w-drawer-right-leave-to .w-drawer__panel {
  transform: translateX(24px);
}

.w-drawer-left-enter-from .w-drawer__panel,
.w-drawer-left-leave-to .w-drawer__panel {
  transform: translateX(-24px);
}
</style>
