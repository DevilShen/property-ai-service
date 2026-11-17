<script setup lang="ts">
import type { IconName } from '@/types'
import WIcon from './Icon.vue'

/** 共享空态：页面/卡片无数据时使用，可挂主操作按钮。 */
withDefaults(
  defineProps<{
    title?: string
    description?: string
    icon?: IconName
    /** 紧凑模式：表格单元格、卡片内嵌 */
    compact?: boolean
  }>(),
  { title: '暂无数据', description: '', icon: 'info', compact: false },
)
</script>

<template>
  <div class="w-empty" :class="{ 'is-compact': compact }">
    <span class="w-empty__icon"><WIcon :name="icon" :size="compact ? 16 : 20" /></span>
    <p class="w-empty__title">{{ title }}</p>
    <p v-if="description" class="w-empty__desc">{{ description }}</p>
    <div v-if="$slots.default" class="w-empty__actions"><slot /></div>
  </div>
</template>

<style scoped>
.w-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 26px 16px;
  text-align: center;
}

.w-empty.is-compact {
  padding: 14px 10px;
}

.w-empty__icon {
  display: grid;
  place-items: center;
  width: 38px;
  height: 38px;
  margin-bottom: 4px;
  border-radius: 50%;
  background: var(--c-bg-2);
  color: var(--c-faint);
  border: 1px dashed var(--c-line);
}

.w-empty.is-compact .w-empty__icon {
  width: 28px;
  height: 28px;
}

.w-empty__title {
  font-size: var(--fs-sm);
  font-weight: var(--fw-semibold);
  color: var(--c-ink-2);
}

.w-empty__desc {
  max-width: 360px;
  font-size: var(--fs-xs);
  color: var(--c-faint);
  line-height: var(--lh-base);
}

.w-empty__actions {
  display: flex;
  gap: var(--sp-2);
  margin-top: 6px;
}
</style>
