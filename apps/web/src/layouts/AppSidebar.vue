<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { WIcon } from '@/components'
import { visibleNavGroups } from '@/app/permissions'
import { useAppStore } from '@/stores/app'
import { useProjectStore } from '@/stores/project'

const app = useAppStore()
const project = useProjectStore()
const route = useRoute()

/** 当前角色可见的导航分组（业主角色看不到管理端分组） */
const groups = computed(() => visibleNavGroups(app.currentRole))

function isActive(path: string): boolean {
  return route.path === path || route.path.startsWith(`${path}/`)
}
</script>

<template>
  <aside class="w-sidebar" aria-label="主导航">
    <div class="w-sidebar__brand">
      <span class="w-sidebar__logo" aria-hidden="true">物</span>
      <span class="w-sidebar__brand-text">
        <b>物业 AI 智能客服</b>
        <small>PROPERTY AI COPILOT</small>
      </span>
    </div>

    <!-- 当前项目 chip（P5 W19：与顶栏同源 stores/project，读 GET /api/v1/projects） -->
    <div
      class="w-sidebar__project"
      :title="`${project.currentMetaText}（演示项目，GET /api/v1/projects）`"
      data-testid="sidebar-project-chip"
      :data-project="project.activeProjectId"
    >
      <span class="w-sidebar__project-dot" :class="`is-${project.currentStatusMeta.dot}`" />
      <span class="w-sidebar__project-name" data-testid="sidebar-project-name">{{ project.currentLabel }}</span>
      <span class="w-sidebar__project-status">{{ project.currentStatusMeta.label }} · 演示项目</span>
    </div>

    <nav class="w-sidebar__nav">
      <div v-for="group in groups" :key="group.key" class="w-sidebar__group">
        <p class="w-sidebar__group-label">{{ group.label }}</p>
        <RouterLink
          v-for="item in group.items"
          :key="item.key"
          :to="item.path"
          class="w-sidebar__item"
          :class="{ 'is-active': isActive(item.path) }"
        >
          <WIcon :name="item.icon" :size="15" />
          <span class="w-sidebar__item-label">{{ item.label }}</span>
          <span v-if="item.badge" class="w-sidebar__item-badge">{{ item.badge }}</span>
        </RouterLink>
      </div>
    </nav>

    <footer class="w-sidebar__foot">
      <p class="w-sidebar__foot-line"><b>P1</b> 演示环境</p>
      <p class="w-sidebar__foot-line is-dim">12 屏 + 8 工作区 · 数据为 mock</p>
      <RouterLink class="w-sidebar__foot-link" to="/ui-kit">组件预览 /ui-kit</RouterLink>
    </footer>
  </aside>
</template>

<style scoped>
.w-sidebar {
  position: relative;
  display: flex;
  flex-direction: column;
  flex: none;
  width: var(--layout-sidebar-w);
  height: 100vh;
  padding: 16px 12px 12px;
  color: var(--c-nav-text);
  background: linear-gradient(178deg, var(--c-nav-start) 0%, var(--c-nav-mid) 62%, var(--c-nav-end) 100%);
  overflow: hidden;
}

.w-sidebar::after {
  content: '';
  position: absolute;
  top: -80px;
  right: -70px;
  width: 200px;
  height: 200px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(18, 164, 140, 0.2), transparent 68%);
  pointer-events: none;
}

.w-sidebar__brand {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 2px 6px 16px;
}

.w-sidebar__logo {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  flex: none;
  border-radius: 10px;
  background: var(--c-brand-gradient);
  color: #fff;
  font-size: 15px;
  font-weight: var(--fw-bold);
  letter-spacing: 0.5px;
  box-shadow: 0 4px 12px rgba(10, 122, 102, 0.35);
}

.w-sidebar__brand-text b {
  display: block;
  font-size: 13.5px;
  font-weight: var(--fw-semibold);
  letter-spacing: 0.2px;
  color: #fff;
}

