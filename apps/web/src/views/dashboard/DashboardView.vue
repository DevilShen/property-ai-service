<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { WButton, WCard, WLoading, WModal, WPageHeader, WTable, WTag, useToast } from '@/components'
import { ExportModal, type CsvColumn } from '@/components/admin-actions'
import { SCREENS } from '@/app/screens'
import StateDemo from '@/content/StateDemo.vue'
import { demoNote } from '@/content'
import type { TableColumn } from '@/types'

/**
 * 12 运营看板（P1-024）
 * 自助率 / 工单质量 / 缴费转化 / 收缴率 / ROI 指标卡 + 周趋势 + 渠道与场景分布 + 7 日转化漏斗。
 * 口径参照方案附录 C；数字为演示项目「云栖苑」mock，不接真实统计。
 * P1-033：导出周报走 ExportModal（指标卡 + 趋势口径 + 漏斗 + ROI，真实 CSV 下载）。
 */
const screen = SCREENS.dashboard
const toast = useToast()

type RangeKey = 'd7' | 'd14' | 'q'

type RangeData = {
  label: string
  period: string
  sessions: string
  stats: { self: number; acc: number; fields: number; pay: number; promise: number }
  trend: number[]
  handoff: number[]
  funnel: { label: string; value: string; width: number }[]
}

const RANGE_DATA: Record<RangeKey, RangeData> = {
  d7: {
    label: '近 7 天',
    period: '2026-11-08 至 11-14',
    sessions: '7,240 会话',
    stats: { self: 46.9, acc: 97.2, fields: 98.8, pay: 18.1, promise: 0 },
    trend: [40.1, 41.4, 42.8, 43.9, 44.6, 45.4, 46.2, 46.9],
    handoff: [59.9, 58.6, 57.2, 56.1, 55.4, 54.6, 53.8, 53.1],
    funnel: [
      { label: 'AI 触达', value: '1,860', width: 100 },
      { label: '账单打开', value: '1,204', width: 65 },
      { label: '承诺缴费', value: '486', width: 34 },
      { label: '7 日缴费', value: '324', width: 24 },
    ],
  },
  d14: {
    label: '近 14 天',
    period: '2026-11-01 至 11-14',
    sessions: '14,820 会话',
    stats: { self: 46.2, acc: 96.8, fields: 98.6, pay: 17.4, promise: 0 },
    trend: [38.4, 39.6, 41.2, 42, 43.6, 44.2, 45.6, 46.2],
    handoff: [61.6, 60.4, 58.8, 58, 56.4, 55.8, 54.4, 53.8],
    funnel: [
      { label: 'AI 触达', value: '1,860', width: 100 },
      { label: '账单打开', value: '1,204', width: 65 },
      { label: '承诺缴费', value: '438', width: 30 },
      { label: '7 日缴费', value: '324', width: 24 },
    ],
  },
  q: {
    label: '本季度',
    period: '2026-09-01 至 11-14',
    sessions: '46,310 会话',
    stats: { self: 44.1, acc: 96.1, fields: 98.2, pay: 16.2, promise: 0 },
    trend: [34.8, 36.2, 38.4, 40.1, 41.6, 42.8, 43.6, 44.1],
    handoff: [65.2, 63.8, 61.6, 59.9, 58.4, 57.2, 56.4, 55.9],
    funnel: [
      { label: 'AI 触达', value: '5,420', width: 100 },
      { label: '账单打开', value: '3,380', width: 62 },
      { label: '承诺缴费', value: '1,124', width: 28 },
      { label: '7 日缴费', value: '878', width: 22 },
    ],
  },
}

const RANGE_ORDER: RangeKey[] = ['d7', 'd14', 'q']

const CHANNELS = [
  { label: '微信服务号', value: '62%', color: '#0a7a66' },
  { label: '小程序 H5', value: '20%', color: '#2cc3a4' },
  { label: '400 电话', value: '11%', color: '#c9d8de' },
  { label: '企业微信', value: '7%', color: '#e3eaee' },
]

