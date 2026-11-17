<script setup lang="ts">
import { ref } from 'vue'
import { WButton, WCard, WEmpty, WLoading, WTag } from '@/components'
import { api, ApiError, errorNextStep, errorText } from '@/api'
import type { DegradeLedgerItem, DegradedFallback } from '@/api'
import { DEGRADED_FALLBACK_LABEL, formatDateTime } from '../erp-data'

/**
 * 降级台账（P2 W7，契约 v0.3.2 §11.8.4）
 * ---------------------------------------------------------------------------
 * `GET /api/v1/degrade/ledger?connectorId&from&to&page&pageSize`：
 * 一条台账 =「连接器 × 能力 × 替代路径」命中聚合，列 connector / 能力 / reason / fallback / count /
 * firstHitAt / lastHitAt；`from`/`to` 按 lastHitAt 闭区间过滤。
 *
 * 口径：台账只记录运行期真实降级命中（缓存回落/转人工），无记录返回空列表——不造数、不塞演示行。
 */
// 连接器选项只用于筛选下拉（数据来自父级 GET /connectors）
defineProps<{ connectors: { connectorId: string; name: string }[] }>()

interface LedgerRow {
  id: string
  connectorId: string
  capability: string
  reason: string
  fallback: DegradedFallback
  count: number
  firstHitAt: string
  lastHitAt: string
}

const rows = ref<LedgerRow[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const loading = ref(false)
const error = ref<{ code: string; message: string; next: string } | null>(null)

const filterConnector = ref('')
const filterFrom = ref('')
const filterTo = ref('')

function fallbackLabel(fallback: DegradedFallback): string {
  return DEGRADED_FALLBACK_LABEL[fallback] ?? fallback
}

function fallbackTone(fallback: DegradedFallback): 'g' | 'a' | 'r' | 'n' {
  if (fallback === 'none') return 'r'
  if (fallback === 'human' || fallback === 'partial') return 'a'
  if (fallback === 'cache' || fallback === 'mock' || fallback === 'alt_connector') return 'n'
  return 'n'
}

function toRows(items: DegradeLedgerItem[]): LedgerRow[] {
  return items.map((item) => ({
    id: item.id,
    connectorId: item.connectorId,
    capability: item.capability,
    reason: item.reason,
    fallback: item.fallback,
    count: item.count,
    firstHitAt: item.firstHitAt,
    lastHitAt: item.lastHitAt,
  }))
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const { data } = await api.degrade.ledger({
      connectorId: filterConnector.value || undefined,
      from: filterFrom.value ? `${filterFrom.value}T00:00:00+08:00` : undefined,
      to: filterTo.value ? `${filterTo.value}T23:59:59+08:00` : undefined,
      page: page.value,
      pageSize: pageSize.value,
    })
    rows.value = toRows(data.items)
    total.value = data.total
  } catch (caught) {
    const apiError = caught instanceof ApiError ? caught : null
    const code = apiError?.code ?? 'E-9001'
    rows.value = []
    total.value = 0
    error.value = { code, message: errorText(code), next: errorNextStep(code) }
  } finally {
    loading.value = false
  }
}

function applyFilters(): void {
  page.value = 1
  void load()
}

function resetFilters(): void {
  filterConnector.value = ''
  filterFrom.value = ''
  filterTo.value = ''
  page.value = 1
  void load()
}

function goPage(next: number): void {
  const maxPage = Math.max(1, Math.ceil(total.value / pageSize.value))
  page.value = Math.min(Math.max(1, next), maxPage)
  void load()
}

void load()
</script>

