<script setup lang="ts">
import { computed } from 'vue'
import { WButton, WCard, WEmpty, WIcon, WTag } from '@/components'
import { capabilityLabel, roleHasCapability } from '@/auth/capabilities'
import { ROLE_LABELS } from '@/app/roles'
import { useAuthStore } from '@/stores/auth'
import type { ActorRole } from '@/api/contract'

/**
 * 页面内「无权限」态（403 兜底，P2 W8 契约 v0.3.3 §8 / P2-030）
 * ===========================================================================
 * 用途：接口返回 403 `E-2001` + `details{requiredCapability, role, reason}` 时，
 * 页面**不编造数据、不伪造成功**，而是展示：
 * - 所需能力（能力码 + 人话，如 `audit.read（审计事件读取）`）；
 * - 当前演示角色 / 账号（谁是当前用户）；
 * - 可切换的演示账号（只有具备该能力的账号才列为可切换，避免再撞 403）；
 * - 可选的替代动作（`#actions` 插槽，如「重新加载」「回到可访问页面」）。
 *
 * 切换账号动作交给父组件（需调登录 API 并重新拉数据），本组件只 `emit('switch', role)`。
 *
 * P2 W9（task-33）增量：
 * - `compact`：内嵌进卡片/面板的紧凑形态（如 /desk 工单列表、/copilot 摘要卡），
 *   与整页形态共用同一套「所需能力 / 当前角色 / 可切换账号」口径；
 * - `candidateRoles`：可切换账号白名单。默认按能力矩阵过滤；当页面本身有可见性门槛时
 *   传入「该页可见角色」，避免推荐一个「切过去又被页面级 403 拦截」的账号。
 */
const props = defineProps<{
  /** 后端 `details.requiredCapability`（如 audit.read / ticket.status） */
  requiredCapability?: string
  /** 后端 `details.role`（403 时的角色；与当前登录角色不一致时两者都展示） */
  requiredRole?: string
  /** 后端 traceId（便于对账/排查；不展示敏感内容） */
  traceId?: string
  /** 补充说明（如「审计面板需要 audit.read」） */
  detail?: string
  /** 切换账号进行中（按钮 loading） */
  switching?: boolean
  /** 紧凑形态：内嵌卡片/面板（无大图标区，单行说明 + 切换按钮） */
  compact?: boolean
  /** 可切换账号白名单（默认 = 具备所需能力的演示账号） */
  candidateRoles?: readonly ActorRole[]
}>()

const emit = defineEmits<{ (event: 'switch', role: ActorRole): void }>()

const auth = useAuthStore()

const currentRoleLabel = computed(() => (auth.role ? ROLE_LABELS[auth.role] : '未登录'))
const currentAccount = computed(() => auth.demoAccounts.find((item) => item.role === auth.role) ?? null)
const capabilityText = computed(() => capabilityLabel(props.requiredCapability))
const summaryText = computed(
  () =>
    props.detail ??
    `当前演示角色「${currentRoleLabel.value}」不具备访问该数据所需的能力，页面不会展示未授权数据。`,
)
/** 具备该能力的演示账号（按能力矩阵过滤；可选页面可见性白名单，避免推荐仍会被页面级拦截的账号） */
const candidates = computed(() =>
  auth.demoAccounts.filter((account) => {
    if (props.candidateRoles && !props.candidateRoles.includes(account.role)) return false
    return props.requiredCapability ? roleHasCapability(account.role, props.requiredCapability) : true
  }),
)
</script>

