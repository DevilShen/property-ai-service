<script setup lang="ts">
import { computed } from 'vue'
import { WPageHeader, WTag } from '@/components'
import { useAuthStore } from '@/stores/auth'
import { ROLE_LABELS } from '@/app/roles'
import StateDemo from '@/content/StateDemo.vue'
import ComplaintWorkbench from './ComplaintWorkbench.vue'
import OwnerComplaints from '@/views/owner/components/OwnerComplaints.vue'

/**
 * `/complaints` 投诉页（P3 W12）
 * ===========================================================================
 * 为什么有这条路由（不在 12 屏导航矩阵内，**不新增导航屏**）：
 * - `/desk` 的「投诉处理台」页签对 agent/supervisor/admin 可见，但 desk 的可见角色
 *   在 `app/screens.ts` 里**不含 steward**（W1 可见性矩阵，不在本次 write_scope 内改动）；
 * - 交付物要求 steward 也能处理投诉 → 提供本非导航路由作为处理台外壳（复用同一 `ComplaintWorkbench`），
 *   owner 进入时切换为「我的投诉」视图（本户只读进度 + 提交/重开）。
 * - 页面**不写死角色**：能力由 `useAuthStore.hasCapability` 决定入口显隐，接口 403 时统一走
 *   `PermissionDenied` + `requiredCapability` 人话（it/admin 进入即演示「投诉无权限态」）。
 *
 * 演示口径：投诉为 Java 进程内演示存储（重启清空），不写 ERP、不外发通知、不自动回复。
 */
const auth = useAuthStore()
const isOwner = computed(() => auth.role === 'owner')
const roleLabel = computed(() => (auth.role ? ROLE_LABELS[auth.role] : '未登录'))
</script>

<template>
  <div class="w-page cmp-page" data-page="complaints" data-testid="page-root">
    <WPageHeader
      eyebrow="P3 · 投诉"
      :title="isOwner ? '我的投诉' : '投诉受理与处理'"
      :description="
        isOwner
          ? '提交本户投诉、查看状态与首响时间、问题仍存在时重新打开；只展示业主可见文案。'
          : '投诉分级（L1/L2/L3）与首响 SLA、人工受理/首响/升级/结案/重开、audit 留痕与关联工单；数据来自 Java BFF。'
      "
    >
      <template #actions>
        <WTag tone="b" plain>当前演示角色：{{ roleLabel }}</WTag>
        <WTag tone="n" plain>演示数据 · 进程内存储</WTag>
        <WTag tone="a" plain>处置必须人工</WTag>
      </template>
    </WPageHeader>

    <OwnerComplaints v-if="isOwner" />
    <ComplaintWorkbench v-else />

    <StateDemo :screen="isOwner ? 'owner' : 'desk'" />
  </div>
</template>

<style scoped>
.cmp-page {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
  min-width: 0;
}
</style>
