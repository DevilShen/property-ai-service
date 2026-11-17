<script setup lang="ts">
import { computed, ref } from 'vue'
import { WButton, WCard, WEmpty, WLoading, WTag, useToast } from '@/components'
import PermissionDenied from '@/components/feedback/PermissionDenied.vue'
import {
  api,
  ApiError,
  DEFAULT_RECON_SAMPLE_SIZE,
  RECON_DIFF_THRESHOLD,
  errorNextStep,
  errorText,
  forbiddenDetailsOf,
  mockBadgeText,
} from '@/api'
import type { ActorRole, ReconReport, ReconRunResult, ReconRunStatus, ReconScope } from '@/api'
import { capabilityLabel } from '@/auth/capabilities'
import { useAuthStore } from '@/stores/auth'
import {
  RECON_DIFF_KIND_LABEL,
  RECON_SCOPE_META,
  RECON_STATUS_META,
  formatDateTime,
  formatRate,
} from '../erp-data'

/**
 * 只读对账（P2-013 / P2-027，契约 v0.3.2）
 * ---------------------------------------------------------------------------
 * - 触发：`POST /api/v1/recon/run`（202 受理，`runId` + status=queued）；
 * - 轮询：`GET /api/v1/recon/report/{runId}` 直到 completed/failed，展示 sampleSize/checked/matched/
 *   diffCount/diffRate/threshold/passed + 差异明细；
 * - 历史：`GET /api/v1/recon/runs` 分页（按 startedAt 倒序），点历史行可回看该次报告。
 * - 导出（P3-031，契约 v1.1.0 `reconReportExport`）：报告 `completed` 后「导出差异 CSV」→
 *   `GET /api/v1/recon/report/{runId}/export?format=csv`（text/csv 文件流，带 Bearer）→ Blob + `a[download]`
 *   真实下载 + Toast 回执（文件名/体积）。无权限（403 `E-2001` + `requiredCapability=recon.report`）、
 *   runId 不存在（404 `E-3001`）、报告未完成（409 `E-3002`）都给可解释态，**不伪造下载成功**。
 *
 * 口径：对账数据来自真实 BFF 执行结果；`mock:true` 标注「演示数据（mock ERP）」；失败/空不编造数字。
 */
const toast = useToast()
const auth = useAuthStore()

/** 导出所需能力（与 Java `PermissionMatrix` 一致：supervisor/it/admin；前端隐藏/禁用只是提示，不是安全边界） */
const EXPORT_CAPABILITY = 'recon.report'
/** 无权限态可切换的演示账号：/erp 页可见且具备 recon.report（避免切过去又被页面级拦截） */
const EXPORT_CANDIDATE_ROLES: readonly ActorRole[] = ['it', 'admin']

const scope = ref<ReconScope>('bill')
const running = ref(false)
const runError = ref<{ code: string; message: string; next: string } | null>(null)
const report = ref<ReconReport | null>(null)
const runResult = ref<ReconRunResult | null>(null)
const pollStatus = ref<ReconRunStatus | ''>('')

const runs = ref<ReconRunResult[]>([])
const runsTotal = ref(0)
const runsLoading = ref(false)
const runsError = ref<{ code: string; message: string; next: string } | null>(null)
const runPage = ref(1)
const runPageSize = ref(10)
const loadingReportId = ref('')

/** 导出态（P3-031）：进行中 / 失败可解释 / 403 无权限 / 最近一次成功回执 */
const exporting = ref(false)
const switching = ref(false)
const exportError = ref<{ code: string; message: string; next: string } | null>(null)
const exportDenied = ref<{ requiredCapability: string; role: string; traceId: string } | null>(null)
const lastExport = ref<{ filename: string; size: number; at: string } | null>(null)

/** 当前演示角色是否有导出能力（隐藏/禁用入口用；真实判定在 Java 403） */
const canExport = computed(() => auth.hasCapability(EXPORT_CAPABILITY))
/** 只有 completed 报告可导出（未完成不导出半成品，后端仍以 409 兜底） */
const exportable = computed(() => report.value?.status === 'completed')

const scopes: ReconScope[] = ['bill', 'room', 'ticket']

