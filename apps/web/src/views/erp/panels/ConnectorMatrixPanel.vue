<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { WButton, WCard, WEmpty, WLoading, WTag, useToast } from '@/components'
import { api, ApiError, degradedText, errorNextStep, errorText, formatIsoMinute } from '@/api'
import type { CapabilityState, CapabilityStatus, ConnectorDetail, ProbeLatestResult, ProbeReport } from '@/api'
import { CAPABILITY_STATE_META, CONNECTOR_HEALTH_META, CONNECTOR_STATUS_META, formatDateTime, stateLabelOf } from '../erp-data'
import ConnectorSdkCard from './ConnectorSdkCard.vue'

/**
 * 连接器详情 + 18 项能力矩阵 + 能力探测（P2 W7，契约 v0.3.2 §11.8.3）
 * ---------------------------------------------------------------------------
 * 数据来源（全部真实 API，不造数）：
 * - `GET /api/v1/connectors/{id}` → `ConnectorDetail`（摘要 + 18 项 `CapabilityState`：level/state/evidence/fallback）；
 * - `POST /api/v1/connectors/{id}/probe` → `ProbeReport`（summary supported/degraded/unsupported/drift + 18 项 states）；
 * - `GET /api/v1/connectors/{id}/probe/latest` → 最近一次 `latest` + 近 7 日 `history`（无历史 `null`/`[]`）。
 *
 * 口径：
 * - 三态视觉 supported(支持) / degraded(降级) / unsupported(不支持)；
 * - 入口显隐只读矩阵：不支持 → 入口隐藏（话术不承诺）、降级 → 入口保留但展示替代通道；
 * - 未接入连接器探测 → 503 `E-5001`，展示错误码人话 + 下一步（不编造探测结果）；
 * - 未探测时能力矩阵来自注册声明（`evidence` 会注明「未探测」），不冒充探测值。
 */
const props = defineProps<{ connectorId: string }>()
const emit = defineEmits<{ probed: [] }>()
const toast = useToast()

const detail = ref<ConnectorDetail | null>(null)
const detailLoading = ref(false)
const detailError = ref<{ code: string; message: string; next: string } | null>(null)

const latest = ref<ProbeLatestResult | null>(null)
const latestLoading = ref(false)
const latestError = ref<{ code: string; message: string; next: string } | null>(null)

const probing = ref(false)
const probeReport = ref<ProbeReport | null>(null)
const probeError = ref<{
  code: string
  message: string
  next: string
  status?: string
  probeable?: boolean
} | null>(null)
const probeDegradedText = ref('')
/** 探测前的能力状态快照（用于展示 18 项变化；不是探测结果本身） */
const previousStates = ref<Map<string, CapabilityStatus>>(new Map())

const stateFilter = ref<'all' | CapabilityStatus>('all')

const filters: { key: 'all' | CapabilityStatus; label: string }[] = [
  { key: 'all', label: '全部能力' },
  { key: 'supported', label: '支持' },
  { key: 'degraded', label: '降级' },
  { key: 'unsupported', label: '不支持' },
]

const connector = computed(() => detail.value?.connector ?? null)
const caps = computed<CapabilityState[]>(() => detail.value?.capabilities ?? [])
const isMock = computed(() => connector.value?.mock === true)
/** 未探测：lastProbeAt 为空 → 矩阵是注册声明值（页面明确标注，不冒充探测） */
const notProbed = computed(() => Boolean(connector.value) && !connector.value?.lastProbeAt)

const counts = computed(() => ({
  supported: caps.value.filter((cap) => cap.state === 'supported').length,
  degraded: caps.value.filter((cap) => cap.state === 'degraded').length,
  unsupported: caps.value.filter((cap) => cap.state === 'unsupported').length,
}))

const filteredCaps = computed<CapabilityState[]>(() =>
  stateFilter.value === 'all' ? caps.value : caps.value.filter((cap) => cap.state === stateFilter.value),
)

/** 缺能力项：降级/不支持都必须给替代通道文案（入口显隐的唯一依据） */
const missingCaps = computed(() => caps.value.filter((cap) => cap.state !== 'supported'))

const latestReport = computed<ProbeReport | null>(() => latest.value?.latest ?? null)
const history = computed<ProbeReport[]>(() => latest.value?.history ?? [])

