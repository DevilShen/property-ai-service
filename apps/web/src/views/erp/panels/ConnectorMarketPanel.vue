<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { WButton, WCard, WEmpty, WLoading, WTag } from '@/components'
import PermissionDenied from '@/components/feedback/PermissionDenied.vue'
import { api, ApiError, errorNextStep, errorText, forbiddenDetailsOf, formatIsoMinute } from '@/api'
import type { CapabilityState, CapabilityStatus, ConnectorDetail, ConnectorSdk, ConnectorSummary } from '@/api'
import { capabilityLabel } from '@/auth/capabilities'
import { useAuthStore } from '@/stores/auth'
import { CAPABILITY_STATE_META, CONNECTOR_HEALTH_META, CONNECTOR_STATUS_META } from '../erp-data'

/**
 * 连接器市场 · 能力对比（P5 W23 / P5-030，契约 v1.3.0 `GET /connectors` + `/connectors/{id}` + `/sdk`）
 * ===========================================================================
 * 数据来源（全部真实 API，页面不硬编码任何能力/计数）：
 * - `GET /api/v1/connectors`：连接器摘要（mock / status / health / 三态计数 / lastProbeAt）；
 * - `GET /api/v1/connectors/{id}`：18 项 `CapabilityState`（level/state/evidence/fallback）；
 * - `GET /api/v1/connectors/{id}/sdk`：`ConnectorSdk{sdkVersion, capabilities[], fieldCount, limits}`。
 *
 * 对比范围（数据驱动，不写死条数）：`mock === true` 的**已接入 mock 数据面连接器**。
 * 仅注册声明（mock=false，无连接器实现、探测 503 E-5001）的连接器不进入对比，避免把「声明」
 * 与「已接入」混为一谈（AGENTS.md NEVER #4/#11）。
 *
 * 口径（不把声明说成已验证）：
 * - 18 项三态来自能力矩阵：`lastProbeAt` 为空时是**注册声明**（未探测），页面明确标注；
 * - SDK `capabilities` 是连接器「自称可实现」的集合（⊆18 项），与三态矩阵是两套口径，不合并展示；
 * - 选型建议由页面按规则生成（supported/degraded/unsupported 计数 + SDK limits + status/health），
 *   规则与依据全部来自 API 数值，结论文案里带出数字与能力码，可回溯；
 * - 3 套连接器均为 mock ERP 测试替身（:4200 / :4400），页面标注「演示连接器（mock ERP）」；
 * - 加载 / 空 / 错 / 403 四态齐全：403 走 `PermissionDenied`（展示 requiredCapability，不渲染未授权数据）。
 */
const auth = useAuthStore()

interface Column {
  summary: ConnectorSummary
  detail: ConnectorDetail | null
  sdk: ConnectorSdk | null
  detailError: string
}

const columns = ref<Column[]>([])
const loading = ref(true)
const error = ref<{ code: string; message: string; next: string } | null>(null)
const denied = ref<{ requiredCapability: string; role: string; traceId: string } | null>(null)
const switching = ref(false)

const capabilityRows = computed<CapabilityState[]>(() => {
  // 行 = 18 项标准能力（顺序取首个连接器矩阵的固定枚举序；缺失按能力码兜底，不新造能力）
  const first = columns.value.find((column) => column.detail)?.detail
  return first?.capabilities ?? []
})

const stateOf = computed(() => {
  const map = new Map<string, Map<string, CapabilityStatus>>()
  for (const column of columns.value) {
    const states = new Map<string, CapabilityStatus>()
    for (const cap of column.detail?.capabilities ?? []) states.set(cap.code, cap.state)
    map.set(column.summary.connectorId, states)
  }
  return map
})

const evidenceOf = computed(() => {
  const map = new Map<string, string>()
  for (const column of columns.value) {
    for (const cap of column.detail?.capabilities ?? []) {
      map.set(`${column.summary.connectorId}:${cap.code}`, cap.evidence ?? '')
    }
  }
  return map
})

const stats = computed(() =>
  columns.value.map((column) => ({
    connectorId: column.summary.connectorId,
    name: column.summary.name,
    supported: column.summary.supportedCount,
    degraded: column.summary.degradedCount,
    unsupported: column.summary.unsupportedCount,
    total: column.summary.capabilityTotal,
  })),
)

