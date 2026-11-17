<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { WButton, WCard, WEmpty, WPageHeader, WTag, useToast } from '@/components'
import {
  MATRIX_ROLES,
  capabilitiesOf,
  denyReason,
  firstAllowedPath,
  navItemForPath,
  screenMatrix,
  visibleNav,
} from '@/app/permissions'
import { ROLE_LABELS } from '@/app/roles'
import { capabilityForPath, capabilityLabel } from '@/auth/capabilities'
import { useAppStore } from '@/stores/app'
import { useAuthStore } from '@/stores/auth'
import type { ActorRole } from '@/api/contract'

/**
 * 无权限页（403 兜底，P1-027 起；P2 W8 接登录态）
 * ---------------------------------------------------------------------------
 * 两类拦截都会落到这里：
 * 1) **前端可见性**（演示矩阵）：当前登录角色在 12 屏矩阵里看不到该页 → 守卫跳 `/forbidden?from=`；
 * 2) **后端 403**（`E-2001` + `details.requiredCapability|role`）：接口越权时页面拿到 ApiError
 *    并展示所需能力（见 ERP 审计面板的无权限态）。
 *
 * demo 口径：这里说明的是「演示账号的角色能力」，切换账号 = 调 `POST /api/v1/auth/login` 换演示 JWT；
 * 真实鉴权由 Java BFF `SecurityFilter` 的角色 × 能力矩阵判定，前端只做入口隐藏与说明。
 */
const app = useAppStore()
const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const toast = useToast()

const switching = ref(false)

const from = computed(() => (typeof route.query.from === 'string' ? route.query.from : ''))
const allowed = computed(() => firstAllowedPath(app.currentRole))
const roleLabel = computed(() => ROLE_LABELS[app.currentRole])
const reason = computed(() => denyReason(app.currentRole, from.value || '/'))
const blockedTarget = computed(() => navItemForPath(from.value)?.label ?? from.value ?? '未知路径')
const visibleScreens = computed(() => visibleNav(app.currentRole))
const actions = computed(() => capabilitiesOf(app.currentRole))
const matrix = computed(() => screenMatrix())
/** 后端能力门槛（如 /erp → connector.read、/mapping → mapping.read）；没有则说明是可见性拦截 */
const requiredCapability = computed(() => capabilityForPath(from.value))
/** 当前演示账号（用于展示「谁被拦了」，切换账号见下方按钮） */
const currentAccount = computed(() => auth.demoAccounts.find((item) => item.role === auth.role) ?? null)

function goBack() {
  void router.push(allowed.value)
}

/** 切到管理员演示账号（调用登录 API），再回到被拦截页面 */
async function switchToAdmin() {
  await switchAccount('admin', from.value || '/overview')
}

async function switchRole(role: ActorRole) {
  await switchAccount(role, firstAllowedPath(role))
}

/** 切换演示账号并跳转；失败保持原账号并提示（不静默改角色） */
async function switchAccount(role: ActorRole, target: string) {
  if (switching.value) return
  if (role === auth.role) {
    void router.push(target)
    return
  }
  switching.value = true
  try {
    const ok = await auth.loginAsRole(role)
    if (!ok) {
      toast.error(`演示账号切换失败：${auth.failure?.message ?? '登录接口不可用'}`)
      return
    }
    toast.info(`已切换演示账号：${ROLE_LABELS[role]}（demo，非真实鉴权）`)
    void router.push(target)
  } finally {
    switching.value = false
  }
}
</script>