/** 能力码 → 中文名（给 SDK 声明卡复用，避免 SDK 卡另写一套能力标签） */
const capabilityNames = computed<Record<string, string>>(() => {
  const map: Record<string, string> = {}
  for (const cap of caps.value) map[cap.code] = cap.name
  return map
})

/** 本次探测 vs 探测前矩阵：状态变化的能力（18 项逐项比对） */
const changedCaps = computed(() => {
  if (!probeReport.value) return []
  return probeReport.value.states
    .map((state) => ({ state, previous: previousStates.value.get(state.code) }))
    .filter((item) => item.previous !== undefined && item.previous !== item.state.state)
})

function stateMeta(state: CapabilityStatus) {
  return CAPABILITY_STATE_META[state]
}

function healthMeta(health: string) {
  return CONNECTOR_HEALTH_META[health as keyof typeof CONNECTOR_HEALTH_META] ?? CONNECTOR_HEALTH_META.unknown
}

function statusMeta(status: string) {
  return CONNECTOR_STATUS_META[status as keyof typeof CONNECTOR_STATUS_META] ?? CONNECTOR_STATUS_META.draft
}

function fillDetailError(error: unknown): { code: string; message: string; next: string } {
  const apiError = error instanceof ApiError ? error : null
  const code = apiError?.code ?? 'E-9001'
  return { code, message: errorText(code), next: errorNextStep(code) }
}

async function loadDetail(): Promise<void> {
  if (!props.connectorId) return
  detailLoading.value = true
  detailError.value = null
  try {
    const { data } = await api.connector.get(props.connectorId)
    detail.value = data
  } catch (error) {
    detail.value = null
    detailError.value = fillDetailError(error)
  } finally {
    detailLoading.value = false
  }
}

async function loadLatest(): Promise<void> {
  if (!props.connectorId) return
  latestLoading.value = true
  latestError.value = null
  try {
    const { data } = await api.connector.probeLatest(props.connectorId)
    latest.value = data
  } catch (error) {
    latest.value = null
    latestError.value = fillDetailError(error)
  } finally {
    latestLoading.value = false
  }
}

async function reload(): Promise<void> {
  probeReport.value = null
  probeError.value = null
  probeDegradedText.value = ''
  await Promise.all([loadDetail(), loadLatest()])
}

watch(() => props.connectorId, reload, { immediate: true })

/** 触发能力探测（POST /probe；配置类动作，幂等键按「连接器 + 时间片」复用） */
async function runProbe(): Promise<void> {
  if (!props.connectorId || probing.value) return
  probing.value = true
  probeError.value = null
  probeDegradedText.value = ''
  previousStates.value = new Map(caps.value.map((cap) => [cap.code, cap.state]))
  try {
    const { data, degraded } = await api.connector.probe(
      props.connectorId,
      `ui-probe-${props.connectorId}-${Date.now().toString(36)}`,
    )
    probeReport.value = data
    probeDegradedText.value = degraded ? degradedText(degraded) : ''
    toast.success(
      `探测完成：支持 ${data.summary.supported} · 降级 ${data.summary.degraded} · 不支持 ${data.summary.unsupported} · drift ${data.summary.drift}`,
    )
    await Promise.all([loadDetail(), loadLatest()])
    emit('probed')
  } catch (error) {
    const apiError = error instanceof ApiError ? error : null
    const code = apiError?.code ?? 'E-9001'
    const status = apiError?.details.status
    probeError.value = {
      code,
      message: errorText(code),
      next: errorNextStep(code),
      status: typeof status === 'string' ? status : undefined,
      probeable: apiError?.details.probeable === false ? false : undefined,
    }
    toast.error(`探测未完成：${errorText(code)}；${errorNextStep(code)}`)
  } finally {
    probing.value = false
  }
}
</script>

