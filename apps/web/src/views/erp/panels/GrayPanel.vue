<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { WButton, WCard, WEmpty, WLoading, WModal, WTag, useToast } from '@/components'
import PermissionDenied from '@/components/feedback/PermissionDenied.vue'
import {
  api,
  ApiError,
  CAPABILITY_CODES,
  errorNextStep,
  errorText,
  forbiddenDetailsOf,
  formatIsoMinute,
  GRAY_PERCENT_RANGE,
  GRAY_SCOPES,
} from '@/api'
import type { GrayCompareResult, GrayPolicy, GrayScope } from '@/api'
import { capabilityLabel } from '@/auth/capabilities'
import { useAuthStore } from '@/stores/auth'

/**
 * 灰度与双跑（P5 W19，契约 v1.3.0 §14 / 架构 §24.4）
 * ===========================================================================
 * 数据来源（全部真实 API，不造数）：
 * - `GET  /api/v1/gray/policies`：全量小集合（≤50，`data` 直接是数组）；
 * - `POST /api/v1/gray/policies`：新建策略（`scope`=connector|capability、`targetId`、`percent` 0—100、
 *   `enabled`；同一 scope+targetId 唯一，重复 → 409 `E-3002`）；
 * - `PATCH /api/v1/gray/policies/{policyId}`：调整比例 / 启停（`enabled=false` = 暂停，等价全量回旧版，≤5 分钟）；
 * - `GET  /api/v1/gray/compare?from&to&connectorId`：双跑比对（逐能力 checked/matched/diffCount/diffRate），
 *   `from`/`to` 必填；`connectorId` 省略 = 全部处于灰度中的连接器。
 *
 * 权限（能力矩阵，不是前端可绕过）：`gray.policy.read`（it/admin 只读）+
 * `gray.policy.manage`（it/admin 可写）；其它角色无 `gray.policy.read` → 页签不出现（接口仍 403 E-2001）。
 * 只读口径：具备 read 但不具备 manage 的角色只展示数据，不出现新建/修改入口。
 *
 * 边界：灰度只改**流量分流配置**，不写业务主数据；双跑只读，不改流量比例；差异只作发布决策证据，
 * 页面不得声称「新旧连接器等价」。
 */
const props = defineProps<{
  /** 连接器选项（来自 GET /connectors，供 scope=connector 的 targetId 选择） */
  connectors: { connectorId: string; name: string }[]
}>()

const auth = useAuthStore()
const toast = useToast()

const policies = ref<GrayPolicy[]>([])
const loading = ref(false)
const error = ref<{ code: string; message: string; next: string } | null>(null)
const denied = ref<{ requiredCapability: string; role: string; traceId: string } | null>(null)
const switching = ref(false)

/** 能力门槛：可管理（gray.policy.manage，前端仅用于显隐入口，真实鉴权在 BFF） */
const canManage = computed(() => auth.hasCapability('gray.policy.manage'))

/* ------------------------------ 策略列表 ------------------------------ */
async function loadPolicies(): Promise<void> {
  loading.value = true
  error.value = null
  denied.value = null
  try {
    const { data } = await api.gray.policies()
    policies.value = data
  } catch (caught) {
    policies.value = []
    const forbidden = forbiddenDetailsOf(caught)
    if (forbidden) {
      denied.value = {
        requiredCapability: forbidden.requiredCapability || 'gray.policy.read',
        role: forbidden.role || (auth.role ?? ''),
        traceId: caught instanceof ApiError ? caught.traceId : '',
      }
    } else {
      const apiError = caught instanceof ApiError ? caught : null
      const code = apiError?.code ?? 'E-9001'
      error.value = { code, message: errorText(code), next: errorNextStep(code) }
    }
  } finally {
    loading.value = false
  }
}

