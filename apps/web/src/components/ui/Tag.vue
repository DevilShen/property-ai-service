<script setup lang="ts">
/**
 * 共享标签。tone 与原型 tag 色板一致：
 * g=品牌绿 / b=蓝 / a=琥珀 / r=红 / v=AI 紫 / n=中性
 */
withDefaults(
  defineProps<{
    tone?: 'g' | 'b' | 'a' | 'r' | 'v' | 'n'
    /** 去掉默认色点（用于纯文字标签） */
    plain?: boolean
  }>(),
  { tone: 'n', plain: false },
)
</script>

<template>
  <span class="w-tag" :class="[`w-tag--${tone}`, { 'is-plain': plain }]">
    <slot />
  </span>
</template>

<style scoped>
.w-tag {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 20px;
  padding: 0 7px;
  border-radius: 5px;
  font-size: 9.5px;
  font-weight: var(--fw-semibold);
  letter-spacing: 0.2px;
  white-space: nowrap;
  background: #f1f4f6;
  color: #617585;
}

.w-tag::before {
  content: '';
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: currentColor;
  opacity: 0.9;
}

.w-tag.is-plain::before {
  display: none;
}

.w-tag--g {
  background: var(--c-brand-soft);
  color: #0a725f;
}

.w-tag--b {
  background: var(--c-blue-soft);
  color: #1d4ed8;
}

.w-tag--a {
  background: var(--c-amber-soft);
  color: #9a4b05;
}

.w-tag--r {
  background: var(--c-red-soft);
  color: #b42318;
}

.w-tag--v {
  background: var(--c-ai-soft);
  color: #4338ca;
}

.w-tag--n {
  background: #f1f4f6;
  color: var(--c-muted);
}
</style>
