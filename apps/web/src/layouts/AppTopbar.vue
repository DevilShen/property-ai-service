<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { WIcon, WTag, useToast } from '@/components'
import GlobalSearch from '@/components/global/GlobalSearch.vue'
import NotificationCenter from '@/components/global/NotificationCenter.vue'
import ApiStatusBadge from '@/components/system/ApiStatusBadge.vue'
import { canAccess, firstAllowedPath } from '@/app/permissions'
import { ROLE_LABELS } from '@/app/roles'
import { useClickOutside } from '@/composables/useClickOutside'
import { useAppStore } from '@/stores/app'
import { useAuthStore } from '@/stores/auth'
import { PROJECT_STATUS_META, useProjectStore } from '@/stores/project'
import type { ActorRole } from '@/api/contract'
import type { Role } from '@/types'

/**
 * 顶栏（P2 W8 + P5 W19）
 * ===========================================================================
 * - 项目切换（P5 W19）：读 `GET /api/v1/projects`，it/admin 可切 3 个演示项目，
 *   owner/staff 只有本项目 → 不展示下拉（服务端强制数据范围，前端隐藏不是安全边界）；
 *   切换后写 `localStorage['property.project']`，业务请求由请求层自动追加 `?projectId=`，
 *   且 `AppShell` 的 `RouterView :key` 变化 → 当前业务页重挂载并重新拉取（不串数据）。
 * - 演示账号切换（P2 W8）：调登录 API 换 token（保留 `.w-topbar__trigger.is-role` /
 *   `.w-topbar__menu.is-wide` / 菜单项 `<b>角色名</b>` 结构，`tests/e2e/support/app.ts` 依赖）。
 *
 * demo 口径（AGENTS.md NEVER #11）：项目与账号都是**演示数据/演示身份**，不是真实组织架构与鉴权。
 */
const app = useAppStore()
const project = useProjectStore()
const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const toast = useToast()

/* ---------- 面包屑：项目 → 当前页 ---------- */
const breadcrumbs = computed<string[]>(() => {
  const titles = route.matched.filter((record) => record.meta.title).map((record) => record.meta.title as string)
  return [project.currentLabel, ...titles]
})

/* ---------- 项目 / 演示账号切换下拉 ---------- */
const projectOpen = ref(false)
const projectSwitching = ref(false)
const accountOpen = ref(false)
const accountSwitching = ref(false)
const projectRef = ref<HTMLElement | null>(null)
const accountRef = ref<HTMLElement | null>(null)

useClickOutside(projectRef, () => {
  projectOpen.value = false
})
useClickOutside(accountRef, () => {
  accountOpen.value = false
})

/** P5 W19：进入工作台即拉一次可见项目（失败不阻塞页面，chip 用演示兜底展示并给提示） */
onMounted(() => {
  void project.load()
})

/** 项目状态标签口径（契约 active/gray/archived） */
function projectStatusMeta(status: string) {
  return PROJECT_STATUS_META[status as keyof typeof PROJECT_STATUS_META] ?? PROJECT_STATUS_META.active
}

/**
 * 切换项目（P5 关键）：仅可切「当前角色可见项目」；切换后业务页由 AppShell 重挂载重新拉取。
 * 给可见反馈：按钮 loading + Toast（含「演示项目」口径），不伪造数据。
 */
async function switchProject(projectId: string) {
  if (projectSwitching.value || projectId === project.currentProjectId) {
    projectOpen.value = false
    return
  }
  projectSwitching.value = true
  try {
    const switched = project.select(projectId)
    projectOpen.value = false
    if (!switched) {
      toast.error('该项目不在当前演示角色可见范围内（越权由服务端 403 E-2002 兜底）')
      return
    }
    toast.info(`已切换到「${project.currentLabel}」· 业务页将按新项目重新拉取（演示项目）`)
    // 项目作用域变了 → 重新拉取列表（校正可见范围，避免跨角色残留）
    await project.load(true)
  } finally {
    projectSwitching.value = false
  }
}