const lastProbeAt = computed(() => {
  const times = columns.value
    .map((column) => column.summary.lastProbeAt)
    .filter((value): value is string => Boolean(value))
    .sort()
  return times[times.length - 1] ?? ''
})

/** 三态是否全部是注册声明（无任何探测记录）：用于「声明 ≠ 已验证」标注 */
const declaredOnly = computed(() => columns.value.length > 0 && !lastProbeAt.value)

const missingOf = (column: ConnectorSummary, state: CapabilityStatus): string => {
  const detail = columns.value.find((item) => item.summary.connectorId === column.connectorId)?.detail
  const codes = (detail?.capabilities ?? []).filter((cap) => cap.state === state).map((cap) => cap.code)
  return codes.join(' / ')
}

function stateMeta(state?: CapabilityStatus) {
  return state ? CAPABILITY_STATE_META[state] : null
}

function statusMeta(status: string) {
  return CONNECTOR_STATUS_META[status as keyof typeof CONNECTOR_STATUS_META] ?? CONNECTOR_STATUS_META.draft
}

function healthMeta(health: string) {
  return CONNECTOR_HEALTH_META[health as keyof typeof CONNECTOR_HEALTH_META] ?? CONNECTOR_HEALTH_META.unknown
}

/**
 * 选型建议（规则生成；每条都带 API 数值与能力码，便于回溯，不做主观排序之外的推测）
 * 规则：① 能力最全（supported 最多）② degraded 描述 ③ unsupported 描述 ④ SDK 限额最保守 ⑤ 生命周期基线
 */
const advice = computed<{ rule: string; text: string }[]>(() => {
  if (stats.value.length === 0) return []
  const items: { rule: string; text: string }[] = []
  const total = stats.value[0]?.total ?? 18

  // ① 能力最全
  const maxSupported = Math.max(...stats.value.map((item) => item.supported))
  const strongest = stats.value.filter((item) => item.supported === maxSupported)
  items.push({
    rule: 'capability-coverage',
    text: `能力最全：${strongest.map((item) => `${item.name}（${item.supported}/${total} supported）`).join('、')}${
      strongest.every((item) => item.degraded === 0 && item.unsupported === 0) ? '，无能力缺口' : ''
    }`,
  })

  // ② 降级项：入口保留但必须展示替代通道
  for (const item of stats.value.filter((entry) => entry.degraded > 0)) {
    const summary = columns.value.find((column) => column.summary.connectorId === item.connectorId)?.summary
    if (!summary) continue
    items.push({
      rule: `degraded:${item.connectorId}`,
      text: `降级保留：${item.name} degraded ${item.degraded} 项（${missingOf(summary, 'degraded')}）——入口保留，必须展示降级路径与替代通道`,
    })
  }

  // ③ 不支持项：入口隐藏，不承诺
  for (const item of stats.value.filter((entry) => entry.unsupported > 0)) {
    const summary = columns.value.find((column) => column.summary.connectorId === item.connectorId)?.summary
    if (!summary) continue
    items.push({
      rule: `unsupported:${item.connectorId}`,
      text: `入口隐藏：${item.name} unsupported ${item.unsupported} 项（${missingOf(summary, 'unsupported')}）——不承诺能力，走替代通道或转人工`,
    })
  }

  // ④ SDK 限额（timeout / pageSize / rateLimit 三个维度分别取最保守 = 最小）
  const withSdk = columns.value.filter((column) => column.sdk)
  if (withSdk.length > 1) {
    const minBy = (pick: (sdk: ConnectorSdk) => number): { name: string; value: number } => {
      const sorted = [...withSdk].sort((left, right) => pick(left.sdk!) - pick(right.sdk!))
      return { name: sorted[0]!.summary.name, value: pick(sorted[0]!.sdk!) }
    }
    const timeout = minBy((sdk) => sdk.limits.timeoutMs)
    const pageSize = minBy((sdk) => sdk.limits.pageSizeMax)
    const rate = minBy((sdk) => sdk.limits.rateLimitPerMin)
    items.push({
      rule: 'sdk-limits',
      text: `SDK 限额：${timeout.name} 超时最保守（timeoutMs ${timeout.value}）· ${pageSize.name} 分页上限最小（pageSizeMax ${pageSize.value}）· ${rate.name} 限流最低（${rate.value} 次/分钟）——批量接入前先评估分页与并发`,
    })
  }

  // ⑤ 生命周期基线（已发布 + 健康 = 可直接作为演示基线；其余处于灰度/草稿）
  const published = stats.value.filter((item) => {
    const summary = columns.value.find((column) => column.summary.connectorId === item.connectorId)?.summary
    return summary?.status === 'published'
  })
  if (published.length) {
    items.push({
      rule: 'lifecycle',
      text: `发布基线：${published.map((item) => item.name).join('、')} 已发布（status=published），其余连接器处于灰度/草稿态`,
    })
  }

  // ⑥ 结论：能力最全 + 已发布优先；其余建议灰度验证
  const scored = [...columns.value]
    .filter((column) => column.detail)
    .sort((left, right) => {
      const statusRank = (status: string) => (status === 'published' ? 2 : status === 'gray' ? 1 : 0)
      const byStatus = statusRank(right.summary.status) - statusRank(left.summary.status)
      if (byStatus !== 0) return byStatus
      if (right.summary.supportedCount !== left.summary.supportedCount) {
        return right.summary.supportedCount - left.summary.supportedCount
      }
      return left.summary.degradedCount - right.summary.degradedCount
    })
  const first = scored[0]
  const rest = scored.slice(1)
  if (first) {
    items.push({
      rule: 'recommendation',
      text:
        `选型建议：首选 ${first.summary.name}（${first.summary.supportedCount}/${first.summary.capabilityTotal} supported，` +
        `status=${first.summary.status}，health=${first.summary.health}）` +
        (rest.length
          ? `；${rest
              .map(
                (column) =>
                  `${column.summary.name} 灰度验证（degraded ${column.summary.degradedCount} / unsupported ${column.summary.unsupportedCount}，需先落地降级路径）`,
              )
              .join('；')}`
          : ''),
    })
  }
  return items
})

