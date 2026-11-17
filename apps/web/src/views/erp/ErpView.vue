<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { WButton, WCard, WEmpty, WLoading, WModal, WPageHeader, WTag } from '@/components'
import { SCREENS } from '@/app/screens'
import StateDemo from '@/content/StateDemo.vue'
import { api, ApiError, errorNextStep, errorText, formatIsoMinute, mockBadgeText } from '@/api'
import type { ConnectorSummary } from '@/api'
import { AUDIT_READ } from '@/auth/capabilities'
import { useAuthStore } from '@/stores/auth'
import AuditPanel from './panels/AuditPanel.vue'
import ConnectorMarketPanel from './panels/ConnectorMarketPanel.vue'
import ConnectorMatrixPanel from './panels/ConnectorMatrixPanel.vue'
import DegradeLedgerPanel from './panels/DegradeLedgerPanel.vue'
import GrayPanel from './panels/GrayPanel.vue'
import MappingVersionsPanel from './panels/MappingVersionsPanel.vue'
import ReconPanel from './panels/ReconPanel.vue'
import EventsPanel from '@/views/events/EventsPanel.vue'
import { CONNECTOR_HEALTH_META, CONNECTOR_STATUS_META } from './erp-data'

/**
 * 07 ERP 适配中心（P2 W7 接真实数据，契约 v0.3.2）
 * ---------------------------------------------------------------------------
 * 数据来源（全部真实 Java BFF API，页面不硬编码业务数字）：
 * - `GET /api/v1/connectors`：5 套连接器摘要（connectorId/name/vendor/vendorVersion/status/health/mock/
 *   能力计数/lastProbeAt/mappingVersion）；
 * - `GET /api/v1/connectors/{id}`：18 项 `CapabilityState`（level/state/evidence/fallback）→ 能力矩阵；
 * - `POST /connectors/{id}/probe` + `GET .../probe/latest` + `.../probes`：能力探测日报/历史；
 * - `GET/POST /api/v1/mappings*`：映射版本列表/字段/试跑/发布/回滚；
 * - `GET /api/v1/degrade/ledger`：降级台账（分页 + 连接器/时间过滤）；
 * - `POST /api/v1/recon/run` + `GET /api/v1/recon/report/{runId}` + `GET /api/v1/recon/runs`：对账与历史。
 *
 * 边界：
 * - 「新增连接器」入口保留 P1-025 独立 4 步向导（/erp/wizard，本次不改向导本身）；
 * - 能力矩阵是能力事实的唯一来源：unsupported/degraded 的入口显隐与其降级路径只读矩阵，
 *   页面不维护第二套能力表；
 * - `mock:true` 的连接器标注「演示数据（mock ERP）」；探测失败/无记录不编造数字。
 */
const screen = SCREENS.erp
const router = useRouter()
const auth = useAuthStore()

type TabKey = 'connector' | 'market' | 'mapping' | 'gray' | 'degrade' | 'recon' | 'audit' | 'events'

/** 审计页签（P2 W8）：仅具备 `audit.read` 的演示角色（supervisor/it/admin）可见 */
const canReadAudit = computed(() => auth.hasCapability(AUDIT_READ))
/** 灰度页签（P5 W19）：具备 `gray.policy.read`（it/admin）可见；只读/可管理由 gray.policy.manage 决定 */
const canReadGray = computed(() => auth.hasCapability('gray.policy.read'))
/** 事件页签（P5 W19）：具备 `event.read`（supervisor/it/admin）可见 */
const canReadEvents = computed(() => auth.hasCapability('event.read'))

const tabs = computed<{ key: TabKey; label: string; hint: string }[]>(() => {
  const base: { key: TabKey; label: string; hint: string }[] = [
    { key: 'connector', label: '连接器与能力矩阵', hint: '5 套连接器 · 18 项三态 · SDK 声明 · 探测' },
    { key: 'market', label: '连接器对比', hint: '已接入 mock 连接器 · 18 项 × N 列 · SDK 元数据 · 选型建议' },
    { key: 'mapping', label: '映射版本', hint: '连接器筛选 · 试跑 200 样本 · 发布 / 回滚' },
    { key: 'degrade', label: '降级台账', hint: '连接器 × 能力 × 替代路径 命中聚合' },
    { key: 'recon', label: '对账', hint: '只读对账 202 → 报告 / 历史' },
  ]
  if (canReadGray.value) {
    base.push({ key: 'gray', label: '灰度与双跑', hint: '策略 percent 0—100 · 新旧连接器差异报表' })
  }
  if (canReadEvents.value) {
    base.push({ key: 'events', label: '事件', hint: 'outbox 事件 · 投递状态 · 类型/项目筛选' })
  }
  if (canReadAudit.value) {
    base.push({ key: 'audit', label: '审计', hint: '谁 · 何时 · 对什么资源 · 结果 · traceId' })
  }
  return base
})

