<script setup lang="ts">
/** 共享加载态：骨架页、表格、按钮旁均可使用。 */
withDefaults(
  defineProps<{
    label?: string
    size?: 'sm' | 'md' | 'lg'
    /** 撑满父容器垂直居中 */
    center?: boolean
  }>(),
  { label: '', size: 'md', center: false },
)
</script>

<template>
  <div class="w-loading" :class="[`w-loading--${size}`, { 'is-center': center }]" role="status" aria-live="polite">
    <span class="w-loading__spinner" aria-hidden="true" />
    <span v-if="label" class="w-loading__label">{{ label }}</span>
  </div>
</template>

<style scoped>
.w-loading {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--c-muted);
  font-size: var(--fs-xs);
}

.w-loading.is-center {
  display: flex;
  justify-content: center;
  width: 100%;
  padding: 28px 0;
}

.w-loading__spinner {
  border-radius: 50%;
  border: 2px solid var(--c-brand-soft);
  border-top-color: var(--c-brand);
  animation: w-loading-spin 0.8s linear infinite;
}

.w-loading--sm .w-loading__spinner {
  width: 12px;
  height: 12px;
  border-width: 1.6px;
}

.w-loading--md .w-loading__spinner {
  width: 18px;
  height: 18px;
}

.w-loading--lg .w-loading__spinner {
  width: 26px;
  height: 26px;
  border-width: 2.4px;
}

@keyframes w-loading-spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
