<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { WButton, WCard, WEmpty, WLoading, WTag, useToast } from '@/components'
import { api, ApiError, errorNextStep, errorText } from '@/api'
import type { ConnectorSdk } from '@/api'

/**
 * 连接器 SDK 声明卡（P5 W19，契约 v1.3.0 `GET /api/v1/connectors/{connectorId}/sdk`）
 * ===========================================================================
 * 数据来源（真实 API，不造数）：`ConnectorSdk{sdkVersion, connectorId, capabilities[], fieldCount,
 * limits{timeoutMs, pageSizeMax, rateLimitPerMin}}`。
 *
 * 口径（架构 §24.4）：
 * - **声明先于探测、探测覆盖声明**：`capabilities[]` 是连接器「声明可实现」的标准能力（⊆ 18 项
 *   `CapabilityCode`），是否真的可用以 `POST /probe` 结果为准 → 卡片明确标注「声明 ≠ 已验证」；
 * - 第三方连接器按同一 SDK 注册（`sdkVersion` 语义化版本）；`fieldCount` 为映射字段数；
 * - `limits` 由连接器运行时强制（超时/分页上限/每分钟限流），页面只读展示；
 * - 未注册连接器 → 404 `E-3001`，展示错误码人话，不伪造元数据。
 */
const props = defineProps<{
  connectorId: string
  /** 能力码 → 中文名（来自同连接器的能力矩阵 `CapabilityState.name`；缺失时只展示能力码） */
  capabilityNames?: Record<string, string>
  /** 是否 mock 连接器（仅为标注，不影响数据） */
  mock?: boolean
}>()

const toast = useToast()
const sdk = ref<ConnectorSdk | null>(null)
const loading = ref(false)
const error = ref<{ code: string; message: string; next: string } | null>(null)

const declared = computed(() => sdk.value?.capabilities ?? [])
const limits = computed(() => sdk.value?.limits ?? null)

function nameOf(code: string): string {
  return props.capabilityNames?.[code] ?? code
}

async function load(): Promise<void> {
  if (!props.connectorId) return
  const startedAt = Date.now()
  loading.value = true
  error.value = null
  try {
    const { data } = await api.connector.sdk(props.connectorId)
    sdk.value = data
    // 最小 loading 展示 320ms，避免本地 mock 过快导致「点击无可见反馈」（W24 死按钮审计口径）
    await new Promise((resolve) => setTimeout(resolve, Math.max(0, 320 - (Date.now() - startedAt))))
    toast.success(`已刷新 SDK 声明：${data.connectorId} · sdkVersion ${data.sdkVersion}（${data.capabilities.length} 项声明能力）`)
  } catch (caught) {
    sdk.value = null
    const apiError = caught instanceof ApiError ? caught : null
    const code = apiError?.code ?? 'E-9001'
    error.value = { code, message: errorText(code), next: errorNextStep(code) }
    toast.error(`SDK 声明读取失败：${errorText(code)}（${code}）`)
  } finally {
    loading.value = false
  }
}

watch(() => props.connectorId, load, { immediate: true })
</script>