function fillError(caught: unknown): { code: string; message: string; next: string } {
  const apiError = caught instanceof ApiError ? caught : null
  const code = apiError?.code ?? 'E-9001'
  return { code, message: errorText(code), next: errorNextStep(code) }
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  denied.value = null
  try {
    const { data } = await api.connector.list()
    // 对比范围：已接入 mock 数据面的连接器（mock=true）；注册声明类（mock=false）不参与对比
    const comparable = data.filter((item) => item.mock)
    const loaded = await Promise.all(
      comparable.map(async (summary): Promise<Column> => {
        try {
          const [detail, sdk] = await Promise.all([
            api.connector.get(summary.connectorId),
            api.connector.sdk(summary.connectorId),
          ])
          return { summary, detail: detail.data, sdk: sdk.data, detailError: '' }
        } catch (caught) {
          const apiError = caught instanceof ApiError ? caught : null
          return { summary, detail: null, sdk: null, detailError: apiError?.code ?? 'E-9001' }
        }
      }),
    )
    columns.value = loaded
  } catch (caught) {
    columns.value = []
    const forbidden = forbiddenDetailsOf(caught)
    if (forbidden) {
      denied.value = {
        requiredCapability: forbidden.requiredCapability || 'connector.read',
        role: forbidden.role || (auth.role ?? ''),
        traceId: caught instanceof ApiError ? caught.traceId : '',
      }
    } else {
      error.value = fillError(caught)
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
    await load()
  } finally {
    switching.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="market" data-testid="erp-market-panel">
    <WCard :padded="false">
      <template #head>
        <span class="market__title">连接器市场 · 能力对比</span>
        <span class="market__sub">
          GET /connectors + /connectors/{id} + /sdk · 18 项能力三态 × SDK 元数据 · 数据全部来自 API
        </span>
      </template>
      <template #extra>
        <WTag tone="b" plain data-testid="erp-market-scope">
          对比范围 {{ columns.length }} 套 · mock=true（已接入 mock 数据面）
        </WTag>
        <WTag v-if="declaredOnly" tone="a" plain data-testid="erp-market-declared-only">
          三态为注册声明（未探测）
        </WTag>
        <span class="dim market__probe" data-testid="erp-market-last-probe">
          最近探测 {{ lastProbeAt ? formatIsoMinute(lastProbeAt) : '—' }}
        </span>
        <WButton size="sm" icon="refresh" :loading="loading" data-testid="erp-market-refresh" @click="load">
          刷新
        </WButton>
      </template>

      <WLoading v-if="loading" center label="正在读取连接器能力矩阵与 SDK 元数据…" />

      <div v-else-if="denied" class="market__state" data-testid="erp-market-denied">
        <PermissionDenied
          :required-capability="denied.requiredCapability"
          :required-role="denied.role"
          :trace-id="denied.traceId"
          :switching="switching"
          :detail="`连接器对比需要能力 ${capabilityLabel(denied.requiredCapability)}；当前演示角色「${auth.role ?? '未登录'}」不具备该能力，页面不会展示未授权连接器数据。`"
          @switch="switchAccount"
        />
      </div>

      <div v-else-if="error" class="market__state" data-testid="erp-market-error">
        <WEmpty
          icon="info"
          title="连接器对比读取失败"
          :description="`${error.message}（错误码 ${error.code}）。${error.next}`"
        />
        <WButton size="sm" icon="refresh" @click="load">重试</WButton>
      </div>

      <WEmpty
        v-else-if="columns.length === 0"
        data-testid="erp-market-empty"
        icon="plug"
        title="暂无可对比的连接器"
        description="GET /connectors 返回的注册表里没有 mock=true（已接入 mock 数据面）的连接器；不造数，请先接入连接器。"
      />

      <div v-else class="market__body">
        <!-- ① 每套连接器：三态统计 -->
        <div class="market__cards">
          <div
            v-for="column in columns"
            :key="column.summary.connectorId"
            class="market__conn"
            data-testid="erp-market-connector"
            :data-connector="column.summary.connectorId"
            :data-supported="column.summary.supportedCount"
            :data-degraded="column.summary.degradedCount"
            :data-unsupported="column.summary.unsupportedCount"
            :data-mock="column.summary.mock ? '1' : '0'"
            :data-status="column.summary.status"
            :data-health="column.summary.health"
          >
            <div class="market__conn-head">
              <b>{{ column.summary.name }}</b>
              <WTag :tone="statusMeta(column.summary.status).tone" plain>
                {{ statusMeta(column.summary.status).label }}
              </WTag>
            </div>
            <span class="market__conn-vendor">
              {{ column.summary.vendor }} · {{ column.summary.vendorVersion }}
              <template v-if="column.summary.mappingVersion"> · 映射 {{ column.summary.mappingVersion }}</template>
            </span>
            <div class="market__conn-nums">
              <div class="is-supported">
                <b class="num">{{ column.summary.supportedCount }}</b><span>supported</span>
              </div>
              <div class="is-degraded">
                <b class="num">{{ column.summary.degradedCount }}</b><span>degraded</span>
              </div>
              <div class="is-unsupported">
                <b class="num">{{ column.summary.unsupportedCount }}</b><span>unsupported</span>
              </div>
              <div>
                <b class="num">{{ column.summary.capabilityTotal }}</b><span>能力总数</span>
              </div>
            </div>
            <div class="market__conn-foot">
              <WTag v-if="column.summary.mock" tone="v" plain>演示连接器（mock ERP）</WTag>
              <WTag :tone="healthMeta(column.summary.health).tone" plain>
                {{ healthMeta(column.summary.health).label }}
              </WTag>
              <WTag v-if="column.detailError" tone="r" plain :data-testid="`erp-market-detail-error-${column.summary.connectorId}`">
                能力矩阵读取失败 {{ column.detailError }}
              </WTag>
            </div>
          </div>
        </div>

        <!-- ② 18 项 × N 连接器能力矩阵 -->
        <section class="market__block" data-testid="erp-market-matrix">
          <header class="market__block-head">
            <b>能力矩阵对比（行 = 能力，列 = 连接器，格 = 三态）</b>
            <span class="dim">
              只读能力矩阵；三态语义：支持 = 入口可用 / 降级 = 入口保留 + 替代通道 / 不支持 = 入口隐藏
            </span>
          </header>
          <div class="market__matrix-wrap">
            <table class="market__matrix">
              <thead>
                <tr>
                  <th class="is-cap">能力</th>
                  <th v-for="column in columns" :key="column.summary.connectorId">
                    {{ column.summary.name }}
                  </th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="cap in capabilityRows"
                  :key="cap.code"
                  data-testid="erp-market-cap-row"
                  :data-code="cap.code"
                  :data-level="cap.level"
                >
                  <th class="is-cap">
                    <code>{{ cap.code }}</code>
                    <span>{{ cap.name }}</span>
                    <em>{{ cap.level }}</em>
                  </th>
                  <td
                    v-for="column in columns"
                    :key="column.summary.connectorId"
                    data-testid="erp-market-cell"
                    :data-connector="column.summary.connectorId"
                    :data-code="cap.code"
                    :data-state="stateOf.get(column.summary.connectorId)?.get(cap.code) ?? 'unknown'"
                    :title="evidenceOf.get(`${column.summary.connectorId}:${cap.code}`) ?? ''"
                    class="market__cell"
                    :class="`is-${stateOf.get(column.summary.connectorId)?.get(cap.code) ?? 'unknown'}`"
                  >
                    <template v-if="stateMeta(stateOf.get(column.summary.connectorId)?.get(cap.code))">
                      <i class="market__dot" />
                      {{ stateMeta(stateOf.get(column.summary.connectorId)?.get(cap.code))?.label }}
                    </template>
                    <template v-else>—</template>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <p class="market__hint">
            矩阵数值来自 <code>GET /connectors/{id}</code> 的 18 项 <code>CapabilityState</code>；
            声明与探测不一致时以探测结果为准，页面不维护第二套能力表。
          </p>
        </section>

        <!-- ③ SDK 元数据对比 -->
        <section class="market__block" data-testid="erp-market-sdk">
          <header class="market__block-head">
            <b>SDK 元数据对比</b>
            <span class="dim">sdkVersion / fieldCount / limits 由连接器声明（GET /connectors/{id}/sdk），运行时强制</span>
          </header>
          <div class="market__sdk">
            <div
              v-for="column in columns"
              :key="column.summary.connectorId"
              class="market__sdk-card"
              data-testid="erp-market-sdk-row"
              :data-connector="column.summary.connectorId"
            >
              <div class="market__sdk-head">
                <b>{{ column.summary.name }}</b>
                <code>{{ column.summary.connectorId }}</code>
              </div>
              <template v-if="column.sdk">
                <div class="market__sdk-grid">
                  <div>
                    <span>sdkVersion</span>
                    <b class="num" data-testid="erp-market-sdk-version">{{ column.sdk.sdkVersion }}</b>
                  </div>
                  <div>
                    <span>fieldCount</span>
                    <b class="num" data-testid="erp-market-sdk-field-count">{{ column.sdk.fieldCount }}</b>
                  </div>
                  <div>
                    <span>声明能力</span>
                    <b class="num" data-testid="erp-market-sdk-capability-count">
                      {{ column.sdk.capabilities.length }} / {{ column.summary.capabilityTotal }}
                    </b>
                  </div>
                  <div>
                    <span>timeoutMs</span>
                    <b class="num" data-testid="erp-market-sdk-timeout">{{ column.sdk.limits.timeoutMs }}</b>
                  </div>
                  <div>
                    <span>pageSizeMax</span>
                    <b class="num" data-testid="erp-market-sdk-page-size">{{ column.sdk.limits.pageSizeMax }}</b>
                  </div>
                  <div>
                    <span>rateLimitPerMin</span>
                    <b class="num" data-testid="erp-market-sdk-rate-limit">{{ column.sdk.limits.rateLimitPerMin }}</b>
                  </div>
                </div>
                <p class="market__sdk-caps">
                  <span>声明能力（≠ 已验证）</span>
                  <code
                    v-for="code in column.sdk.capabilities"
                    :key="code"
                    data-testid="erp-market-sdk-capability"
                    :data-connector="column.summary.connectorId"
                    :data-code="code"
                  >{{ code }}</code>
                  <em v-if="column.sdk.capabilities.length === 0">未声明任何能力（不造数）</em>
                </p>
              </template>
              <p v-else class="market__sdk-missing" data-testid="erp-market-sdk-missing">
                SDK 声明读取失败（{{ column.detailError || 'E-3001' }}）：不伪造 sdkVersion / 限额。
              </p>
            </div>
          </div>
        </section>

        <!-- ④ 选型建议（规则生成） -->
        <section class="market__block" data-testid="erp-market-advice">
          <header class="market__block-head">
            <b>选型建议（按 API 数值规则生成）</b>
            <span class="dim">
              规则：supported 计数 / degraded 能力码 / unsupported 能力码 / SDK limits 最保守值 / status·health 生命周期
            </span>
          </header>
          <ul class="market__advice">
            <li
              v-for="(item, index) in advice"
              :key="item.rule"
              :data-testid="`erp-market-advice-${index}`"
              :data-rule="item.rule"
              :class="{ 'is-key': item.rule === 'recommendation' }"
            >
              <span class="market__advice-idx">{{ String(index + 1).padStart(2, '0') }}</span>
              <p>{{ item.text }}</p>
            </li>
          </ul>
        </section>

        <WCard flat class="market__note">
          <p>
            数据口径：能力三态 / SDK 元数据 / 计数全部来自 Java BFF 真实接口（契约 v1.3.0），页面不做二次计算、
            不只展示「好看的连接器」。对比范围只含 <code>mock=true</code> 的已接入 mock 数据面连接器；
            <template v-if="declaredOnly">当前三态为<strong>注册声明</strong>（未探测），</template>
            其余未探测连接器不代表已验证。
          </p>
          <p class="dim" data-testid="erp-market-mock-note">
            {{ columns.map((column) => column.summary.name).join(' / ') }} 均为
            <strong>演示连接器（mock ERP 测试替身，:4200 / :4400）</strong>，不对接真实 ERP；SDK 声明
            <code>capabilities[]</code> 是「自称可实现」，是否可用以能力探测（<code>POST /probe</code>）为准。
          </p>
        </WCard>
      </div>
    </WCard>
  </div>
</template>

<style scoped>
.market {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
  min-width: 0;
}

.market__title {
  font-size: 12.5px;
  font-weight: var(--fw-semibold);
  color: var(--c-ink);
}

.market__sub {
  margin-left: 6px;
  font-size: var(--fs-2xs);
  color: var(--c-faint);
}

.market__probe {
  font-size: var(--fs-2xs);
  color: var(--c-faint);
}

.market__state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--sp-2);
  padding: var(--sp-3);
}

.market__body {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 10px 12px;
}

/* ---------- 连接器卡片 ---------- */
.market__cards {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--sp-2);
}

