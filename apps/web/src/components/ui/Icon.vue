<script setup lang="ts">
import { computed } from 'vue'
import type { IconName } from '@/types'
import { ICON_PATHS } from './icons'

const props = withDefaults(
  defineProps<{
    name: IconName
    /** 像素尺寸，默认 16（与原型图标一致） */
    size?: number
    /** 无障碍标签；纯装饰图标保持为空 */
    label?: string
  }>(),
  { size: 16, label: '' },
)

const paths = computed(() => ICON_PATHS[props.name] ?? [])
</script>

<template>
  <svg
    class="w-icon"
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.7"
    stroke-linecap="round"
    stroke-linejoin="round"
    :aria-hidden="label ? undefined : 'true'"
    :role="label ? 'img' : undefined"
    :aria-label="label || undefined"
  >
    <path v-for="(d, index) in paths" :key="index" :d="d" />
  </svg>
</template>

<style scoped>
.w-icon {
  flex: none;
  display: block;
}
</style>
