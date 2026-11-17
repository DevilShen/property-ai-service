<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '@/api'

type Status = 'checking' | 'online' | 'offline'
const status = ref<Status>('checking')
const isDev = import.meta.env.DEV

onMounted(async () => {
  try {
    const health = await api.health()
    status.value = health.ok ? 'online' : 'offline'
  } catch {
    status.value = 'offline'
  }
})
</script>

<template>
  <span v-if="isDev" class="api-status" :class="`is-${status}`" :title="`Java API /healthz：${status}`">
    <i class="api-status__dot" />
    <span>Java API</span>
  </span>
</template>

<style scoped>
.api-status {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 26px;
  padding: 0 8px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-pill);
  background: var(--c-bg-2);
  color: var(--c-faint);
  font-size: 9px;
  white-space: nowrap;
}

.api-status__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #b4c2cc;
}

.api-status.is-online .api-status__dot {
  background: #2bb673;
  box-shadow: 0 0 0 3px rgba(43, 182, 115, 0.12);
}

.api-status.is-offline .api-status__dot {
  background: #e8684a;
  box-shadow: 0 0 0 3px rgba(232, 104, 74, 0.12);
}
</style>