const activeTab = ref<TabKey>('connector')

const connectors = ref<ConnectorSummary[]>([])
const connectorsLoading = ref(true)
const connectorsError = ref<{ code: string; message: string; next: string } | null>(null)
const selectedId = ref('')
const specOpen = ref(false)

const selectedConnector = computed<ConnectorSummary | null>(
  () => connectors.value.find((item) => item.connectorId === selectedId.value) ?? connectors.value[0] ?? null,
)

const totals = computed(() => {
  const base = { supported: 0, degraded: 0, unsupported: 0 }
  for (const item of connectors.value) {
    base.supported += item.supportedCount
    base.degraded += item.degradedCount
    base.unsupported += item.unsupportedCount
  }
  return base
})

const capabilityTotal = computed(() => connectors.value[0]?.capabilityTotal ?? 18)
const mockConnectors = computed(() => connectors.value.filter((item) => item.mock))
const probedConnectors = computed(() => connectors.value.filter((item) => Boolean(item.lastProbeAt)))
const lastProbeAt = computed(() => {
  const times = connectors.value
    .map((item) => item.lastProbeAt)
    .filter((value): value is string => Boolean(value))
    .sort()
  return times[times.length - 1] ?? ''
})
const connectorOptions = computed(() =>
  connectors.value.map((item) => ({ connectorId: item.connectorId, name: item.name })),
)

function statusMeta(item: ConnectorSummary) {
  return CONNECTOR_STATUS_META[item.status] ?? CONNECTOR_STATUS_META.draft
}

function healthMeta(item: ConnectorSummary) {
  return CONNECTOR_HEALTH_META[item.health] ?? CONNECTOR_HEALTH_META.unknown
}

/** 卡片 logo：取名称首字（P1 视觉沿用，不参与业务语义） */
function logoOf(name: string): string {
  return name.trim().slice(0, 1) || 'E'
}

function toneOf(name: string): string {
  const index = connectors.value.findIndex((item) => item.name === name)
  return ['g', 'k', 'm', 's', 'n'][index % 5] ?? 'g'
}

async function loadConnectors(): Promise<void> {
  connectorsLoading.value = connectors.value.length === 0
  connectorsError.value = null
  try {
    const { data } = await api.connector.list()
    connectors.value = data
    if (!selectedId.value && data.length > 0) {
      const preferred = data.find((item) => item.connectorId === 'yonyou-nc-mock')
      selectedId.value = preferred?.connectorId ?? data[0]!.connectorId
    }
  } catch (caught) {
    const apiError = caught instanceof ApiError ? caught : null
    const code = apiError?.code ?? 'E-9001'
    connectors.value = []
    connectorsError.value = { code, message: errorText(code), next: errorNextStep(code) }
  } finally {
    connectorsLoading.value = false
  }
}

/** 探测成功后刷新卡片摘要（lastProbeAt / 能力计数由 BFF 重新计算） */
async function refreshConnectors(): Promise<void> {
  await loadConnectors()
}

function selectConnector(connectorId: string): void {
  selectedId.value = connectorId
}

function openWizard(): void {
  void router.push('/erp/wizard')
}

onMounted(loadConnectors)
</script>