<template>
  <section class="erp-ledger" data-testid="erp-degrade-panel">
    <WCard :padded="false">
      <template #head>
        <span class="erp__card-title">降级台账</span>
        <span class="erp__card-sub">
          连接器 × 能力 × 替代路径 命中聚合 · 按 lastHitAt 倒序 · 仅记录运行期真实降级（不造数）
        </span>
      </template>
      <template #extra>
        <WButton size="sm" icon="refresh" :loading="loading" data-testid="erp-degrade-refresh" @click="load">刷新</WButton>
      </template>

      <div class="erp-ledger__filters">
        <label class="erp-ledger__field">
          <span>连接器</span>
          <select v-model="filterConnector" data-testid="erp-degrade-filter-connector">
            <option value="">全部连接器</option>
            <option v-for="item in connectors" :key="item.connectorId" :value="item.connectorId">
              {{ item.name }}（{{ item.connectorId }}）
            </option>
          </select>
        </label>
        <label class="erp-ledger__field">
          <span>命中起（含）</span>
          <input v-model="filterFrom" type="date" data-testid="erp-degrade-filter-from" />
        </label>
        <label class="erp-ledger__field">
          <span>命中止（含）</span>
          <input v-model="filterTo" type="date" data-testid="erp-degrade-filter-to" />
        </label>
        <WButton size="sm" variant="primary" icon="search" data-testid="erp-degrade-query" @click="applyFilters">查询</WButton>
        <WButton size="sm" variant="ghost" @click="resetFilters">重置</WButton>
        <span class="erp-ledger__stat dim" data-testid="erp-degrade-total">共 {{ total }} 条命中聚合</span>
      </div>

      <WLoading v-if="loading" center label="正在读取降级台账…" />

      <div v-else-if="error" class="erp__state" data-testid="erp-degrade-error">
        <WEmpty
          icon="info"
          title="降级台账读取失败"
          :description="`${error.message}（错误码 ${error.code}）。${error.next}`"
        />
        <WButton size="sm" icon="refresh" @click="load">重试</WButton>
      </div>

      <WEmpty
        v-else-if="rows.length === 0"
        data-testid="erp-degrade-empty"
        icon="info"
        title="当前筛选没有降级命中"
        description="降级台账只记录运行期真实降级（上游超时走缓存/转人工等）；没有命中就不展示记录，不造数。可调整连接器或时间范围后重查。"
      />

      <table v-else class="erp-ledger__table" data-testid="erp-degrade-ledger">
        <thead>
          <tr>
            <th scope="col" style="width: 150px">连接器</th>
            <th scope="col" style="width: 150px">能力</th>
            <th scope="col">降级原因</th>
            <th scope="col" style="width: 96px">替代路径</th>
            <th scope="col" style="width: 60px">命中次数</th>
            <th scope="col" style="width: 130px">首次命中</th>
            <th scope="col" style="width: 130px">最近命中</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in rows" :key="row.id" data-testid="erp-degrade-row" :data-connector="row.connectorId">
            <td><span class="erp-ledger__conn">{{ row.connectorId }}</span></td>
            <td><code>{{ row.capability }}</code></td>
            <td><span class="erp__evidence">{{ row.reason || '—' }}</span></td>
            <td><WTag :tone="fallbackTone(row.fallback)">{{ fallbackLabel(row.fallback) }}</WTag></td>
            <td><b class="num">{{ row.count }}</b></td>
            <td><span class="dim">{{ formatDateTime(row.firstHitAt) }}</span></td>
            <td><span class="dim">{{ formatDateTime(row.lastHitAt) }}</span></td>
          </tr>
        </tbody>
      </table>

      <template #footer>
        <div class="erp-ledger__foot">
          <span class="dim">第 {{ page }} 页 · 每页 {{ pageSize }} 条</span>
          <div class="erp-ledger__pager">
            <WButton size="sm" variant="ghost" :disabled="page <= 1 || loading" data-testid="erp-degrade-prev" @click="goPage(page - 1)">
              上一页
            </WButton>
            <WButton
              size="sm"
              variant="ghost"
              :disabled="page * pageSize >= total || loading"
              data-testid="erp-degrade-next"
              @click="goPage(page + 1)"
            >
              下一页
            </WButton>
          </div>
        </div>
      </template>
    </WCard>
  </section>
</template>

<style scoped>
.erp-ledger {
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

.erp__evidence {
  font-size: var(--fs-2xs);
  color: var(--c-muted);
  line-height: var(--lh-base);
}

.erp-ledger__filters {
  display: flex;
  align-items: flex-end;
  gap: var(--sp-2);
  flex-wrap: wrap;
  padding: 10px 12px;
  border-bottom: 1px solid var(--c-line-2);
}

.erp-ledger__field {
  display: flex;
  flex-direction: column;
  gap: 3px;
  font-size: 9px;
  color: var(--c-faint);
}

.erp-ledger__field select,
.erp-ledger__field input {
  height: 28px;
  min-width: 132px;
  padding: 0 8px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-sm);
  background: var(--c-card);
  font-size: var(--fs-2xs);
  color: var(--c-ink);
}

.erp-ledger__field select:focus,
.erp-ledger__field input:focus {
  outline: none;
  border-color: var(--c-brand);
  box-shadow: var(--sh-ring);
}

.erp-ledger__stat {
  margin-left: auto;
  font-size: var(--fs-2xs);
}

.erp-ledger__table {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--fs-sm);
}

.erp-ledger__table th {
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

.erp-ledger__table td {
  padding: 7px 12px;
  border-bottom: 1px solid #f0f3f5;
  color: var(--c-ink-2);
  vertical-align: middle;
}

.erp-ledger__table tbody tr:last-child td {
  border-bottom: 0;
}

.erp-ledger__table tbody tr:hover td {
  background: var(--c-bg-2);
}

.erp-ledger__table code {
  font-family: var(--font-mono);
  font-size: 9.5px;
  color: var(--c-ink-2);
}

.erp-ledger__conn {
  font-family: var(--font-mono);
  font-size: 9.5px;
  color: var(--c-muted);
}

.erp-ledger__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
}

.erp-ledger__pager {
  display: flex;
  gap: var(--sp-2);
}
</style>
