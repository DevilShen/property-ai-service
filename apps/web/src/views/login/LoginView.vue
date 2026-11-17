<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { WCard, WIcon, WTag } from '@/components'
import { DEMO_PASSWORD } from '@/api'
import { capabilityLabel, capabilitiesOfRole } from '@/auth/capabilities'
import { ROLES } from '@/app/roles'
import { safeReturnTo, landingAfterLogin } from '@/router'
import { useAuthStore } from '@/stores/auth'
import type { ActorRole } from '@/api/contract'

/**
 * 演示登录页（P2 W8，契约 v0.3.3 §11.9.1 / P2-029）
 * ===========================================================================
 * - 独立路由 `/login`（不进 12 屏导航、不渲染 AppShell）；
 * - 6 个演示账号卡片一键登录（`POST /api/v1/auth/login`，口令统一 `DEMO_PASSWORD`），
 *   每张卡展示该角色**实际具备的能力**（来源 `auth/capabilities.ts`，与 Java PermissionMatrix 同口径）；
 * - 失败显示 401 人话（账号或密码不正确，不区分账号是否存在），不泄露内部错误码；
 * - 登录成功后回跳 `?returnTo=`（站内相对路径，防开放重定向），否则落到该角色第一个可见页。
 *
 * **demo 口径**：演示账号 + 固定口令 + 本地演示 JWT，不是真实 SSO/微信/短信鉴权；
 * 页面与卡片均标注「演示账号（demo）」。只展示演示口令（README §2.1 公开演示信息），不存口令。
 */
const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const returnTo = computed(() => safeReturnTo(route.query.returnTo))
const returnToText = computed(() => returnTo.value ?? '')
const roleLabels = computed<Record<ActorRole, string>>(
  () =>
    ROLES.reduce(
      (acc, item) => ({ ...acc, [item.key]: item.label }),
      {} as Record<ActorRole, string>,
    ),
)

/** 登录过期提示：401 出口清 token 后带了 returnTo，说明用户是被「请重新登录」送来的 */
const expiredHint = computed(() => (returnTo.value && !auth.isAuthed ? '登录已过期或未登录，请选择演示账号重新登录。' : ''))

const capabilityCount = (accountRole: ActorRole): number => capabilitiesOfRole(accountRole).length

async function signIn(accountRole: ActorRole): Promise<void> {
  const ok = await auth.loginAsRole(accountRole)
  if (!ok) return
  const target = landingAfterLogin(auth.role ?? accountRole, route.query.returnTo)
  await router.replace(target)
}

onMounted(() => {
  auth.clearFailure()
})
</script>

