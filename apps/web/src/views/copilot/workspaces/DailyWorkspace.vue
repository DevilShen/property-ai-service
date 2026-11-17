<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { WButton, WCard, WEmpty, WIcon, WLoading, WPageHeader, WTag, useToast } from '@/components'
import { COPILOT_WORKSPACES } from '@/app/copilot'
import { api, errorNextStep, errorText, forbiddenDetailsOf, formatIsoMinute } from '@/api'
import type { CopilotDigest, CopilotReport } from '@/api'
import { capabilityLabel } from '@/auth/capabilities'
import { COPILOT_RULE_NOTE, downloadCsv } from '../shared'

/**
 * Copilot 工作区 5/8 · 日报周报（P4 W16，契约 v1.2.0 `GET /copilot/digest` + `GET /copilot/report`）
 * ===========================================================================
 * - 日报：`date` 查询 → `summary[]` + `metrics{}`（诉求/工单/投诉/首响均值等，全部服务端聚合）；
 * - 报表：`from`/`to` 区间 → `metrics{}` + `breakdown[]`（按场景分组，含 ratio）+ `generatedAt`；
 * - **前端不自算业务数字**：指标卡与分组明细直接渲染接口值，仅做格式化/百分比展示；
 * - 空数据日期回空数组与 0 值（接口口径），页面渲染「空态」而非编造；
 * - `mock:true` = 规则组装口径（模型调用由 Python 编排层），页面强制标注。
 */
const workspace = COPILOT_WORKSPACES.daily
const toast = useToast()

const date = ref('')
const from = ref('')
const to = ref('')
const loading = ref(false)
const digest = ref<CopilotDigest | null>(null)
const report = ref<CopilotReport | null>(null)
const digestError = ref('')
const reportError = ref('')
const denied = ref<{ requiredCapability: string; role: string } | null>(null)

/** 指标中文口径（键名来自服务端聚合；未知键原样展示，不编造含义） */
const METRIC_LABELS: Readonly<Record<string, string>> = {
  requests: '诉求量',
  aiResolved: '自助解决',
  tickets: '工单数',
  complaints: '投诉数',
  avgFirstResponseMinutes: '首响均值（分钟）',
  selfServiceRate: '自助解决率',
  knowledgeAdoptionRate: '知识采纳率',
}

function metricLabel(key: string): string {
  return METRIC_LABELS[key] ?? key
}

/** 比率类键展示成百分比（0—1）；其余保持整数/一位小数 */
function metricValue(key: string, value: number): string {
  if (/Rate$/i.test(key) || /rate$/i.test(key)) return `${(value * 100).toFixed(1)}%`
  if (Number.isInteger(value)) return String(value)
  return value.toFixed(1)
}

const digestMetrics = computed(() =>
  Object.entries(digest.value?.metrics ?? {}).map(([key, value]) => ({
    key,
    label: metricLabel(key),
    value: metricValue(key, value),
  })),
)

const reportMetrics = computed(() =>
  Object.entries(report.value?.metrics ?? {}).map(([key, value]) => ({
    key,
    label: metricLabel(key),
    value: metricValue(key, value),
  })),
)

const breakdownRows = computed(() =>
  (report.value?.breakdown ?? []).map((row) => ({
    ...row,
    ratioText: row.ratio === undefined ? '—' : `${(row.ratio * 100).toFixed(1)}%`,
  })),
)

const digestEmpty = computed(() => Boolean(digest.value && digest.value.summary.length === 0))

function apiMessage(err: unknown): string {
  if (err instanceof Error && 'code' in err) {
    const code = String(err.code)
    return `${errorText(code)}；${errorNextStep(code)}`
  }
  return '请求失败：请稍后重试或转人工'
}

/**
 * 读取日报。`announce` = 用户主动点击（按钮/重试）时给 Toast 回执：
 * 成功说明命中日期与条目/指标数，失败给人话错误 + 下一步；首屏 `loadAll` 用汇总 Toast，避免连弹三条。
 */
async function loadDigest(announce = false): Promise<void> {
  loading.value = true
  digestError.value = ''
  denied.value = null
  try {
    const { data } = await api.copilot.digest(date.value ? { date: date.value } : {})
    digest.value = data
    if (announce) {
      toast.success(
        `已读取 ${data.date || '当日'} 日报：${data.summary.length} 条摘要 / ${Object.keys(data.metrics).length} 项指标（服务端聚合）`,
      )
    }
  } catch (err) {
    digest.value = null
    const forbidden = forbiddenDetailsOf(err)
    if (forbidden) {
      denied.value = { requiredCapability: forbidden.requiredCapability || 'copilot.use', role: forbidden.role }
      if (announce) toast.warning('日报读取被拒绝：当前角色缺少 copilot.use 能力')
      return
    }
    digestError.value = apiMessage(err)
    if (announce) toast.error(`日报读取失败：${digestError.value}`)
  } finally {
    loading.value = false
  }
}

/**
 * 读取报表。`announce` 同上：成功 Toast 带数据截止时间与分组数，
 * 失败给错误 Toast；都不在前端补数字（空分组=接口空数组）。
 */
