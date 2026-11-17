<script setup lang="ts">
import { WButton, WIcon, WModal } from '@/components'
import type { IconName } from '@/types'
import type { ActionModalName } from './shared'

/**
 * 动作弹层统一外壳（统一 WModal 包装）
 * ------------------------------------------------------------------
 * 把 7 个业主端动作弹层共用的结构固化下来，避免每个弹层重复写头部/校验/底部：
 *   顶部说明（lead，可选）→ 业务内容（默认插槽）→ 合规说明（hint，可选）→ 校验错误（error）
 *   底部固定「取消 + 确认」，可用 footer 插槽覆盖（语音入口的三态按钮）。
 *
 * 交互/可达性：Esc 关闭、点击遮罩关闭、右上角关闭均复用 WModal 既有能力；
 * 动效、遮罩、阴影与既有弹层完全一致（同一个 WModal，不另写一份）。
 */
withDefaults(
  defineProps<{
    open: boolean
    /** 弹层名，决定 data-testid="action-modal-<name>" */
    name: ActionModalName
    title: string
    width?: string
    /** 顶部说明：一句话讲清这个动作在系统里做什么 */
    lead?: string
    leadIcon?: IconName
    /** 底部合规说明（盾牌图标）：不接真实支付/短信/微信等边界 */
    hint?: string
    /** 校验错误；非空即渲染 role="alert" 的错误条 */
    error?: string
    confirmText?: string
    cancelText?: string
    confirmDisabled?: boolean
    showCancel?: boolean
  }>(),
  {
    width: '480px',
    lead: '',
    leadIcon: 'info',
    hint: '',
    error: '',
    confirmText: '确认',
    cancelText: '取消',
    confirmDisabled: false,
    showCancel: true,
  },
)

const emit = defineEmits<{ 'update:open': [value: boolean]; confirm: [] }>()

function onUpdateOpen(value: boolean): void {
  emit('update:open', value)
}

function cancel(): void {
  emit('update:open', false)
}
</script>

<template>
  <WModal :open="open" :title="title" :width="width" @update:open="onUpdateOpen">
    <div class="act" :data-testid="`action-modal-${name}`" :data-action="name">
      <div v-if="lead" class="act-lead">
        <WIcon :name="leadIcon" :size="12" />
        <span>{{ lead }}</span>
      </div>

      <slot />

      <div v-if="hint" class="act-hint">
        <WIcon name="shield" :size="12" />
        <span>{{ hint }}</span>
      </div>

      <p v-if="error" class="act-error" role="alert" :data-testid="`action-error-${name}`">{{ error }}</p>
    </div>

    <template #footer>
      <slot name="footer">
        <WButton
          v-if="showCancel"
          size="sm"
          variant="ghost"
          :data-testid="`action-cancel-${name}`"
          @click="cancel"
        >
          {{ cancelText }}
        </WButton>
        <WButton
          size="sm"
          variant="primary"
          :disabled="confirmDisabled"
          :data-testid="`action-confirm-${name}`"
          @click="emit('confirm')"
        >
          {{ confirmText }}
        </WButton>
      </slot>
    </template>
  </WModal>
</template>
