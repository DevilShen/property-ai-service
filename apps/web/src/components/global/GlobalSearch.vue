<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { WEmpty, WIcon, WTag } from '@/components'
import { useAppStore } from '@/stores/app'
import { highlightSegments, useGlobalSearch, type SearchItem } from './useGlobalSearch'

/**
 * P1-026 全局搜索浮层（Ctrl/⌘ + K 打开，Esc 关闭）。
 *
 * - 顶部搜索框 + 分组结果（知识 / 工单 / 会话 / 页面 / Copilot 工作区）+ 底部快捷键提示；
 * - ↑↓ 选择、Enter 跳转、输入框自动聚焦；
 * - 输入为空展示「最近搜索」（localStorage `property:recent-search`）；
 * - 权限过滤占位：索引由 `canAccess(role, route)` 过滤，底部明确标注非真实鉴权；
 * - 索引数据为演示数据（mock），不接真实搜索服务。
 */
const app = useAppStore()
const route = useRoute()
const router = useRouter()

const role = computed(() => app.currentRole)
const { query, recents, groups, flatItems, totalMatches, hasQuery, remember, clearRecents } = useGlobalSearch(role)

const inputRef = ref<HTMLInputElement | null>(null)
const listRef = ref<HTMLElement | null>(null)
const activeIndex = ref(0)

/** 搜索框自动聚焦：等浮层渲染完成后再 focus */
watch(
  () => app.searchOpen,
  async (open) => {
    if (!open) {
      activeIndex.value = 0
      return
    }
    query.value = ''
    activeIndex.value = 0
    await nextTick()
    inputRef.value?.focus()
  },
)

/** 关键词变化后重置选中项 */
watch(query, () => {
  activeIndex.value = 0
})

/** 选中项变化时保持可见（键盘上下移动不超出可视区） */
watch(activeIndex, async () => {
  await nextTick()
  const active = listRef.value?.querySelector<HTMLElement>('.gs-item.is-active')
  active?.scrollIntoView({ block: 'nearest' })
})

function close() {
  app.setSearchOpen(false)
}

function segmentsOf(text: string) {
  return highlightSegments(text, query.value)
}

function setActiveByItem(item: SearchItem) {
  const index = flatItems.value.findIndex((entry) => entry.id === item.id)
  if (index >= 0) activeIndex.value = index
}

function moveActive(delta: number) {
  const total = flatItems.value.length
  if (total === 0) return
  activeIndex.value = (activeIndex.value + delta + total) % total
}

function openItem(item: SearchItem | undefined) {
  if (!item) return
  const term = query.value.trim()
  if (term) remember(term)
  app.setSearchOpen(false)
  query.value = ''
  void router.push(item.route)
}

function activateActive() {
  openItem(flatItems.value[activeIndex.value])
}

function applyRecent(term: string) {
  query.value = term
  void nextTick(() => inputRef.value?.focus())
}

/**
 * 原型直达参数 `?open=search`（见 `原型说明.md` §直达参数）：
 * 打开浮层后把参数从地址栏移除，避免刷新/关闭后反复弹出。
 */
watch(
  () => route.query.open,
  (value) => {
    if (value !== 'search') return
    app.setSearchOpen(true)
    const rest = { ...route.query }
    delete rest.open
    void router.replace({ query: rest })
  },
  { immediate: true },
)

onBeforeUnmount(() => {
  // 组件卸载（AppTopbar 重挂）时不留悬浮层
  if (app.searchOpen) app.setSearchOpen(false)
})
</script>