<template>
  <div class="login" data-page="login" data-testid="login-page">
    <div class="login__glow" aria-hidden="true" />

    <header class="login__head">
      <div class="login__brand">
        <span class="login__logo">物</span>
        <div class="login__brand-text">
          <b>物业 AI 智能客服</b>
          <span>P2 演示环境 · 统一工作台</span>
        </div>
      </div>
      <WTag tone="v" data-testid="login-demo-badge">演示账号（demo）· 非真实鉴权</WTag>
    </header>

    <div class="login__body">
      <section class="login__intro">
        <p class="login__eyebrow">P2 W8 · 演示登录</p>
        <h1 class="login__title">选择一个演示账号进入工作台</h1>
        <p class="login__desc">
          演示登录签发本地演示 JWT（有效期 2 小时），登录后请求统一带
          <code>Authorization: Bearer</code>；角色能力由 Java BFF 校验，未登录/凭证失效返回 401、
          越权返回 403。这里不是真实 SSO / 微信 / 短信鉴权，账号不含真实手机号与工号。
        </p>

        <ul class="login__facts">
          <li>
            <WIcon name="shield" :size="13" />
            <span>演示口令统一 <code>{{ DEMO_PASSWORD }}</code>（README §2.1 公开演示信息，不落浏览器存储）</span>
          </li>
          <li>
            <WIcon name="user" :size="13" />
            <span>6 个演示账号：业主 / 管家 / 坐席 / 主管 / IT / 管理员</span>
          </li>
          <li>
            <WIcon name="lock" :size="13" />
            <span>登录态持久到 <code>localStorage['property.auth']</code>；401 自动回登录页并带 returnTo</span>
          </li>
        </ul>

        <p v-if="expiredHint" class="login__hint" data-testid="login-expired-hint">{{ expiredHint }}</p>
        <p v-if="returnToText" class="login__hint" data-testid="login-return-to">
          登录成功后回到：<code>{{ returnToText }}</code>
        </p>

        <p v-if="auth.failure" class="login__error" role="alert" data-testid="login-error">
          <b>登录失败：</b>{{ auth.failure.message }}<br />
          <span class="login__error-next">{{ auth.failure.next }}</span>
        </p>
      </section>

      <section class="login__accounts" aria-label="演示账号">
        <div
          v-for="account in auth.demoAccounts"
          :key="account.account"
          class="login__card"
          :class="{ 'is-current': auth.role === account.role }"
          role="button"
          tabindex="0"
          :aria-disabled="auth.pending"
          data-testid="login-account-card"
          :data-role="account.role"
          :data-account="account.account"
          @click="auth.pending ? undefined : signIn(account.role)"
          @keydown.enter.prevent="auth.pending ? undefined : signIn(account.role)"
          @keydown.space.prevent="auth.pending ? undefined : signIn(account.role)"
        >
          <header class="login__card-head">
            <span class="login__avatar">{{ roleLabels[account.role]?.slice(0, 1) ?? '演' }}</span>
            <span class="login__card-titles">
              <b>{{ roleLabels[account.role] ?? account.role }}视角</b>
              <span>{{ account.name }} · <code>{{ account.account }}</code></span>
            </span>
            <WTag tone="v" plain>demo</WTag>
          </header>

          <p class="login__card-caps">
            <span class="login__card-caps-label">
              能力 {{ capabilityCount(account.role) }} 项
              <template v-if="account.projectId"> · 项目 {{ account.projectId }}</template>
              <template v-else> · 平台级</template>
            </span>
            <span class="login__caps">
              <code v-for="cap in account.capabilities.slice(0, 4)" :key="cap" :title="capabilityLabel(cap)">
                {{ cap }}
              </code>
              <span v-if="account.capabilities.length > 4" class="login__caps-more">
                +{{ account.capabilities.length - 4 }}
              </span>
            </span>
          </p>

          <footer class="login__card-foot">
            <span class="login__card-cta" data-testid="login-submit">
              {{ auth.role === account.role ? '当前账号 · 重新登录' : '一键登录' }}
              <WIcon name="arrow-right" :size="12" />
            </span>
            <span class="login__card-hint">
              {{ account.role === 'owner' ? '业主端 / 工单进度' : '内部员工视角' }}
            </span>
          </footer>
        </div>
      </section>
    </div>

    <WCard flat class="login__note">
      <p>
        <b>边界说明：</b>演示登录只用于 P2 联调与验收（演示账号 + 固定口令 + 本地演示 JWT），
        不等于生产鉴权；生产由 SSO / 微信 / 短信与密钥轮换承接（P6）。页面不会把演示身份说成真实账号。
      </p>
    </WCard>
  </div>
</template>

<style scoped>
.login {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
  min-height: 100vh;
  padding: 28px clamp(20px, 5vw, 72px) 32px;
  overflow: hidden auto;
  background:
    radial-gradient(1200px 520px at 8% -10%, #e8f6f0 0%, transparent 60%),
    radial-gradient(900px 480px at 96% 0%, #eef3fb 0%, transparent 58%),
    var(--c-bg);
}

.login__glow {
  position: absolute;
  inset: 0;
  pointer-events: none;
  background-image: radial-gradient(circle at 1px 1px, rgba(18, 59, 78, 0.06) 1px, transparent 0);
  background-size: 22px 22px;
  opacity: 0.5;
}

.login__head,
.login__body,
.login__note {
  position: relative;
  z-index: 1;
}

.login__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
  flex-wrap: wrap;
}

.login__brand {
  display: flex;
  align-items: center;
  gap: 10px;
}

.login__logo {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border-radius: var(--rd-md);
  background: linear-gradient(140deg, #123b4e, #0a7a66);
  color: #fff;
  font-size: var(--fs-sm);
  font-weight: var(--fw-semibold);
}

.login__brand-text {
  display: flex;
  flex-direction: column;
  line-height: 1.2;
}

.login__brand-text b {
  font-size: var(--fs-base);
  color: var(--c-ink);
}

.login__brand-text span {
  font-size: var(--fs-2xs);
  color: var(--c-faint);
}

.login__body {
  display: grid;
  grid-template-columns: minmax(300px, 0.82fr) minmax(0, 1.35fr);
  gap: var(--sp-4);
  align-items: start;
}

.login__intro {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 18px 20px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-xl);
  background: var(--c-card);
  box-shadow: var(--sh-md);
}

.login__eyebrow {
  font-size: 9px;
  font-weight: var(--fw-semibold);
  letter-spacing: 1.4px;
  color: var(--c-brand);
  text-transform: uppercase;
}

.login__title {
  font-size: var(--fs-xl);
  font-weight: var(--fw-semibold);
  color: var(--c-ink);
  line-height: 1.25;
}

.login__desc {
  font-size: var(--fs-xs);
  color: var(--c-muted);
  line-height: var(--lh-relaxed, 1.7);
}

.login__desc code,
.login__facts code,
.login__hint code,
.login__note code {
  padding: 0 3px;
  border-radius: var(--rd-xs);
  background: var(--c-bg-2);
  font-family: var(--font-mono);
  font-size: 9.5px;
  color: var(--c-ink-2);
}

.login__facts {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 10px 0 2px;
  margin: 0;
  list-style: none;
  border-top: 1px dashed var(--c-line-2);
}

.login__facts li {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  font-size: 10px;
  color: var(--c-muted);
  line-height: 1.55;
}

.login__facts svg {
  flex: none;
  margin-top: 1px;
  color: var(--c-brand);
}

.login__hint {
  padding: 7px 9px;
  border: 1px solid #f0e2bf;
  border-radius: var(--rd-sm);
  background: var(--c-amber-soft);
  font-size: 10px;
  color: var(--c-ink-2);
}

.login__error {
  padding: 9px 11px;
  border: 1px solid #f2c9c2;
  border-radius: var(--rd-sm);
  background: var(--c-red-soft);
  font-size: var(--fs-xs);
  color: #a2352a;
  line-height: 1.55;
}

.login__error-next {
  font-size: 10px;
  color: #8a4a42;
}

.login__accounts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--sp-2);
}