function fillError(caught: unknown): { code: string; message: string; next: string } {
  const apiError = caught instanceof ApiError ? caught : null
  const code = apiError?.code ?? 'E-9001'
  return { code, message: errorText(code), next: errorNextStep(code) }
}

function statusMeta(status?: ReconRunStatus | '') {
  return status ? RECON_STATUS_META[status] : RECON_STATUS_META.queued
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => {
    window.setTimeout(resolve, ms)
  })
}

let pollToken = 0

/** 触发对账（202）→ 轮询报告；轮询用 token 防重复点击互相覆盖 */
async function runRecon(): Promise<void> {
  if (running.value) return
  running.value = true
  runError.value = null
  report.value = null
  runResult.value = null
  pollStatus.value = ''
  exportError.value = null
  exportDenied.value = null
  lastExport.value = null
  const token = ++pollToken
  try {
    const { data } = await api.recon.run(
      { scope: scope.value, sampleSize: DEFAULT_RECON_SAMPLE_SIZE },
      `ui-recon-${scope.value}-${Date.now().toString(36)}`,
    )
    runResult.value = data
    pollStatus.value = data.status
    toast.success(`对账已受理（202）：${data.runId} · ${RECON_SCOPE_META[data.scope]} ${data.sampleSize} 样本`)
    let current: ReconRunStatus = data.status
    for (let attempt = 0; attempt < 60 && token === pollToken; attempt += 1) {
      if (attempt > 0) await sleep(500)
      if (token !== pollToken) return
      const { data: rep } = await api.recon.report(data.runId)
      report.value = rep
      current = rep.status
      pollStatus.value = rep.status
      if (current !== 'queued' && current !== 'running') break
    }
    if (token !== pollToken) return
    if (report.value && report.value.status === 'completed') {
      const rep = report.value
      toast.success(
        `对账完成：${rep.checked}/${rep.sampleSize} 条 · 差异率 ${formatRate(rep.diffRate)}（阈值 ${formatRate(rep.threshold)}）· ${rep.passed ? '通过' : '未通过'}`,
      )
    } else if (report.value && report.value.status === 'failed') {
      toast.error('对账执行失败：不编造结果，请稍后在历史中查看或重试')
    } else {
      toast.warning('对账仍在执行：可稍后在「对账历史」中查看报告')
    }
    await loadRuns()
  } catch (caught) {
    runError.value = fillError(caught)
    toast.error(`对账未完成：${runError.value.message}；${runError.value.next}`)
  } finally {
    running.value = false
  }
}

async function loadRuns(): Promise<void> {
  runsLoading.value = true
  runsError.value = null
  try {
    const { data } = await api.recon.runs({ page: runPage.value, pageSize: runPageSize.value })
    runs.value = data.items
    runsTotal.value = data.total
  } catch (caught) {
    runs.value = []
    runsTotal.value = 0
    runsError.value = fillError(caught)
  } finally {
    runsLoading.value = false
  }
}

async function selectRun(runId: string): Promise<void> {
  loadingReportId.value = runId
  runError.value = null
  exportError.value = null
  exportDenied.value = null
  try {
    const { data } = await api.recon.report(runId)
    report.value = data
    pollStatus.value = data.status
  } catch (caught) {
    runError.value = fillError(caught)
  } finally {
    loadingReportId.value = ''
  }
}

/** 浏览器真实下载：Blob → object URL → a[download]（不是 Toast 占位，也不伪造成功） */
function saveBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  anchor.rel = 'noopener'
  anchor.style.display = 'none'
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  // 下载已开始，延迟回收 object URL，避免部分浏览器取消下载
  window.setTimeout(() => URL.revokeObjectURL(url), 1000)
}

/** 字节数 → 人话（回执用；数字来自 Blob，不自算业务口径） */
function formatBytes(size: number): string {
  if (!Number.isFinite(size) || size <= 0) return '0 B'
  return size < 1024 ? `${size} B` : `${(size / 1024).toFixed(1)} KB`
}

/**
 * 导出差异 CSV（P3-031）：报告完成后可用。
 * 成功 → 真实下载 + Toast 回执（文件名/体积/演示数据标注）；失败 → 403/404/409 可解释态。
 */
