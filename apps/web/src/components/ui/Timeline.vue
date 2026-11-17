<script setup lang="ts">
import type { TimelineItem } from '@/types'

/** 共享时间轴：用于工单 7 节点、追溯链路等纵向节点展示。 */
withDefaults(defineProps<{ items: TimelineItem[] }>(), { items: () => [] })
</script>

<template>
  <ol class="w-timeline">
    <li
      v-for="(item, index) in items"
      :key="`${item.title}-${index}`"
      class="w-timeline__item"
      :class="`is-${item.status ?? 'pending'}`"
    >
      <span class="w-timeline__dot" aria-hidden="true" />
      <div class="w-timeline__body">
        <div class="w-timeline__head">
          <b class="w-timeline__title">{{ item.title }}</b>
          <span v-if="item.time" class="w-timeline__time num">{{ item.time }}</span>
        </div>
        <p v-if="item.description" class="w-timeline__desc">{{ item.description }}</p>
        <slot :item="item" :index="index" />
      </div>
    </li>
  </ol>
</template>

<style scoped>
.w-timeline {
  display: flex;
  flex-direction: column;
}

.w-timeline__item {
  position: relative;
  display: flex;
  gap: 10px;
  padding-bottom: 14px;
}

.w-timeline__item:last-child {
  padding-bottom: 0;
}

.w-timeline__item:not(:last-child)::before {
  content: '';
  position: absolute;
  left: 5px;
  top: 16px;
  bottom: 0;
  width: 1px;
  background: var(--c-line);
}

.w-timeline__dot {
  width: 11px;
  height: 11px;
  margin-top: 3px;
  flex: none;
  border-radius: 50%;
  border: 2px solid var(--c-card);
  background: var(--c-faint);
  box-shadow: 0 0 0 1px var(--c-line);
  z-index: 1;
}

.w-timeline__item.is-done .w-timeline__dot {
  background: var(--c-success);
}

.w-timeline__item.is-active .w-timeline__dot {
  background: var(--c-brand);
  box-shadow: 0 0 0 1px var(--c-brand), 0 0 0 4px var(--c-brand-soft);
}

.w-timeline__item.is-error .w-timeline__dot {
  background: var(--c-red);
}

.w-timeline__body {
  flex: 1;
  min-width: 0;
}

.w-timeline__head {
  display: flex;
  align-items: baseline;
  gap: var(--sp-2);
}

.w-timeline__title {
  font-size: var(--fs-sm);
  font-weight: var(--fw-semibold);
  color: var(--c-ink-2);
}

.w-timeline__time {
  margin-left: auto;
  font-size: var(--fs-2xs);
  color: var(--c-faint);
}

.w-timeline__desc {
  margin-top: 2px;
  font-size: var(--fs-xs);
  color: var(--c-muted);
  line-height: var(--lh-base);
}
</style>