.market__conn {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 10px 12px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-xl);
  background: var(--c-card);
}

.market__conn-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
}

.market__conn-head b {
  font-size: var(--fs-sm);
  color: var(--c-ink);
}

.market__conn-vendor {
  font-size: 9px;
  color: var(--c-faint);
  line-height: 1.5;
}

.market__conn-nums {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 4px;
  padding-top: 6px;
  border-top: 1px dashed var(--c-line-2);
}

.market__conn-nums div {
  display: flex;
  flex-direction: column;
  gap: 0;
}

.market__conn-nums b {
  font-size: var(--fs-lg);
  line-height: 1.15;
}

.market__conn-nums span {
  font-size: 9px;
  color: var(--c-faint);
}

.market__conn-nums .is-supported b {
  color: var(--c-brand);
}

.market__conn-nums .is-degraded b {
  color: var(--c-amber);
}

.market__conn-nums .is-unsupported b {
  color: var(--c-muted);
}

.market__conn-foot {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
}

/* ---------- 区块 ---------- */
.market__block {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.market__block-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  flex-wrap: wrap;
}

.market__block-head b {
  font-size: var(--fs-xs);
  color: var(--c-ink-2);
}

.market__block-head span {
  font-size: var(--fs-2xs);
}

.market__matrix-wrap {
  overflow-x: auto;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-md);
}

