<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { WButton, WCard, WLoading, WTable, WTag } from '@/components'
import PermissionDenied from '@/components/feedback/PermissionDenied.vue'
import { api, ApiError, errorNextStep, errorText, forbiddenDetailsOf } from '@/api'
import type { ForbiddenDetails, RagComponent, RagHealth } from '@/api'
import type { TableColumn } from '@/types'
import {
  healthComponentLabel,
  healthGeneratedAt,
  healthStatusLabel,
  healthStatusTone,
  healthSummary,
} from './rag-copy'

/**
 * RAG 链路健康面板（P6 W33 / task-81；契约 v1.4.0 `GET /api/v1/rag/health`，**仅 it/admin**）
 * ===========================================================================
 * 与知识页「RAG 健康」页签、Copilot 侧共用同一实现；页面只按能力显隐入口（`rag.health` 仅 it/admin）。
 *
 * 口径（AGENTS.md NEVER #11）：
 * - `status=degraded` 是**正常演示态**（如 reranker 关闭、LLM 无真实中转凭据），页面如实展示，
 *   不把 PoC 编排/本地向量库说成生产可用；
 * - 组件表逐项展示 `name/status/detail`（detail 由服务端给，只含模型名/计数/开关，不含密钥与内网地址）；
 * - 权限边界在服务端：非 it/admin 403 `E-2001`（`details.requiredCapability=rag.health`）→ 无权限态；
 * - 契约 `RagComponent.status` 枚举为 `ok|degraded|down`；服务端若回其它值（实测有 `off`），
 *   页面**原样容错展示**（`data-status` 保留原值），不在前端改写契约。
 */
const props = withDefaults(
  defineProps<{
    /** page = 知识页整页页签；embedded = 卡片内嵌 */
    variant?: 'page' | 'embedded'
    /** 是否在挂载时自动拉取（默认 true） */
    autoLoad?: boolean
  }>(),
  { variant: 'page', autoLoad: true },
)

const loading = ref(false)
const error = ref('')
const denied = ref<(ForbiddenDetails & { traceId?: string }) | null>(null)
const health = ref<RagHealth | null>(null)

const isEmbedded = computed(() => props.variant === 'embedded')
const summary = computed(() => healthSummary(health.value))
const generatedAt = computed(() => healthGeneratedAt(health.value))
const statusLabel = computed(() => healthStatusLabel(health.value?.status))
const statusTone = computed(() => healthStatusTone(health.value?.status))
const llm = computed(() => health.value?.llm ?? null)
const embedding = computed(() => health.value?.embedding ?? null)
const vectorStore = computed(() => health.value?.vectorStore ?? null)

const columns: TableColumn[] = [
  { key: 'component', title: '组件', width: '150px' },
  { key: 'status', title: '状态', width: '90px' },
  { key: 'detail', title: '说明（服务端 detail，仅模型名/计数/开关）' },
]

interface ComponentRow extends Record<string, unknown> {
  name: string
  component: string
  status: string
  statusLabel: string
  statusTone: string
  detail: string
}

const rows = computed<ComponentRow[]>(() =>
  (health.value?.components ?? []).map((item: RagComponent) => ({
    name: item.name,
    component: healthComponentLabel(item.name),
    status: item.status,
    statusLabel: healthStatusLabel(item.status),
    statusTone: healthStatusTone(item.status),
    detail: item.detail,
  })),
)

function rowOf(row: unknown): ComponentRow {
  return row as ComponentRow
}

function componentOf(row: unknown): string {
  return rowOf(row).name
}

function statusToneOf(row: unknown): 'g' | 'b' | 'a' | 'r' | 'v' | 'n' {
  return rowOf(row).statusTone as 'g' | 'b' | 'a' | 'r' | 'v' | 'n'
}

function apiMessage(caught: unknown): string {
  if (caught instanceof ApiError) return `${errorText(caught.code)}；${errorNextStep(caught.code)}`
  return '请求失败：请稍后重试'
}

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  denied.value = null
  try {
    const { data } = await api.rag.health()
    health.value = data
  } catch (caught) {
    health.value = null
    const forbidden = forbiddenDetailsOf(caught)
    if (forbidden) {
      denied.value = { ...forbidden, traceId: caught instanceof ApiError ? caught.traceId : '' }
      return
    }
    error.value = apiMessage(caught)
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  if (props.autoLoad) void load()
})
</script>