/** 从无权限态切换演示账号（换 token 后重拉，不伪造成功） */
async function switchAccount(role: 'owner' | 'steward' | 'agent' | 'supervisor' | 'it' | 'admin'): Promise<void> {
  if (switching.value) return
  switching.value = true
  try {
    const ok = await auth.loginAsRole(role)
    if (!ok) {
      error.value = {
        code: 'E-9001',
        message: auth.failure?.message ?? '演示账号切换失败',
        next: auth.failure?.next ?? '稍后重试或返回登录页',
      }
      return
    }
    await loadPolicies()
  } finally {
    switching.value = false
  }
}

function scopeLabel(scope: GrayScope): string {
  return scope === 'connector' ? '连接器整体分流' : '单能力分流'
}

function scopeTone(scope: GrayScope): 'b' | 'v' {
  return scope === 'connector' ? 'b' : 'v'
}

function targetLabel(policy: GrayPolicy): string {
  if (policy.scope !== 'capability') return policy.targetId
  const name = props.connectors.find((item) => item.connectorId === policy.targetId)?.name
  return name ? `${policy.targetId}（${name}）` : policy.targetId
}

/* ------------------------------ 修改策略（percent / enabled） ------------------------------ */
const updatingId = ref('')
const rowPercent = ref<Record<string, number>>({})
const rowError = ref<{ policyId: string; code: string; message: string; next: string } | null>(null)

function percentOf(policy: GrayPolicy): number {
  return rowPercent.value[policy.policyId] ?? policy.percent
}

function setRowPercent(policy: GrayPolicy, value: number): void {
  const min = GRAY_PERCENT_RANGE.min
  const max = GRAY_PERCENT_RANGE.max
  const next = Number.isFinite(value) ? Math.min(max, Math.max(min, Math.round(value))) : policy.percent
  rowPercent.value = { ...rowPercent.value, [policy.policyId]: next }
}

/** range input → setRowPercent（模板里不写类型断言，保持模板表达式简单） */
function onPercentInput(policy: GrayPolicy, event: Event): void {
  const target = event.target as HTMLInputElement | null
  setRowPercent(policy, Number(target?.value ?? policy.percent))
}

interface RowError {
  policyId: string
  code: string
  message: string
  next: string
}

/** 策略更新失败 → 行内人话（409 重复 / 400 参数 / 其它错误码统一转人话） */
function fillUpdateError(policyId: string, caught: unknown): RowError {
  const apiError = caught instanceof ApiError ? caught : null
  const code = apiError?.code ?? 'E-9001'
  const message =
    code === 'E-3002'
      ? '同一 scope + targetId 已存在策略（E-3002）'
      : code === 'E-1001'
        ? '参数不合法：percent 需在 0—100 之间，body 至少一项'
        : errorText(code)
  return { policyId, code, message, next: errorNextStep(code) }
}

async function patchPolicy(policy: GrayPolicy, body: { percent?: number; enabled?: boolean }): Promise<void> {
  if (!canManage.value || updatingId.value) return
  updatingId.value = policy.policyId
  rowError.value = null
  try {
    const { data } = await api.gray.updatePolicy(
      policy.policyId,
      body,
      `ui-gray-${policy.policyId}-${Date.now().toString(36)}`,
    )
    toast.success(
      `策略已更新：${data.policyId} · ${scopeLabel(data.scope)} ${data.targetId} · ${data.percent}% · ${
        data.enabled ? '启用' : '已暂停'
      }`,
    )
    await loadPolicies()
  } catch (caught) {
    const info = fillUpdateError(policy.policyId, caught)
    rowError.value = info
    toast.error(`策略更新未完成：${info.message}`)
  } finally {
    updatingId.value = ''
  }
}

function savePercent(policy: GrayPolicy): void {
  void patchPolicy(policy, { percent: percentOf(policy) })
}

function toggleEnabled(policy: GrayPolicy): void {
  void patchPolicy(policy, { enabled: !policy.enabled })
}

