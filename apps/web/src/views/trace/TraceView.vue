<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { WButton, WCard, WEmpty, WIcon, WLoading, WPageHeader, WTag, useToast } from '@/components'
import PermissionDenied from '@/components/feedback/PermissionDenied.vue'
import { SCREENS } from '@/app/screens'
import { ROLE_LABELS } from '@/app/roles'
import {
  api,
  ApiError,
  errorNextStep,
  errorText,
  forbiddenDetailsOf,
  formatIsoMinute,
  formatIsoTime,
} from '@/api'
import type { ActorRole, TraceDetail, TraceEvidence, TraceSearchItem } from '@/api'
import { capabilityLabel } from '@/auth/capabilities'
import { useAuthStore } from '@/stores/auth'
import StateDemo from '@/content/StateDemo.vue'
import { downloadBlob } from '@/views/copilot/shared'

/**
 * 10 全链路回溯（P4 W17，契约 v1.2.0 §13 / P4-020）
 * ===========================================================================
 * 数据来源（全部来自 Java BFF，页面不编造）：
 * - `GET /api/v1/trace/search?actorId&action&from&to&page&pageSize`：按 `lastAt` 降序分页；
 * - `GET /api/v1/trace/{traceId}`：时间线（entries 按 `at` 升序）；
 * - `GET /api/v1/trace/{traceId}/evidence?format=json|csv`：证据包（JSON 信封 / **CSV 文件流例外**）。
 *
 * 口径：
 * - 权限：`trace.read`（检索/详情）与 `trace.export`（证据包）**仅 supervisor/it/admin**；
 *   403 → PermissionDenied（不展示未授权时间线/证据），/trace 路由对其它角色由可见性矩阵拦截；
 * - 脱敏：时间线字段已由服务端脱敏（`maskedFields` 列出已脱敏字段），页面只标注、不还原明文；
 * - 导出是敏感动作（审计 `trace.export`）：JSON 由信封序列化下载，CSV 用服务端
 *   `Content-Disposition` 文件名，均为真实浏览器下载，不伪造成功。
 */
const screen = SCREENS.trace
const toast = useToast()
const auth = useAuthStore()
const router = useRouter()

const canExport = computed(() => auth.hasCapability('trace.export'))
const currentRoleLabel = computed(() => (auth.role ? ROLE_LABELS[auth.role] : '未登录'))
/** 可切换演示账号：具备 trace.read 的演示角色（supervisor/it/admin） */
const SWITCH_ROLES: readonly ActorRole[] = ['supervisor', 'it', 'admin']

/* ------------------------------ 检索 ------------------------------ */
interface DeniedInfo {
  requiredCapability: string
  role: string
}

const traceId = ref('')
const actorId = ref('')
const action = ref('')
const from = ref('')
const to = ref('')
const searching = ref(false)
const searchError = ref('')
const searchDenied = ref<DeniedInfo | null>(null)
const results = ref<TraceSearchItem[]>([])
const total = ref(0)
const searched = ref(false)

const resultRows = computed(() =>
  results.value.map((item) => ({
    ...item,
    periodText: `${formatIsoMinute(item.firstAt) || item.firstAt} → ${formatIsoMinute(item.lastAt) || item.lastAt}`,
  })),
)

function apiMessage(error: unknown): string {
  if (error instanceof ApiError) {
    const code = String(error.code)
    return `${errorText(code)}；${errorNextStep(code)}`
  }
  return '请求失败：请稍后重试或转人工'
}

function deniedOf(error: unknown, fallback: string): DeniedInfo | null {
  const forbidden = forbiddenDetailsOf(error)
  if (!forbidden) return null
  return { requiredCapability: forbidden.requiredCapability || fallback, role: forbidden.role }
}

/** 条件检索（也兼容把 traceId 填到输入框直接查详情：两处按钮语义分开） */
async function search(): Promise<void> {
  searching.value = true
  searchError.value = ''
  searchDenied.value = null
  try {
    const { data } = await api.trace.search({
      actorId: actorId.value.trim() || undefined,
      action: action.value.trim() || undefined,
      from: from.value ? new Date(`${from.value}T00:00:00`).toISOString() : undefined,
      to: to.value ? new Date(`${to.value}T23:59:59`).toISOString() : undefined,
      pageSize: 10,
    })
    results.value = data.items
    total.value = data.total
    searched.value = true
    if (!data.items.length) toast.info('未命中链路：可放宽 actorId/动作/时间条件后重试（服务端按 lastAt 降序分页）')
    else toast.success(`命中 ${data.total} 条链路（当前页 ${data.items.length} 条），点击行可查看时间线`)
  } catch (error) {
    results.value = []
    total.value = 0
    searched.value = true
    const denied = deniedOf(error, 'trace.read')
    if (denied) searchDenied.value = denied
    else searchError.value = apiMessage(error)
  } finally {
    searching.value = false
  }
}

