<script setup lang="ts">
/** 共享 Tabs：受控组件（v-model），用于页内分段与证据/映射切换。 */
interface TabItem {
  key: string
  label: string
  badge?: string
}

withDefaults(
  defineProps<{
    modelValue: string
    tabs: TabItem[]
    size?: 'sm' | 'md'
  }>(),
  { size: 'md' },
)

const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
</script>

<template>
  <div class="w-tabs" :class="`w-tabs--${size}`" role="tablist">
    <button
      v-for="tab in tabs"
      :key="tab.key"
      type="button"
      role="tab"
      class="w-tabs__item"
      :class="{ 'is-active': tab.key === modelValue }"
      :aria-selected="tab.key === modelValue"
      @click="emit('update:modelValue', tab.key)"
    >
      <span>{{ tab.label }}</span>
      <span v-if="tab.badge" class="w-tabs__badge">{{ tab.badge }}</span>
    </button>
  </div>
</template>

<style scoped>
.w-tabs {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  padding: 3px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-md);
  background: var(--c-bg-2);
}

.w-tabs__item {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 26px;
  padding: 0 11px;
  border-radius: var(--rd-sm);
  font-size: var(--fs-sm);
  font-weight: var(--fw-medium);
  color: var(--c-muted);
  transition: background-color var(--tr-fast), color var(--tr-fast), box-shadow var(--tr-fast);
}

.w-tabs__item:hover {
  color: var(--c-ink-2);
  background: rgba(255, 255, 255, 0.7);
}

.w-tabs__item:focus-visible {
  outline: none;
  box-shadow: var(--sh-ring);
}

.w-tabs__item.is-active {
  background: var(--c-card);
  color: var(--c-brand);
  font-weight: var(--fw-semibold);
  box-shadow: var(--sh-sm);
}

.w-tabs__badge {
  display: inline-flex;
  align-items: center;
  height: 15px;
  padding: 0 5px;
  border-radius: var(--rd-pill);
  background: var(--c-brand-soft);
  color: var(--c-brand);
  font-size: 9px;
  font-weight: var(--fw-semibold);
}

.w-tabs--sm .w-tabs__item {
  height: 23px;
  padding: 0 9px;
  font-size: var(--fs-xs);
}
</style>