/** 当前演示账号（登录态 → shared DEMO_ACCOUNTS 对应项） */
const currentAccount = computed(() => auth.demoAccounts.find((item) => item.role === auth.role) ?? null)

/**
 * 切换演示账号：调登录 API 换 token（失败保持原账号并提示，不静默改角色）。
 * 切换后若当前页对新角色不可见，回落到该角色第一个可见页（沿用 P1 可见性口径）。
 */
async function switchAccount(role: ActorRole) {
  if (accountSwitching.value) return
  if (auth.isRole(role)) {
    accountOpen.value = false
    return
  }
  accountSwitching.value = true
  try {
    const ok = await auth.loginAsRole(role)
    if (!ok) {
      toast.error(`演示账号切换失败：${auth.failure?.message ?? '登录接口不可用'}`)
      return
    }
    accountOpen.value = false
    const label = ROLE_LABELS[role as Role] ?? role
    toast.info(`已切换演示账号：${label} · ${currentAccount.value?.name ?? role}（demo，非真实鉴权）`)
    if (!canAccess(role as Role, route.path)) {
      await router.push(firstAllowedPath(role as Role))
    }
  } finally {
    accountSwitching.value = false
  }
}

/** 演示登出：撤销 token + 清本地登录态 → 回登录页（带 returnTo） */
async function signOut() {
  accountOpen.value = false
  const returnTo = route.fullPath
  await auth.logout()
  toast.info('已退出演示账号（demo）')
  await router.replace({ name: 'login', query: { returnTo } })
}

/* ---------- 全局搜索 / 通知中心：W3 已抽到 components/global，顶栏只保留入口与徽标 ---------- */
</script>

