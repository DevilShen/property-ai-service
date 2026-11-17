<script setup lang="ts">
/**
 * 页面标题区：eyebrow + 标题 + 说明 + 右侧操作（与原型 pagehead 一致）。
 * 插槽：actions（右侧操作区）、meta（覆盖 eyebrow）。
 */
withDefaults(
  defineProps<{
    eyebrow?: string
    title: string
    description?: string
  }>(),
  { eyebrow: '', description: '' },
)
</script>

<template>
  <header class="w-page-header">
    <div class="w-page-header__main">
      <div v-if="eyebrow || $slots.meta" class="w-page-header__eyebrow">
        <slot name="meta">{{ eyebrow }}</slot>
      </div>
      <h1 class="w-page-header__title">{{ title }}</h1>
      <p v-if="description" class="w-page-header__desc">{{ description }}</p>
    </div>
    <div v-if="$slots.actions" class="w-page-header__actions"><slot name="actions" /></div>
  </header>
</template>

<style scoped>
.w-page-header {
  display: flex;
  align-items: flex-end;
  gap: var(--sp-4);
  flex: none;
  padding-bottom: 2px;
}

.w-page-header__main {
  min-width: 0;
}

.w-page-header__eyebrow {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 3px;
  font-size: 9.5px;
  font-weight: var(--fw-semibold);
  letter-spacing: 1.6px;
  color: var(--c-brand);
  text-transform: uppercase;
}

.w-page-header__title {
  font-size: var(--fs-xl);
  font-weight: var(--fw-semibold);
  letter-spacing: 0.1px;
  line-height: var(--lh-tight);
  color: var(--c-ink);
}

.w-page-header__desc {
  margin-top: 4px;
  max-width: 760px;
  font-size: var(--fs-sm);
  color: var(--c-muted);
}

.w-page-header__actions {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  margin-left: auto;
  flex: none;
}
</style>