<template>
  <section class="erp-matrix" data-testid="erp-capability-panel">
    <WCard :padded="false">
      <template #head>
        <span class="erp__card-title">
          标准能力矩阵 · {{ connector?.name ?? '连接器' }}
          <em v-if="connector" class="erp__card-code">{{ connector.connectorId }}</em>
        </span>
        <span class="erp__card-sub">
          {{ notProbed ? '注册声明值（未探测）' : '由能力探测自动生成' }} · 18 项标准能力固定顺序 CAP-01…CAP-18
        </span>
      </template>
      <template #extra>
        <div class="erp__legend" data-testid="erp-capability-legend">
          <span><i class="is-y" />支持</span>
          <span><i class="is-d" />降级</span>
          <span><i class="is-n" />不支持</span>
        </div>
        <WTag v-if="isMock" tone="v" plain data-testid="erp-connector-mock">演示连接器（mock ERP）</WTag>
        <WTag v-if="notProbed" tone="a" plain data-testid="erp-capability-declared">声明值（未探测）</WTag>
        <WButton size="sm" icon="refresh" :loading="probing" data-testid="erp-probe-run" @click="runProbe">
          {{ probing ? '探测中…' : '执行能力探测' }}
        </WButton>
      </template>

      <div class="erp__toolbar">
        <div class="erp__segs" role="tablist" aria-label="能力状态筛选">
          <button
            v-for="item in filters"
            :key="item.key"
            type="button"
            role="tab"
            class="erp__seg"
            :class="{ 'is-on': stateFilter === item.key }"
            :aria-selected="stateFilter === item.key"
            @click="stateFilter = item.key"
          >
            {{ item.label }}
          </button>
        </div>
        <span class="erp__toolbar-stat dim" data-testid="erp-capability-counts">
          支持 {{ counts.supported }} · 降级 {{ counts.degraded }} · 不支持 {{ counts.unsupported }}（共
          {{ caps.length }} 项）
        </span>
      </div>

      <WLoading v-if="detailLoading" center label="正在读取连接器 18 项能力三态…" />

      <div v-else-if="detailError" class="erp__state" data-testid="erp-capability-error">
        <WEmpty
          icon="info"
          title="能力矩阵读取失败"
          :description="`${detailError.message}（错误码 ${detailError.code}）。${detailError.next}`"
        />
        <WButton size="sm" icon="refresh" @click="loadDetail">重试</WButton>
      </div>

      <WEmpty
        v-else-if="caps.length === 0"
        icon="mapping"
        title="该连接器尚无能力数据"
        description="未探测连接器返回空能力矩阵（不造数）；可点击「执行能力探测」或等待注册声明同步。"
      />

      <table v-else class="erp__cap-table" data-testid="erp-capability-matrix">
        <thead>
          <tr>
            <th scope="col" style="width: 208px">标准能力</th>
            <th scope="col" style="width: 46px">级别</th>
            <th scope="col" style="width: 66px">状态</th>
            <th scope="col" style="width: 236px">入口显隐 / 降级路径</th>
            <th scope="col">探测证据</th>
            <th scope="col" style="width: 104px">探测时间</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="cap in filteredCaps"
            :key="cap.code"
            data-testid="erp-cap-row"
            :data-code="cap.code"
            :data-state="cap.state"
          >
            <td>
              <div class="erp__cap">
                <b>{{ cap.name }}</b>
                <code>{{ cap.capabilityCode ?? '—' }} · {{ cap.code }}</code>
              </div>
            </td>
            <td><span class="dim">{{ cap.level }}</span></td>
            <td>
              <WTag :tone="stateMeta(cap.state).tone">{{ stateMeta(cap.state).label }}</WTag>
            </td>
            <td>
              <span
                class="erp__entry"
                :class="{
                  'is-on': cap.state === 'supported',
                  'is-warn': cap.state === 'degraded',
                  'is-off': cap.state === 'unsupported',
                }"
              >
                {{ stateMeta(cap.state).entry }}
                <template v-if="cap.state !== 'supported'"> → {{ cap.fallback || '转人工' }}</template>
              </span>
            </td>
            <td><span class="erp__evidence">{{ cap.evidence || '—' }}</span></td>
            <td><span class="dim">{{ formatDateTime(cap.checkedAt) }}</span></td>
          </tr>
        </tbody>
      </table>

      <template #footer>
        <div class="erp__matrix-foot">
          <div v-if="missingCaps.length" class="erp__entry-hide" data-testid="erp-capability-hidden" :data-count="missingCaps.length">
            <b>缺能力入口显隐（{{ missingCaps.length }} 项，页面不硬编码）</b>
            <p v-for="cap in missingCaps" :key="`hidden-${cap.code}`" data-testid="erp-cap-hidden-item">
              {{ cap.name }}（{{ cap.code }}）→
              {{ cap.state === 'unsupported' ? '入口隐藏 · 话术不承诺' : '入口降级 · 能力受限提示' }}：{{
                cap.fallback || '转人工'
              }}
            </p>
          </div>
          <p v-else class="dim">18 项能力全部 supported：入口全部可用，无需降级路径。</p>
          <span class="dim" data-testid="erp-capability-meta">
            {{ connector ? `${connector.vendor} · ${connector.vendorVersion}` : '' }} ·
            {{ connector ? statusMeta(connector.status).label : '' }} ·
            健康度 {{ connector ? healthMeta(connector.health).label : '' }}
            <template v-if="connector?.lastProbeAt"> · 最近探测 {{ formatIsoMinute(connector.lastProbeAt) }}</template>
            <template v-if="connector?.mappingVersion"> · 映射 {{ connector.mappingVersion }}</template>
          </span>
        </div>
      </template>
    </WCard>

    <!-- P5 W19：连接器 SDK 声明（sdkVersion / fieldCount / limits / 声明能力） -->
    <ConnectorSdkCard
      :connector-id="connectorId"
      :capability-names="capabilityNames"
      :mock="isMock"
    />

    <!-- 探测结果：summary + 18 项变化 -->
    <WCard v-if="probeReport" data-testid="erp-probe-report" :padded="false">
      <template #head>
        <span class="erp__card-title">探测日报 · {{ probeReport.probeId }}</span>
        <span class="erp__card-sub">{{ formatDateTime(probeReport.startedAt) }} → {{ formatDateTime(probeReport.finishedAt) }}</span>
      </template>
      <template #extra>
        <WTag :tone="probeReport.summary.unsupported > 0 ? 'a' : 'g'">
          drift {{ probeReport.summary.drift }}
        </WTag>
      </template>
      <div class="erp__probe-body">
        <div class="erp__probe-summary" data-testid="erp-probe-summary">
          <span><b class="num">{{ probeReport.summary.supported }}</b> 支持</span>
          <span><b class="num is-warn">{{ probeReport.summary.degraded }}</b> 降级</span>
          <span><b class="num is-off">{{ probeReport.summary.unsupported }}</b> 不支持</span>
          <span data-testid="erp-probe-drift"><b class="num">{{ probeReport.summary.drift }}</b> drift（与上次探测相比变化数）</span>
        </div>
        <p v-if="probeDegradedText" class="erp__probe-degraded">{{ probeDegradedText }}</p>
        <div v-if="changedCaps.length" class="erp__probe-changes" data-testid="erp-probe-changes">
          <b>与探测前矩阵比对：{{ changedCaps.length }} 项状态变化</b>
          <span v-for="item in changedCaps" :key="`change-${item.state.code}`" data-testid="erp-probe-change">
            {{ item.state.name }}（{{ item.state.code }}）：{{ stateLabelOf(item.previous) }} →
            {{ stateLabelOf(item.state.state) }}
          </span>
        </div>
        <p v-else class="dim" data-testid="erp-probe-no-change">与探测前矩阵一致：18 项能力状态无变化。</p>
      </div>
    </WCard>

    <!-- 探测失败：可解释错误（如未接入连接器 503 E-5001） -->
    <WCard v-if="probeError" data-testid="erp-probe-error" class="erp-probe-error">
      <template #head>
        <span class="erp__card-title">本次探测未完成</span>
        <span class="erp__card-sub">{{ probeError.code }}</span>
      </template>
      <template #extra>
        <WTag tone="a" plain>{{ probeError.code }}</WTag>
      </template>
      <div class="erp__probe-body">
        <p class="erp__probe-fail">{{ probeError.message }}（错误码 {{ probeError.code }}）。{{ probeError.next }}</p>
        <p v-if="probeError.probeable === false" class="dim">
          该连接器为注册声明（status={{ probeError.status ?? 'unknown' }}，probeable=false）：P2 W7 仅用友 NC
          mock-erp 可真实探测；其余连接器接入后自动开放探测。能力矩阵仍按注册声明展示，不代表已验证。
        </p>
        <p v-else-if="probeError.status" class="dim">连接器当前状态：{{ probeError.status }}。</p>
        <p class="erp__probe-note">失败不更新能力矩阵：保留上一次探测/声明结果，不编造探测数字。</p>
      </div>
    </WCard>

    <!-- 最近一次 + 近 7 日历史 -->
    <WCard v-if="latestLoading" :padded="false"><WLoading center label="正在读取最近一次探测日报…" /></WCard>
    <WCard v-else-if="latestError" :padded="false" data-testid="erp-probe-latest-error">
      <div class="erp__state">
        <WEmpty icon="info" title="探测历史读取失败" :description="`${latestError.message}（${latestError.code}）`" />
        <WButton size="sm" icon="refresh" @click="loadLatest">重试</WButton>
      </div>
    </WCard>
    <WCard v-else data-testid="erp-probe-latest" :padded="false">
      <template #head>
        <span class="erp__card-title">探测日报 · 最近一次 + 近 7 日历史</span>
        <span class="erp__card-sub">GET /connectors/{{ connectorId }}/probe/latest</span>
      </template>
      <div class="erp__history-body">
        <div v-if="latestReport" class="erp__latest">
          <div class="erp__latest-head">
            <b>{{ latestReport.probeId }}</b>
            <span class="dim">{{ formatDateTime(latestReport.finishedAt) }}</span>
            <WTag tone="g" plain>最新</WTag>
          </div>
          <div class="erp__probe-summary">
            <span><b class="num">{{ latestReport.summary.supported }}</b> 支持</span>
            <span><b class="num is-warn">{{ latestReport.summary.degraded }}</b> 降级</span>
            <span><b class="num is-off">{{ latestReport.summary.unsupported }}</b> 不支持</span>
            <span><b class="num">{{ latestReport.summary.drift }}</b> drift</span>
          </div>
        </div>
        <WEmpty
          v-else
          compact
          icon="refresh"
          title="尚无探测日报"
          description="该连接器还没有探测记录（latest=null，不造数）；点击「执行能力探测」生成 18 项探测日报。"
        />
        <div v-if="history.length" class="erp__history" data-testid="erp-probe-history">
          <b>近 7 日历史（{{ history.length }} 条）</b>
          <div v-for="item in history" :key="item.probeId" class="erp__history-row" data-testid="erp-probe-history-item">
            <span>{{ item.probeId }}</span>
            <span class="dim">{{ formatDateTime(item.finishedAt) }}</span>
            <span class="num">支持 {{ item.summary.supported }} · 降级 {{ item.summary.degraded }} · 不支持 {{ item.summary.unsupported }} · drift {{ item.summary.drift }}</span>
          </div>
        </div>
        <p v-else class="dim">近 7 日暂无历史探测日报。</p>
      </div>
    </WCard>
  </section>