async function loadReport(announce = false): Promise<void> {
  loading.value = true
  reportError.value = ''
  denied.value = null
  try {
    const { data } = await api.copilot.report({
      from: from.value || undefined,
      to: to.value || undefined,
    })
    report.value = data
    if (announce) {
      toast.success(
        `已读取报表：数据截止 ${formatIsoMinute(data.generatedAt) || '未标注'} · ${data.breakdown.length} 个分组 / ${Object.keys(data.metrics).length} 项指标`,
      )
    }
  } catch (err) {
    report.value = null
    const forbidden = forbiddenDetailsOf(err)
    if (forbidden) {
      denied.value = { requiredCapability: forbidden.requiredCapability || 'copilot.use', role: forbidden.role }
      if (announce) toast.warning('报表读取被拒绝：当前角色缺少 copilot.use 能力')
      return
    }
    reportError.value = apiMessage(err)
    if (announce) toast.error(`报表读取失败：${reportError.value}`)
  } finally {
    loading.value = false
  }
}

async function loadAll(): Promise<void> {
  await Promise.all([loadDigest(), loadReport()])
  if (digest.value || report.value) toast.success('已从 API 读取日报与报表指标（数字全部服务端聚合）')
}

function exportDigest(): void {
  const data = digest.value
  if (!data) {
    toast.warning('暂无日报数据可导出，请先加载')
    return
  }
  downloadCsv(
    `Copilot日报-${data.date || '未标注'}.csv`,
    ['项目', '内容', '口径'],
    [
      ['日期', data.date, 'GET /copilot/digest'],
      ...data.summary.map((line, index) => [`摘要 ${index + 1}`, line, '服务端摘要']),
      ...Object.entries(data.metrics).map(([key, value]) => [metricLabel(key), String(value), '服务端聚合']),
    ],
  )
  toast.success(`已导出日报 CSV（${data.summary.length} 条摘要 + ${Object.keys(data.metrics).length} 项指标）`)
}

function exportReport(): void {
  const data = report.value
  if (!data) {
    toast.warning('暂无报表数据可导出，请先加载')
    return
  }
  downloadCsv(
    `Copilot报表-${from.value || '默认区间'}-${to.value || ''}.csv`,
    ['维度', '指标', '数值'],
    [
      ['周期', '数据截止', formatIsoMinute(data.generatedAt) || '—'],
      ...Object.entries(data.metrics).map(([key, value]) => ['指标', metricLabel(key), String(value)]),
      ...data.breakdown.map((row) => ['分组', row.name, `${row.value}${row.ratio === undefined ? '' : `（${(row.ratio * 100).toFixed(1)}%）`}`]),
    ],
  )
  toast.success(`已导出报表 CSV（${data.breakdown.length} 个分组）`)
}

onMounted(() => {
  void loadAll()
})
</script>