<template>
  <header class="w-topbar">
    <nav class="w-topbar__crumb" aria-label="面包屑">
      <template v-for="(crumb, index) in breadcrumbs" :key="`${crumb}-${index}`">
        <span class="w-topbar__crumb-item">
          <b v-if="index === breadcrumbs.length - 1">{{ crumb }}</b>
          <span v-else>{{ crumb }}</span>
        </span>
        <WIcon
          v-if="index < breadcrumbs.length - 1"
          name="chevron-right"
          :size="11"
          class="w-topbar__crumb-sep"
        />
      </template>
    </nav>

    <div class="grow" />

    <ApiStatusBadge />

    <button
      type="button"
      class="w-topbar__search"
      data-testid="global-search-trigger"
      aria-label="打开全局搜索（Ctrl K）"
      @click="app.setSearchOpen(true)"
    >
      <WIcon name="search" :size="14" />
      <span class="w-topbar__search-text">搜索知识 / 工单 / 会话</span>
      <kbd class="w-topbar__kbd">Ctrl K</kbd>
    </button>

    <button
      type="button"
      class="w-topbar__icon"
      data-testid="notification-trigger"
      aria-label="通知中心"
      @click="app.setNotificationOpen(true)"
    >
      <WIcon name="bell" :size="16" />
      <span v-if="app.unreadCount > 0" class="w-topbar__badge" data-testid="notification-badge">
        {{ app.unreadCount }}
      </span>
    </button>

    <!-- 项目切换（P5 W19：GET /projects；owner/staff 只有本项目 → 不展示下拉） -->
    <div ref="projectRef" class="w-topbar__switch">
      <button
        v-if="project.canSwitch"
        type="button"
        class="w-topbar__trigger"
        data-testid="project-switcher"
        :aria-expanded="projectOpen"
        aria-haspopup="listbox"
        :disabled="projectSwitching"
        :title="`当前项目：${project.currentLabel}（演示项目）`"
        @click="projectOpen = !projectOpen"
      >
        <span class="w-topbar__dot" :class="`is-${project.currentStatusMeta.dot}`" />
        <span class="w-topbar__trigger-label" data-testid="project-chip-label">{{ project.currentLabel }}</span>
        <WTag tone="v" plain data-testid="project-chip-mock">演示项目</WTag>
        <WIcon name="chevron-down" :size="13" />
      </button>
      <button
        v-else
        type="button"
        class="w-topbar__trigger is-static"
        data-testid="project-chip"
        :disabled="true"
        :title="`当前角色仅可见本项目「${project.currentLabel}」（切换需 it/admin；演示项目）`"
      >
        <span class="w-topbar__dot" :class="`is-${project.currentStatusMeta.dot}`" />
        <span class="w-topbar__trigger-label" data-testid="project-chip-label">{{ project.currentLabel }}</span>
        <WTag tone="v" plain data-testid="project-chip-mock">演示项目</WTag>
      </button>
      <div v-if="projectOpen && project.canSwitch" class="w-topbar__menu" data-testid="project-menu">
        <p class="w-topbar__menu-title">切换项目（{{ project.projects.length }} 个演示项目）</p>
        <button
          v-for="item in project.projects"
          :key="item.projectId"
          type="button"
          class="w-topbar__menu-item"
          :class="{ 'is-active': item.projectId === project.currentProjectId }"
          data-testid="project-option"
          :data-project="item.projectId"
          :data-status="item.status"
          @click="switchProject(item.projectId)"
        >
          <span class="w-topbar__menu-main">
            <b>{{ item.name }}</b>
            <small>{{ item.roomCount }} 户 · 主连接器 {{ item.connectorId }}</small>
          </span>
          <code class="w-topbar__code">{{ item.projectId }}</code>
          <WTag :tone="projectStatusMeta(item.status).label === '灰度中' ? 'a' : 'g'" plain>
            {{ projectStatusMeta(item.status).label }}
          </WTag>
          <WIcon v-if="item.projectId === project.currentProjectId" name="check" :size="14" class="w-topbar__menu-check" />
        </button>
        <p class="w-topbar__menu-hint" data-testid="project-menu-hint">
          演示项目（GET /api/v1/projects，非真实组织架构）：切换后 rooms / bills / tickets / complaints /
          审计等请求自动带 <code>?projectId=</code>，当前页重新拉取；跨项目访问由服务端 403 E-2002 兜底。
        </p>
      </div>
    </div>

    <!-- 演示账号切换（调登录 API；保留 .is-role / .is-wide / <b>角色名</b> 供 e2e 复用） -->
    <div ref="accountRef" class="w-topbar__switch">
      <button
        type="button"
        class="w-topbar__trigger is-role"
        data-testid="role-switcher"
        :aria-expanded="accountOpen"
        aria-haspopup="listbox"
        :disabled="accountSwitching"
        @click="accountOpen = !accountOpen"
      >
        <span class="w-topbar__avatar">{{ app.currentRoleMeta.short }}</span>
        <span class="w-topbar__trigger-col">
          <span class="w-topbar__trigger-label">{{ app.currentRoleMeta.label }}视角</span>
          <small class="w-topbar__trigger-sub">
            {{ currentAccount?.name ?? app.currentRoleMeta.label }} · 演示账号
          </small>
        </span>
        <WIcon name="chevron-down" :size="13" />
      </button>
      <div v-if="accountOpen" class="w-topbar__menu is-wide" data-testid="role-menu">
        <p class="w-topbar__menu-title">演示账号切换（demo · 调登录 API）</p>
        <button
          v-for="account in auth.demoAccounts"
          :key="account.account"
          type="button"
          class="w-topbar__menu-item"
          :class="{ 'is-active': account.role === auth.role }"
          data-testid="role-option"
          :data-role="account.role"
          :data-account="account.account"
          :disabled="accountSwitching"
          @click="switchAccount(account.role)"
        >
          <span class="w-topbar__avatar is-small">{{ ROLE_LABELS[account.role]?.slice(0, 1) ?? '演' }}</span>
          <span class="w-topbar__menu-main">
            <b>{{ ROLE_LABELS[account.role] ?? account.role }}</b>
            <small>{{ account.name }} · <code class="w-topbar__code">{{ account.account }}</code></small>
          </span>
          <WTag tone="v" plain>demo</WTag>
          <WIcon v-if="account.role === auth.role" name="check" :size="14" class="w-topbar__menu-check" />
        </button>
        <div class="w-topbar__menu-actions">
          <button type="button" class="w-topbar__menu-logout" data-testid="demo-logout" @click="signOut">
            <WIcon name="lock" :size="12" />
            退出演示账号
          </button>
          <span class="w-topbar__menu-hint is-inline">演示账号 + 固定口令登录，非真实鉴权</span>
        </div>
      </div>
    </div>
  </header>

  <!-- 全局搜索（W3：知识 / 工单 / 会话 / 页面 / 工作区，⌘K 直达） -->
  <GlobalSearch />

  <!-- 通知中心（W3：未读徽标 / 全部·未读筛选 / 单条已读 / 全部已读） -->
  <NotificationCenter />