<template>
  <Teleport to="body">
    <Transition name="gs-fade">
      <div
        v-if="app.searchOpen"
        class="gs"
        data-testid="global-search"
        role="dialog"
        aria-modal="true"
        aria-label="全局搜索"
        @keydown.esc.stop.prevent="close"
      >
        <div class="gs__mask" @click="close" />

        <div class="gs__panel" role="document">
          <!-- 顶部搜索框 -->
          <header class="gs__head">
            <WIcon name="search" :size="17" class="gs__head-icon" />
            <input
              ref="inputRef"
              v-model="query"
              class="gs__input"
              type="text"
              data-testid="global-search-input"
              aria-label="全局搜索"
              autocomplete="off"
              spellcheck="false"
              placeholder="搜索知识 / 工单 / 会话 / 页面 / 工作区…"
              @keydown.down.stop.prevent="moveActive(1)"
              @keydown.up.stop.prevent="moveActive(-1)"
              @keydown.enter.stop.prevent="activateActive"
            />
            <kbd class="gs__kbd">Esc</kbd>
            <button type="button" class="gs__close" aria-label="关闭全局搜索" @click="close">
              <WIcon name="close" :size="15" />
            </button>
          </header>

          <!-- 结果区 -->
          <div ref="listRef" class="gs__body">
            <template v-if="hasQuery">
              <section v-for="group in groups" :key="group.key" class="gs__group" :data-group="group.key">
                <p class="gs__group-head">
                  <span class="gs__group-label">{{ group.label }}</span>
                  <span class="gs__group-count">{{ group.total }} 条</span>
                </p>
                <button
                  v-for="item in group.items"
                  :key="item.id"
                  type="button"
                  class="gs-item"
                  :class="{ 'is-active': flatItems[activeIndex]?.id === item.id }"
                  data-testid="search-result-item"
                  :data-group="item.group"
                  :data-route="item.route"
                  @click="openItem(item)"
                  @mouseenter="setActiveByItem(item)"
                >
                  <span class="gs-item__icon"><WIcon :name="item.icon" :size="15" /></span>
                  <span class="gs-item__main">
                    <span class="gs-item__title">
                      <template v-for="(seg, index) in segmentsOf(item.title)" :key="`${item.id}-t-${index}`">
                        <mark v-if="seg.hit">{{ seg.text }}</mark>
                        <template v-else>{{ seg.text }}</template>
                      </template>
                    </span>
                    <span class="gs-item__meta">
                      <template v-for="(seg, index) in segmentsOf(item.meta)" :key="`${item.id}-m-${index}`">
                        <mark v-if="seg.hit">{{ seg.text }}</mark>
                        <template v-else>{{ seg.text }}</template>
                      </template>
                    </span>
                  </span>
                  <WTag :tone="item.badgeTone" plain class="gs-item__badge">{{ item.badge }}</WTag>
                  <WIcon name="arrow-right" :size="13" class="gs-item__arrow" />
                </button>
              </section>

              <WEmpty
                v-if="flatItems.length === 0"
                compact
                icon="search"
                title="没有匹配结果"
                description="试试：公摊 / 漏水 / 账单，或换一个关键词。"
              />
            </template>

            <!-- 空输入：最近搜索 + 快捷提示 -->
            <section v-else class="gs__recent">
              <p class="gs__recent-head">
                <span class="gs__group-label">最近搜索</span>
                <button v-if="recents.length" type="button" class="gs__recent-clear" @click="clearRecents">清空</button>
              </p>
              <div v-if="recents.length" class="gs__chips">
                <button
                  v-for="term in recents"
                  :key="term"
                  type="button"
                  class="gs-chip"
                  data-testid="search-recent-item"
                  @click="applyRecent(term)"
                >
                  <WIcon name="clock" :size="12" />
                  <span>{{ term }}</span>
                </button>
              </div>
              <p v-else class="gs__recent-empty">还没有搜索记录；输入关键词即可搜索知识、工单、会话与页面。</p>
              <p class="gs__hint">
                试试：<button type="button" class="gs__hint-link" @click="applyRecent('公摊')">公摊</button>
                <span class="gs__hint-sep">/</span>
                <button type="button" class="gs__hint-link" @click="applyRecent('漏水')">漏水</button>
                <span class="gs__hint-sep">/</span>
                <button type="button" class="gs__hint-link" @click="applyRecent('账单')">账单</button>
              </p>
            </section>
          </div>

          <!-- 底部提示 -->
          <footer class="gs__foot">
            <span class="gs__filter-note">
              按当前角色过滤（{{ app.currentRoleMeta.label }}视角 · 占位，非真实鉴权）
            </span>
            <span class="gs__keys">
              <span class="gs__key-hint"><kbd>↑</kbd><kbd>↓</kbd> 选择</span>
              <span class="gs__key-hint"><kbd>Enter</kbd> 打开</span>
              <span class="gs__key-hint"><kbd>Esc</kbd> 关闭</span>
            </span>
            <span class="gs__total">{{ hasQuery ? `${totalMatches} 条结果` : '演示索引 · mock' }}</span>
          </footer>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.gs {
  position: fixed;
  inset: 0;
  z-index: var(--z-modal);
  display: flex;
  justify-content: center;
  align-items: flex-start;
  padding: 10vh var(--sp-6) var(--sp-6);
}