async function exportCsv(): Promise<void> {
  const current = report.value
  if (!current || exporting.value) return
  if (!canExport.value) {
    exportDenied.value = {
      requiredCapability: EXPORT_CAPABILITY,
      role: auth.role ?? '',
      traceId: '',
    }
    toast.warning(`导出差异 CSV 需要能力 ${capabilityLabel(EXPORT_CAPABILITY)}，当前角色不具备`)
    return
  }
  exporting.value = true
  exportError.value = null
  exportDenied.value = null
  try {
    const file = await api.recon.exportReport(current.runId)
    saveBlob(file.blob, file.filename)
    lastExport.value = { filename: file.filename, size: file.size, at: new Date().toISOString() }
    toast.success(
      `已下载差异 CSV：${file.filename}（${formatBytes(file.size)}${current.mock ? ' · 演示数据（mock ERP）' : ''}）`,
    )
  } catch (caught) {
    // 403：保留 requiredCapability/role，渲染「无权限」态；其它错误给人话 + 下一步（不显示内部报文）
    const forbidden = forbiddenDetailsOf(caught)
    if (forbidden) {
      exportDenied.value = {
        requiredCapability: forbidden.requiredCapability || EXPORT_CAPABILITY,
        role: forbidden.role || (auth.role ?? ''),
        traceId: caught instanceof ApiError ? caught.traceId : '',
      }
      toast.error(`导出无权限：需要能力 ${capabilityLabel(EXPORT_CAPABILITY)}（E-2001）`)
    } else {
      exportError.value = fillError(caught)
      toast.error(`导出失败：${exportError.value.message}（${exportError.value.code}）；${exportError.value.next}`)
    }
  } finally {
    exporting.value = false
  }
}

/** 无权限态切换演示账号：换演示 JWT 后重读当前报告（不伪造导出成功） */
async function switchAccount(role: ActorRole): Promise<void> {
  if (switching.value) return
  switching.value = true
  try {
    const ok = await auth.loginAsRole(role)
    if (!ok) {
      exportError.value = {
        code: 'E-9001',
        message: auth.failure?.message ?? '演示账号切换失败',
        next: auth.failure?.next ?? '稍后重试或返回登录页',
      }
      return
    }
    await loadRuns()
    const current = report.value
    if (current) await selectRun(current.runId)
  } finally {
    switching.value = false
  }
}

function goRunPage(next: number): void {
  const maxPage = Math.max(1, Math.ceil(runsTotal.value / runPageSize.value))
  runPage.value = Math.min(Math.max(1, next), maxPage)
  void loadRuns()
}

void loadRuns()
</script>