/* ------------------------------ 时间线详情 ------------------------------ */
const detail = ref<TraceDetail | null>(null)
const detailLoading = ref(false)
const detailError = ref('')
const detailDenied = ref<DeniedInfo | null>(null)
const activeTraceId = ref('')

async function loadDetail(id: string): Promise<void> {
  const clean = id.trim()
  if (!clean) {
    toast.warning('请先输入 traceId（或先检索后点击结果行）')
    return
  }
  activeTraceId.value = clean
  detailLoading.value = true
  detailError.value = ''
  detailDenied.value = null
  evidence.value = null
  try {
    const { data } = await api.trace.get(clean)
    detail.value = data
  } catch (error) {
    detail.value = null
    const denied = deniedOf(error, 'trace.read')
    if (denied) {
      detailDenied.value = denied
      return
    }
    const code = error instanceof ApiError ? String(error.code) : 'E-9001'
    detailError.value =
      error instanceof ApiError && error.httpStatus === 404
        ? '链路不存在（404 E-3001）：请确认 traceId 是否正确，或先按条件检索'
        : `链路详情暂不可用（${errorText(code)}）`
  } finally {
    detailLoading.value = false
  }
}

function searchById(): void {
  void loadDetail(traceId.value)
}

function selectResult(row: TraceSearchItem): void {
  traceId.value = row.traceId
  void loadDetail(row.traceId)
}

const detailRows = computed(() => detail.value?.entries ?? [])

/** 角色中文（`ActorRole` 来自契约，不另写枚举） */
function roleText(role: string): string {
  return (ROLE_LABELS as Record<string, string>)[role] ?? role
}

/** 结果语气：success 绿 / denied 琥珀 / failed 红 */
function resultTone(result: string): 'g' | 'a' | 'r' {
  if (result === 'success') return 'g'
  if (result === 'denied') return 'a'
  return 'r'
}

const resultText = (result: string): string =>
  result === 'success' ? '成功' : result === 'denied' ? '被拒绝' : result === 'failed' ? '失败' : result

/* ------------------------------ 证据包导出 ------------------------------ */
const evidence = ref<TraceEvidence | null>(null)
const exportingJson = ref(false)
const exportingCsv = ref(false)

const evidenceMeta = computed(() => {
  const data = evidence.value
  if (!data) return []
  return [
    { label: '链路 ID', value: data.traceId },
    { label: '生成时间', value: formatIsoMinute(data.generatedAt) || data.generatedAt },
    { label: '校验和', value: data.checksum || '—' },
    { label: '已脱敏字段', value: data.maskedFields.length ? data.maskedFields.join(' / ') : '（无）' },
    { label: '演示标记', value: data.mock ? '含演示数据（P4 审计源）' : '实时审计数据' },
  ]
})

/** 证据包 JSON：读信封 → 页面序列化为 .json 下载（文件名 trace-<id>-evidence.json） */
async function exportJson(): Promise<void> {
  // 目标链路：已选中的时间线 > 输入框 traceId > 检索结果首条（避免「有结果但未点行」时导出无响应）
  const id = activeTraceId.value || traceId.value.trim() || results.value[0]?.traceId || ''
  if (!id) {
    toast.warning('请先选择一条链路（输入 traceId 或检索后点行）')
    return
  }
  if (!canExport.value) {
    toast.warning(`当前角色「${currentRoleLabel.value}」无证据包导出能力（trace.export）`)
    return
  }
  exportingJson.value = true
  try {
    const { data } = await api.trace.evidenceJson(id)
    evidence.value = data
    const filename = `trace-${id}-evidence.json`
    const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json;charset=utf-8' })
    downloadBlob(blob, filename)
    toast.success(`已下载证据包 JSON：${filename}（${(blob.size / 1024).toFixed(1)} KB，checksum ${data.checksum}）`)
  } catch (error) {
    const denied = deniedOf(error, 'trace.export')
    if (denied) {
      detailDenied.value = denied
      return
    }
    toast.error(`证据包 JSON 导出失败：${apiMessage(error)}`)
  } finally {
    exportingJson.value = false
  }
}