<template>
  <div class="w-page" data-page="forbidden" data-testid="page-root">
    <WPageHeader eyebrow="权限说明 · 403 无权限态" title="当前角色无权访问该页面" :description="reason">
      <template #actions>
        <WTag tone="a" data-testid="forbidden-403-badge">403 · 无权限（演示账号）</WTag>
        <WTag tone="b" plain>当前角色：{{ roleLabel }}</WTag>
        <WTag v-if="currentAccount" tone="v" plain data-testid="forbidden-current-account">
          演示账号 {{ currentAccount.account }}
        </WTag>
      </template>
    </WPageHeader>

    <div class="fb-grid">
      <WCard title="被拦截的路径" :subtitle="from || '（未提供来源路径）'">
        <WEmpty
          icon="lock"
          :title="`「${blockedTarget}」未对「${roleLabel}」开放`"
          :description="`当前角色可访问的入口：${allowed}。可回到可访问页面，或切换有权限的演示账号继续演示。`"
        >
          <WButton size="sm" variant="primary" @click="goBack">回到可访问页面</WButton>
          <WButton size="sm" :loading="switching" @click="switchToAdmin">切换到管理员视角</WButton>
        </WEmpty>
      </WCard>

      <WCard
        title="所需能力与演示账号"
        subtitle="前端可见性只隐藏入口；接口级 403 由 Java BFF 角色 × 能力矩阵校验（E-2001）"
      >
        <div class="fb-block">
          <span class="fb-label">拦截来源</span>
          <p class="fb-line" data-testid="forbidden-required-capability">
            <template v-if="requiredCapability">
              该页依赖后端能力 <code>{{ capabilityLabel(requiredCapability) }}</code>；前端可见性矩阵在演示环境先行拦截，
              接口越权时 Java 返回 403 <code>E-2001</code> + <code>details.requiredCapability</code>。
            </template>
            <template v-else>
              该路径不设后端能力门槛，属于 12 屏「演示可见性矩阵」拦截（不是接口 403）。
            </template>
          </p>
        </div>

        <div class="fb-block">
          <span class="fb-label">切换演示账号（调 POST /api/v1/auth/login）</span>
          <div class="fb-tags">
            <WButton
              v-for="account in auth.demoAccounts"
              :key="account.account"
              size="sm"
              :variant="account.role === auth.role ? 'primary' : 'ghost'"
              :loading="switching"
              :data-testid="`forbidden-switch-${account.role}`"
              @click="switchAccount(account.role, account.role === 'admin' ? from || '/overview' : firstAllowedPath(account.role))"
            >
              {{ ROLE_LABELS[account.role] }}
            </WButton>
          </div>
          <p class="fb-line dim">
            演示账号 + 固定口令（demo）登录，登录态写本地并带 Bearer 调接口；不是真实 SSO/微信/短信鉴权。
          </p>
        </div>

        <div class="fb-block">
          <span class="fb-label">快速切换（保留原有入口）</span>
          <div class="fb-tags">
            <WButton
              v-for="role in MATRIX_ROLES"
              :key="`quick-${role}`"
              size="sm"
              :variant="role === app.currentRole ? 'primary' : 'ghost'"
              :loading="switching"
              @click="switchRole(role)"
            >
              {{ ROLE_LABELS[role] }}
            </WButton>
          </div>
        </div>

        <div class="fb-block">
          <span class="fb-label">当前角色可见范围 · {{ visibleScreens.length }}/12</span>
          <div class="fb-tags">
            <WTag v-for="item in visibleScreens" :key="item.key" tone="g" plain>{{ item.label }}</WTag>
          </div>
        </div>

        <div class="fb-block">
          <span class="fb-label">可执行动作 · {{ actions.length }} 项</span>
          <div class="fb-action-list">
            <div v-for="action in actions" :key="action.key" class="fb-action">
              <b>{{ action.label }}</b>
              <span>{{ action.desc }}</span>
            </div>
          </div>
        </div>
      </WCard>
    </div>

    <WCard
      title="权限矩阵 · 12 屏 × 6 角色"
      subtitle="✓ 可见 / — 不可见；入口可见性为演示矩阵，接口越权由后端 403 兜底"
    >
      <div class="fb-matrix" data-testid="permission-matrix">
        <div class="fb-matrix__head">
          <span class="fb-matrix__cell fb-matrix__cell--page">页面</span>
          <span
            v-for="role in MATRIX_ROLES"
            :key="role"
            class="fb-matrix__cell fb-matrix__cell--role"
            :class="{ 'is-current': role === app.currentRole }"
          >
            {{ ROLE_LABELS[role] }}
          </span>
        </div>
        <div v-for="row in matrix" :key="row.key" class="fb-matrix__row" :class="{ 'is-blocked': row.label === blockedTarget }">
          <span class="fb-matrix__cell fb-matrix__cell--page">{{ row.label }}</span>
          <span
            v-for="role in MATRIX_ROLES"
            :key="role"
            class="fb-matrix__cell fb-matrix__cell--role"
            :class="{ 'is-current': role === app.currentRole, 'is-yes': row.roles.includes(role) }"
          >
            {{ row.roles.includes(role) ? '✓' : '—' }}
          </span>
        </div>
      </div>
    </WCard>
  </div>
</template>

<style scoped>
.fb-grid {
  display: grid;
  grid-template-columns: minmax(280px, 420px) minmax(0, 1fr);
  gap: 12px;
  align-items: start;
}

.fb-block + .fb-block {
  margin-top: 10px;
}

.fb-label {
  display: block;
  margin-bottom: 5px;
  font-size: var(--fs-2xs);
  color: var(--c-faint);
}

.fb-line {
  font-size: 10px;
  color: var(--c-muted);
  line-height: 1.6;
}

.fb-line code {
  padding: 0 3px;
  border-radius: var(--rd-xs);
  background: var(--c-bg-2);
  font-family: var(--font-mono);
  font-size: 9.5px;
  color: var(--c-ink-2);
}

.fb-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}

.fb-action-list {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.fb-action {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 5px 8px;
  border: 1px solid var(--c-line-2);
  border-radius: var(--rd-sm);
  background: var(--c-bg-2);
}

.fb-action b {
  flex: none;
  font-size: var(--fs-xs);
  font-weight: var(--fw-medium);
  color: var(--c-ink-2);
}

.fb-action span {
  font-size: 9.5px;
  line-height: 1.5;
  color: var(--c-muted);
}

.fb-matrix {
  display: flex;
  flex-direction: column;
  border: 1px solid var(--c-line-2);
  border-radius: var(--rd-md);
  overflow: hidden;
}

.fb-matrix__head,
.fb-matrix__row {
  display: grid;
  grid-template-columns: minmax(140px, 1.6fr) repeat(6, minmax(56px, 1fr));
  align-items: center;
}

.fb-matrix__head {
  background: var(--c-bg-2);
  border-bottom: 1px solid var(--c-line);
}

.fb-matrix__row + .fb-matrix__row {
  border-top: 1px solid var(--c-line-2);
}

.fb-matrix__row:hover {
  background: var(--c-bg-2);
}

.fb-matrix__row.is-blocked {
  background: var(--c-red-soft);
}

.fb-matrix__cell {
  padding: 6px 8px;
  font-size: 9.5px;
  color: var(--c-muted);
}

.fb-matrix__cell--page {
  color: var(--c-ink-2);
  font-size: var(--fs-xs);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.fb-matrix__cell--role {
  text-align: center;
  font-family: var(--font-mono);
}

.fb-matrix__head .fb-matrix__cell--role {
  color: var(--c-faint);
  font-family: var(--font-sans);
  white-space: nowrap;
}

.fb-matrix__cell.is-current {
  background: var(--c-brand-soft);
  color: var(--c-brand-deep);
}

.fb-matrix__cell--role.is-yes {
  color: var(--c-success);
  font-weight: var(--fw-semibold);
}

@media (max-width: 1100px) {
  .fb-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