.gs__mask {
  position: absolute;
  inset: 0;
  background: color-mix(in srgb, var(--c-ink) 38%, transparent);
  backdrop-filter: blur(2px);
}

/* 毛玻璃面板：token 派生 + 大阴影 + 大圆角 */
.gs__panel {
  position: relative;
  display: flex;
  flex-direction: column;
  width: 680px;
  max-width: calc(100vw - 48px);
  max-height: 74vh;
  overflow: hidden;
  border: 1px solid color-mix(in srgb, var(--c-line) 72%, transparent);
  border-radius: var(--rd-lg);
  background: color-mix(in srgb, var(--c-card) 88%, transparent);
  box-shadow: var(--sh-lg);
  backdrop-filter: blur(18px) saturate(150%);
}

/* ---------- 搜索框 ---------- */
.gs__head {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  flex: none;
  padding: 12px 14px;
  border-bottom: 1px solid var(--c-line-2);
}

.gs__head-icon {
  color: var(--c-brand);
}

.gs__input {
  flex: 1;
  min-width: 0;
  height: 26px;
  border: 0;
  outline: none;
  background: transparent;
  font-size: var(--fs-md);
  color: var(--c-ink);
}

.gs__input::placeholder {
  color: var(--c-faint);
}

.gs__kbd,
.gs__keys kbd {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 18px;
  height: 18px;
  padding: 0 4px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-xs);
  background: var(--c-bg-2);
  color: var(--c-faint);
  font-family: var(--font-sans);
  font-size: 9px;
  line-height: 1;
}

.gs__close {
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  border-radius: var(--rd-sm);
  color: var(--c-faint);
  transition: background-color var(--tr-fast), color var(--tr-fast);
}

.gs__close:hover {
  background: var(--c-bg-2);
  color: var(--c-ink-2);
}

.gs__close:focus-visible {
  outline: none;
  box-shadow: var(--sh-ring);
}

/* ---------- 结果区 ---------- */
.gs__body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 6px 8px 10px;
}

.gs__group + .gs__group {
  margin-top: 4px;
}

.gs__group-head {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  padding: 8px 8px 4px;
}

.gs__group-label {
  font-size: 9px;
  font-weight: var(--fw-semibold);
  letter-spacing: 1.2px;
  color: var(--c-faint);
  text-transform: uppercase;
}

.gs__group-count {
  font-size: 9px;
  color: var(--c-faint);
  opacity: 0.85;
}

.gs-item {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 8px 10px;
  border: 1px solid transparent;
  border-radius: var(--rd-md);
  text-align: left;
  transition: background-color var(--tr-fast), border-color var(--tr-fast);
}

.gs-item.is-active {
  border-color: color-mix(in srgb, var(--c-brand) 26%, transparent);
  background: var(--c-brand-soft);
}

.gs-item:focus-visible {
  outline: none;
  box-shadow: var(--sh-ring);
}

.gs-item__icon {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  flex: none;
  border-radius: var(--rd-sm);
  background: var(--c-bg-2);
  color: var(--c-muted);
}

.gs-item.is-active .gs-item__icon {
  background: var(--c-card);
  color: var(--c-brand);
}

