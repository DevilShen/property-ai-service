<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { WButton, WDrawer, WEmpty, WIcon, WTag, useToast } from '@/components'
import { COPILOT_WORKSPACES } from '@/app/copilot'
import { SCREENS } from '@/app/screens'
import { useAppStore, type AppNotification, type NotificationFilter, type NotificationType } from '@/stores/app'
import type { IconName } from '@/types'

/**
 * P1-026 通知中心（右侧 Drawer，复用 WDrawer）。
 *
 * - 未读徽标（红点 + 数字，徽标在 AppTopbar 按钮上，由 store.unreadCount 驱动）；
 * - 筛选 Tab「全部 / 未读」；
 * - 单条点击标记已读 + 跳转 targetRoute（路由由通知 type/targetRoute 决定）；
 * - 「全部已读」+ 空态；
 * - 通知数据来自 store（8 条演示数据，标注 mock；不接真实事件推送）。
 */
const app = useAppStore()
const router = useRouter()
const toast = useToast()

/** 通知类型 → 图标/色调/中文标签（与 store 的 type 字段对齐） */
const TYPE_META: Record<NotificationType, { label: string; icon: IconName; tone: 'g' | 'b' | 'a' | 'r' | 'v' | 'n' }> = {
  'ticket-timeout': { label: '工单超时', icon: 'clock', tone: 'r' },
  'knowledge-expired': { label: '知识过期', icon: 'book', tone: 'a' },
  'probe-failed': { label: '能力探测', icon: 'plug', tone: 'r' },
  collection: { label: '催缴任务', icon: 'wallet', tone: 'a' },
  'gray-release': { label: '灰度发布', icon: 'workflow', tone: 'b' },
  system: { label: '系统公告', icon: 'info', tone: 'n' },
}

const FILTER_TABS: { key: NotificationFilter; label: string }[] = [
  { key: 'all', label: '全部' },
  { key: 'unread', label: '未读' },
]

const list = computed<AppNotification[]>(() =>
  app.notificationFilter === 'unread'
    ? app.notifications.filter((item) => !item.read)
    : app.notifications,
)

function countOf(filter: NotificationFilter): number {
  return filter === 'unread' ? app.unreadCount : app.notifications.length
}

/** 通知目标路由 → 屏/工作区的中文名，用于「前往 X」提示 */
function routeLabel(path: string): string {
  const workspace = Object.values(COPILOT_WORKSPACES).find((item) => item.path === path)
  if (workspace) return workspace.label
  const screen = Object.values(SCREENS).find(
    (item) => path === item.path || path.startsWith(`${item.path}/`),
  )
  return screen?.navLabel ?? path
}

function typeMeta(type: NotificationType) {
  return TYPE_META[type] ?? TYPE_META.system
}

/** 单条点击：标记已读 → 关闭抽屉 → 跳转关联页面 */
function openNotification(item: AppNotification) {
  app.markRead(item.id)
  app.setNotificationOpen(false)
  void router.push(item.targetRoute)
}

function markAllRead() {
  if (app.unreadCount === 0) {
    toast.info('当前没有未读通知')
    return
  }
  app.markAllRead()
  toast.success('已全部标记为已读')
}

/** 空态文案随筛选变化 */
const emptyTitle = computed(() => (app.notificationFilter === 'unread' ? '没有未读通知' : '暂无通知'))
const emptyDesc = computed(() =>
  app.notificationFilter === 'unread'
    ? '所有通知都已读过了；切到「全部」查看历史通知。'
    : '通知来自物业事件（工单/知识/能力探测/催缴等），当前为演示数据。',
)
</script>

<template>
  <WDrawer
    :open="app.notificationOpen"
    title="通知中心"
    width="400px"
    @update:open="app.setNotificationOpen($event)"
  >
    <template #extra>
      <WTag v-if="app.unreadCount > 0" tone="r" plain>{{ app.unreadCount }} 条未读</WTag>
      <WTag v-else tone="g" plain>全部已读</WTag>
    </template>

    <div class="gs-notice" data-testid="notification-center">
      <!-- 筛选 Tab -->
      <div class="gs-notice-tabs" role="tablist" aria-label="通知筛选">
        <button
          v-for="tab in FILTER_TABS"
          :key="tab.key"
          type="button"
          role="tab"
          class="gs-notice-tabs__item"
          :class="{ 'is-active': app.notificationFilter === tab.key }"
          :aria-selected="app.notificationFilter === tab.key"
          :data-testid="`notification-filter-${tab.key}`"
          @click="app.setNotificationFilter(tab.key)"
        >
          <span>{{ tab.label }}</span>
          <span class="gs-notice-tabs__count">{{ countOf(tab.key) }}</span>
        </button>
        <span class="gs-notice-tabs__hint">通知为演示数据（mock）</span>
      </div>

      <!-- 通知列表 -->
      <ul v-if="list.length" class="gs-notice-list">
        <li v-for="item in list" :key="item.id">
          <button
            type="button"
            class="gs-notice-item"
            :class="{ 'is-unread': !item.read }"
            data-testid="notification-item"
            :data-type="item.type"
            :data-read="item.read ? 'true' : 'false'"
            @click="openNotification(item)"
          >
            <span class="gs-notice-item__icon" :class="`is-${typeMeta(item.type).tone}`">
              <WIcon :name="typeMeta(item.type).icon" :size="15" />
            </span>
            <span class="gs-notice-item__main">
              <span class="gs-notice-item__head">
                <b class="gs-notice-item__title">{{ item.title }}</b>
                <em class="gs-notice-item__time">{{ item.time }}</em>
              </span>
              <span class="gs-notice-item__desc">{{ item.description }}</span>
              <span class="gs-notice-item__foot">
                <WTag :tone="typeMeta(item.type).tone" plain>{{ typeMeta(item.type).label }}</WTag>
                <span class="gs-notice-item__route">前往「{{ routeLabel(item.targetRoute) }}」</span>
              </span>
            </span>
            <span v-if="!item.read" class="gs-notice-item__dot" aria-label="未读" />
          </button>
        </li>
      </ul>

      <WEmpty v-else compact icon="bell" :title="emptyTitle" :description="emptyDesc" />

      <p class="gs-notice-demo">
        演示数据：通知由前端 store 维护（8 条 mock），不接真实事件推送；后续接 Java 事件流与已读回执。
      </p>
    </div>

    <template #footer>
      <WButton
        size="sm"
        variant="ghost"
        data-testid="notification-mark-all"
        :disabled="app.unreadCount === 0"
        @click="markAllRead"
      >
        全部已读
      </WButton>
      <WButton size="sm" variant="primary" @click="app.setNotificationOpen(false)">关闭</WButton>
    </template>
  </WDrawer>