.market__matrix {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--fs-2xs);
}

.market__matrix th,
.market__matrix td {
  padding: 5px 8px;
  border-bottom: 1px solid var(--c-line-2);
  text-align: left;
  white-space: nowrap;
}

.market__matrix thead th {
  position: sticky;
  top: 0;
  z-index: 1;
  background: var(--c-bg-2);
  font-size: 9.5px;
  font-weight: var(--fw-semibold);
  color: var(--c-muted);
}

.market__matrix th.is-cap {
  display: table-cell;
  min-width: 210px;
}

.market__matrix th.is-cap code {
  font-family: var(--font-mono);
  font-size: 9.5px;
  color: var(--c-ink-2);
}

.market__matrix th.is-cap span {
  margin-left: 6px;
  color: var(--c-muted);
}

.market__matrix th.is-cap em {
  margin-left: 6px;
  font-style: normal;
  font-size: 9px;
  color: var(--c-faint);
}

.market__matrix tbody tr:last-child th,
.market__matrix tbody tr:last-child td {
  border-bottom: none;
}

.market__cell {
  font-size: 9.5px;
  color: var(--c-muted);
}

.market__cell.is-supported {
  color: var(--c-brand);
}

.market__cell.is-degraded {
  color: var(--c-amber);
}

.market__cell.is-unsupported {
  color: var(--c-faint);
}