const SCENES = [
  { label: '费用查询', share: '34%', width: 92 },
  { label: '报修受理', share: '26%', width: 70 },
  { label: '进度查询', share: '18%', width: 49 },
  { label: '规则咨询', share: '12%', width: 32 },
  { label: '投诉首响', share: '6%', width: 16 },
  { label: '其他', share: '4%', width: 11 },
]

const SCENE_ROWS = [
  { key: 's1', scene: '费用查询 / 账单解释', sessions: '5,040', self: '71.2%', once: '—', action: 'bill.query' },
  { key: 's2', scene: '报修受理', sessions: '3,850', self: '64.8%', once: '78.6%', action: 'ticket.create' },
  { key: 's3', scene: '工单进度查询', sessions: '2,670', self: '93.1%', once: '—', action: 'ticket.status' },
  { key: 's4', scene: '社区规则咨询', sessions: '1,780', self: '88.4%', once: '—', action: 'kb.search' },
  { key: 's5', scene: '投诉首响', sessions: '890', self: '12.5%', once: '—', action: 'complaint.create' },
]

const sceneColumns: TableColumn[] = [
  { key: 'scene', title: '场景' },
  { key: 'sessions', title: '会话', width: '64px', align: 'right' },
  { key: 'self', title: '自助率', width: '64px', align: 'right' },
  { key: 'once', title: '一次解决', width: '70px', align: 'right' },
  { key: 'action', title: '主要动作', width: '122px' },
]

const VALUE_ROWS = [
  { label: '年化多收现金', value: '¥14.4 万' },
  { label: '无效上门减少', value: '−36%' },
  { label: '重复报修减少', value: '−52%' },
  { label: '客服人力释放', value: '1.0 人' },
  { label: '首年投入（含实施）', value: '¥18.0 万' },
  { label: '首年净收益（中性）', value: '¥22.3 万', highlight: true },
  { label: 'ROI', value: '124%', highlight: true },
  { label: '投资回收期', value: '约 6 个月' },
]

const range = ref<RangeKey>('d14')
const loading = ref(true)
const projectOpen = ref(false)
const metricOpen = ref(false)
const exportOpen = ref(false)
const exportedAt = ref('')
const project = ref('云栖苑 · 2000 户')
const timers: number[] = []

const data = computed(() => RANGE_DATA[range.value])

const stats = computed(() => [
  { key: 'self', label: '自助解决率', value: data.value.stats.self, unit: '%', note: '目标 40% · 较上周 +1.4pt', tone: 'teal' },
  { key: 'acc', label: '问答准确率', value: data.value.stats.acc, unit: '%', note: '抽检 300 条 · 目标 ≥95%', tone: 'plain' },
  { key: 'fields', label: '工单字段完整率', value: data.value.stats.fields, unit: '%', note: '无效上门 ↓ 36%', tone: 'plain' },
  { key: 'pay', label: '7 日缴费转化', value: data.value.stats.pay, unit: '%', note: `触达 1,860 户 · 目标 15%`, tone: 'plain' },
  { key: 'promise', label: '错误承诺事件', value: data.value.stats.promise, unit: '', note: '一票否决 · 连续 9 周', tone: 'plain' },
])

const trendPoints = computed(() => {
  const values = data.value.trend
  const min = 28
  const max = 52
  const step = 560 / (values.length - 1)
  return values.map((value, index) => `${24 + index * step},${150 - ((value - min) / (max - min)) * 110}`).join(' ')
})

const trendAreaPoints = computed(() => {
  const values = data.value.trend
  const min = 28
  const max = 52
  const step = 560 / (values.length - 1)
  const line = values.map((value, index) => `${24 + index * step},${150 - ((value - min) / (max - min)) * 110}`)
  return `M24,150 L${line.join(' L')} L${24 + (values.length - 1) * step},150 Z`
})