</template>

<style scoped>
.erp-matrix {
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

.erp__card-code {
  margin-left: 6px;
  font-family: var(--font-mono);
  font-size: 9px;
  font-style: normal;
  font-weight: var(--fw-regular);
  color: var(--c-faint);
}

.erp__card-sub {
  margin-left: 6px;
  font-size: var(--fs-2xs);
  color: var(--c-faint);
}

.erp__legend {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-right: 6px;
  font-size: var(--fs-2xs);
  color: var(--c-muted);
}

.erp__legend span {
  display: inline-flex;
  align-items: center;
  gap: 3px;
}

.erp__legend i {
  width: 8px;
  height: 8px;
  border-radius: 2px;
  background: #bfe7db;
}

.erp__legend i.is-d {
  background: #f6d9ae;
}

.erp__legend i.is-n {
  background: #e5eaee;
}

.erp__toolbar {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  padding: 8px 12px;
  border-bottom: 1px solid var(--c-line-2);
  flex-wrap: wrap;
}

.erp__segs {
  display: flex;
  gap: 2px;
  padding: 2px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-md);
  background: var(--c-bg-2);
}

.erp__seg {
  height: 23px;
  padding: 0 9px;
  border-radius: var(--rd-sm);
  font-size: var(--fs-2xs);
  color: var(--c-muted);
  transition: background-color var(--tr-fast), color var(--tr-fast);
}