.gs-item__main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  flex: 1;
}

.gs-item__title {
  overflow: hidden;
  font-size: var(--fs-sm);
  font-weight: var(--fw-medium);
  color: var(--c-ink-2);
  white-space: nowrap;
  text-overflow: ellipsis;
}

.gs-item__meta {
  overflow: hidden;
  font-size: 9.5px;
  color: var(--c-faint);
  white-space: nowrap;
  text-overflow: ellipsis;
}

.gs-item__title mark,
.gs-item__meta mark {
  padding: 0 1px;
  border-radius: var(--rd-xs);
  background: color-mix(in srgb, var(--c-brand) 20%, transparent);
  color: var(--c-brand-deep);
  font-weight: var(--fw-semibold);
}

.gs-item__badge {
  flex: none;
}

.gs-item__arrow {
  flex: none;
  color: var(--c-faint);
  opacity: 0;
  transition: opacity var(--tr-fast);
}

.gs-item.is-active .gs-item__arrow {
  opacity: 1;
  color: var(--c-brand);
}

/* ---------- 最近搜索 ---------- */
.gs__recent {
  padding: 8px 8px 4px;
}

.gs__recent-head {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  padding-bottom: 6px;
}

.gs__recent-clear {
  margin-left: auto;
  font-size: 9.5px;
  color: var(--c-faint);
  border-radius: var(--rd-xs);
  padding: 1px 4px;
}

.gs__recent-clear:hover {
  color: var(--c-ink-2);
  background: var(--c-bg-2);
}

.gs__chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.gs-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 25px;
  padding: 0 10px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-pill);
  background: var(--c-card);
  color: var(--c-ink-2);
  font-size: var(--fs-xs);
  transition: background-color var(--tr-fast), border-color var(--tr-fast), color var(--tr-fast);
}

.gs-chip:hover {
  border-color: var(--c-brand);
  background: var(--c-brand-soft);
  color: var(--c-brand);
}

.gs-chip:focus-visible {
  outline: none;
  box-shadow: var(--sh-ring);
}

.gs-chip .w-icon {
  color: var(--c-faint);
}

.gs__recent-empty {
  padding: 4px 2px 8px;
  font-size: var(--fs-xs);
  color: var(--c-faint);
  line-height: var(--lh-base);
}

.gs__hint {
  display: flex;
  align-items: center;
  gap: 4px;
  padding-top: 10px;
  font-size: var(--fs-xs);
  color: var(--c-faint);
}

.gs__hint-link {
  padding: 0 2px;
  color: var(--c-brand);
  font-size: var(--fs-xs);
  border-radius: var(--rd-xs);
}

.gs__hint-link:hover {
  text-decoration: underline;
}

.gs__hint-link:focus-visible {
  outline: none;
  box-shadow: var(--sh-ring);
}

.gs__hint-sep {
  color: var(--c-line);
}

/* ---------- 底部 ---------- */
.gs__foot {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  flex: none;
  padding: 8px 14px;
  border-top: 1px solid var(--c-line-2);
  background: color-mix(in srgb, var(--c-bg-2) 70%, transparent);
  font-size: 9.5px;
  color: var(--c-faint);
}

.gs__filter-note {
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.gs__keys {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  margin-left: auto;
}

.gs__key-hint {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  white-space: nowrap;
}

.gs__total {
  padding-left: var(--sp-3);
  border-left: 1px solid var(--c-line);
  white-space: nowrap;
}

/* 动画 */
.gs-fade-enter-active,
.gs-fade-leave-active {
  transition: opacity var(--tr-base);
}

.gs-fade-enter-active .gs__panel,
.gs-fade-leave-active .gs__panel {
  transition: transform var(--tr-base), opacity var(--tr-base);
}

.gs-fade-enter-from,
.gs-fade-leave-to {
  opacity: 0;
}

.gs-fade-enter-from .gs__panel,
.gs-fade-leave-to .gs__panel {
  transform: translateY(-8px) scale(0.99);
  opacity: 0;
}
</style>