<template>
  <div class="cop-page" data-page="copilot" data-testid="copilot-daily">
    <WPageHeader :eyebrow="`04 · Copilot · ${workspace.label}`" :title="workspace.label" :description="workspace.description">
      <template #actions>
        <WTag tone="a">{{ COPILOT_RULE_NOTE }}</WTag>
        <WButton size="sm" icon="refresh" :loading="loading" data-testid="copilot-daily-refresh" @click="loadAll">重新加载</WButton>
      </template>
    </WPageHeader>

    <p v-if="denied" class="cop-note" data-testid="copilot-daily-denied">
      <WIcon name="lock" :size="13" />
      <span>
        无权限：日报/报表需要能力 {{ capabilityLabel(denied.requiredCapability) }}（当前角色「{{ denied.role || '未知' }}」不具备）。
      </span>
    </p>

    <div class="dl-toolbar">
      <label class="dl-field">
        <span>日报日期</span>
        <input v-model="date" type="date" data-testid="copilot-digest-date" />
      </label>
      <WButton size="sm" variant="primary" :loading="loading" data-testid="copilot-digest-load" @click="loadDigest(true)">读取日报</WButton>
      <WButton size="sm" icon="download" :disabled="!digest" data-testid="copilot-digest-export" @click="exportDigest">导出日报 CSV</WButton>
      <span class="dl-sep" />
      <label class="dl-field">
        <span>区间起</span>
        <input v-model="from" type="date" data-testid="copilot-report-from" />
      </label>
      <label class="dl-field">
        <span>区间止</span>
        <input v-model="to" type="date" data-testid="copilot-report-to" />
      </label>
      <WButton size="sm" variant="primary" :loading="loading" data-testid="copilot-report-load" @click="loadReport(true)">读取报表</WButton>
      <WButton size="sm" icon="download" :disabled="!report" data-testid="copilot-report-export" @click="exportReport">导出报表 CSV</WButton>
    </div>
    <p class="cop-dim dl-hint">日期/区间留空 = 服务端默认（日报=当日，报表=最近 7 天）；数字全部来自 API，前端不自算。</p>

    <WLoading v-if="loading" center label="正在读取日报/报表指标…" />

    <template v-else>
      <WCard
        title="今日指标（日报）"
        :subtitle="digest ? `GET /copilot/digest · ${digest.date}` : 'GET /copilot/digest'"
        data-testid="copilot-digest"
      >
        <template #extra>
          <WTag v-if="digest?.mock" tone="a" plain>{{ COPILOT_RULE_NOTE }}</WTag>
          <WTag v-if="digestEmpty" tone="n" plain>当日无数据</WTag>
        </template>
        <p v-if="digestError" class="cop-note" data-testid="copilot-digest-error">
          <WIcon name="refresh" :size="13" />
          <span>{{ digestError }}</span>
          <WButton size="sm" variant="ghost" style="margin-left: 6px" @click="loadDigest(true)">重试</WButton>
        </p>
        <template v-else-if="digest">
          <div class="dl-stats" data-testid="copilot-digest-metrics">
            <div v-for="metric in digestMetrics" :key="metric.key" class="cop-stat" :data-metric="metric.key">
              <b>{{ metric.value }}</b>
              <span>{{ metric.label }}</span>
            </div>
          </div>
          <div class="dl-doc" style="margin-top: 10px" data-testid="copilot-digest-summary">
            <p v-for="(line, index) in digest.summary" :key="index">{{ line }}</p>
            <p v-if="digestEmpty" class="cop-dim">该日期无日报条目（接口返回空数组，不编造）。</p>
          </div>
        </template>
        <WEmpty v-else icon="report" title="还没有日报" description="读取出错时不会展示示例数字；可点重试或调整日期。" />
      </WCard>

      <WCard
        title="周期报表"
        :subtitle="report ? `GET /copilot/report · 数据截止 ${formatIsoMinute(report.generatedAt) || '—'}` : 'GET /copilot/report?from&to'"
        data-testid="copilot-report"
      >
        <template #extra>
          <WTag v-if="report?.mock" tone="a" plain>{{ COPILOT_RULE_NOTE }}</WTag>
        </template>
        <p v-if="reportError" class="cop-note" data-testid="copilot-report-error">
          <WIcon name="refresh" :size="13" />
          <span>{{ reportError }}</span>
          <WButton size="sm" variant="ghost" style="margin-left: 6px" @click="loadReport(true)">重试</WButton>
        </p>
        <template v-else-if="report">
          <div class="dl-stats" data-testid="copilot-report-metrics">
            <div v-for="metric in reportMetrics" :key="metric.key" class="cop-stat" :data-metric="metric.key">
              <b>{{ metric.value }}</b>
              <span>{{ metric.label }}</span>
            </div>
          </div>
          <div class="dl-breakdown" data-testid="copilot-report-breakdown">
            <div class="dl-breakdown__head">
              <b>分组明细（按场景）</b>
              <WTag tone="n" plain>{{ breakdownRows.length }} 组</WTag>
            </div>
            <div v-for="row in breakdownRows" :key="row.name" class="dl-breakdown__row">
              <span>{{ row.name }}</span>
              <i :style="{ width: `${Math.max(2, Math.round((row.ratio ?? 0) * 100))}%` }" />
              <b>{{ row.value }}</b>
              <span class="cop-dim">{{ row.ratioText }}</span>
            </div>
            <p v-if="!breakdownRows.length" class="cop-dim">该区间无分组数据（接口返回空数组，不编造）。</p>
          </div>
        </template>
        <WEmpty v-else icon="chart" title="还没有报表" description="读取出错时不会展示示例数字；可点重试或调整区间。" />
      </WCard>
    </template>
  </div>
</template>

<style scoped>
.dl-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: 8px;
  padding: 9px 12px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-lg);
  background: var(--c-card);
}

.dl-field {
  display: flex;
  flex-direction: column;
  gap: 3px;
  font-size: 9.5px;
  color: var(--c-faint);
}

.dl-field input {
  height: 26px;
  padding: 0 8px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-sm);
  background: var(--c-card);
  font-size: var(--fs-xs);
  color: var(--c-ink-2);
}

.dl-sep {
  width: 1px;
  height: 22px;
  background: var(--c-line-2);
}

.dl-hint {
  margin: 4px 2px 0;
  font-size: 9.5px;
}

.dl-stats {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(110px, 1fr));
  gap: 10px;
}

.dl-doc {
  display: flex;
  flex-direction: column;
  gap: 7px;
  font-size: var(--fs-sm);
  line-height: 1.8;
  color: var(--c-ink-2);
}

.dl-breakdown {
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed var(--c-line-2);
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.dl-breakdown__head {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-xs);
  color: var(--c-ink-2);
}

.dl-breakdown__row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 10px;
  color: var(--c-muted);
}

.dl-breakdown__row span:first-child {
  flex: none;
  width: 72px;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.dl-breakdown__row i {
  display: block;
  height: 6px;
  border-radius: var(--rd-pill);
  background: linear-gradient(90deg, #2cc3a4, #0a7a66);
}

.dl-breakdown__row b {
  flex: none;
  width: 34px;
  text-align: right;
  color: var(--c-ink-2);
}
</style>