<template>
  <section class="erp-recon" data-testid="erp-recon-panel">
    <WCard :padded="false">
      <template #head>
        <span class="erp__card-title">只读对账</span>
        <span class="erp__card-sub">
          POST /api/v1/recon/run（202）→ GET /api/v1/recon/report/{runId} · P2 门禁：差异率 ≤
          {{ formatRate(RECON_DIFF_THRESHOLD) }}
        </span>
      </template>
      <template #extra>
        <WTag v-if="report?.mock" tone="v" plain data-testid="erp-recon-mock">{{ mockBadgeText }}</WTag>
        <WTag v-if="pollStatus" :tone="statusMeta(pollStatus).tone" data-testid="erp-recon-poll-status">
          {{ statusMeta(pollStatus).label }}
        </WTag>
      </template>

      <div class="erp-recon__console">
        <label class="erp-recon__field">
          <span>对账范围</span>
          <select v-model="scope" data-testid="erp-recon-scope" :disabled="running">
            <option v-for="item in scopes" :key="item" :value="item">{{ RECON_SCOPE_META[item] }}（{{ item }}）</option>
          </select>
        </label>
        <WButton
          size="sm"
          variant="primary"
          icon="refresh"
          :loading="running"
          data-testid="erp-recon-run"
          @click="runRecon"
        >
          {{ running ? `对账执行中（${statusMeta(pollStatus).label}）` : `触发对账（${DEFAULT_RECON_SAMPLE_SIZE} 样本）` }}
        </WButton>
        <span class="dim erp-recon__hint">
          异步受理（202）后按 500ms 轮询报告；只读对账，不写业务主数据，不改 ERP。
        </span>
      </div>

      <div v-if="runError" class="erp__state" data-testid="erp-recon-error">
        <WEmpty
          icon="info"
          title="对账未完成"
          :description="`${runError.message}（错误码 ${runError.code}）。${runError.next}`"
        />
      </div>

      <WLoading v-else-if="running && !report" center label="已受理 202，正在轮询对账报告…" />

      <div v-else-if="report" class="erp-recon__report" data-testid="erp-recon-report">
        <div class="erp-recon__head">
          <b>{{ report.runId }}</b>
          <WTag :tone="RECON_STATUS_META[report.status].tone" data-testid="erp-recon-status">
            {{ RECON_STATUS_META[report.status].label }}
          </WTag>
          <WTag :tone="report.passed ? 'g' : 'a'" data-testid="erp-recon-passed">
            {{ report.passed ? '通过' : '未通过' }}
          </WTag>
          <span class="dim">
            {{ RECON_SCOPE_META[report.scope] }} · {{ formatDateTime(report.startedAt) }} →
            {{ report.finishedAt ? formatDateTime(report.finishedAt) : '执行中' }}
          </span>
        </div>

        <!-- P3-031：对账差异 CSV 导出（complete 后可用；无权限/未完成给可解释态） -->
        <div class="erp-recon__export" data-testid="erp-recon-export-bar">
          <WButton
            size="sm"
            variant="primary"
            icon="download"
            :loading="exporting"
            :disabled="!canExport || !exportable"
            data-testid="erp-recon-export"
            @click="exportCsv"
          >
            {{ exporting ? '正在导出差异 CSV…' : '导出差异 CSV' }}
          </WButton>
          <span class="dim erp-recon__export-hint" data-testid="erp-recon-export-hint">
            <template v-if="!canExport">
              需要能力 {{ capabilityLabel(EXPORT_CAPABILITY) }}；当前演示角色「{{ auth.role ?? '未登录' }}」不具备，
              按钮不可用（后端仍会 403 E-2001 兜底，前端隐藏不是安全边界）。
            </template>
            <template v-else-if="!exportable">
              仅 status=completed 的报告可导出（当前「{{ RECON_STATUS_META[report.status].label }}」），
              未完成不导出半成品（后端 409 E-3002 兜底）。
            </template>
            <template v-else>
              GET /api/v1/recon/report/{{ report.runId }}/export?format=csv · 文件名 recon-{{ report.runId }}.csv
              （text/csv · UTF-8 BOM，Excel 可直接打开；导出落 recon.export 审计）
            </template>
          </span>
          <WTag v-if="lastExport" tone="g" plain data-testid="erp-recon-export-last">
            已导出 {{ lastExport.filename }} · {{ formatBytes(lastExport.size) }}
          </WTag>
        </div>

        <PermissionDenied
          v-if="exportDenied"
          compact
          data-testid="erp-recon-export-denied"
          :required-capability="exportDenied.requiredCapability"
          :required-role="exportDenied.role"
          :trace-id="exportDenied.traceId"
          :candidate-roles="EXPORT_CANDIDATE_ROLES"
          :switching="switching"
          :detail="`导出差异 CSV 需要能力 ${capabilityLabel(exportDenied.requiredCapability)}；当前演示角色「${auth.role ?? '未登录'}」不具备（后端 403 E-2001），本次未发起任何下载。`"
          @switch="switchAccount"
        />

        <div v-if="exportError" class="erp__state" data-testid="erp-recon-export-error">
          <WEmpty
            icon="info"
            title="差异 CSV 导出失败"
            :description="`${exportError.message}（错误码 ${exportError.code}）。${exportError.next}`"
          />
          <WButton size="sm" icon="download" :loading="exporting" @click="exportCsv">重试导出</WButton>
        </div>
        <div class="erp-recon__stats">
          <div><span>抽样 sampleSize</span><b class="num">{{ report.sampleSize }}</b></div>
          <div><span>实际对账 checked</span><b class="num">{{ report.checked }}</b></div>
          <div><span>一致 matched</span><b class="num">{{ report.matched }}</b></div>
          <div><span>差异 diffCount</span><b class="num" :class="{ 'is-warn': report.diffCount > 0 }">{{ report.diffCount }}</b></div>
          <div><span>差异率 diffRate</span><b class="num" data-testid="erp-recon-diff-rate">{{ formatRate(report.diffRate) }}</b></div>
          <div><span>阈值 threshold</span><b class="num">{{ formatRate(report.threshold) }}</b></div>
        </div>
        <div v-if="report.items.length" class="erp-recon__diffs" data-testid="erp-recon-diff">
          <b>差异明细（{{ report.items.length }} 条，仅含单号/房号，不含手机号）</b>
          <div v-for="item in report.items" :key="`${item.key}-${item.kind}-${item.field}`" class="erp-recon__diff" data-testid="erp-recon-diff-item">
            <code>{{ item.key }}</code>
            <WTag tone="a" plain>{{ RECON_DIFF_KIND_LABEL[item.kind] }}</WTag>
            <span>{{ item.field || '—' }}</span>
            <span class="dim">期望 {{ item.expected ?? '—' }} / 实际 {{ item.actual ?? '—' }}</span>
          </div>
        </div>
        <p v-else class="dim erp-recon__note">
          差异明细为空：{{ report.checked }} 条样本比对一致（差异率 {{ formatRate(report.diffRate) }}）。
        </p>
      </div>

      <WEmpty
        v-else
        data-testid="erp-recon-idle"
        icon="report"
        title="尚未触发对账"
        description="选择对账范围后点击「触发对账」：受理后自动轮询报告；也可以直接在下方历史里回看某次对账。"
      />
    </WCard>

    <WCard :padded="false">
      <template #head>
        <span class="erp__card-title">对账历史（{{ runsTotal }}）</span>
        <span class="erp__card-sub">GET /api/v1/recon/runs · 按 startedAt 倒序 · 点行回看报告</span>
      </template>
      <template #extra>
        <WButton size="sm" icon="refresh" :loading="runsLoading" data-testid="erp-recon-history-refresh" @click="loadRuns">
          刷新
        </WButton>
      </template>

      <WLoading v-if="runsLoading && runs.length === 0" center label="正在读取对账历史…" />

      <div v-else-if="runsError" class="erp__state" data-testid="erp-recon-history-error">
        <WEmpty icon="info" title="对账历史读取失败" :description="`${runsError.message}（${runsError.code}）`" />
        <WButton size="sm" icon="refresh" @click="loadRuns">重试</WButton>
      </div>

      <WEmpty
        v-else-if="runs.length === 0"
        data-testid="erp-recon-history-empty"
        icon="report"
        title="暂无对账历史"
        description="每触发一次对账都会落一条历史（GET /recon/runs）；没有记录时不造数。"
      />

      <table v-else class="erp-recon__table" data-testid="erp-recon-history">
        <thead>
          <tr>
            <th scope="col" style="width: 190px">runId</th>
            <th scope="col" style="width: 90px">范围</th>
            <th scope="col" style="width: 80px">样本</th>
            <th scope="col" style="width: 90px">状态</th>
            <th scope="col" style="width: 170px">开始时间</th>
            <th scope="col" style="width: 70px">数据</th>
            <th scope="col" style="width: 90px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in runs" :key="item.runId" data-testid="erp-recon-row" :data-run="item.runId">
            <td><code>{{ item.runId }}</code></td>
            <td>{{ RECON_SCOPE_META[item.scope] }}</td>
            <td><span class="num">{{ item.sampleSize }}</span></td>
            <td><WTag :tone="RECON_STATUS_META[item.status].tone">{{ RECON_STATUS_META[item.status].label }}</WTag></td>
            <td><span class="dim">{{ formatDateTime(item.startedAt) }}</span></td>
            <td><WTag v-if="item.mock" tone="v" plain>mock</WTag></td>
            <td>
              <WButton
                size="sm"
                variant="ghost"
                :loading="loadingReportId === item.runId"
                :data-testid="`erp-recon-view-${item.runId}`"
                @click="selectRun(item.runId)"
              >
                查看报告
              </WButton>
            </td>
          </tr>
        </tbody>
      </table>

      <template #footer>
        <div class="erp-recon__foot">
          <span class="dim">第 {{ runPage }} 页 · 每页 {{ runPageSize }} 条 · 共 {{ runsTotal }} 条</span>
          <div class="erp-recon__pager">
            <WButton size="sm" variant="ghost" :disabled="runPage <= 1 || runsLoading" data-testid="erp-recon-prev" @click="goRunPage(runPage - 1)">
              上一页
            </WButton>
            <WButton
              size="sm"
              variant="ghost"
              :disabled="runPage * runPageSize >= runsTotal || runsLoading"
              data-testid="erp-recon-next"
              @click="goRunPage(runPage + 1)"
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
.erp-recon {
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

.erp-recon__console {
  display: flex;
  align-items: flex-end;
  gap: var(--sp-2);
  flex-wrap: wrap;
  padding: 10px 12px;
  border-bottom: 1px solid var(--c-line-2);
}

.erp-recon__field {
  display: flex;
  flex-direction: column;
  gap: 3px;
  font-size: 9px;
  color: var(--c-faint);
}

.erp-recon__field select {
  height: 28px;
  min-width: 150px;
  padding: 0 8px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-sm);
  background: var(--c-card);
  font-size: var(--fs-2xs);
  color: var(--c-ink);
}

