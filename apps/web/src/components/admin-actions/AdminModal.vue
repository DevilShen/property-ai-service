<script setup lang="ts">
import { WModal } from '@/components'

/**
 * 管理端统一弹层壳（P1-033）
 * ----------------------------------------------------------------
 * - 统一 WModal 的标题 / 宽度 / 页脚，六个视图不再各写一套弹层骨架；
 * - 统一 `data-testid="admin-modal-<name>"`，e2e 可直接定位每个动作弹层；
 * - 只做壳：业务内容与确认按钮由具体弹层（FilterModal / ExportModal…）提供。
 */
withDefaults(
  defineProps<{
    open: boolean
    /** 弹层标识：data-testid="admin-modal-<name>" */
    name: string
    title?: string
    width?: string
    closeOnMask?: boolean
  }>(),
  { title: '', width: '520px', closeOnMask: true },
)

const emit = defineEmits<{ 'update:open': [value: boolean]; close: [] }>()

function onOpenChange(value: boolean) {
  emit('update:open', value)
}

function onClose() {
  emit('close')
}
</script>

<template>
  <WModal
    :open="open"
    :title="title"
    :width="width"
    :close-on-mask="closeOnMask"
    @update:open="onOpenChange"
    @close="onClose"
  >
    <div class="adm-modal" :data-testid="`admin-modal-${name}`" :data-admin-modal="name">
      <slot />
    </div>
    <!-- 管理端动作弹层都以「取消 / 确认」结尾，页脚统一由 WModal 的 footer 渲染 -->
    <template #footer>
      <slot name="footer" />
    </template>
  </WModal>
</template>

<style scoped>
.adm-modal {
  display: flex;
  flex-direction: column;
  gap: 7px;
}
</style>