const trendDots = computed(() => {
  const values = data.value.trend
  const min = 28
  const max = 52
  const step = 560 / (values.length - 1)
  return values.map((value, index) => ({
    x: 24 + index * step,
    y: 150 - ((value - min) / (max - min)) * 110,
    value,
  }))
})

const donutStyle = computed(() => ({
  background:
    'conic-gradient(#0a7a66 0% 62%, #2cc3a4 62% 82%, #c9d8de 82% 93%, #e3eaee 93% 100%)',
}))

const sceneRows = computed<Record<string, unknown>[]>(() =>
  SCENE_ROWS.map((row) => ({ ...row } as unknown as Record<string, unknown>)),
)

const valueRows = computed(() => VALUE_ROWS)

function schedule(ms: number, task: () => void) {
  const timer = window.setTimeout(task, ms)
  timers.push(timer)
}

onMounted(() => {
  schedule(460, () => {
    loading.value = false
  })
})

onBeforeUnmount(() => {
  timers.forEach((timer) => window.clearTimeout(timer))
})

function cycleRange() {
  const index = RANGE_ORDER.indexOf(range.value)
  range.value = RANGE_ORDER[(index + 1) % RANGE_ORDER.length]
  toast.info(`时间范围已切换：${RANGE_DATA[range.value].label}（${RANGE_DATA[range.value].period}）`)
}

function switchProject(name: string) {
  project.value = name
  projectOpen.value = false
  toast.info(`已切换项目：${name}` + demoNote('指标口径与分母同步切换，未接真实统计'))
}

function exportWeekly() {
  exportOpen.value = true
}

function onExported(payload: { filename: string; rowCount: number }) {
  exportedAt.value = `${new Date().toLocaleTimeString('zh-CN', { hour12: false })} 导出 ${payload.filename}（${payload.rowCount} 行）`
  toast.success(`周报已导出：${payload.filename} · ${payload.rowCount} 行（${project.value} · ${data.value.period}）`)
}

/* ------------------------- 周报 CSV 结构（P1-033） ------------------------- */

const exportColumns: CsvColumn[] = [
  { key: 'group', title: '分组' },
  { key: 'label', title: '指标' },
  { key: 'value', title: '数值' },
  { key: 'note', title: '说明 / 口径' },
]

const exportRows = computed<Record<string, unknown>[]>(() => [
  { group: '范围', label: '项目', value: project.value, note: '项目级隔离，不跨项目汇总' },
  { group: '范围', label: '统计区间', value: data.value.label, note: data.value.period },
  { group: '范围', label: '会话量', value: data.value.sessions, note: '口径见「口径说明」弹层' },
  ...stats.value.map((item) => ({ group: '指标卡', label: item.label, value: `${item.value}${item.unit}`, note: item.note })),
  { group: '趋势', label: '自助解决率（8 周）', value: data.value.trend.join(' / '), note: '%' },
  { group: '趋势', label: '转人工率（8 周）', value: data.value.handoff.join(' / '), note: '%' },
  ...data.value.funnel.map((step) => ({ group: '7 日缴费漏斗', label: step.label, value: step.value, note: `占触达 ${step.width}%` })),
  ...VALUE_ROWS.map((row) => ({ group: '业务价值与 ROI', label: row.label, value: row.value, note: '中性情景' })),
])

const exportSummary = computed(
  () => `${exportRows.value.length} 行 · ${exportColumns.length} 个字段 · ${project.value} · ${data.value.period}`,
)

function sceneOf(row: unknown): Record<string, string> {
  return row as unknown as Record<string, string>
}
</script>