.erp__seg.is-on {
  background: var(--c-card);
  color: var(--c-brand);
  font-weight: var(--fw-semibold);
  box-shadow: var(--sh-sm);
}

.erp__seg:focus-visible {
  outline: none;
  box-shadow: var(--sh-ring);
}

.erp__toolbar-stat {
  margin-left: auto;
  font-size: var(--fs-2xs);
}

.erp__cap-table {
  width: 100%;
  border-collapse: separate;
  border-spacing: 0;
  font-size: var(--fs-sm);
}

.erp__cap-table th {
  position: sticky;
  top: 0;
  z-index: 1;
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

.erp__cap-table td {
  padding: 7px 12px;
  border-bottom: 1px solid #f0f3f5;
  color: var(--c-ink-2);
  vertical-align: middle;
}

.erp__cap-table tbody tr:last-child td {
  border-bottom: 0;
}

.erp__cap-table tbody tr:hover td {
  background: var(--c-bg-2);
}

.erp__cap {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  flex-wrap: wrap;
}

.erp__cap b {
  font-size: var(--fs-xs);
  color: var(--c-ink-2);
  white-space: nowrap;
}

.erp__cap code {
  font-family: var(--font-mono);
  font-size: 9px;
  color: var(--c-faint);
}

.erp__entry {
  font-size: var(--fs-2xs);
  color: var(--c-muted);
  line-height: var(--lh-base);
}

.erp__entry.is-on {
  color: var(--c-brand);
}

.erp__entry.is-warn {
  color: var(--c-amber);
}

.erp__entry.is-off {
  color: var(--c-faint);
  text-decoration: line-through;
  text-decoration-color: #cfd8de;
}

.erp__evidence {
  font-size: var(--fs-2xs);
  color: var(--c-muted);
  line-height: var(--lh-base);
}

.erp__matrix-foot {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--sp-3);
  flex-wrap: wrap;
}