.w-sidebar__brand-text small {
  display: block;
  margin-top: 2px;
  font-size: 8.5px;
  font-weight: var(--fw-medium);
  letter-spacing: 1.2px;
  color: #6f8a97;
}

.w-sidebar__project {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  gap: 7px;
  margin: 0 2px 14px;
  padding: 8px 10px;
  border: 1px solid rgba(255, 255, 255, 0.07);
  border-radius: 9px;
  background: rgba(255, 255, 255, 0.055);
  font-size: 11.5px;
  color: #d7e4ea;
}

.w-sidebar__project-dot {
  width: 6px;
  height: 6px;
  flex: none;
  border-radius: 50%;
  background: #2bd9b4;
  box-shadow: 0 0 0 3px rgba(43, 217, 180, 0.14);
}

.w-sidebar__project-dot.is-gray {
  background: #e8a23a;
  box-shadow: 0 0 0 3px rgba(232, 162, 58, 0.16);
}

.w-sidebar__project-dot.is-archived,
.w-sidebar__project-dot.is-planned {
  background: #7b8b96;
  box-shadow: 0 0 0 3px rgba(123, 139, 150, 0.16);
}

.w-sidebar__project-name {
  font-weight: var(--fw-medium);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.w-sidebar__project-status {
  margin-left: auto;
  font-size: 9px;
  color: #7f9aa6;
  white-space: nowrap;
}

.w-sidebar__nav {
  position: relative;
  z-index: 1;
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
}

.w-sidebar__group {
  margin-bottom: 2px;
}

.w-sidebar__group-label {
  padding: 12px 10px 6px;
  font-size: 9px;
  font-weight: var(--fw-semibold);
  letter-spacing: 2px;
  color: var(--c-nav-label);
}

.w-sidebar__item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 9px;
  height: 35px;
  margin-bottom: 1px;
  padding: 0 10px;
  border-radius: 9px;
  font-size: 12px;
  color: var(--c-nav-text);
  transition: background-color var(--tr-fast), color var(--tr-fast);
}

.w-sidebar__item :deep(.w-icon) {
  color: var(--c-nav-icon);
  transition: color var(--tr-fast);
}

.w-sidebar__item:hover {
  background: var(--c-nav-hover);
  color: var(--c-nav-text-strong);
}

.w-sidebar__item:focus-visible {
  outline: none;
  box-shadow: var(--sh-ring);
}

.w-sidebar__item.is-active {
  background: var(--c-nav-active);
  color: #fff;
  font-weight: var(--fw-semibold);
  box-shadow: inset 0 0 0 1px rgba(43, 217, 180, 0.16);
}

.w-sidebar__item.is-active::before {
  content: '';
  position: absolute;
  left: 0;
  top: 9px;
  bottom: 9px;
  width: 2.5px;
  border-radius: 2px;
  background: var(--c-nav-accent);
}

.w-sidebar__item.is-active :deep(.w-icon) {
  color: #4fe0c0;
}

.w-sidebar__item-label {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.w-sidebar__item-badge {
  margin-left: auto;
  padding: 1px 5px;
  border-radius: 4px;
  background: rgba(79, 70, 229, 0.28);
  color: #bdb8ff;
  font-size: 8.5px;
  font-weight: var(--fw-semibold);
  letter-spacing: 0.3px;
}

.w-sidebar__foot {
  position: relative;
  z-index: 1;
  flex: none;
  margin-top: 8px;
  padding: 11px 8px 2px;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
}

.w-sidebar__foot-line {
  font-size: 9.5px;
  line-height: 1.7;
  color: #5e7885;
}

.w-sidebar__foot-line b {
  font-weight: var(--fw-medium);
  color: #8fa8b4;
}

.w-sidebar__foot-line.is-dim {
  color: #4f6774;
}

.w-sidebar__foot-link {
  display: inline-block;
  margin-top: 3px;
  font-size: 9.5px;
  color: #8fa8b4;
  text-decoration: underline;
  text-underline-offset: 2px;
}

.w-sidebar__foot-link:hover {
  color: #4fe0c0;
}
</style>