.market__dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  margin-right: 4px;
  border-radius: 50%;
  background: currentColor;
  vertical-align: 1px;
}

.market__hint {
  font-size: 9.5px;
  color: var(--c-faint);
  line-height: 1.6;
}

.market__hint code,
.market__note code {
  padding: 0 3px;
  border-radius: var(--rd-xs);
  background: var(--c-bg-2);
  font-family: var(--font-mono);
  font-size: 9px;
}

/* ---------- SDK ---------- */
.market__sdk {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--sp-2);
}

.market__sdk-card {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 9px 10px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-md);
  background: var(--c-card);
}

.market__sdk-head {
  display: flex;
  align-items: baseline;
  gap: 6px;
}

.market__sdk-head b {
  font-size: var(--fs-xs);
  color: var(--c-ink-2);
}

.market__sdk-head code {
  font-family: var(--font-mono);
  font-size: 9px;
  color: var(--c-faint);
}

.market__sdk-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 4px;
}

.market__sdk-grid div {
  display: flex;
  flex-direction: column;
  gap: 1px;
  padding: 5px 7px;
  border-radius: var(--rd-sm);
  background: var(--c-bg-2);
}

.market__sdk-grid span {
  font-size: 9px;
  color: var(--c-faint);
}

.market__sdk-grid b {
  font-size: 11px;
  color: var(--c-ink-2);
  font-weight: var(--fw-medium);
}

