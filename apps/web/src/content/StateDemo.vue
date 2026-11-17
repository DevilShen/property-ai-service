<script setup lang="ts">
import { computed } from 'vue'
import { WCard, WEmpty, WLoading, WTag } from '@/components'
import type { ScreenKey } from '@/types'
import { STATE_LABELS, degradedCopy, emptyCopy, errorCopy, loadingCopy } from './state-copy'

/**
 * 状态演示区块（P1-036）
 * ---------------------------------------------------------------------------
 * 12 屏统一挂载：把「空 / 加载 / 错误 / 降级」四态的人话口径直接展示出来，
 * 说明“文案来自 `src/content`，真实状态由数据与响应里的 degraded 标记决定”，
 * 不造假数据、不模拟接口结果。
 */
const props = defineProps<{ screen: ScreenKey }>()

const empty = computed(() => emptyCopy(props.screen))
const error = computed(() => errorCopy(props.screen))
const degraded = computed(() => degradedCopy(props.screen))
const loadingLabel = computed(() => loadingCopy(props.screen).description)
</script>

<template>
  <WCard
    title="状态演示 · 空 / 加载 / 错误 / 降级"
    subtitle="四态文案统一来自 src/content；真实状态由数据与响应里的 degraded 标记决定，本区块仅展示口径"
    data-testid="state-demo"
  >
    <template #extra>
      <WTag tone="n" plain>{{ STATE_LABELS.empty }} / {{ STATE_LABELS.loading }} / {{ STATE_LABELS.error }} / {{ STATE_LABELS.degraded }}</WTag>
    </template>
    <div class="state-demo">
      <div class="state-demo__cell" data-state="empty">
        <WTag :tone="empty.tone" plain>{{ STATE_LABELS.empty }}</WTag>
        <WEmpty compact :icon="empty.icon" :title="empty.title" :description="empty.description" />
      </div>
      <div class="state-demo__cell" data-state="loading">
        <WTag tone="b" plain>{{ STATE_LABELS.loading }}</WTag>
        <WLoading center :label="loadingLabel" />
      </div>
      <div class="state-demo__cell" data-state="error">
        <WTag :tone="error.tone" plain>{{ STATE_LABELS.error }}</WTag>
        <WEmpty compact :icon="error.icon" :title="error.title" :description="error.description" />
      </div>
      <div class="state-demo__cell" data-state="degraded">
        <WTag :tone="degraded.tone" plain>{{ STATE_LABELS.degraded }}</WTag>
        <WEmpty compact :icon="degraded.icon" :title="degraded.title" :description="degraded.description" />
      </div>
    </div>
    <p class="state-demo__note">
      口径：空态讲“下一步”、加载只讲“正在读什么”、错误不出现内部码与业务数字、降级必须说明“哪些写操作转人工”。
    </p>
  </WCard>
</template>

<style scoped>
.state-demo {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--sp-2);
}

.state-demo__cell {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
  padding: 8px 10px 10px;
  border: 1px solid var(--c-line-2);
  border-radius: var(--rd-md);
  background: var(--c-bg-2);
}

.state-demo__cell :deep(.w-empty),
.state-demo__cell :deep(.w-loading) {
  align-self: center;
}

.state-demo__note {
  margin-top: 8px;
  font-size: var(--fs-xs);
  color: var(--c-muted);
  line-height: 1.7;
}

@media (max-width: 1279px) {
  .state-demo {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