.erp-recon__field select:focus {
  outline: none;
  border-color: var(--c-brand);
  box-shadow: var(--sh-ring);
}

.erp-recon__hint {
  font-size: var(--fs-2xs);
}

.erp-recon__report {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px 12px;
}

.erp-recon__head {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  flex-wrap: wrap;
  font-size: var(--fs-xs);
}

.erp-recon__head b {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--c-ink-2);
}

/* P3-031 导出条：按钮 + 口径说明 + 最近一次回执 */
.erp-recon__export {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  flex-wrap: wrap;
  padding: 8px 10px;
  border: 1px solid var(--c-line-2);
  border-radius: var(--rd-md);
  background: var(--c-bg-2);
}

.erp-recon__export-hint {
  flex: 1 1 320px;
  min-width: 0;
  font-size: var(--fs-2xs);
  line-height: 1.5;
}

.erp-recon__stats {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 6px;
}
.erp-recon__stats div {
  display: flex;
  flex-direction: column;
  gap: 1px;
  padding: 7px 9px;
  border-radius: var(--rd-sm);
  background: var(--c-bg-2);
}

.erp-recon__stats span {
  font-size: 9px;
  color: var(--c-faint);
}

.erp-recon__stats b {
  font-size: var(--fs-sm);
  color: var(--c-ink-2);
}