/* ------------------------------ 新建策略 ------------------------------ */
const createOpen = ref(false)
const creating = ref(false)
const createError = ref<{ code: string; message: string; next: string } | null>(null)
const formScope = ref<GrayScope>('connector')
const formTargetId = ref('')
const formPercent = ref(10)
const formEnabled = ref(true)

/** scope=capability 的 targetId 选项：18 项标准能力（顺序固定，来自 shared CAPABILITY_CODES） */
const capabilityOptions = computed(() => CAPABILITY_CODES)

function openCreate(): void {
  createError.value = null
  formScope.value = 'connector'
  formTargetId.value = props.connectors.find((item) => item.connectorId === 'kingdee-eas')?.connectorId ?? ''
  formPercent.value = 10
  formEnabled.value = true
  createOpen.value = true
}

function onScopeChange(): void {
  formTargetId.value = formScope.value === 'connector' ? (props.connectors[0]?.connectorId ?? '') : ''
}

async function submitCreate(): Promise<void> {
  if (creating.value) return
  if (!formTargetId.value) {
    createError.value = {
      code: 'E-1001',
      message: '请选择 targetId（连接器 ID 或能力码）',
      next: 'scope=connector 选连接器；scope=capability 选 18 项标准能力之一',
    }
    return
  }
  creating.value = true
  createError.value = null
  try {
    const { data } = await api.gray.createPolicy(
      {
        scope: formScope.value,
        targetId: formTargetId.value,
        percent: formPercent.value,
        enabled: formEnabled.value,
      },
      `ui-gray-create-${Date.now().toString(36)}`,
    )
    createOpen.value = false
    toast.success(`已创建灰度策略 ${data.policyId}：${data.targetId} · ${data.percent}%`)
    await loadPolicies()
  } catch (caught) {
    const apiError = caught instanceof ApiError ? caught : null
    const code = apiError?.code ?? 'E-9001'
    const message =
      code === 'E-3002'
        ? '同一 scope + targetId 已存在策略（重复创建 → 409 E-3002）'
        : code === 'E-1001'
          ? '参数不合法：percent 需在 0—100，targetId 需为已注册连接器或标准能力码'
          : errorText(code)
    createError.value = { code, message, next: errorNextStep(code) }
  } finally {
    creating.value = false
  }
}