<template>
  <div class="w-page erp-page" data-page="erp" data-testid="page-root">
    <WPageHeader :eyebrow="screen.eyebrow" :title="screen.title" :description="screen.description">
      <template #actions>
        <WTag tone="g" plain data-testid="erp-summary-tag">
          {{ connectors.length }} 套 ERP · {{ capabilityTotal }} 项标准能力
          <template v-if="mockConnectors.length"> · {{ mockConnectors.length }} 套 mock-erp</template>
        </WTag>
        <WButton size="sm" icon="report" @click="specOpen = true">适配规范</WButton>
        <WButton size="sm" variant="primary" icon="plus" @click="openWizard">新增连接器</WButton>
      </template>
    </WPageHeader>

    <div class="erp__banner">
      <span class="erp__banner-icon">解</span>
      <div class="erp__banner-main">
        <b>解耦原则：AI 不直连 ERP，只对接标准能力契约</b>
        <p>
          字段名、状态码、鉴权方式全部收敛在连接器内；能力矩阵来自能力探测，缺能力自动降级并隐藏入口，不做“假支持”。
        </p>
      </div>
      <WTag tone="g">Canonical API v1.4</WTag>
      <span class="dim" data-testid="erp-last-probe">
        最近探测 {{ lastProbeAt ? formatIsoMinute(lastProbeAt) : '—' }}
      </span>
    </div>

    <div class="erp__stats">
      <div class="erp__stat">
        <span class="erp__stat-lab">已接入 ERP</span>
        <b class="erp__stat-val num">{{ connectors.length }}<small>套</small></b>
        <span class="erp__stat-delta">
          {{ mockConnectors.length }} 套 mock-erp 测试替身（演示数据，非真实 ERP）
        </span>
      </div>
      <div class="erp__stat">
        <span class="erp__stat-lab">标准能力</span>
        <b class="erp__stat-val num">{{ capabilityTotal }}<small>项</small></b>
        <span class="erp__stat-delta">CAP-01…CAP-18 · 入口显隐只读能力矩阵</span>
      </div>
      <div class="erp__stat">
        <span class="erp__stat-lab">已探测连接器</span>
        <b class="erp__stat-val num">{{ probedConnectors.length }}<small>/{{ connectors.length }}</small></b>
        <span class="erp__stat-delta">
          未探测 {{ Math.max(0, connectors.length - probedConnectors.length) }} 套按注册声明展示
        </span>
      </div>
      <div class="erp__stat is-teal">
        <span class="erp__stat-lab">能力缺口（跨连接器）</span>
        <b class="erp__stat-val num">{{ totals.degraded + totals.unsupported }}<small>项</small></b>
        <span class="erp__stat-delta">降级 {{ totals.degraded }} · 不支持 {{ totals.unsupported }}（{{ connectors.length }} × {{ capabilityTotal }} 格）</span>
      </div>
    </div>

    <WLoading v-if="connectorsLoading" center label="正在读取 5 套连接器与能力探测摘要…" />

    <div v-else-if="connectorsError" class="erp__state" data-testid="erp-connectors-error">
      <WEmpty
        icon="info"
        title="连接器列表读取失败"
        :description="`${connectorsError.message}（错误码 ${connectorsError.code}）。${connectorsError.next}`"
      />
      <WButton size="sm" icon="refresh" @click="loadConnectors">重试</WButton>
    </div>

    <template v-else>
      <div
        class="erp__tabs"
        :style="{ '--erp-tab-cols': String(tabs.length) }"
        role="tablist"
        aria-label="ERP 适配中心面板"
      >
        <button
          v-for="item in tabs"
          :key="item.key"
          type="button"
          role="tab"
          class="erp__tab"
          :class="{ 'is-on': activeTab === item.key }"
          :aria-selected="activeTab === item.key"
          :data-testid="`erp-tab-${item.key}`"
          @click="activeTab = item.key"
        >
          <b>{{ item.label }}</b>
          <span>{{ item.hint }}</span>
        </button>
      </div>

      <template v-if="activeTab === 'connector'">
        <div class="erp__conns" data-testid="erp-connector-list">
          <button
            v-for="item in connectors"
            :key="item.connectorId"
            type="button"
            class="erp__conn"
            :class="[{ 'is-on': selectedConnector?.connectorId === item.connectorId }, `is-${toneOf(item.name)}`]"
            data-testid="erp-connector-card"
            :data-connector="item.connectorId"
            :data-status="item.status"
            :data-health="item.health"
            :data-mock="item.mock ? '1' : '0'"
            @click="selectConnector(item.connectorId)"
          >
            <div class="erp__conn-head">
              <span class="erp__conn-logo">{{ logoOf(item.name) }}</span>
              <div class="erp__conn-titles">
                <b>{{ item.name }}</b>
                <span>{{ item.vendor }} · {{ item.vendorVersion }}</span>
              </div>
              <WTag :tone="statusMeta(item).tone">{{ statusMeta(item).label }}</WTag>
            </div>
            <div class="erp__conn-meta">
              <div><b class="num">{{ item.supportedCount }}/{{ item.capabilityTotal }}</b><span>支持能力</span></div>
              <div><b class="num">{{ item.degradedCount }}</b><span>降级</span></div>
              <div><b class="num">{{ item.unsupportedCount }}</b><span>不支持</span></div>
            </div>
            <div class="erp__conn-foot">
              <WTag :tone="healthMeta(item).tone" plain data-testid="erp-connector-health" :data-health="healthMeta(item).healthTone">
                {{ healthMeta(item).label }}
              </WTag>
              <WTag v-if="item.mock" tone="v" plain>{{ mockBadgeText }}</WTag>
              <span class="num" data-testid="erp-connector-last-probe">
                {{ item.lastProbeAt ? formatIsoMinute(item.lastProbeAt) : '未探测' }}
              </span>
            </div>
            <div class="erp__conn-tags">
              <code v-if="item.mappingVersion">映射 {{ item.mappingVersion }}</code>
              <span class="dim">{{ healthMeta(item).hint }}</span>
            </div>
          </button>
        </div>

        <ConnectorMatrixPanel
          v-if="selectedId"
          :connector-id="selectedId"
          @probed="refreshConnectors"
        />
        <WEmpty
          v-else
          icon="plug"
          title="暂无连接器"
          description="连接器注册表为空（GET /api/v1/connectors 返回空数组）；不造数，请先接入 ERP。"
        />
      </template>

      <MappingVersionsPanel v-else-if="activeTab === 'mapping'" />
      <ConnectorMarketPanel v-else-if="activeTab === 'market'" />
      <GrayPanel v-else-if="activeTab === 'gray' && canReadGray" :connectors="connectorOptions" />
      <EventsPanel v-else-if="activeTab === 'events' && canReadEvents" />
      <DegradeLedgerPanel v-else-if="activeTab === 'degrade'" :connectors="connectorOptions" />
      <AuditPanel v-else-if="activeTab === 'audit' && canReadAudit" />
      <ReconPanel v-else />

      <WCard flat class="erp__note">
        <p>
          数据口径：连接器/能力/探测/映射/降级台账/对账/审计全部来自 Java BFF 真实接口（契约 v0.3.3）；
          <template v-if="mockConnectors.length">
            {{ mockConnectors.map((item) => item.name).join(' / ') }} 为 mock-erp 测试替身，页面标注「{{ mockBadgeText }}」，
          </template>
          其余连接器为注册声明，未经探测不代表已验证。
        </p>
        <p v-if="!canReadAudit" class="erp__note-dim" data-testid="erp-audit-hidden-hint">
          审计页签仅对具备 <code>{{ AUDIT_READ }}</code> 能力的演示角色（supervisor / it / admin）可见；
          当前演示角色「{{ auth.role ?? '未登录' }}」不展示该入口（接口越权同样会返回 403 E-2001）。
        </p>
      </WCard>
    </template>

    <!-- 适配规范 -->
    <WModal v-model:open="specOpen" title="适配规范（Canonical API v1.4 · 契约 v0.3.2）" width="620px">
      <div class="erp__detail">
        <section class="erp__detail-block">
          <header>标准能力契约，而不是“接口清单”</header>
          <p>
            AI 侧只暴露 18 项标准能力（CAP-01…CAP-18，顺序固定）；每项能力有固定入参、出参、错误码与权限级别
            （L1 只说 / L2 可以做 / L3 不提供）。能力矩阵是唯一事实来源，页面不维护第二套能力表。
          </p>
        </section>
        <section class="erp__detail-block">
          <header>连接器可插拔（5 套注册表）</header>
          <p>
            连接器摘要来自 GET /api/v1/connectors：状态 draft / gray / published / retired，健康度
            healthy / degraded / error / unknown；P2 W7 仅用友 NC mock-erp 可真实探测，其余为注册声明。
          </p>
        </section>
        <section class="erp__detail-block">
          <header>能力探测 + 降级台账</header>
          <p>
            POST /connectors/{id}/probe 逐能力只读探测并落探测日报（summary supported/degraded/unsupported/drift，
            drift = 与上次探测相比状态变化数）；探测失败不更新矩阵并保留上次结果。降级命中写入
            GET /degrade/ledger（连接器 × 能力 × 替代路径），页面只读展示。
          </p>
        </section>
        <section class="erp__detail-block">
          <header>映射版本状态机</header>
          <p>
            draft →（试跑 200 样本 diffRate ≤ 2%）→ trial_passed → 发布 → published → 回滚 rolled_back；
            非法动作返回 409 E-3002，页面把错误码转人话并展示 allowed 允许动作。
          </p>
        </section>
        <section class="erp__detail-block">
          <header>多连接器 · SDK 声明 · 灰度双跑 · 事件（P5 W19 已接真数据）</header>
          <p>
            连接器可插拔由 `GET /connectors` 与 `GET /connectors/{id}/sdk` 表达（sdkVersion / 声明能力 /
            映射字段数 / 运行限制）；灰度策略由 `GET/POST /gray/policies` + `PATCH /gray/policies/{id}`
            管理（percent 0—100，enabled=false 即暂停回滚 ≤5 分钟），新旧连接器差异走
            `GET /gray/compare`（只读，仅作发布决策证据）；事件走 `GET /events`（outbox，投递状态与死信可查）。
          </p>
        </section>
        <section class="erp__detail-block">
          <header>多项目路由（P5 W19：按 projectId 隔离）</header>
          <p>
            项目来自 `GET /projects`（演示 3 个项目：云栖苑 / 金竹苑 / 绿苑，非真实组织架构）；
            顶栏项目切换后 rooms / bills / tickets / complaints / 审计等请求自动带 `?projectId=`，
            跨项目访问由服务端 403 `E-2002` `cross_project` 强制拦截；项目主连接器见 `Project.connectorId`。
          </p>
        </section>
      </div>
      <template #footer>
        <WButton size="sm" variant="primary" @click="specOpen = false">知道了</WButton>
      </template>
    </WModal>

    <StateDemo screen="erp" />
  </div>