/** 证据包 CSV：文件流例外，文件名取服务端 Content-Disposition（回落 trace-<id>.csv） */
async function exportCsv(): Promise<void> {
  const id = activeTraceId.value || traceId.value.trim() || results.value[0]?.traceId || ''
  if (!id) {
    toast.warning('请先选择一条链路（输入 traceId 或检索后点行）')
    return
  }
  if (!canExport.value) {
    toast.warning(`当前角色「${currentRoleLabel.value}」无证据包导出能力（trace.export）`)
    return
  }
  exportingCsv.value = true
  try {
    const file = await api.trace.evidenceCsv(id)
    downloadBlob(file.blob, file.filename)
    toast.success(`已下载证据包 CSV：${file.filename}（${file.size} 字节 · ${file.contentType || 'text/csv'}）`)
  } catch (error) {
    const denied = deniedOf(error, 'trace.export')
    if (denied) {
      detailDenied.value = denied
      return
    }
    toast.error(`证据包 CSV 导出失败：${apiMessage(error)}`)
  } finally {
    exportingCsv.value = false
  }
}

async function switchAccount(role: ActorRole): Promise<void> {
  const ok = await auth.loginAsRole(role)
  if (!ok) {
    toast.error(`演示账号切换失败：${auth.failure?.message ?? '登录接口不可用'}`)
    return
  }
  toast.info(`已切换演示账号：${ROLE_LABELS[role]}（demo，非真实鉴权），正在重新检索…`)
  await search()
  if (activeTraceId.value) await loadDetail(activeTraceId.value)
}

function retrySearch(): void {
  toast.warning('正在重新检索全链路（GET /api/v1/trace/search）')
  void search()
}

onMounted(() => {
  // 首屏自动检索一次（最近 10 条链路），与「按 traceId 直查」互补；失败/403 均有可见态
  void search()
})
</script>