.erp__entry-hide {
  display: flex;
  flex-direction: column;
  gap: 2px;
  max-width: 720px;
}

.erp__entry-hide b {
  font-size: var(--fs-2xs);
  color: var(--c-ink-2);
}

.erp__entry-hide p {
  font-size: var(--fs-2xs);
  color: var(--c-muted);
  line-height: var(--lh-base);
}

.erp__state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--sp-2);
  padding: var(--sp-3);
}

.erp__probe-body,
.erp__history-body {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px 12px;
}

.erp__probe-summary {
  display: flex;
  align-items: center;
  gap: var(--sp-4);
  flex-wrap: wrap;
  font-size: var(--fs-xs);
  color: var(--c-muted);
}

.erp__probe-summary b {
  font-size: var(--fs-base);
  color: var(--c-brand);
  margin-right: 2px;
}

.erp__probe-summary b.is-warn {
  color: var(--c-amber);
}

.erp__probe-summary b.is-off {
  color: var(--c-red);
}

.erp__probe-changes {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px 10px;
  border: 1px solid var(--c-line-2);
  border-radius: var(--rd-md);
  background: var(--c-bg-2);
  font-size: var(--fs-2xs);
  color: var(--c-muted);
}

.erp__probe-changes b {
  color: var(--c-ink-2);
}

.erp__probe-fail {
  font-size: var(--fs-xs);
  color: var(--c-amber);
  line-height: var(--lh-base);
}

.erp__probe-note,
.erp__probe-degraded {
  font-size: var(--fs-2xs);
  color: var(--c-faint);
  line-height: var(--lh-base);
}

.erp-probe-error {
  border-color: #f0dcb8;
}

.erp__latest {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 8px 10px;
  border: 1px solid var(--c-line-2);
  border-radius: var(--rd-md);
}

.erp__latest-head {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  font-size: var(--fs-xs);
}

.erp__history {
  display: flex;
  flex-direction: column;
  gap: 3px;
  font-size: var(--fs-2xs);
  color: var(--c-muted);
}

.erp__history-row {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  padding: 4px 8px;
  border-radius: var(--rd-sm);
  background: var(--c-bg-2);
}

.erp__history-row span:first-child {
  font-family: var(--font-mono);
  color: var(--c-ink-2);
}
</style>