<template>
  <div class="w-denied" :class="{ 'is-compact': compact }" data-testid="permission-denied">
    <!-- ① 整页形态：空态标题 + 明细卡 -->
    <template v-if="!compact">
      <WEmpty icon="lock" title="无权限（403）" :description="summaryText">
        <template #default>
          <slot name="actions" />
        </template>
      </WEmpty>

      <WCard flat class="w-denied__detail">
        <dl class="w-denied__rows">
          <div class="w-denied__row">
            <dt>所需能力</dt>
            <dd data-testid="permission-denied-capability"><code>{{ capabilityText }}</code></dd>
          </div>
          <div class="w-denied__row">
            <dt>当前角色</dt>
            <dd data-testid="permission-denied-role">
              {{ currentRoleLabel }}
              <template v-if="currentAccount">· 演示账号 <code>{{ currentAccount.account }}</code></template>
              <WTag v-if="requiredRole && requiredRole !== auth.role" tone="a" plain>
                后端判定角色：{{ requiredRole }}
              </WTag>
            </dd>
          </div>
          <div v-if="traceId" class="w-denied__row">
            <dt>traceId</dt>
            <dd><code>{{ traceId }}</code></dd>
          </div>
        </dl>

        <div class="w-denied__switch">
          <span class="w-denied__switch-label">可切换演示账号（具备该能力）</span>
          <div class="w-denied__switch-btns">
            <WButton
              v-for="account in candidates"
              :key="account.account"
              size="sm"
              :loading="switching"
              :variant="account.role === auth.role ? 'primary' : 'ghost'"
              :data-testid="`permission-denied-switch-${account.role}`"
              @click="emit('switch', account.role)"
            >
              {{ ROLE_LABELS[account.role] }}（{{ account.name }}）
            </WButton>
          </div>
          <p class="w-denied__hint">
            切换账号会调用 <code>POST /api/v1/auth/login</code> 换演示 JWT（demo，非真实鉴权），再重新拉取数据。
          </p>
        </div>
      </WCard>
    </template>

    <!-- ② 紧凑形态：卡片/面板内嵌 -->
    <template v-else>
      <div class="w-denied__bar">
        <WIcon name="lock" :size="14" />
        <b>无权限（403）</b>
        <span class="w-denied__bar-text">{{ summaryText }}</span>
        <span v-if="$slots.actions" class="w-denied__bar-acts"><slot name="actions" /></span>
      </div>
      <div class="w-denied__inline">
        <span>所需能力 <code data-testid="permission-denied-capability">{{ capabilityText }}</code></span>
        <span>
          当前角色 <em data-testid="permission-denied-role">{{ currentRoleLabel }}</em>
          <template v-if="currentAccount">· 演示账号 <code>{{ currentAccount.account }}</code></template>
        </span>
        <span v-if="requiredRole && requiredRole !== auth.role">后端判定角色 <code>{{ requiredRole }}</code></span>
        <span v-if="traceId">traceId <code>{{ traceId }}</code></span>
      </div>
      <div class="w-denied__switch is-inline">
        <span class="w-denied__switch-label">可切换演示账号（具备该能力，demo 非真实鉴权）</span>
        <div class="w-denied__switch-btns">
          <WButton
            v-for="account in candidates"
            :key="account.account"
            size="sm"
            :loading="switching"
            :variant="account.role === auth.role ? 'primary' : 'ghost'"
            :data-testid="`permission-denied-switch-${account.role}`"
            @click="emit('switch', account.role)"
          >
            {{ ROLE_LABELS[account.role] }}（{{ account.name }}）
          </WButton>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.w-denied {
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
  padding: var(--sp-3);
}

.w-denied__detail {
  max-width: 760px;
  margin: 0 auto;
}

.w-denied__rows {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin: 0;
}

.w-denied__row {
  display: flex;
  align-items: baseline;
  gap: 10px;
  font-size: var(--fs-xs);
}

.w-denied__row dt {
  flex: none;
  width: 64px;
  font-size: 9.5px;
  color: var(--c-faint);
}

.w-denied__row dd {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  margin: 0;
  color: var(--c-ink-2);
}

.w-denied__row code,
.w-denied__hint code,
.w-denied__bar code,
.w-denied__inline code {
  padding: 0 3px;
  border-radius: var(--rd-xs);
  background: var(--c-bg-2);
  font-family: var(--font-mono);
  font-size: 9.5px;
}

.w-denied__switch {
  display: flex;
  flex-direction: column;
  gap: 5px;
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed var(--c-line-2);
}

.w-denied__switch-label {
  font-size: 9.5px;
  color: var(--c-faint);
}

.w-denied__switch-btns {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}

.w-denied__hint {
  font-size: 9px;
  color: var(--c-faint);
  line-height: 1.5;
}

/* ---------- compact：卡片/面板内嵌形态 ---------- */
.w-denied.is-compact {
  gap: 6px;
  padding: 10px;
  border: 1px dashed var(--c-amber);
  border-radius: var(--rd-md);
  background: var(--c-amber-soft);
}

.w-denied__bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  font-size: var(--fs-xs);
  line-height: var(--lh-base);
  color: var(--c-ink-2);
}

.w-denied__bar b {
  flex: none;
  color: var(--c-ink);
  font-weight: var(--fw-semibold);
}

.w-denied__bar-text {
  flex: 1 1 320px;
  min-width: 0;
}

.w-denied__bar-acts {
  display: flex;
  flex: none;
  gap: 5px;
}

.w-denied__inline {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px 12px;
  font-size: 10px;
  color: var(--c-muted);
}

.w-denied__inline em {
  font-style: normal;
  color: var(--c-ink-2);
}

.w-denied__switch.is-inline {
  margin-top: 2px;
  padding-top: 6px;
  border-top-color: var(--c-line);
}
</style>
