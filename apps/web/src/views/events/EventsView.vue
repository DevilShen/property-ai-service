<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { WButton, WPageHeader, WTag } from '@/components'
import { useAuthStore } from '@/stores/auth'
import { useProjectStore } from '@/stores/project'
import EventsPanel from './EventsPanel.vue'

/**
 * 事件流页（P5 W19，非 12 屏导航页；沿用 P3 W12 `/complaints` 的非导航路由先例）
 * ===========================================================================
 * 用途：supervisor / it / admin 查看 outbox 事件（`GET /api/v1/events`），
 * 与「审计」（谁做了什么）分列：事件是**消息投递视角**（发生过什么、投递到哪、是否进死信）。
 *
 * 入口：
 * 1) ERP 适配中心 · 「事件」页签（it/admin，`event.read`）；
 * 2) 全链路回溯页头「事件流」按钮（supervisor/it/admin，`event.read`）。
 *
 * 权限：页面本身对所有角色可达（路由不做 12 屏可见性拦截），但 `event.read` 缺失时
 * 面板渲染 PermissionDenied（并展示接口 403 E-2001 的 requiredCapability），不展示未授权数据。
 */
const router = useRouter()
const auth = useAuthStore()
const project = useProjectStore()

const canRead = computed(() => auth.hasCapability('event.read'))
</script>

<template>
  <div class="w-page ev-page" data-page="events" data-testid="page-root">
    <WPageHeader
      eyebrow="P5 · 事件"
      title="事件流（outbox）"
      description="事件信封：类型 / 项目 / 脱敏 payload / 投递状态（pending→published，失败可重试，超限进死信）。按 occurredAt 倒序分页，支持类型 / 项目 / 时间窗筛选。"
    >
      <template #actions>
        <WTag :tone="project.canSwitch ? 'v' : 'n'" plain data-testid="events-project-context">
          当前项目：{{ project.currentLabel }}（演示项目）
        </WTag>
        <WTag :tone="canRead ? 'g' : 'a'" plain>event.read</WTag>
        <WButton
          v-if="auth.hasCapability('trace.read')"
          size="sm"
          icon="arrow-right"
          data-testid="events-back"
          @click="router.push('/trace')"
        >
          去全链路回溯
        </WButton>
      </template>
    </WPageHeader>

    <div class="ev-page__banner">
      <span class="ev-page__banner-icon">事</span>
      <div class="ev-page__banner-main">
        <b>outbox 口径：至少一次投递 + 消费幂等 + 死信队列</b>
        <p>
          `pending` 待投递 / `published` 已投递 / `failed` 可重试 / `dead` 死信（运维待办，不静默丢失）；
          事件由真实写操作产生（建单、工单迁移、灰度改比例等），演示环境为进程内存储（重启清空）。
        </p>
      </div>
      <WTag tone="v" plain>演示事件（mock outbox）</WTag>
    </div>

    <EventsPanel />
  </div>
</template>

<style scoped>
.ev-page {
  min-width: 0;
}

.ev-page__banner {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  padding: 10px 14px;
  border: 1px solid #d8eae5;
  border-radius: var(--rd-xl);
  background: linear-gradient(95deg, #f2faf7, #fbfdfc);
}

.ev-page__banner-icon {
  display: grid;
  place-items: center;
  flex: none;
  width: 30px;
  height: 30px;
  border-radius: var(--rd-md);
  background: var(--c-brand-soft);
  color: var(--c-brand);
  font-size: var(--fs-sm);
  font-weight: var(--fw-semibold);
}

.ev-page__banner-main {
  flex: 1;
  min-width: 0;
}

.ev-page__banner-main b {
  font-size: var(--fs-sm);
  color: var(--c-ink);
}

.ev-page__banner-main p {
  margin-top: 1px;
  font-size: var(--fs-2xs);
  color: var(--c-muted);
  line-height: var(--lh-base);
}
</style>