</template>

<style scoped>
.w-topbar {
  display: flex;
  align-items: center;
  gap: 14px;
  flex: none;
  height: var(--layout-topbar-h);
  padding: 0 22px;
  background: var(--c-card);
  border-bottom: 1px solid var(--c-line);
}

.w-topbar__crumb {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  font-size: var(--fs-sm);
  color: var(--c-faint);
}

.w-topbar__crumb-item {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 220px;
}

.w-topbar__crumb-item b {
  font-size: var(--fs-base);
  font-weight: var(--fw-semibold);
  color: var(--c-ink-2);
}

.w-topbar__crumb-sep {
  color: #c7d2da;
}

.w-topbar__search {
  display: flex;
  align-items: center;
  gap: 7px;
  width: 236px;
  height: 32px;
  padding: 0 10px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-md);
  background: var(--c-bg-2);
  color: var(--c-faint);
  font-size: var(--fs-sm);
  transition: border-color var(--tr-fast), background-color var(--tr-fast);
}

.w-topbar__search:hover {
  border-color: #cbd7de;
  background: var(--c-card);
}

.w-topbar__search:focus-visible {
  outline: none;
  box-shadow: var(--sh-ring);
}

.w-topbar__search-text {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.w-topbar__kbd {
  margin-left: auto;
  padding: 1px 4px;
  border: 1px solid #dde5ea;
  border-radius: var(--rd-xs);
  background: var(--c-card);
  color: #9aabb8;
  font-family: var(--font-sans);
  font-size: 9px;
  white-space: nowrap;
}

.w-topbar__icon {
  position: relative;
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  border: 1px solid transparent;
  border-radius: var(--rd-md);
  color: var(--c-muted);
  transition: background-color var(--tr-fast), border-color var(--tr-fast);
}

.w-topbar__icon:hover {
  background: #f4f7f9;
  border-color: var(--c-line);
}

.w-topbar__icon:focus-visible {
  outline: none;
  box-shadow: var(--sh-ring);
}

.w-topbar__badge {
  position: absolute;
  top: 3px;
  right: 3px;
  min-width: 14px;
  height: 14px;
  padding: 0 3px;
  border-radius: var(--rd-pill);
  background: var(--c-red);
  color: #fff;
  font-size: 9px;
  font-weight: var(--fw-semibold);
  line-height: 14px;
  text-align: center;
}

/* 下拉 */
.w-topbar__switch {
  position: relative;
}

.w-topbar__trigger {
  display: flex;
  align-items: center;
  gap: 7px;
  height: 32px;
  padding: 0 9px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-md);
  background: var(--c-card);
  color: var(--c-ink-2);
  font-size: var(--fs-sm);
  transition: background-color var(--tr-fast), border-color var(--tr-fast);
}

.w-topbar__trigger:hover {
  background: var(--c-bg-2);
  border-color: #cbd7de;
}

.w-topbar__trigger:focus-visible {
  outline: none;
  box-shadow: var(--sh-ring);
}