<template>
  <div class="w-page dash-page" data-page="dashboard" data-testid="page-root">
    <WPageHeader :eyebrow="`${screen.eyebrow} · ${data.period}`" :title="screen.title" :description="screen.description">
      <template #actions>
        <WButton size="sm" icon="overview" @click="projectOpen = true">{{ project }}</WButton>
        <WButton size="sm" icon="clock" @click="cycleRange">{{ data.label }}</WButton>
        <WButton size="sm" variant="ghost" icon="info" @click="metricOpen = true">口径说明</WButton>
        <WButton size="sm" variant="primary" icon="download" data-testid="dashboard-export-open" @click="exportWeekly">导出周报</WButton>
      </template>
    </WPageHeader>

    <WLoading v-if="loading" center label="正在加载指标卡、趋势与漏斗…" />

    <template v-else>
      <div class="dash__stats">
        <div v-for="item in stats" :key="item.key" class="dash__stat" :class="`is-${item.tone}`">
          <span class="dash__stat-lab">{{ item.label }}</span>
          <b class="dash__stat-val num">
            {{ item.value }}<small v-if="item.unit">{{ item.unit }}</small>
          </b>
          <span class="dash__stat-delta">{{ item.note }}</span>
        </div>
      </div>

      <div class="dash__row">
        <WCard :padded="false">
          <template #head>
            <span class="dash__card-title">自助解决率 / 转人工率</span>
            <span class="dash__card-sub">周维度 · 灰度扩量节点已标注</span>
          </template>
          <template #extra>
            <div class="dash__legend">
              <span><i class="is-self" />自助解决率</span>
              <span><i class="is-hand" />转人工率</span>
              <span><i class="is-target" />目标 40%</span>
            </div>
          </template>

          <div class="dash__chart">
            <svg viewBox="0 0 600 176" preserveAspectRatio="none" class="dash__svg" role="img" aria-label="自助解决率周趋势">
              <defs>
                <linearGradient id="dash-area" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0" stop-color="#12a48c" stop-opacity="0.22" />
                  <stop offset="1" stop-color="#12a48c" stop-opacity="0" />
                </linearGradient>
              </defs>
              <g stroke="#eef2f4" stroke-width="1">
                <line x1="24" y1="40" x2="584" y2="40" />
                <line x1="24" y1="75" x2="584" y2="75" />
                <line x1="24" y1="110" x2="584" y2="110" />
                <line x1="24" y1="150" x2="584" y2="150" />
              </g>
              <g fill="#9aaebb" font-size="8">
                <text x="0" y="43">52%</text>
                <text x="0" y="78">44%</text>
                <text x="0" y="113">36%</text>
                <text x="0" y="153">28%</text>
              </g>
              <line x1="24" y1="95" x2="584" y2="95" stroke="#0a6b5b" stroke-width="1" stroke-dasharray="5 4" opacity="0.55" />
              <path :d="trendAreaPoints" fill="url(#dash-area)" />
              <polyline :points="trendPoints" fill="none" stroke="#0a7a66" stroke-width="2.2" stroke-linejoin="round" stroke-linecap="round" />
              <g fill="#0a7a66">
                <circle v-for="dot in trendDots" :key="dot.x" :cx="dot.x" :cy="dot.y" r="2.4" />
              </g>
              <g fill="#9aaebb" font-size="8" text-anchor="middle">
                <text v-for="(label, index) in ['1 周', '2 周', '3 周', '4 周', '5 周', '6 周', '7 周', '8 周']" :key="label" :x="24 + index * 80" y="168">
                  {{ label }}
                </text>
              </g>
              <rect x="325" y="60" width="42" height="15" rx="3" fill="#eef7f3" />
              <text x="346" y="70.5" fill="#0a725f" font-size="7.5" text-anchor="middle">灰度 30%</text>
              <text :x="584" :y="trendDots[trendDots.length - 1].y - 8" fill="#075e50" font-size="9" font-weight="700" text-anchor="end">
                {{ data.stats.self }}%
              </text>
            </svg>
          </div>

          <div class="dash__chart-foot">
            <span>会话量 {{ data.sessions }} · 自助 {{ data.stats.self }}% / 转人工 {{ data.handoff[data.handoff.length - 1] }}%</span>
            <span class="dash__chart-note">口径：自助解决 = 未转人工且 24h 内无同题重复问；投诉场景不计入自助率考核。</span>
          </div>
        </WCard>

        <WCard title="渠道分布" :subtitle="data.sessions">
          <div class="dash__donut-wrap">
            <div class="dash__donut" :style="donutStyle">
              <div class="dash__donut-hole"><b>82%</b><span>微信生态</span></div>
            </div>
            <div class="dash__chans">
              <div v-for="item in CHANNELS" :key="item.label">
                <span><i :style="{ background: item.color }" />{{ item.label }}</span>
                <b class="num">{{ item.value }}</b>
              </div>
            </div>
          </div>
          <p class="dash__chan-note">老年业主语音入口占比 6.4%；电话渠道只做兜底，不主动外呼。</p>
        </WCard>
      </div>

      <div class="dash__row">
        <WCard :padded="false">
          <template #head>
            <span class="dash__card-title">场景分布与闭环质量</span>
            <span class="dash__card-sub">按会话量排序</span>
          </template>
          <template #extra><WTag tone="g">P0 场景占 78%</WTag></template>

          <div class="dash__scene">
            <div class="dash__bars">
              <div v-for="item in SCENES" :key="item.label" class="dash__hrow">
                <span>{{ item.label }}</span>
                <div class="dash__htrack"><i :style="{ width: `${item.width}%` }" /></div>
                <b class="num">{{ item.share }}</b>
              </div>
            </div>
            <div class="dash__scene-table">
              <WTable :columns="sceneColumns" :rows="sceneRows" row-key="key" dense>
                <template #scene="{ row }">
                  <span class="dash__scene-name">{{ sceneOf(row).scene }}</span>
                </template>
                <template #sessions="{ row }"><span class="num">{{ sceneOf(row).sessions }}</span></template>
                <template #self="{ row }"><span class="num">{{ sceneOf(row).self }}</span></template>
                <template #once="{ row }"><span class="num">{{ sceneOf(row).once }}</span></template>
                <template #action="{ row }"><WTag tone="b">{{ sceneOf(row).action }}</WTag></template>
              </WTable>
              <p class="dash__tip">
                投诉场景自助率低是设计使然：AI 只做首响与分级，处理必须转人工，不纳入自助率考核。
              </p>
            </div>
          </div>
        </WCard>

        <WCard title="业务价值与 ROI" subtitle="中性情景">
          <template #extra><WTag tone="g">中性</WTag></template>
          <div class="dash__value">
            <div class="dash__value-big">
              <span>收缴率</span>
              <b>88.0% <em>→</em> 90.4%</b>
              <small>+2.4 个百分点</small>
            </div>
            <div v-for="row in valueRows" :key="row.label" class="dash__vrow" :class="{ 'is-hl': row.highlight }">
              <span>{{ row.label }}</span>
              <b class="num">{{ row.value }}</b>
            </div>
            <p class="dash__tip is-muted">
              收益对收缴率高度敏感：若不改善收缴，首年为 −4.0 万。不建议只做“问答 + 报修”。
            </p>
          </div>
        </WCard>
      </div>

      <WCard :padded="false">
        <template #head>
          <span class="dash__card-title">7 日缴费转化漏斗</span>
          <span class="dash__card-sub">触达 → 打开 → 承诺 → 缴费</span>
        </template>
        <template #extra>
          <WTag tone="v">AI 催缴触达</WTag>
          <WTag tone="g">转化 {{ data.stats.pay }}% · 目标 ≥15%</WTag>
        </template>

        <div class="dash__funnel">
          <template v-for="(step, index) in data.funnel" :key="step.label">
            <div class="dash__fstep">
              <span class="dash__fstep-lab">{{ step.label }}</span>
              <div class="dash__fstep-track"><i :style="{ width: `${step.width}%` }" /></div>
              <b class="num">{{ step.value }}</b>
            </div>
            <span v-if="index < data.funnel.length - 1" class="dash__farrow">›</span>
          </template>
          <div class="dash__frate">
            <b class="num">17.4%</b>
            <span>回收 ¥13.2 万</span>
          </div>
        </div>
        <div class="dash__funnel-foot">
          口径：触达 = 企微/短信/服务号模板成功下发；缴费 = 支付回调成功（演示环境为示例数据）；同一业主 7 日内多次触达只计一次。
          <span v-if="exportedAt" data-testid="dashboard-exported-note"> · {{ exportedAt }}</span>
        </div>
      </WCard>
    </template>

    <ExportModal
      v-model:open="exportOpen"
      title="导出周报"
      base-name="运营周报"
      :columns="exportColumns"
      :rows="exportRows"
      :summary="exportSummary"
      hint="周报含指标卡、8 周趋势、7 日缴费漏斗与 ROI 中性情景；数字为演示值，不作为对外承诺。"
      @exported="onExported"
    />

    <WModal v-model:open="projectOpen" title="切换项目" width="460px">
      <div class="dash__modal">
        <button
          v-for="name in ['云栖苑 · 2000 户', '金地名邸 · 1200 户', '智造园 · 800 户']"
          :key="name"
          type="button"
          class="dash__proj"
          :class="{ 'is-on': project === name }"
          @click="switchProject(name)"
        >
          <b>{{ name }}</b>
          <WTag tone="n">{{ name.startsWith('云栖苑') ? '用友 NC' : name.startsWith('金地') ? '金蝶 EAS' : '明源云' }}</WTag>
        </button>
        <p class="dash__modal-note">项目级隔离：指标、知识域、连接器路由均按项目切换，不跨项目汇总。</p>
      </div>
    </WModal>

    <WModal v-model:open="metricOpen" title="指标口径（参照附录 C）" width="580px">
      <div class="dash__modal">
        <div class="dash__modal-row"><span>自助解决率</span><b>未转人工且 24h 内无同题重复问 ÷ 总会话</b></div>
        <div class="dash__modal-row"><span>问答准确率</span><b>人工抽检 300 条中“答案与出处一致”的占比</b></div>
        <div class="dash__modal-row"><span>工单字段完整率</span><b>必填要素齐全的建单 ÷ AI 建单总量</b></div>
        <div class="dash__modal-row"><span>7 日缴费转化</span><b>触达后 7 日内完成缴费户数 ÷ 触达户数</b></div>
        <div class="dash__modal-row"><span>收缴率</span><b>实收 ÷ 应收（按账期口径，不含预收下期）</b></div>
        <div class="dash__modal-row"><span>错误承诺事件</span><b>出现减免 / 赔偿 / 定责 / 时限承诺等越权话术，一票否决</b></div>
        <p class="dash__modal-note">
          不看“回答准确率”这种自我感动指标：对外验收只看自助解决、工单质量、缴费转化与收缴率；数字为演示值。
        </p>
      </div>
      <template #footer>
        <WButton size="sm" variant="primary" @click="metricOpen = false">知道了</WButton>
      </template>
    </WModal>

    <StateDemo screen="dashboard" />
  </div>