</template>

<style scoped>
.erp-page {
  min-width: 0;
}

/* ---------- 横幅 ---------- */
.erp__banner {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  padding: 10px 14px;
  border: 1px solid #d8eae5;
  border-radius: var(--rd-xl);
  background: linear-gradient(95deg, #f2faf7, #fbfdfc);
}

.erp__banner-icon {
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

.erp__banner-main {
  flex: 1;
  min-width: 0;
}

.erp__banner-main b {
  font-size: var(--fs-sm);
  color: var(--c-ink);
}

.erp__banner-main p {
  margin-top: 1px;
  font-size: var(--fs-2xs);
  color: var(--c-muted);
  line-height: var(--lh-base);
}

/* ---------- 指标 ---------- */
.erp__stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--sp-3);
}

.erp__stat {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 12px 14px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-xl);
  background: var(--c-card);
  box-shadow: var(--sh-md);
}

.erp__stat.is-teal {
  border-color: #cfe9e2;
  background: linear-gradient(180deg, #f4fbf9, var(--c-card));
}

.erp__stat-lab {
  font-size: var(--fs-xs);
  color: var(--c-muted);
}

.erp__stat-val {
  font-size: var(--fs-2xl);
  font-weight: var(--fw-semibold);
  color: var(--c-ink);
  line-height: 1.15;
}

.erp__stat-val small {
  margin-left: 2px;
  font-size: var(--fs-xs);
  font-weight: var(--fw-medium);
  color: var(--c-muted);
}

.erp__stat-delta {
  font-size: var(--fs-2xs);
  color: var(--c-faint);
}

/* ---------- 页签 ---------- */
.erp__tabs {
  display: grid;
  grid-template-columns: repeat(var(--erp-tab-cols, 5), minmax(0, 1fr));
  gap: var(--sp-2);
}

.erp__tab {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 1px;
  padding: 8px 12px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-xl);
  background: var(--c-card);
  text-align: left;
  transition: border-color var(--tr-fast), background-color var(--tr-fast), box-shadow var(--tr-fast);
}