<template>
  <div class="w-page trace-page" data-page="trace" data-testid="page-root">
    <WPageHeader :eyebrow="screen.eyebrow" :title="screen.title" :description="screen.description">
      <template #actions>
        <WTag tone="n" plain>当前角色：{{ currentRoleLabel }}</WTag>
        <WTag :tone="auth.hasCapability('trace.read') ? 'g' : 'a'" plain>trace.read</WTag>
        <WTag :tone="canExport ? 'g' : 'a'" plain>{{ canExport ? '可导出证据包' : '只读：无 trace.export' }}</WTag>
        <WButton size="sm" icon="refresh" :loading="searching" data-testid="trace-refresh" @click="search">重新检索</WButton>
        <WButton
          v-if="auth.hasCapability('event.read')"
          size="sm"
          icon="activity"
          data-testid="trace-events-link"
          @click="router.push('/events')"
        >
          事件流（outbox）
        </WButton>
      </template>
    </WPageHeader>

    <!-- ============ 检索：traceId 直查 + 条件检索 ============ -->
    <WCard title="链路检索" subtitle="traceId 直查（GET /trace/{id}）或按 actorId / action / 时间检索（GET /trace/search，按 lastAt 降序）">
      <div class="tr-search">
        <WIcon name="search" :size="15" />
        <input
          v-model="traceId"
          class="tr-search__input"
          type="text"
          placeholder="traceId 直查，例：b4fe3897-71fb-43fd-bf3d-21a8b84338a8"
          aria-label="traceId"
          data-testid="trace-id-input"
          @keyup.enter="searchById"
        />
        <WButton variant="primary" size="sm" :loading="detailLoading" data-testid="trace-id-submit" @click="searchById">按 traceId 查</WButton>
      </div>

      <div class="tr-filters">
        <label>actorId<input v-model="actorId" type="text" placeholder="如 agent-01" data-testid="trace-filter-actor" /></label>
        <label>action<input v-model="action" type="text" placeholder="如 auth.login / ticket.create" data-testid="trace-filter-action" /></label>
        <label>起始日<input v-model="from" type="date" data-testid="trace-filter-from" /></label>
        <label>结束日<input v-model="to" type="date" data-testid="trace-filter-to" /></label>
        <WButton size="sm" variant="primary" :loading="searching" data-testid="trace-search-submit" @click="search">条件检索</WButton>
        <WTag tone="n" plain>{{ total }} 条链路</WTag>
      </div>

      <PermissionDenied
        v-if="searchDenied"
        compact
        data-testid="trace-denied"
        :required-capability="searchDenied.requiredCapability"
        :required-role="searchDenied.role"
        :candidate-roles="SWITCH_ROLES"
        :detail="`全链路回溯需要能力 ${capabilityLabel(searchDenied.requiredCapability)}；当前角色「${currentRoleLabel}」不具备（仅 主管 / IT / 管理员 可见）。`"
        @switch="switchAccount"
      >
        <template #actions>
          <WButton size="sm" variant="primary" data-testid="trace-denied-retry" @click="retrySearch">重新检索</WButton>
        </template>
      </PermissionDenied>

      <p v-else-if="searchError" class="tr-note" data-testid="trace-error">
        <WIcon name="refresh" :size="13" />
        <span>{{ searchError }}</span>
        <WButton size="sm" variant="ghost" @click="search">重试</WButton>
      </p>

      <WLoading v-if="searching" center label="正在检索全链路（服务端按 lastAt 降序）…" />

      <template v-else-if="!searchDenied">
        <WEmpty
          v-if="searched && resultRows.length === 0"
          compact
          icon="activity"
          title="未命中链路"
          description="放宽 actorId / action / 时间条件，或直接输入 traceId 查详情。"
          data-testid="trace-empty"
        />
        <table v-else-if="resultRows.length" class="tr-table" data-testid="trace-results">
          <thead>
            <tr><th>traceId</th><th>首末时间</th><th>主体</th><th>首个动作</th><th>节点</th><th>操作</th></tr>
          </thead>
          <tbody>
            <tr
              v-for="row in resultRows"
              :key="row.traceId"
              data-testid="trace-row"
              :data-trace-id="row.traceId"
              :class="{ 'is-active': activeTraceId === row.traceId }"
            >
              <td class="mono">{{ row.traceId.slice(0, 18) }}…</td>
              <td>{{ row.periodText }}</td>
              <td>{{ row.actorId || '—' }}</td>
              <td class="mono">{{ row.action }}</td>
              <td>{{ row.entries }}</td>
              <td>
                <WButton size="sm" variant="ghost" data-testid="trace-row-open" @click="selectResult(row)">时间线</WButton>
              </td>
            </tr>
          </tbody>
        </table>
      </template>
    </WCard>

    <!-- ============ 时间线（脱敏）============ -->
    <WCard
      title="链路时间线"
      :subtitle="detail ? `${detail.traceId} · ${detailRows.length} 个节点（按 at 升序）` : '输入 traceId 或点击上方结果行查看'"
      data-testid="trace-detail"
    >
      <template #extra>
        <WTag v-if="detail?.mock" tone="a" plain>含演示数据（P4 审计源）</WTag>
        <WTag tone="b" plain>已脱敏</WTag>
        <WButton
          size="sm"
          variant="primary"
          icon="download"
          :loading="exportingJson"
          :disabled="!canExport"
          data-testid="trace-evidence-open"
          @click="exportJson"
        >
          导出证据包
        </WButton>
        <WButton
          size="sm"
          icon="download"
          :loading="exportingCsv"
          :disabled="!canExport"
          data-testid="trace-export-csv"
          @click="exportCsv"
        >
          导出 CSV
        </WButton>
      </template>

      <PermissionDenied
        v-if="detailDenied"
        compact
        data-testid="trace-detail-denied"
        :required-capability="detailDenied.requiredCapability"
        :required-role="detailDenied.role"
        :candidate-roles="SWITCH_ROLES"
        :detail="`该操作需要能力 ${capabilityLabel(detailDenied.requiredCapability)}；当前角色「${currentRoleLabel}」不具备，未展示未授权内容。`"
        @switch="switchAccount"
      />

      <p v-else-if="detailError" class="tr-note" data-testid="trace-detail-error">
        <WIcon name="refresh" :size="13" />
        <span>{{ detailError }}</span>
        <WButton size="sm" variant="ghost" @click="loadDetail(activeTraceId || traceId)">重试</WButton>
      </p>

      <WLoading v-if="detailLoading" center label="正在加载链路时间线…" />

      <template v-else-if="detail && !detailDenied">
        <div class="tr-timeline" data-testid="trace-timeline">
          <div v-for="(entry, index) in detailRows" :key="`${entry.at}-${index}`" class="tr-node" data-testid="trace-entry">
            <span class="tr-node__dot" :class="`is-${entry.result}`" />
            <div class="tr-node__time num">{{ formatIsoTime(entry.at) || entry.at }}</div>
            <div class="tr-node__body">
              <div class="tr-node__head">
                <b>{{ entry.actorId || '（未记录主体）' }}</b>
                <WTag tone="n" plain>{{ roleText(entry.role) }}</WTag>
                <WTag :tone="resultTone(entry.result)">{{ resultText(entry.result) }}</WTag>
                <span class="mono tr-node__action">{{ entry.action }}</span>
                <span v-if="entry.durationMs !== undefined" class="tr-node__ms">{{ entry.durationMs }}ms</span>
              </div>
              <div class="tr-node__meta">
                <span>资源：{{ entry.resourceType }} / {{ entry.resourceId || '—' }}</span>
                <span>时间：{{ formatIsoMinute(entry.at) || entry.at }}</span>
              </div>
            </div>
          </div>
        </div>
        <p class="tr-note" data-testid="trace-masked">
          <WIcon name="shield" :size="13" />
          <span>时间线字段由服务端脱敏（手机号/业主姓名/房号等以掩码或占位返回），页面不还原明文；证据包含 `maskedFields` 清单。</span>
        </p>
      </template>

      <WEmpty
        v-else
        icon="activity"
        title="还没有时间线"
        description="输入 traceId 或点击上方结果行的「时间线」，服务端返回 entries（按 at 升序）。"
        data-testid="trace-detail-empty"
      />
    </WCard>

    <!-- ============ 证据包状态（JSON 信封元数据）============ -->
    <WCard v-if="evidence" title="证据包（JSON 元数据）" subtitle="GET /trace/{id}/evidence?format=json · 导出前已脱敏" data-testid="trace-evidence">
      <div v-for="row in evidenceMeta" :key="row.label" class="tr-kv">
        <span>{{ row.label }}</span><b>{{ row.value }}</b>
      </div>
      <p class="tr-note">
        <WIcon name="shield" :size="13" />
        <span>证据包用于投诉举证 / 审计抽查 / badcase 复盘；导出动作已落审计 `trace.export`。</span>
      </p>
    </WCard>

    <StateDemo screen="trace" />
  </div>