</template>

<style scoped>
.dash-page {
  min-width: 0;
}

/* ---------- 指标卡 ---------- */
.dash__stats {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: var(--sp-2);
}

.dash__stat {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 11px 13px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-xl);
  background: var(--c-card);
  box-shadow: var(--sh-md);
}

.dash__stat.is-teal {
  border-color: #cfe9e2;
  background: linear-gradient(180deg, #f4fbf9, var(--c-card));
}

.dash__stat-lab {
  font-size: var(--fs-xs);
  color: var(--c-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.dash__stat-val {
  font-size: var(--fs-2xl);
  font-weight: var(--fw-semibold);
  color: var(--c-ink);
  line-height: 1.15;
}

.dash__stat-val small {
  margin-left: 1px;
  font-size: var(--fs-xs);
  font-weight: var(--fw-medium);
  color: var(--c-muted);
}

.dash__stat-delta {
  font-size: var(--fs-2xs);
  color: var(--c-faint);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* ---------- 行布局 ---------- */
.dash__row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 292px;
  gap: var(--sp-3);
  align-items: stretch;
}

.dash__card-title {
  font-size: 12.5px;
  font-weight: var(--fw-semibold);
  color: var(--c-ink);
}

.dash__card-sub {
  margin-left: 6px;
  font-size: var(--fs-2xs);
  color: var(--c-faint);
}

.dash__legend {
  display: flex;
  align-items: center;
  gap: 9px;
  font-size: var(--fs-2xs);
  color: var(--c-muted);
}

.dash__legend span {
  display: inline-flex;
  align-items: center;
  gap: 3px;
}

.dash__legend i {
  width: 10px;
  height: 3px;
  border-radius: var(--rd-pill);
  background: #0a7a66;
}

.dash__legend i.is-hand {
  background: #c9d8de;
}

.dash__legend i.is-target {
  background: repeating-linear-gradient(90deg, #0a6b5b, #0a6b5b 3px, transparent 3px, transparent 6px);
}

/* ---------- 趋势 ---------- */
.dash__chart {
  height: 208px;
  padding: 10px 16px 4px;
}

.dash__svg {
  width: 100%;
  height: 100%;
  overflow: visible;
}

.dash__chart-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
  flex-wrap: wrap;
  padding: 8px 16px;
  border-top: 1px solid var(--c-line-2);
  background: var(--c-bg-2);
  font-size: var(--fs-2xs);
  color: var(--c-muted);
}

.dash__chart-note {
  color: var(--c-faint);
}

/* ---------- 渠道 ---------- */
.dash__donut-wrap {
  display: flex;
  align-items: center;
  gap: 14px;
}

.dash__donut {
  position: relative;
  flex: none;
  width: 92px;
  height: 92px;
  border-radius: 50%;
}

.dash__donut-hole {
  position: absolute;
  inset: 16px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: var(--c-card);
}

.dash__donut-hole b {
  font-size: var(--fs-base);
  color: var(--c-ink);
}

.dash__donut-hole span {
  font-size: 8px;
  color: var(--c-faint);
}

.dash__chans {
  display: flex;
  flex-direction: column;
  gap: 4px;
  flex: 1;
  min-width: 0;
}

.dash__chans div {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  font-size: var(--fs-2xs);
  color: var(--c-muted);
}

.dash__chans span {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dash__chans i {
  width: 7px;
  height: 7px;
  border-radius: 2px;
  flex: none;
}

.dash__chans b {
  flex: none;
  color: var(--c-ink-2);
}

.dash__chan-note {
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px solid var(--c-line-2);
  font-size: var(--fs-2xs);
  color: var(--c-faint);
  line-height: var(--lh-base);
}

/* ---------- 场景 ---------- */
.dash__scene {
  display: flex;
  gap: 18px;
  padding: 12px 14px;
}

.dash__bars {
  display: flex;
  flex-direction: column;
  gap: 6px;
  flex: none;
  width: 208px;
}

.dash__hrow {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-2xs);
  color: var(--c-muted);
}

.dash__hrow > span {
  flex: none;
  width: 52px;
}

.dash__htrack {
  flex: 1;
  height: 6px;
  border-radius: var(--rd-pill);
  background: var(--c-line-2);
  overflow: hidden;
}

.dash__htrack i {
  display: block;
  height: 100%;
  border-radius: var(--rd-pill);
  background: var(--c-brand-gradient);
}

.dash__hrow b {
  flex: none;
  width: 30px;
  text-align: right;
  color: var(--c-ink-2);
}

.dash__scene-table {
  flex: 1;
  min-width: 0;
}

.dash__scene-name {
  font-size: var(--fs-2xs);
  color: var(--c-ink-2);
}

.dash__tip {
  margin-top: 6px;
  padding: 6px 8px;
  border-radius: var(--rd-sm);
  background: var(--c-amber-soft);
  color: #9a4b05;
  font-size: var(--fs-2xs);
  line-height: var(--lh-base);
}

.dash__tip.is-muted {
  background: var(--c-bg-2);
  color: var(--c-muted);
  border: 1px dashed var(--c-line);
}

/* ---------- 业务价值 ---------- */
.dash__value {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.dash__value-big {
  display: flex;
  flex-direction: column;
  gap: 1px;
  padding: 8px 10px;
  margin-bottom: 5px;
  border-radius: var(--rd-md);
  background: var(--c-brand-soft);
}

.dash__value-big span {
  font-size: 9px;
  color: var(--c-brand);
}

.dash__value-big b {
  font-size: var(--fs-base);
  color: var(--c-brand);
}

.dash__value-big em {
  font-style: normal;
  margin: 0 2px;
  color: var(--c-faint);
}

.dash__value-big small {
  font-size: 9px;
  color: #0a725f;
}

.dash__vrow {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--sp-2);
  padding: 2px 0;
  font-size: var(--fs-2xs);
  color: var(--c-muted);
}

.dash__vrow b {
  color: var(--c-ink-2);
  font-weight: var(--fw-medium);
}

.dash__vrow.is-hl b {
  color: var(--c-brand);
  font-weight: var(--fw-semibold);
}

/* ---------- 漏斗 ---------- */
.dash__funnel {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  padding: 14px 16px 10px;
}

.dash__fstep {
  display: flex;
  flex-direction: column;
  gap: 4px;
  flex: 1;
  min-width: 0;
}

.dash__fstep-lab {
  font-size: var(--fs-xs);
  color: var(--c-muted);
}

.dash__fstep-track {
  height: 10px;
  border-radius: var(--rd-pill);
  background: var(--c-line-2);
  overflow: hidden;
}

.dash__fstep-track i {
  display: block;
  height: 100%;
  border-radius: var(--rd-pill);
  background: var(--c-brand-gradient);
}

.dash__fstep b {
  font-size: var(--fs-base);
  color: var(--c-ink);
}

.dash__farrow {
  flex: none;
  color: var(--c-faint);
  font-size: var(--fs-lg);
}

.dash__frate {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 1px;
  flex: none;
  padding-left: var(--sp-3);
  border-left: 1px solid var(--c-line-2);
}

.dash__frate b {
  font-size: var(--fs-lg);
  color: var(--c-brand);
}

.dash__frate span {
  font-size: 9px;
  color: var(--c-faint);
}

.dash__funnel-foot {
  padding: 8px 16px;
  border-top: 1px solid var(--c-line-2);
  background: var(--c-bg-2);
  font-size: var(--fs-2xs);
  color: var(--c-faint);
  line-height: var(--lh-base);
}

/* ---------- 弹层 ---------- */
.dash__modal {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.dash__proj {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-2);
  padding: 8px 10px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-sm);
  background: var(--c-card);
  font-size: var(--fs-xs);
  color: var(--c-ink-2);
  text-align: left;
}

.dash__proj.is-on {
  border-color: var(--c-brand);
  background: var(--c-brand-soft);
  color: var(--c-brand);
}

.dash__proj:focus-visible {
  outline: none;
  box-shadow: var(--sh-ring);
}

.dash__modal-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--sp-3);
  padding: 7px 10px;
  border-radius: var(--rd-sm);
  background: var(--c-bg-2);
  font-size: var(--fs-xs);
}

.dash__modal-row span {
  flex: none;
  color: var(--c-faint);
}

.dash__modal-row b {
  font-weight: var(--fw-medium);
  color: var(--c-ink-2);
  text-align: right;
}

.dash__modal-note {
  font-size: var(--fs-2xs);
  color: var(--c-faint);
  line-height: var(--lh-base);
}

@media (max-width: 1279px) {
  .dash__stats {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .dash__row {
    grid-template-columns: minmax(0, 1fr);
  }

  .dash__scene {
    flex-direction: column;
  }

  .dash__bars {
    width: 100%;
  }
}
</style>
