<script setup lang="ts">
import WIcon from '../ui/Icon.vue'
import { useToast } from '@/composables/useToast'

/** Toast 渲染宿主：挂在 AppShell 根部，一次即可。 */
const toast = useToast()
</script>

<template>
  <Teleport to="body">
    <div class="w-toast-host" aria-live="polite" aria-atomic="false">
      <TransitionGroup name="w-toast">
        <div
          v-for="item in toast.items"
          :key="item.id"
          class="w-toast"
          :class="`is-${item.tone}`"
          role="status"
          @click="toast.dismiss(item.id)"
        >
          <WIcon :name="item.tone === 'success' ? 'check' : item.tone === 'error' ? 'close' : 'info'" :size="14" />
          <span class="w-toast__text">{{ item.message }}</span>
        </div>
      </TransitionGroup>
    </div>
  </Teleport>
</template>

<style scoped>
.w-toast-host {
  position: fixed;
  top: 18px;
  left: 50%;
  transform: translateX(-50%);
  z-index: var(--z-toast);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  pointer-events: none;
}

.w-toast {
  display: flex;
  align-items: center;
  gap: 8px;
  max-width: 460px;
  padding: 9px 14px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-md);
  background: var(--c-card);
  box-shadow: var(--sh-lg);
  font-size: var(--fs-sm);
  font-weight: var(--fw-medium);
  color: var(--c-ink-2);
  pointer-events: auto;
  cursor: pointer;
}

.w-toast.is-success {
  border-color: #bfe6d6;
  color: #0a6b5b;
}

.w-toast.is-warning {
  border-color: #f0d9b6;
  color: #8a4708;
}

.w-toast.is-error {
  border-color: #f3cfcc;
  color: var(--c-red);
}

.w-toast.is-info {
  border-color: #d7e3ea;
  color: var(--c-ink-2);
}

.w-toast__text {
  line-height: 1.4;
}

.w-toast-enter-active,
.w-toast-leave-active {
  transition: opacity var(--tr-base), transform var(--tr-base);
}

.w-toast-enter-from,
.w-toast-leave-to {
  opacity: 0;
  transform: translateY(-6px);
}
</style>