.erp-recon__stats b.is-warn {
  color: var(--c-amber);
}

.erp-recon__diffs {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 8px 10px;
  border: 1px solid var(--c-line-2);
  border-radius: var(--rd-md);
  background: var(--c-bg-2);
  font-size: var(--fs-2xs);
  color: var(--c-muted);
}

.erp-recon__diffs b {
  color: var(--c-ink-2);
}

.erp-recon__diff {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  flex-wrap: wrap;
}

.erp-recon__diff code {
  font-family: var(--font-mono);
  font-size: 9px;
  color: var(--c-ink-2);
}

.erp-recon__note {
  font-size: var(--fs-2xs);
}

.erp-recon__table {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--fs-sm);
}

.erp-recon__table th {
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

.erp-recon__table td {
  padding: 7px 12px;
  border-bottom: 1px solid #f0f3f5;
  color: var(--c-ink-2);
  vertical-align: middle;
}

.erp-recon__table tbody tr:last-child td {
  border-bottom: 0;
}

.erp-recon__table code {
  font-family: var(--font-mono);
  font-size: 9.5px;
  color: var(--c-ink-2);
}

.erp-recon__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
}

.erp-recon__pager {
  display: flex;
  gap: var(--sp-2);
}

@media (max-width: 1279px) {
  .erp-recon__stats {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}
</style>