<template>
  <div
    class="rag-health"
    :class="{ 'is-embedded': isEmbedded }"
    data-testid="rag-health-panel"
    :data-variant="isEmbedded ? 'embedded' : 'page'"
  >
    <WCard
      title="RAG 链路健康"
      subtitle="GET /api/v1/rag/health（能力 rag.health，仅 it/admin）：编排层 / LLM / Embedding / 向量库 / 重排器"
      data-testid="rag-health-card"
    >
      <template #extra>
        <WTag v-if="health" :tone="statusTone" data-testid="rag-health-status" :data-status="health.status">
          {{ statusLabel }}
        </WTag>
        <WTag v-if="health" :tone="health.degraded ? 'a' : 'g'" plain data-testid="rag-health-degraded" :data-degraded="String(health.degraded)">
          {{ health.degraded ? 'degraded=true（存在降级）' : 'degraded=false（无降级）' }}
        </WTag>
        <WButton size="sm" variant="ghost" icon="refresh" :loading="loading" data-testid="rag-health-refresh" @click="load">
          重新探测
        </WButton>
      </template>

      <p v-if="loading" class="rag-health__load" data-testid="rag-health-loading">
        <WLoading label="正在探测 RAG 编排层与模型组件…" />
      </p>

      <PermissionDenied
        v-else-if="denied"
        compact
        data-testid="rag-health-denied"
        :required-capability="denied.requiredCapability"
        :required-role="denied.role"
        :trace-id="denied.traceId"
        detail="RAG 健康探测需要 rag.health 能力（仅 it/admin）；当前演示角色不具备，页面不展示组件状态。"
      />

      <p v-else-if="error" class="rag-health__note rag-health__note--warn" data-testid="rag-health-error">
        <WTag tone="r" plain>健康探测失败</WTag>{{ error }}
        <WButton size="sm" variant="ghost" @click="load">重试</WButton>
      </p>

      <template v-else-if="health">
        <p class="rag-health__summary" data-testid="rag-health-summary" :data-status="health.status">
          {{ summary }}
        </p>

        <div class="rag-health__meta" data-testid="rag-health-meta">
          <div :class="{ 'is-warn': health.degraded }">
            <span>LLM（生成）</span>
            <b data-testid="rag-health-llm">
              {{ llm ? `${llm.provider} / ${llm.model} · mode=${llm.mode}` : '—' }}
            </b>
          </div>
          <div>
            <span>Embedding（向量化）</span>
            <b data-testid="rag-health-embedding">
              {{ embedding ? `${embedding.model} · dim=${embedding.dim}` : '—' }}
            </b>
          </div>
          <div>
            <span>向量库</span>
            <b data-testid="rag-health-vector-store">
              {{ vectorStore ? `${vectorStore.backend} · ${vectorStore.count} 片段` : '—' }}
            </b>
          </div>
          <div>
            <span>重排（reranked）</span>
            <b data-testid="rag-health-reranked">{{ health.reranked ? '已开启' : '已关闭（原序透传）' }}</b>
          </div>
          <div>
            <span>探测时间</span>
            <b data-testid="rag-health-generated-at">{{ generatedAt }}</b>
          </div>
          <div>
            <span>组件数</span>
            <b>{{ rows.length }}</b>
          </div>
        </div>

        <WTable
          :columns="columns"
          :rows="rows"
          row-key="name"
          dense
          data-testid="rag-health-table"
          empty-text="服务端未返回组件列表"
        >
          <template #component="{ row }">
            <span class="rag-health__comp" :data-testid="`rag-health-comp-${componentOf(row)}`">
              <b>{{ rowOf(row).component }}</b>
              <code>{{ rowOf(row).name }}</code>
            </span>
          </template>
          <template #status="{ row }">
            <WTag
              :tone="statusToneOf(row)"
              plain
              :data-testid="`rag-health-comp-status-${componentOf(row)}`"
              :data-status="rowOf(row).status"
            >
              {{ rowOf(row).statusLabel }}
            </WTag>
          </template>
          <template #detail="{ row }">
            <span class="rag-health__detail">{{ rowOf(row).detail }}</span>
          </template>
        </WTable>

        <p class="rag-health__hint" data-testid="rag-health-hint">
          演示口径：RAG 编排在 <code>services/ai-orchestrator</code>（PoC），向量库为本地 Chroma，
          LLM 无真实中转凭据时 <code>mode=retrieval_only|mock</code>、<code>degraded=true</code> 属正常状态；
          不得把 PoC 链路说成生产可用。健康端点只读、不落审计。
        </p>
      </template>
    </WCard>
  </div>
</template>

<style scoped>
.rag-health {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
  min-width: 0;
}

.rag-health__load {
  padding: 12px 0;
}

.rag-health__summary {
  margin-bottom: 10px;
  padding: 8px 10px;
  border-left: 2px solid var(--c-brand);
  border-radius: 0 var(--rd-sm) var(--rd-sm) 0;
  background: var(--c-brand-soft);
  color: #0a725f;
  font-size: var(--fs-xs);
  line-height: var(--lh-base);
}

.rag-health__summary[data-status='degraded'] {
  border-left-color: var(--c-amber);
  background: var(--c-amber-soft);
  color: #9a4b05;
}

.rag-health__summary[data-status='down'] {
  border-left-color: var(--c-red);
  background: var(--c-red-soft);
  color: #b42318;
}

.rag-health__meta {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  margin-bottom: 10px;
  padding: 9px 10px;
  border: 1px solid var(--c-line-2);
  border-radius: var(--rd-md);
  background: var(--c-bg-2);
}

.rag-health__meta span {
  display: block;
  font-size: 9.5px;
  color: var(--c-faint);
}

.rag-health__meta b {
  display: block;
  margin-top: 1px;
  font-size: 10.5px;
  font-weight: 500;
  color: var(--c-ink-2);
  word-break: break-all;
}

.rag-health__meta .is-warn b {
  color: #9a4b05;
}

.rag-health__comp {
  display: flex;
  align-items: baseline;
  gap: 6px;
}

.rag-health__comp b {
  font-weight: var(--fw-semibold);
  color: var(--c-ink-2);
}

.rag-health__comp code,
.rag-health__hint code {
  padding: 0 3px;
  border-radius: var(--rd-xs);
  background: var(--c-bg-2);
  font-family: var(--font-mono);
  font-size: 9.5px;
  color: var(--c-faint);
}

.rag-health__detail {
  font-size: var(--fs-2xs);
  color: var(--c-muted);
  word-break: break-word;
}

.rag-health__note {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  font-size: var(--fs-2xs);
  color: var(--c-muted);
  line-height: var(--lh-base);
}

.rag-health__note--warn {
  padding: 7px 10px;
  border-radius: var(--rd-sm);
  background: var(--c-amber-soft);
  color: #9a4b05;
}

.rag-health__hint {
  margin-top: 10px;
  font-size: 9.5px;
  color: var(--c-faint);
  line-height: 1.6;
}

@media (max-width: 1279px) {
  .rag-health__meta {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