</template>

<style scoped>
.gs-notice {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

/* ---------- 筛选 Tab ---------- */
.gs-notice-tabs {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 3px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-md);
  background: var(--c-bg-2);
}

.gs-notice-tabs__item {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 26px;
  padding: 0 11px;
  border-radius: var(--rd-sm);
  font-size: var(--fs-sm);
  font-weight: var(--fw-medium);
  color: var(--c-muted);
  transition: background-color var(--tr-fast), color var(--tr-fast), box-shadow var(--tr-fast);
}

.gs-notice-tabs__item:hover {
  color: var(--c-ink-2);
  background: color-mix(in srgb, var(--c-card) 70%, transparent);
}

.gs-notice-tabs__item:focus-visible {
  outline: none;
  box-shadow: var(--sh-ring);
}

.gs-notice-tabs__item.is-active {
  background: var(--c-card);
  color: var(--c-brand);
  font-weight: var(--fw-semibold);
  box-shadow: var(--sh-sm);
}

.gs-notice-tabs__count {
  display: inline-flex;
  align-items: center;
  height: 15px;
  padding: 0 5px;
  border-radius: var(--rd-pill);
  background: var(--c-brand-soft);
  color: var(--c-brand);
  font-size: 9px;
  font-weight: var(--fw-semibold);
}

.gs-notice-tabs__hint {
  margin-left: auto;
  padding-right: 6px;
  font-size: 9px;
  color: var(--c-faint);
  white-space: nowrap;
}

/* ---------- 列表 ---------- */
.gs-notice-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.gs-notice-item {
  display: flex;
  align-items: flex-start;
  gap: 9px;
  width: 100%;
  padding: 10px 11px;
  border: 1px solid var(--c-line-2);
  border-radius: var(--rd-md);
  background: var(--c-bg-2);
  text-align: left;
  transition: background-color var(--tr-fast), border-color var(--tr-fast), box-shadow var(--tr-fast);
}

.gs-notice-item:hover {
  border-color: color-mix(in srgb, var(--c-brand) 28%, transparent);
  background: var(--c-card);
  box-shadow: var(--sh-sm);
}

.gs-notice-item:focus-visible {
  outline: none;
  box-shadow: var(--sh-ring);
}

.gs-notice-item.is-unread {
  border-color: color-mix(in srgb, var(--c-brand) 30%, transparent);
  background: var(--c-brand-soft);
}

.gs-notice-item__icon {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  flex: none;
  border-radius: var(--rd-sm);
  background: var(--c-card);
  color: var(--c-muted);
}

.gs-notice-item__icon.is-r {
  color: var(--c-red);
}

.gs-notice-item__icon.is-a {
  color: var(--c-amber);
}

.gs-notice-item__icon.is-b {
  color: var(--c-blue);
}

.gs-notice-item__icon.is-v {
  color: var(--c-ai);
}

.gs-notice-item__icon.is-g {
  color: var(--c-success);
}

.gs-notice-item__main {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
  flex: 1;
}

.gs-notice-item__head {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.gs-notice-item__title {
  font-size: var(--fs-sm);
  font-weight: var(--fw-semibold);
  color: var(--c-ink-2);
  line-height: var(--lh-tight);
}

.gs-notice-item__time {
  margin-left: auto;
  flex: none;
  font-size: 9px;
  font-style: normal;
  color: var(--c-faint);
}

.gs-notice-item__desc {
  font-size: var(--fs-xs);
  color: var(--c-muted);
  line-height: var(--lh-base);
}

.gs-notice-item__foot {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 1px;
}

.gs-notice-item__route {
  font-size: 9px;
  color: var(--c-faint);
}

.gs-notice-item__dot {
  width: 6px;
  height: 6px;
  margin-top: 4px;
  flex: none;
  border-radius: 50%;
  background: var(--c-red);
  box-shadow: 0 0 0 3px var(--c-red-soft);
}

.gs-notice-demo {
  padding-top: 2px;
  border-top: 1px dashed var(--c-line-2);
  margin-top: 2px;
  font-size: 9px;
  color: var(--c-faint);
  line-height: var(--lh-base);
}
</style>