.erp__tab b {
  font-size: var(--fs-sm);
  color: var(--c-ink-2);
}

.erp__tab span {
  font-size: var(--fs-2xs);
  color: var(--c-faint);
}

.erp__tab.is-on {
  border-color: var(--c-brand);
  background: linear-gradient(180deg, #f2faf7, var(--c-card));
  box-shadow: 0 0 0 3px var(--c-brand-ring), var(--sh-md);
}

.erp__tab.is-on b {
  color: var(--c-brand);
}

.erp__tab:focus-visible {
  outline: none;
  box-shadow: var(--sh-ring);
}

/* ---------- 连接器卡片 ---------- */
.erp__conns {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: var(--sp-2);
}

.erp__conn {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px 12px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-xl);
  background: var(--c-card);
  box-shadow: var(--sh-md);
  text-align: left;
  transition: border-color var(--tr-fast), box-shadow var(--tr-fast), transform var(--tr-fast);
}

.erp__conn:hover {
  border-color: #cbd7de;
  transform: translateY(-1px);
}

.erp__conn.is-on {
  border-color: var(--c-brand);
  box-shadow: 0 0 0 3px var(--c-brand-ring), var(--sh-md);
}

.erp__conn:focus-visible {
  outline: none;
  box-shadow: var(--sh-ring);
}