<template>
  <WCard :padded="false" data-testid="erp-sdk-card" :data-connector="connectorId">
    <template #head>
      <span class="erp__card-title">连接器 SDK 声明</span>
      <span class="erp__card-sub">GET /connectors/{{ connectorId }}/sdk · 声明先于探测、探测覆盖声明</span>
    </template>
    <template #extra>
      <WTag v-if="sdk" tone="b" plain data-testid="erp-sdk-version">sdkVersion {{ sdk.sdkVersion }}</WTag>
      <WTag v-if="mock" tone="v" plain>演示连接器（mock ERP）</WTag>
      <WButton size="sm" icon="refresh" :loading="loading" data-testid="erp-sdk-refresh" @click="load">刷新</WButton>
    </template>

    <WLoading v-if="loading && !sdk" center label="正在读取连接器 SDK 声明…" />

    <div v-else-if="error" class="erp__state" data-testid="erp-sdk-error">
      <WEmpty
        icon="info"
        title="SDK 声明读取失败"
        :description="`${error.message}（错误码 ${error.code}）。${error.next}`"
      />
      <WButton size="sm" icon="refresh" @click="load">重试</WButton>
    </div>

    <WEmpty
      v-else-if="!sdk"
      compact
      icon="plug"
      title="该连接器未注册 SDK 声明"
      description="未注册连接器返回 404 E-3001；页面不伪造 sdkVersion / 能力声明。"
    />

    <div v-else class="erp-sdk__body">
      <div class="erp-sdk__stats" data-testid="erp-sdk-meta">
        <div>
          <span>连接器</span>
          <b data-testid="erp-sdk-connector"><code>{{ sdk.connectorId }}</code></b>
        </div>
        <div>
          <span>SDK 版本</span>
          <b class="num" data-testid="erp-sdk-version-value">{{ sdk.sdkVersion }}</b>
        </div>
        <div>
          <span>声明能力</span>
          <b class="num" data-testid="erp-sdk-capability-count">{{ declared.length }} / 18</b>
        </div>
        <div>
          <span>映射字段数</span>
          <b class="num" data-testid="erp-sdk-field-count">{{ sdk.fieldCount }}</b>
        </div>
        <div v-if="limits">
          <span>超时 timeoutMs</span>
          <b class="num" data-testid="erp-sdk-timeout">{{ limits.timeoutMs }}</b>
        </div>
        <div v-if="limits">
          <span>分页上限 pageSizeMax</span>
          <b class="num" data-testid="erp-sdk-page-size">{{ limits.pageSizeMax }}</b>
        </div>
        <div v-if="limits">
          <span>限流（次/分钟）</span>
          <b class="num" data-testid="erp-sdk-rate-limit">{{ limits.rateLimitPerMin }}</b>
        </div>
      </div>

      <div class="erp-sdk__caps">
        <b>声明能力（{{ declared.length }} 项，是否可用以能力探测为准）</b>
        <div class="erp-sdk__chips" data-testid="erp-sdk-capabilities">
          <span
            v-for="code in declared"
            :key="code"
            class="erp-sdk__chip"
            data-testid="erp-sdk-capability"
            :data-code="code"
          >
            <code>{{ code }}</code>
            <em>{{ nameOf(code) }}</em>
          </span>
        </div>
        <p v-if="declared.length === 0" class="dim">SDK 未声明任何能力（不造数）：该连接器接入后需重新注册声明。</p>
      </div>

      <p class="erp-sdk__note" data-testid="erp-sdk-note">
        SDK 声明是连接器侧「自称可实现」的能力集合，<b>不等于已验证</b>：以
        <code>POST /connectors/{{ sdk.connectorId }}/probe</code> 的 18 项探测结果为准；声明与探测不一致时按探测结果降级。
        运行限制（超时 / 分页 / 限流）由连接器运行时强制，页面只读展示。
        <template v-if="mock">本连接器为 mock ERP 测试替身（非真实 ERP）。</template>
      </p>
    </div>
  </WCard>
</template>

<style scoped>
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

.erp-sdk__body {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 10px 12px;
}

.erp-sdk__stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 6px;
}

.erp-sdk__stats div {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 7px 9px;
  border-radius: var(--rd-sm);
  background: var(--c-bg-2);
}

.erp-sdk__stats span {
  font-size: 9px;
  color: var(--c-faint);
}

.erp-sdk__stats b {
  font-size: var(--fs-sm);
  color: var(--c-ink-2);
  font-weight: var(--fw-medium);
}

.erp-sdk__stats code {
  font-family: var(--font-mono);
  font-size: 9.5px;
}

.erp-sdk__caps {
  display: flex;
  flex-direction: column;
  gap: 5px;
  font-size: var(--fs-2xs);
  color: var(--c-muted);
}

.erp-sdk__caps b {
  color: var(--c-ink-2);
}

.erp-sdk__chips {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.erp-sdk__chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 6px;
  border: 1px solid var(--c-line-2);
  border-radius: var(--rd-sm);
  background: var(--c-card);
}

.erp-sdk__chip code {
  font-family: var(--font-mono);
  font-size: 9px;
  color: var(--c-ink-2);
}

.erp-sdk__chip em {
  font-style: normal;
  font-size: 9px;
  color: var(--c-faint);
}

.erp-sdk__note {
  font-size: 9.5px;
  color: var(--c-faint);
  line-height: 1.6;
}

.erp-sdk__note code {
  padding: 0 3px;
  border-radius: var(--rd-xs);
  background: var(--c-bg-2);
  font-family: var(--font-mono);
  font-size: 9px;
}

@media (max-width: 1279px) {
  .erp-sdk__stats {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