.market__sdk-caps {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
  font-size: 9px;
  color: var(--c-faint);
}

.market__sdk-caps code {
  padding: 1px 4px;
  border: 1px solid var(--c-line-2);
  border-radius: var(--rd-xs);
  font-family: var(--font-mono);
  font-size: 8.5px;
  color: var(--c-muted);
}

.market__sdk-missing {
  font-size: 9.5px;
  color: var(--c-amber);
  line-height: 1.6;
}

/* ---------- 选型建议 ---------- */
.market__advice {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.market__advice li {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 7px 10px;
  border: 1px solid var(--c-line-2);
  border-radius: var(--rd-md);
  background: var(--c-card);
}

.market__advice li.is-key {
  border-color: #cfe9e2;
  background: linear-gradient(95deg, #f2faf7, var(--c-card));
}

.market__advice-idx {
  flex: none;
  font-family: var(--font-mono);
  font-size: 9px;
  color: var(--c-brand);
  padding-top: 1px;
}

.market__advice p {
  font-size: var(--fs-2xs);
  color: var(--c-ink-2);
  line-height: 1.6;
}

.market__note p {
  font-size: var(--fs-2xs);
  color: var(--c-muted);
  line-height: 1.6;
}

.market__note p + p {
  margin-top: 4px;
}

@media (max-width: 1279px) {
  .market__cards,
  .market__sdk {
    grid-template-columns: 1fr;
  }
}
</style>