.erp__conn-head {
  display: flex;
  align-items: flex-start;
  gap: 7px;
}

.erp__conn-logo {
  display: grid;
  place-items: center;
  flex: none;
  width: 26px;
  height: 26px;
  border-radius: var(--rd-md);
  background: var(--c-brand-soft);
  color: var(--c-brand);
  font-size: var(--fs-xs);
  font-weight: var(--fw-semibold);
}

.erp__conn.is-k .erp__conn-logo {
  background: #eaf1fe;
  color: #1d4ed8;
}

.erp__conn.is-m .erp__conn-logo {
  background: var(--c-amber-soft);
  color: var(--c-amber);
}

.erp__conn.is-s .erp__conn-logo {
  background: var(--c-ai-soft);
  color: var(--c-ai);
}

.erp__conn.is-n .erp__conn-logo {
  background: #f1f4f6;
  color: var(--c-muted);
}

.erp__conn-titles {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
  flex: 1;
}

.erp__conn-titles b {
  font-size: var(--fs-sm);
  color: var(--c-ink);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.erp__conn-titles span {
  font-size: 9px;
  color: var(--c-faint);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.erp__conn-meta {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 2px;
  padding-top: 6px;
  border-top: 1px dashed var(--c-line-2);
}

.erp__conn-meta div {
  display: flex;
  flex-direction: column;
  gap: 0;
}

.erp__conn-meta b {
  font-size: var(--fs-sm);
  color: var(--c-ink-2);
}

.erp__conn-meta span {
  font-size: 9px;
  color: var(--c-faint);
}

.erp__conn-foot {
  display: flex;
  align-items: center;
  gap: 5px;
  flex-wrap: wrap;
}

.erp__conn-foot span {
  font-size: 9px;
  color: var(--c-faint);
}

.erp__conn-tags {
  display: flex;
  flex-direction: column;
  gap: 1px;
  padding-top: 5px;
  border-top: 1px dashed var(--c-line-2);
}

.erp__conn-tags code {
  font-family: var(--font-mono);
  font-size: 9px;
  color: var(--c-ink-2);
}

.erp__conn-tags span {
  font-size: 9px;
  color: var(--c-faint);
  line-height: var(--lh-base);
}

/* ---------- 状态 / 说明 ---------- */
.erp__state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--sp-2);
  padding: var(--sp-3);
}

.erp__note p {
  font-size: var(--fs-2xs);
  color: var(--c-muted);
  line-height: var(--lh-base);
}

.erp__note-dim {
  margin-top: 5px;
  color: var(--c-faint) !important;
}

.erp__note-dim code {
  padding: 0 3px;
  border-radius: var(--rd-xs);
  background: var(--c-bg-2);
  font-family: var(--font-mono);
  font-size: 9px;
}

/* ---------- 规范弹层 ---------- */
.erp__detail {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.erp__detail-block {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 8px 10px;
  border: 1px solid var(--c-line-2);
  border-radius: var(--rd-md);
}

.erp__detail-block header {
  font-size: var(--fs-xs);
  font-weight: var(--fw-semibold);
  color: var(--c-ink-2);
}

.erp__detail-block p {
  font-size: var(--fs-2xs);
  color: var(--c-muted);
  line-height: var(--lh-base);
}

@media (max-width: 1279px) {
  .erp__stats {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .erp__tabs {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .erp__conns {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}
</style>