</template>

<style scoped>
.trace-page {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
}

.tr-search {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 9px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-md);
  background: var(--c-bg-2);
}

.tr-search__input {
  flex: 1;
  min-width: 0;
  height: 26px;
  border: 0;
  background: transparent;
  font-size: var(--fs-xs);
  color: var(--c-ink-2);
}

.tr-search__input:focus-visible {
  outline: none;
}

.tr-filters {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: 8px;
  margin-top: 8px;
}

.tr-filters label {
  display: flex;
  flex-direction: column;
  gap: 3px;
  font-size: 9.5px;
  color: var(--c-faint);
}

.tr-filters input {
  height: 26px;
  padding: 0 8px;
  min-width: 112px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-sm);
  background: var(--c-card);
  font-size: var(--fs-xs);
  color: var(--c-ink-2);
}

.tr-table {
  width: 100%;
  margin-top: 8px;
  border-collapse: collapse;
  font-size: 10.5px;
}

.tr-table th,
.tr-table td {
  padding: 6px 8px;
  border-bottom: 1px solid var(--c-line-2);
  text-align: left;
  color: var(--c-ink-2);
}

.tr-table th {
  font-size: 9.5px;
  font-weight: 600;
  color: var(--c-faint);
  background: var(--c-bg-2);
}

.tr-table tr.is-active td {
  background: var(--c-brand-soft);
}

.tr-timeline {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 4px 0;
}

.tr-node {
  position: relative;
  display: flex;
  gap: 10px;
  padding: 7px 10px 7px 16px;
  border: 1px solid var(--c-line-2);
  border-radius: var(--rd-md);
  background: var(--c-bg-2);
}

.tr-node__dot {
  position: absolute;
  left: 7px;
  top: 13px;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--c-brand);
}

.tr-node__dot.is-denied {
  background: #c9a227;
}

.tr-node__dot.is-failed {
  background: #b42318;
}

.tr-node__time {
  flex: none;
  width: 64px;
  font-size: 10px;
  color: var(--c-faint);
}

.tr-node__body {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.tr-node__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  font-size: 10.5px;
  color: var(--c-ink-2);
}

.tr-node__action {
  color: var(--c-brand);
}

.tr-node__ms {
  margin-left: auto;
  font-size: 9.5px;
  color: var(--c-faint);
}

.tr-node__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  font-size: 9.5px;
  color: var(--c-muted);
}

.tr-note {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
  padding: 6px 9px;
  border-radius: var(--rd-sm);
  background: var(--c-brand-soft);
  color: #0a725f;
  font-size: 10px;
  line-height: 1.6;
}

.tr-kv {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  padding: 3px 0;
  font-size: 10.5px;
}

.tr-kv span {
  flex: none;
  color: var(--c-faint);
}

.tr-kv b {
  font-weight: var(--fw-medium);
  color: var(--c-ink-2);
  text-align: right;
  word-break: break-all;
}

.mono {
  font-family: var(--font-mono);
}
</style>