.w-topbar__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #2bb673;
  box-shadow: 0 0 0 3px rgba(43, 182, 115, 0.12);
}

.w-topbar__dot.is-gray {
  background: #e8a23a;
  box-shadow: 0 0 0 3px rgba(232, 162, 58, 0.14);
}

.w-topbar__dot.is-archived {
  background: #b4c2cc;
  box-shadow: 0 0 0 3px rgba(180, 194, 204, 0.16);
}

/* 单项目角色（owner/staff）：静态项目 chip，不提供切换入口（越权由服务端 403 兜底） */
.w-topbar__trigger.is-static {
  cursor: default;
  opacity: 1;
  background: var(--c-bg-2);
}

.w-topbar__trigger.is-static:disabled {
  opacity: 1;
}

.w-topbar__trigger-label {
  font-weight: var(--fw-medium);
  white-space: nowrap;
}

.w-topbar__trigger-col {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  line-height: 1.15;
}

.w-topbar__trigger-sub {
  font-size: 9px;
  color: var(--c-faint);
}

.w-topbar__avatar {
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  flex: none;
  border-radius: 50%;
  background: linear-gradient(140deg, #123b4e, #0a7a66);
  color: #fff;
  font-size: 9.5px;
  font-weight: var(--fw-semibold);
}

.w-topbar__avatar.is-small {
  width: 22px;
  height: 22px;
  font-size: 9px;
}

.w-topbar__menu {
  position: absolute;
  top: calc(100% + 6px);
  right: 0;
  z-index: var(--z-dropdown);
  width: 268px;
  padding: 6px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-lg);
  background: var(--c-card);
  box-shadow: var(--sh-lg);
}

.w-topbar__menu.is-wide {
  width: 300px;
}

.w-topbar__menu-title {
  padding: 6px 8px 5px;
  font-size: 9px;
  font-weight: var(--fw-semibold);
  letter-spacing: 1.2px;
  color: var(--c-faint);
  text-transform: uppercase;
}

.w-topbar__menu-item {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 7px 8px;
  border-radius: var(--rd-md);
  text-align: left;
  transition: background-color var(--tr-fast);
}

.w-topbar__menu-item:hover {
  background: var(--c-bg-2);
}

.w-topbar__menu-item.is-active {
  background: var(--c-brand-soft);
}

.w-topbar__menu-item:focus-visible {
  outline: none;
  box-shadow: var(--sh-ring);
}

.w-topbar__menu-main {
  display: flex;
  flex-direction: column;
  min-width: 0;
  flex: 1;
}

.w-topbar__menu-main b {
  font-size: var(--fs-sm);
  font-weight: var(--fw-medium);
  color: var(--c-ink-2);
}

.w-topbar__menu-main small {
  font-size: 9.5px;
  color: var(--c-faint);
  line-height: 1.4;
}

.w-topbar__menu-check {
  color: var(--c-brand);
}

.w-topbar__menu-hint {
  padding: 6px 8px 2px;
  border-top: 1px dashed var(--c-line-2);
  margin-top: 4px;
  font-size: 9px;
  color: var(--c-faint);
  line-height: 1.5;
}

.w-topbar__menu-hint.is-inline {
  border: 0;
  margin: 0;
  padding: 0;
}

.w-topbar__code {
  font-family: var(--font-mono);
  font-size: 9px;
}

.w-topbar__trigger:disabled {
  opacity: 0.7;
  cursor: progress;
}

.w-topbar__menu-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 5px;
  padding: 6px 8px 2px;
  border-top: 1px dashed var(--c-line-2);
}

.w-topbar__menu-logout {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 8px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-sm);
  background: var(--c-card);
  color: var(--c-muted);
  font-size: 10px;
  transition: border-color var(--tr-fast), color var(--tr-fast);
}

.w-topbar__menu-logout:hover {
  border-color: #e7b9b2;
  color: var(--c-red);
}

.w-topbar__menu-logout:focus-visible {
  outline: none;
  box-shadow: var(--sh-ring);
}
</style>