.login__card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px 14px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-xl);
  background: var(--c-card);
  box-shadow: var(--sh-md);
  text-align: left;
  cursor: pointer;
  transition: border-color var(--tr-fast), box-shadow var(--tr-fast), transform var(--tr-fast);
}

.login__card:hover:not([aria-disabled='true']) {
  border-color: var(--c-brand);
  transform: translateY(-1px);
  box-shadow: 0 0 0 3px var(--c-brand-ring), var(--sh-md);
}

.login__card.is-current {
  border-color: var(--c-brand);
  background: linear-gradient(180deg, #f2faf7, var(--c-card));
}

.login__card[aria-disabled='true'] {
  opacity: 0.72;
  cursor: progress;
}

.login__card:focus-visible {
  outline: none;
  box-shadow: var(--sh-ring);
}

.login__card-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.login__avatar {
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  flex: none;
  border-radius: 50%;
  background: linear-gradient(140deg, #123b4e, #0a7a66);
  color: #fff;
  font-size: 9.5px;
  font-weight: var(--fw-semibold);
}

.login__card-titles {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
  flex: 1;
  line-height: 1.25;
}

.login__card-titles b {
  font-size: var(--fs-sm);
  color: var(--c-ink);
}

.login__card-titles span {
  font-size: 9.5px;
  color: var(--c-faint);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.login__card-caps {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding-top: 6px;
  border-top: 1px dashed var(--c-line-2);
  margin: 0;
}

.login__card-caps-label {
  font-size: 9px;
  color: var(--c-faint);
}

.login__caps {
  display: flex;
  flex-wrap: wrap;
  gap: 3px;
}

.login__caps code {
  padding: 1px 4px;
  border-radius: var(--rd-xs);
  background: var(--c-bg-2);
  font-family: var(--font-mono);
  font-size: 9px;
  color: var(--c-ink-2);
}

.login__caps-more {
  font-size: 9px;
  color: var(--c-faint);
}

.login__card-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
}

.login__card-hint {
  font-size: 9px;
  color: var(--c-faint);
}

.login__card-cta {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 26px;
  padding: 0 10px;
  border: 1px solid var(--c-brand);
  border-radius: var(--rd-md);
  background: var(--c-brand-soft);
  color: var(--c-brand-deep);
  font-size: 10px;
  font-weight: var(--fw-medium);
  transition: background-color var(--tr-fast), color var(--tr-fast);
}

.login__card:hover:not([aria-disabled='true']) .login__card-cta {
  background: var(--c-brand);
  color: #fff;
}

.login__note p {
  font-size: 10px;
  color: var(--c-muted);
  line-height: 1.6;
}

@media (max-width: 1100px) {
  .login__body {
    grid-template-columns: minmax(0, 1fr);
  }

  .login__accounts {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 720px) {
  .login__accounts {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