/* ------------------------------ 双跑比对 ------------------------------ */
function todayInput(): string {
  const now = new Date()
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`
}

const compareFrom = ref(todayInput())
const compareTo = ref(todayInput())
const compareConnectorId = ref('')
const comparing = ref(false)
const compare = ref<GrayCompareResult | null>(null)
const compareError = ref<{ code: string; message: string; next: string } | null>(null)

/** 可供对比的连接器：灰度策略中 scope=connector 的 targetId（服务端要求「灰度中的连接器」） */
const grayConnectorOptions = computed(() => {
  const ids = policies.value.filter((item) => item.scope === 'connector').map((item) => item.targetId)
  return Array.from(new Set(ids))
})

function formatRate(rate: number): string {
  if (!Number.isFinite(rate)) return '—'
  return `${(rate * 100).toFixed(2)}%`
}

async function runCompare(): Promise<void> {
  if (comparing.value) return
  comparing.value = true
  compareError.value = null
  compare.value = null
  try {
    const { data } = await api.gray.compare({
      from: `${compareFrom.value}T00:00:00+08:00`,
      to: `${compareTo.value}T23:59:59+08:00`,
      connectorId: compareConnectorId.value || undefined,
    })
    compare.value = data
    toast.success(`双跑比对完成：${data.items.length} 项能力（只读，不改流量比例）`)
  } catch (caught) {
    const apiError = caught instanceof ApiError ? caught : null
    const code = apiError?.code ?? 'E-9001'
    const reason = typeof apiError?.details.reason === 'string' ? apiError.details.reason : ''
    const message =
      reason === 'not_gray'
        ? 'connectorId 需指向灰度中的连接器（基线连接器不能作为对比目标）'
        : errorText(code)
    compareError.value = { code, message, next: errorNextStep(code) }
  } finally {
    comparing.value = false
  }
}

onMounted(() => {
  void loadPolicies()
})
</script>

<template>
  <section class="erp-gray" data-testid="erp-gray-panel">
    <WCard :padded="false">
      <template #head>
        <span class="erp__card-title">灰度策略（{{ policies.length }} 条）</span>
        <span class="erp__card-sub">
          GET /api/v1/gray/policies · percent 0—100 为新版本流量比例 · enabled=false 即暂停（回滚 ≤5 分钟）
        </span>
      </template>
      <template #extra>
        <WTag tone="b" plain>gray.policy.read</WTag>
        <WTag :tone="canManage ? 'g' : 'a'" plain data-testid="erp-gray-manage-tag">
          {{ canManage ? '可管理（gray.policy.manage）' : '只读（无 gray.policy.manage）' }}
        </WTag>
        <WButton
          v-if="canManage"
          size="sm"
          variant="primary"
          icon="plus"
          data-testid="erp-gray-create-open"
          @click="openCreate"
        >
          新建策略
        </WButton>
        <WButton size="sm" icon="refresh" :loading="loading" data-testid="erp-gray-refresh" @click="loadPolicies">
          刷新
        </WButton>
      </template>

      <WLoading v-if="loading && policies.length === 0" center label="正在读取灰度策略…" />

      <PermissionDenied
        v-else-if="denied"
        data-testid="erp-gray-denied"
        :required-capability="denied.requiredCapability"
        :required-role="denied.role"
        :trace-id="denied.traceId"
        :switching="switching"
        :detail="`灰度策略读取需要能力 ${capabilityLabel(denied.requiredCapability)}；当前演示角色「${auth.role ?? '未登录'}」不具备该能力，页面不会展示未授权数据。`"
        @switch="switchAccount"
      />

      <div v-else-if="error" class="erp__state" data-testid="erp-gray-error">
        <WEmpty
          icon="info"
          title="灰度策略读取失败"
          :description="`${error.message}（错误码 ${error.code}）。${error.next}`"
        />
        <WButton size="sm" icon="refresh" @click="loadPolicies">重试</WButton>
      </div>

      <WEmpty
        v-else-if="policies.length === 0"
        data-testid="erp-gray-empty"
        icon="workflow"
        title="暂无灰度策略"
        description="灰度策略由 BFF 注册表提供（GET /gray/policies 返回空数组），不造数；具备 gray.policy.manage 的演示账号可新建。"
      />

      <table v-else class="erp-gray__table" data-testid="erp-gray-table">
        <thead>
          <tr>
            <th scope="col" style="width: 176px">策略 ID</th>
            <th scope="col" style="width: 120px">作用域</th>
            <th scope="col">目标（连接器 / 能力）</th>
            <th scope="col" style="width: 150px">新版本流量比例</th>
            <th scope="col" style="width: 92px">状态</th>
            <th scope="col" style="width: 116px">更新时间</th>
            <th scope="col" style="width: 176px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="policy in policies"
            :key="policy.policyId"
            data-testid="erp-gray-row"
            :data-policy="policy.policyId"
            :data-scope="policy.scope"
            :data-target="policy.targetId"
            :data-enabled="policy.enabled ? '1' : '0'"
            :data-percent="policy.percent"
          >
            <td><code>{{ policy.policyId }}</code></td>
            <td>
              <WTag :tone="scopeTone(policy.scope)" plain>{{ scopeLabel(policy.scope) }}</WTag>
            </td>
            <td>
              <code class="erp-gray__target">{{ targetLabel(policy) }}</code>
            </td>
            <td>
              <div class="erp-gray__percent">
                <input
                  v-if="canManage"
                  type="range"
                  :min="GRAY_PERCENT_RANGE.min"
                  :max="GRAY_PERCENT_RANGE.max"
                  :value="percentOf(policy)"
                  :data-testid="`erp-gray-percent-${policy.policyId}`"
                  @input="onPercentInput(policy, $event)"
                />
                <b class="num" data-testid="erp-gray-percent-value">{{ percentOf(policy) }}%</b>
              </div>
            </td>
            <td>
              <WTag :tone="policy.enabled ? 'g' : 'n'" data-testid="erp-gray-enabled">
                {{ policy.enabled ? '启用中' : '已暂停' }}
              </WTag>
            </td>
            <td><span class="dim">{{ formatIsoMinute(policy.updatedAt) }}</span></td>
            <td>
              <div v-if="canManage" class="erp-gray__acts">
                <WButton
                  size="sm"
                  :loading="updatingId === policy.policyId"
                  :data-testid="`erp-gray-save-${policy.policyId}`"
                  @click="savePercent(policy)"
                >
                  保存比例
                </WButton>
                <WButton
                  size="sm"
                  variant="ghost"
                  :disabled="updatingId === policy.policyId"
                  :data-testid="`erp-gray-toggle-${policy.policyId}`"
                  @click="toggleEnabled(policy)"
                >
                  {{ policy.enabled ? '暂停' : '启用' }}
                </WButton>
              </div>
              <span v-else class="dim" data-testid="erp-gray-readonly">只读</span>
            </td>
          </tr>
        </tbody>
      </table>

      <template #footer>
        <p v-if="rowError" class="erp-gray__fail" data-testid="erp-gray-update-error">
          {{ rowError.message }}（错误码 {{ rowError.code }}）。{{ rowError.next }}
        </p>
        <p class="dim">
          灰度只改流量分流配置（不写业务主数据）；`enabled=false` 等价全量回旧版，回滚 ≤5 分钟；写操作落审计
          `gray.policy.create` / `gray.policy.update`（资源类型 `gray_policy`）。
        </p>
      </template>
    </WCard>

    <!-- 双跑比对 -->
    <WCard :padded="false" data-testid="erp-gray-compare-card">
      <template #head>
        <span class="erp__card-title">双跑比对（新旧连接器差异报表）</span>
        <span class="erp__card-sub">GET /api/v1/gray/compare?from&to&connectorId · 只读，不改流量比例</span>
      </template>
      <template #extra>
        <WTag tone="n" plain data-testid="erp-gray-compare-count">
          {{ compare ? `${compare.items.length} 项能力` : '未运行' }}
        </WTag>
      </template>

      <div class="erp-gray__compare-form">
        <label class="erp-gray__field">
          <span>起始（含）</span>
          <input v-model="compareFrom" type="date" data-testid="erp-gray-compare-from" />
        </label>
        <label class="erp-gray__field">
          <span>结束（含）</span>
          <input v-model="compareTo" type="date" data-testid="erp-gray-compare-to" />
        </label>
        <label class="erp-gray__field">
          <span>连接器（省略 = 全部灰度连接器）</span>
          <select v-model="compareConnectorId" data-testid="erp-gray-compare-connector">
            <option value="">全部灰度连接器</option>
            <option v-for="id in grayConnectorOptions" :key="id" :value="id">{{ id }}</option>
          </select>
        </label>
        <WButton
          size="sm"
          variant="primary"
          icon="activity"
          :loading="comparing"
          data-testid="erp-gray-compare-run"
          @click="runCompare"
        >
          运行双跑比对
        </WButton>
      </div>

      <div v-if="compareError" class="erp__state" data-testid="erp-gray-compare-error">
        <WEmpty
          icon="info"
          title="双跑比对未完成"
          :description="`${compareError.message}（错误码 ${compareError.code}）。${compareError.next}`"
        />
      </div>

      <WEmpty
        v-else-if="!compare"
        compact
        data-testid="erp-gray-compare-empty"
        icon="workflow"
        title="尚未运行双跑比对"
        description="选择时间窗后点「运行双跑比对」：逐能力对比新旧连接器，返回 checked / matched / diffCount / diffRate。"
      />

      <WEmpty
        v-else-if="compare.items.length === 0"
        compact
        data-testid="erp-gray-compare-empty-result"
        icon="workflow"
        title="该时间窗内没有可比对的能力"
        description="双跑结果为空（不造数）：确认时间窗内是否已有灰度流量与新连接器调用。"
      />

      <template v-else>
        <table class="erp-gray__table" data-testid="erp-gray-compare-table">
          <thead>
            <tr>
              <th scope="col" style="width: 160px">标准能力</th>
              <th scope="col" style="width: 268px">旧连接器 → 新连接器</th>
              <th scope="col" style="width: 92px">checked</th>
              <th scope="col" style="width: 92px">matched</th>
              <th scope="col" style="width: 104px">diffCount</th>
              <th scope="col" style="width: 104px">diffRate</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="item in compare.items"
              :key="`${item.capability}-${item.newConnector}`"
              data-testid="erp-gray-compare-row"
              :data-capability="item.capability"
              :data-diff-rate="item.diffRate"
            >
              <td><code>{{ item.capability }}</code></td>
              <td><span class="erp-gray__conn">{{ item.oldConnector }} → {{ item.newConnector }}</span></td>
              <td><span class="num">{{ item.checked }}</span></td>
              <td><span class="num">{{ item.matched }}</span></td>
              <td><span class="num" :class="{ 'is-warn': item.diffCount > 0 }">{{ item.diffCount }}</span></td>
              <td>
                <b class="num" :class="{ 'is-warn': item.diffRate > 0 }" data-testid="erp-gray-diff-rate">
                  {{ formatRate(item.diffRate) }}
                </b>
              </td>
            </tr>
          </tbody>
        </table>
        <p class="erp-gray__note" data-testid="erp-gray-compare-note">
          比对生成时间 {{ formatIsoMinute(compare.generatedAt) }}：差异只作发布决策证据，不得据此声称「新旧连接器等价」；
          双跑只读，不写业务主数据、不改流量比例（改比例走 PATCH /gray/policies/{policyId}）。
        </p>
      </template>
    </WCard>

    <!-- 新建策略 -->
    <WModal v-model:open="createOpen" title="新建灰度策略（scope + targetId 唯一）" width="520px">
      <div class="erp-gray__form">
        <label class="erp-gray__field">
          <span>作用域 scope</span>
          <select v-model="formScope" data-testid="erp-gray-form-scope" @change="onScopeChange">
            <option v-for="scope in GRAY_SCOPES" :key="scope" :value="scope">{{ scopeLabel(scope) }}（{{ scope }}）</option>
          </select>
        </label>
        <label class="erp-gray__field">
          <span>目标 targetId</span>
          <select v-if="formScope === 'connector'" v-model="formTargetId" data-testid="erp-gray-form-target">
            <option value="">请选择连接器</option>
            <option v-for="item in connectors" :key="item.connectorId" :value="item.connectorId">
              {{ item.name }}（{{ item.connectorId }}）
            </option>
          </select>
          <select v-else v-model="formTargetId" data-testid="erp-gray-form-target">
            <option value="">请选择标准能力（18 项）</option>
            <option v-for="code in capabilityOptions" :key="code" :value="code">{{ code }}</option>
          </select>
        </label>
        <label class="erp-gray__field">
          <span>新版本流量比例 percent（{{ GRAY_PERCENT_RANGE.min }}—{{ GRAY_PERCENT_RANGE.max }}）</span>
          <div class="erp-gray__percent">
            <input
              v-model.number="formPercent"
              type="range"
              :min="GRAY_PERCENT_RANGE.min"
              :max="GRAY_PERCENT_RANGE.max"
              data-testid="erp-gray-form-percent"
            />
            <b class="num">{{ formPercent }}%</b>
          </div>
        </label>
        <label class="erp-gray__check">
          <input v-model="formEnabled" type="checkbox" data-testid="erp-gray-form-enabled" />
          <span>创建后立即启用（enabled=true；不勾选 = 暂停态）</span>
        </label>
        <p v-if="createError" class="erp-gray__fail" data-testid="erp-gray-create-error">
          {{ createError.message }}（错误码 {{ createError.code }}）。{{ createError.next }}
        </p>
        <p class="dim">
          灰度只改分流配置，不写业务主数据；写操作落审计 gray.policy.create。重复 scope+targetId → 409 E-3002。
        </p>
      </div>
      <template #footer>
        <WButton size="sm" variant="ghost" @click="createOpen = false">取消</WButton>
        <WButton
          size="sm"
          variant="primary"
          :loading="creating"
          data-testid="erp-gray-form-submit"
          @click="submitCreate"
        >
          创建策略
        </WButton>
      </template>
    </WModal>
  </section>
</template>

<style scoped>
.erp-gray {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
  min-width: 0;
}

.erp__card-title {
  font-size: 12.5px;
  font-weight: var(--fw-semibold);
  color: var(--c-ink);
}

.erp__card-sub {
  margin-left: 6px;
  font-size: var(--fs-2xs);
  color: var(--c-faint);
}

.erp__state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--sp-2);
  padding: var(--sp-3);
}

.erp-gray__table {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--fs-sm);
}

.erp-gray__table th {
  padding: 8px 12px;
  background: var(--c-bg-2);
  border-bottom: 1px solid var(--c-line);
  color: var(--c-muted);
  font-size: 9.5px;
  font-weight: var(--fw-semibold);
  letter-spacing: 0.6px;
  text-align: left;
  white-space: nowrap;
}

.erp-gray__table td {
  padding: 7px 12px;
  border-bottom: 1px solid #f0f3f5;
  color: var(--c-ink-2);
  vertical-align: middle;
}

.erp-gray__table tbody tr:last-child td {
  border-bottom: 0;
}

.erp-gray__table code {
  font-family: var(--font-mono);
  font-size: 9.5px;
}

.erp-gray__percent {
  display: flex;
  align-items: center;
  gap: 6px;
}

.erp-gray__percent input[type='range'] {
  width: 96px;
}

.erp-gray__percent b {
  font-size: var(--fs-xs);
  color: var(--c-ink-2);
}

.erp-gray__acts {
  display: flex;
  gap: 5px;
}

.erp-gray__conn {
  font-family: var(--font-mono);
  font-size: 9px;
  color: var(--c-muted);
}

.erp-gray__target {
  word-break: break-all;
}

.erp-gray__compare-form {
  display: flex;
  align-items: flex-end;
  gap: var(--sp-2);
  flex-wrap: wrap;
  padding: 10px 12px;
  border-bottom: 1px solid var(--c-line-2);
}

.erp-gray__field {
  display: flex;
  flex-direction: column;
  gap: 3px;
  font-size: 9px;
  color: var(--c-faint);
}

.erp-gray__field select,
.erp-gray__field input {
  height: 28px;
  min-width: 140px;
  padding: 0 8px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-sm);
  background: var(--c-card);
  font-size: var(--fs-2xs);
  color: var(--c-ink);
}

.erp-gray__field select:focus,
.erp-gray__field input:focus {
  outline: none;
  border-color: var(--c-brand);
  box-shadow: var(--sh-ring);
}

.erp-gray__check {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-2xs);
  color: var(--c-muted);
}

.erp-gray__note {
  padding: 8px 12px;
  font-size: 9.5px;
  color: var(--c-faint);
  line-height: 1.6;
}

.erp-gray__fail {
  padding: 6px 12px;
  font-size: var(--fs-2xs);
  color: var(--c-amber);
  line-height: 1.6;
}

.erp-gray__form {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.is-warn {
  color: var(--c-amber);
}
</style>
