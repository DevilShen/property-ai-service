<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { WButton, WCard, WIcon, WLoading, WModal, WPageHeader, WTable, WTag, WTimeline, useToast } from '@/components'
import { DegradeModal, useClipboard, type DegradePayload } from '@/components/admin-actions'
import { SCREENS } from '@/app/screens'
import StateDemo from '@/content/StateDemo.vue'
import { demoNote, successReceipt } from '@/content'
import type { IconName, TableColumn, TimelineItem } from '@/types'

/**
 * 09 能力编排 · 运行时（P1-021）
 * 一次请求的 7 跳链路（4 个场景可切）+ 项目 → ERP 路由表 + 编排规则与开关 + 降级台账。
 * 链路耗时为 mock，不发起真实请求；降级与路由规则只做展示与演示动作。
 * P1-033：降级策略走 DegradeModal（三选一 + 回执），复制规则走真实剪贴板。
 */
const screen = SCREENS.orchestration
const toast = useToast()

type FlowKey = 'bill' | 'ticket' | 'status' | 'dunning'

type FlowNode = { title: string; sub: string; ms: string; icon: IconName; kind: 'plain' | 'ai' | 'core' }

type Flow = {
  question: string
  nodes: FlowNode[]
  summary: string
  citation: string
  degrade: string
  total: string
}

type RouteRow = {
  key: string
  project: string
  projectId: string
  erp: string
  connector: string
  gray: string
  grayTone: 'g' | 'a' | 'n'
  calls: number
  success: string
  degrade: string
  state: string
  stateTone: 'g' | 'a' | 'n'
}

const FLOWS: Record<FlowKey, Flow> = {
  bill: {
    question: '“我家这个月物业费多少？”',
    nodes: [
      { title: '业主消息', sub: '小程序 / 400 / 企微', ms: '0ms', icon: 'chat', kind: 'plain' },
      { title: '意图识别', sub: '小模型分类 + 实体抽取', ms: '120ms', icon: 'sparkles', kind: 'ai' },
      { title: '核验与脱敏', sub: '身份前置 · 手机号掩码', ms: '40ms', icon: 'shield', kind: 'plain' },
      { title: '能力路由', sub: 'bill.query · 项目→ERP', ms: '8ms', icon: 'workflow', kind: 'core' },
      { title: '连接器执行', sub: '用友 NC · nc-1.8.2', ms: '620ms', icon: 'plug', kind: 'core' },
      { title: '结果归一化', sub: '金额 / 账期 / 费项', ms: '12ms', icon: 'refresh', kind: 'plain' },
      { title: '生成与引用', sub: '大模型 temp 0.2 + 出处', ms: '1.10s', icon: 'book', kind: 'ai' },
    ],
    summary: '本次请求：bill.query · 命中 K-2031 · 脱敏 3 项 · 无降级',
    citation: '依据《前期物业服务合同》第三条第 2 款 · 收费公示 2026-11 版',
    degrade: '无',
    total: '1.94s',
  },
  ticket: {
    question: '“主卧天花板漏水，今天能来人吗？”',
    nodes: [
      { title: '业主消息', sub: '微信服务号 · 文本 + 图片', ms: '0ms', icon: 'chat', kind: 'plain' },
      { title: '意图识别', sub: '小模型分类 + 要素抽取', ms: '150ms', icon: 'sparkles', kind: 'ai' },
      { title: '核验与脱敏', sub: '房号核验 · 手机号掩码', ms: '45ms', icon: 'shield', kind: 'plain' },
      { title: '能力路由', sub: 'ticket.create · P-1024', ms: '10ms', icon: 'workflow', kind: 'core' },
      { title: '连接器执行', sub: '用友 NC · 建单（标记 AI）', ms: '880ms', icon: 'plug', kind: 'core' },
      { title: '结果归一化', sub: '工单号回写 / 状态码对齐', ms: '15ms', icon: 'refresh', kind: 'plain' },
      { title: '生成与引用', sub: '建单回执 + 上门时段', ms: '1.24s', icon: 'book', kind: 'ai' },
    ],
    summary: '本次请求：ticket.create · 工单 WO-20261114-0832 · 脱敏 2 项 · 无降级',
    citation: '依据知识 K-5011 v2.3（报修上门服务标准）',
    degrade: '无',
    total: '2.34s',
  },
  status: {
    question: '“上次报修的单子到哪一步了？”',
    nodes: [
      { title: '业主消息', sub: '小程序 H5 · 文本', ms: '0ms', icon: 'chat', kind: 'plain' },
      { title: '意图识别', sub: '小模型 · ticket.status', ms: '110ms', icon: 'sparkles', kind: 'ai' },
      { title: '核验与脱敏', sub: '仅查本人房号', ms: '40ms', icon: 'shield', kind: 'plain' },
      { title: '能力路由', sub: 'ticket.status · P-1024', ms: '8ms', icon: 'workflow', kind: 'core' },
      { title: '连接器执行', sub: '用友 NC · 状态查询', ms: '420ms', icon: 'plug', kind: 'core' },
      { title: '结果归一化', sub: 'ERP 2 → 处理中', ms: '10ms', icon: 'refresh', kind: 'plain' },
      { title: '生成与引用', sub: '节点播报（不承诺时限）', ms: '760ms', icon: 'book', kind: 'ai' },
    ],
    summary: '本次请求：ticket.status · 状态 2 → 处理中 · 未承诺上门时限 · 无降级',
    citation: '依据知识 K-5011 v2.3 · 工单 WO-20261112-0714',
    degrade: '无',
    total: '1.35s',
  },
  dunning: {
    question: '“我收到短信说我欠费 408 元”',
    nodes: [
      { title: '业主消息', sub: '400 电话 · 语音转写', ms: '0ms', icon: 'chat', kind: 'plain' },
      { title: '意图识别', sub: '费用查询 + 异议识别', ms: '130ms', icon: 'sparkles', kind: 'ai' },
      { title: '核验与脱敏', sub: '身份核验 · 只查本人账单', ms: '40ms', icon: 'shield', kind: 'plain' },
      { title: '能力路由', sub: 'bill.query + dunning.task', ms: '8ms', icon: 'workflow', kind: 'core' },
      { title: '连接器执行', sub: '用友 NC · 账单查询', ms: '560ms', icon: 'plug', kind: 'core' },
      { title: '结果归一化', sub: '费项拆解 + 公示记录', ms: '12ms', icon: 'refresh', kind: 'plain' },
      { title: '生成与引用', sub: '命中「减免」→ 标准话术 + 转人工', ms: '980ms', icon: 'book', kind: 'ai' },
    ],
    summary: '本次请求：bill.query + dunning.task · 命中 L3 红线 → 转人工 · 1 项降级',
    citation: '依据知识 K-2031 v2.0 · 收费公示 2026-11 版',
    degrade: '命中“减免”关键词：跳过生成，直接标准话术 + 转人工',
    total: '1.72s',
  },
}

const FLOW_TABS: { key: FlowKey; label: string }[] = [
  { key: 'bill', label: '查账单' },
  { key: 'ticket', label: '报修建单' },
  { key: 'status', label: '查进度' },
  { key: 'dunning', label: '催缴触达' },
]

const ROUTES: RouteRow[] = [
  { key: 'r1', project: '云栖苑', projectId: 'P-1024', erp: '用友 NC', connector: 'nc-1.8.2', gray: '100%', grayTone: 'g', calls: 8420, success: '99.97%', degrade: '—', state: '正常', stateTone: 'g' },
  { key: 'r2', project: '金地名邸', projectId: 'P-1088', erp: '金蝶 EAS', connector: 'eas-1.3.0', gray: '100%', grayTone: 'g', calls: 5110, success: '99.95%', degrade: '发票 → 跳转', state: '正常', stateTone: 'g' },
  { key: 'r3', project: '明源 · 智造园', projectId: 'P-1120', erp: '明源云', connector: 'my-0.9.4', gray: '30%', grayTone: 'a', calls: 1240, success: '99.91%', degrade: '投诉 → 人工池', state: '灰度中', stateTone: 'a' },
  { key: 'r4', project: 'XX 公建', projectId: 'P-1133', erp: '自研 ERP', connector: 'self-2.1.0', gray: '100%', grayTone: 'g', calls: 2760, success: '99.99%', degrade: '通知 → 企微', state: '正常', stateTone: 'g' },
  { key: 'r5', project: '新视窗 · 试点', projectId: 'P-1150', erp: '新视窗', connector: 'v0.3 沙箱', gray: '沙箱', grayTone: 'n', calls: 86, success: '—', degrade: '仅只读能力', state: '联调', stateTone: 'n' },
  { key: 'r6', project: '滨江华庭', projectId: 'P-1162', erp: '金蝶 EAS', connector: 'eas-1.3.0', gray: '100%', grayTone: 'g', calls: 3980, success: '99.96%', degrade: '发票 → 跳转', state: '正常', stateTone: 'g' },
  { key: 'r7', project: '云麓公馆', projectId: 'P-1175', erp: '用友 NC', connector: 'nc-1.8.2', gray: '100%', grayTone: 'g', calls: 2140, success: '99.98%', degrade: '—', state: '正常', stateTone: 'g' },
  { key: 'r8', project: '智造园二期', projectId: 'P-1180', erp: '明源云', connector: 'my-0.9.4', gray: '10%', grayTone: 'a', calls: 610, success: '99.87%', degrade: '投诉 → 人工池', state: '灰度中', stateTone: 'a' },
]

const DEGRADES = [
  { time: '14:32:08', reason: '金蝶主连接器超时 800ms · 未重试成功', action: '已降级 → 只读视图 2.1s' },
  { time: '11:05:44', reason: '自研 ERP 通知接口 502', action: '已改走企微群发' },
  { time: '09:18:21', reason: '明源投诉单能力未开通', action: '落 AI 投诉池 · 人工补录' },
]

const RULES = `# route · bill.query
adapter: erp.byProject(projectId)
require: owner.verify
mask: [phone, idCard, bankCard]
timeout: 800ms
retry: 1
fallback:
  - read_replica: bill_view
  - transfer_human: billing
answer: must_cite(knowledge_id)`

const SWITCHES = [
  { key: 'verify', label: '身份核验前置', value: '强制', on: true },
  { key: 'readonly', label: '只读优先，写操作白名单', value: 'L2', on: true },
  { key: 'degrade', label: '失败降级并转人工', value: 'L3', on: true },
  { key: 'cross', label: '允许跨项目知识检索', value: '项目隔离', on: false },
]

const columns: TableColumn[] = [
  { key: 'project', title: '项目', width: '150px' },
  { key: 'erp', title: 'ERP / 连接器' },
  { key: 'gray', title: '灰度', width: '70px' },
  { key: 'calls', title: '今日调用', width: '86px', align: 'right' },
  { key: 'success', title: '成功率', width: '78px', align: 'right' },
  { key: 'degrade', title: '降级能力', width: '124px' },
  { key: 'state', title: '状态', width: '76px' },
]

const flowKey = ref<FlowKey>('bill')
const loading = ref(true)
const simulating = ref(false)
const simulatedAt = ref('')
const simulateOpen = ref(false)
const routeOpen = ref(false)
const degradeOpen = ref(false)
const switches = ref(SWITCHES.map((item) => ({ ...item })))
const timers: number[] = []
const { copy } = useClipboard()

/** 当前降级演练对象：金蝶 EAS 未开通 invoice.apply（发票申请） */
const DEGRADE_TARGET = {
  capability: 'invoice.apply · 发票申请',
  reason: '金蝶 EAS 能力探测结果：invoice.apply 未开通（eas-1.3.0 只读权限）',
}

const flow = computed(() => FLOWS[flowKey.value])

const routeRows = computed<Record<string, unknown>[]>(() =>
  ROUTES.map((row) => ({ ...row } as unknown as Record<string, unknown>)),
)

const degradeTimeline = computed<TimelineItem[]>(() =>
  DEGRADES.map((item, index) => ({
    title: item.reason,
    time: item.time,
    description: item.action,
    status: index === 0 ? 'active' : 'done',
  })),
)

function routeOf(row: unknown): RouteRow {
  return row as RouteRow
}

function schedule(ms: number, task: () => void) {
  const timer = window.setTimeout(task, ms)
  timers.push(timer)
}

onMounted(() => {
  schedule(420, () => {
    loading.value = false
  })
})

onBeforeUnmount(() => {
  timers.forEach((timer) => window.clearTimeout(timer))
})

function simulate() {
  if (simulating.value) return
  simulating.value = true
  schedule(1000, () => {
    simulating.value = false
    simulatedAt.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
    simulateOpen.value = true
  })
}

function toggleSwitch(key: string) {
  switches.value = switches.value.map((item) => (item.key === key ? { ...item, on: !item.on } : item))
  const target = switches.value.find((item) => item.key === key)
  if (target) toast.success(`已${target.on ? '开启' : '关闭'}「${target.label}」` + demoNote('仅本地开关状态'))
}

function createRoute() {
  routeOpen.value = false
  toast.success(successReceipt('新增项目路由', '无需改 AI 编排'))
}

async function copyRule() {
  await copy(RULES, `编排规则 bill.query（${RULES.split('\n').length} 行）`)
}

function viewAllDegrade() {
  degradeOpen.value = true
}

function onDegradeConfirm(payload: DegradePayload) {
  toast.success(`降级策略已登记：${payload.strategyLabel} · 回执 ${payload.receiptCode}` + demoNote('台账留痕，未写真实 ERP'))
}
</script>

<template>
  <div class="w-page orch-page" data-page="orchestration" data-testid="page-root">
    <WPageHeader :eyebrow="screen.eyebrow" :title="screen.title" :description="screen.description">
      <template #actions>
        <WTag tone="g" plain>P95 1.9s</WTag>
        <WTag tone="v" plain>编排版本 v2.3</WTag>
        <WButton size="sm" variant="primary" icon="activity" :loading="simulating" @click="simulate">模拟一次请求</WButton>
      </template>
    </WPageHeader>

    <WLoading v-if="loading" center label="正在加载编排链路与路由表…" />

    <template v-else>
      <WCard :padded="false">
        <template #head>
          <span class="orch__card-title">场景链路</span>
        </template>
        <template #extra>
          <div class="orch__flow-tabs" role="tablist">
            <button
              v-for="item in FLOW_TABS"
              :key="item.key"
              type="button"
              role="tab"
              class="orch__flow-tab"
              :class="{ 'is-on': flowKey === item.key }"
              :aria-selected="flowKey === item.key"
              @click="flowKey = item.key"
            >
              {{ item.label }}
            </button>
          </div>
          <span class="dim">{{ flow.question }}</span>
          <WTag tone="g">全链路留痕</WTag>
        </template>

        <div class="orch__flow">
          <template v-for="(node, index) in flow.nodes" :key="node.title">
            <div class="orch__node" :class="`is-${node.kind}`">
              <span class="orch__node-icon"><WIcon :name="node.icon" :size="15" /></span>
              <b>{{ node.title }}</b>
              <span class="orch__node-sub">{{ node.sub }}</span>
              <em class="num">{{ node.ms }}</em>
            </div>
            <span v-if="index < flow.nodes.length - 1" class="orch__arrow">→</span>
          </template>
        </div>

        <div class="orch__flow-note">
          <span class="orch__dot" />
          <span>{{ flow.summary }}</span>
          <span class="orch__citation">{{ flow.citation }}</span>
          <span class="orch__flow-total num">总耗时 {{ flow.total }}</span>
          <span class="orch__l1">L1 只说 · 不写业务数据</span>
        </div>
      </WCard>

      <div class="orch__grid">
        <WCard :padded="false">
          <template #head>
            <span class="orch__card-title">项目 → ERP 路由表</span>
            <span class="orch__card-sub">同一套 AI，按项目路由到不同 ERP，互不影响</span>
          </template>
          <template #extra>
            <WButton size="sm" icon="plus" @click="routeOpen = true">新增路由</WButton>
          </template>

          <WTable :columns="columns" :rows="routeRows" row-key="key" dense>
            <template #project="{ row }">
              <div class="orch__proj">
                <b>{{ routeOf(row).project }}</b>
                <span class="num">{{ routeOf(row).projectId }}</span>
              </div>
            </template>
            <template #erp="{ row }">
              <div class="orch__erp">
                <span>{{ routeOf(row).erp }}</span>
                <code>{{ routeOf(row).connector }}</code>
              </div>
            </template>
            <template #gray="{ row }">
              <WTag :tone="routeOf(row).grayTone">{{ routeOf(row).gray }}</WTag>
            </template>
            <template #calls="{ row }">
              <span class="num">{{ routeOf(row).calls.toLocaleString('zh-CN') }}</span>
            </template>
            <template #success="{ row }">
              <span class="num">{{ routeOf(row).success }}</span>
            </template>
            <template #degrade="{ row }">
              <span class="orch__muted">{{ routeOf(row).degrade }}</span>
            </template>
            <template #state="{ row }">
              <WTag :tone="routeOf(row).stateTone">{{ routeOf(row).state }}</WTag>
            </template>
            <template #footer>
              路由键为 projectId，与 ERP 厂商无关；同一项目可双写灰度（新旧连接器并行比对）。
            </template>
          </WTable>

          <div class="orch__degrade">
            <header class="orch__degrade-h">
              <WTag tone="a" plain>降级台账</WTag>
              <b>近 24 小时降级与失败</b>
              <span class="dim">共 7 次 · 全部转人工或只读兜底</span>
              <WButton size="sm" variant="ghost" data-testid="orchestration-degrade-open" @click="viewAllDegrade">查看全部</WButton>
            </header>
            <WTimeline :items="degradeTimeline" />
          </div>
        </WCard>

        <div class="orch__side">
          <WCard title="编排规则" subtitle="bill.query">
            <template #extra>
              <WTag tone="v">runtime</WTag>
              <WButton size="sm" variant="ghost" icon="copy" @click="copyRule">复制</WButton>
            </template>
            <pre class="orch__code">{{ RULES }}</pre>
            <div class="orch__switch-list">
              <button
                v-for="item in switches"
                :key="item.key"
                type="button"
                class="orch__switch"
                :aria-pressed="item.on"
                @click="toggleSwitch(item.key)"
              >
                <span class="orch__switch-track" :class="{ 'is-on': item.on }"><i /></span>
                <span class="orch__switch-label">{{ item.label }}</span>
                <em>{{ item.value }}</em>
              </button>
            </div>
            <p class="orch__tip">
              <WTag tone="r" plain>红线</WTag>
              命中“减免 / 赔偿 / 定责 / 起诉”时跳过生成，直接标准话术 + 转人工。
            </p>
          </WCard>

          <WCard title="降级演练" subtitle="最近 24 小时">
            <div class="orch__drill">
              <div><b>降级演练 15:20</b><span>主连接器超时 → 只读 → 成功 2.1s</span></div>
              <div><b>最近失败 0</b><span>24h 内无静默失败</span></div>
            </div>
            <p class="orch__tip is-muted">
              演练每周一次：人为断开主连接器，验证只读兜底与转人工是否生效；不允许“界面有、实际不能用”。
            </p>
          </WCard>
        </div>
      </div>
    </template>

    <DegradeModal
      v-model:open="degradeOpen"
      :capability="DEGRADE_TARGET.capability"
      :reason="DEGRADE_TARGET.reason"
      hint="降级必须留痕并转人工兜底；不允许「界面有、实际不能用」。发票只做跳转与人工承接，AI 不碰钱。"
      @confirm="onDegradeConfirm"
    />

    <WModal v-model:open="simulateOpen" title="模拟一次请求" width="520px">
      <div class="orch__modal">
        <div class="orch__modal-row"><span>场景</span><b>{{ FLOW_TABS.find((item) => item.key === flowKey)?.label }}</b></div>
        <div class="orch__modal-row"><span>链路</span><b>7 跳 · 全部留痕</b></div>
        <div class="orch__modal-row"><span>请求</span><b>{{ flow.question }}</b></div>
        <div class="orch__modal-row"><span>结果</span><b>{{ flow.summary }}</b></div>
        <div class="orch__modal-row"><span>降级</span><b>{{ flow.degrade }}</b></div>
        <div class="orch__modal-row"><span>模拟时间</span><b class="num">{{ simulatedAt }}</b></div>
        <p class="orch__modal-note">模拟只回放演示链路，不发起真实 ERP 调用；真实链路可在「全链路回溯」查看。</p>
      </div>
      <template #footer>
        <WButton size="sm" variant="ghost" @click="simulateOpen = false">关闭</WButton>
        <WButton size="sm" variant="primary" @click="toast.success('已把本次模拟写入演示记录' + demoNote('仅本地记录'))">记录本次模拟</WButton>
      </template>
    </WModal>

    <WModal v-model:open="routeOpen" title="新增项目路由" width="520px">
      <div class="orch__modal">
        <div class="orch__modal-row"><span>项目</span><b>新建项目（演示）</b></div>
        <div class="orch__modal-row"><span>连接器</span><b>用友 NC · nc-1.8.2</b></div>
        <div class="orch__modal-row"><span>灰度</span><b>10% 业主 → 观察 2 周 → 30% → 100%</b></div>
        <p class="orch__modal-note">路由键为 projectId；新增路由不改 AI 编排与话术，只切连接器。</p>
      </div>
      <template #footer>
        <WButton size="sm" variant="ghost" @click="routeOpen = false">取消</WButton>
        <WButton size="sm" variant="primary" @click="createRoute">确认新增</WButton>
      </template>
    </WModal>

    <StateDemo screen="orchestration" />
  </div>
</template>

<style scoped>
.orch-page {
  min-width: 0;
}

.orch__card-title {
  font-size: 12.5px;
  font-weight: var(--fw-semibold);
  color: var(--c-ink);
}

.orch__card-sub {
  margin-left: 6px;
  font-size: var(--fs-2xs);
  color: var(--c-faint);
}

/* ---------- 7 跳链路 ---------- */
.orch__flow-tabs {
  display: inline-flex;
  gap: 2px;
  padding: 2px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-md);
  background: var(--c-bg-2);
}

.orch__flow-tab {
  height: 23px;
  padding: 0 9px;
  border-radius: var(--rd-sm);
  font-size: var(--fs-2xs);
  color: var(--c-muted);
}

.orch__flow-tab.is-on {
  background: var(--c-card);
  color: var(--c-brand);
  font-weight: var(--fw-semibold);
  box-shadow: var(--sh-sm);
}

.orch__flow-tab:focus-visible {
  outline: none;
  box-shadow: var(--sh-ring);
}

.orch__flow {
  display: flex;
  align-items: stretch;
  gap: 4px;
  padding: 14px 16px 10px;
  overflow-x: auto;
}

.orch__node {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  flex: 1;
  min-width: 104px;
  padding: 9px 6px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-lg);
  background: var(--c-bg-2);
  text-align: center;
}

.orch__node.is-ai {
  border-color: #e4e0fb;
  background: linear-gradient(180deg, #f7f5ff, #fbfaff);
}

.orch__node.is-core {
  border-color: #cfe9e2;
  background: linear-gradient(180deg, #f2faf7, #fbfdfc);
}

.orch__node-icon {
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: var(--c-card);
  color: var(--c-muted);
  border: 1px solid var(--c-line);
}

.orch__node.is-ai .orch__node-icon {
  color: var(--c-ai);
  border-color: #e4e0fb;
}

.orch__node.is-core .orch__node-icon {
  color: var(--c-brand);
  border-color: #cfe9e2;
}

.orch__node b {
  font-size: var(--fs-xs);
  color: var(--c-ink);
}

.orch__node-sub {
  font-size: 9px;
  color: var(--c-faint);
  line-height: 1.35;
}

.orch__node em {
  margin-top: 1px;
  font-style: normal;
  font-size: 9px;
  color: var(--c-brand);
  font-weight: var(--fw-semibold);
}

.orch__arrow {
  align-self: center;
  flex: none;
  color: var(--c-faint);
  font-size: var(--fs-sm);
}

.orch__flow-note {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  padding: 8px 16px;
  border-top: 1px solid var(--c-line-2);
  background: var(--c-bg-2);
  font-size: var(--fs-2xs);
  color: var(--c-muted);
}

.orch__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--c-success);
}

.orch__citation {
  color: var(--c-faint);
}

.orch__flow-total {
  margin-left: auto;
  color: var(--c-brand);
  font-weight: var(--fw-semibold);
}

.orch__l1 {
  padding: 1px 7px;
  border-radius: var(--rd-pill);
  background: var(--c-brand-soft);
  color: var(--c-brand);
}

/* ---------- 路由与规则 ---------- */
.orch__grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 330px;
  gap: var(--sp-3);
  align-items: start;
}

.orch__proj {
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.orch__proj b {
  font-size: var(--fs-xs);
  color: var(--c-ink);
}

.orch__proj span {
  font-size: 9px;
  color: var(--c-faint);
}

.orch__erp {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: var(--fs-2xs);
  color: var(--c-ink-2);
}

.orch__erp code {
  font-family: var(--font-mono);
  font-size: 9px;
  color: var(--c-faint);
}

.orch__muted {
  font-size: var(--fs-2xs);
  color: var(--c-muted);
}

.orch__degrade {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px 14px 14px;
  border-top: 1px solid var(--c-line-2);
  background: var(--c-bg-2);
}

.orch__degrade-h {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  font-size: var(--fs-xs);
  color: var(--c-ink-2);
}

.orch__degrade-h .dim {
  margin-left: auto;
}

.orch__side {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
}

.orch__code {
  margin: 0 0 8px;
  padding: 9px 10px;
  border-radius: var(--rd-sm);
  background: #f7fafc;
  border: 1px solid var(--c-line-2);
  font-family: var(--font-mono);
  font-size: 9.5px;
  line-height: 1.7;
  color: var(--c-ink-2);
  overflow-x: auto;
  white-space: pre;
}

.orch__switch-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 8px 0;
  border-top: 1px solid var(--c-line-2);
}

.orch__switch {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 4px 2px;
  font-size: var(--fs-xs);
  color: var(--c-muted);
  text-align: left;
}

.orch__switch-track {
  position: relative;
  flex: none;
  width: 26px;
  height: 14px;
  border-radius: var(--rd-pill);
  background: var(--c-line);
  transition: background-color var(--tr-fast);
}

.orch__switch-track i {
  position: absolute;
  top: 2px;
  left: 2px;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: var(--c-card);
  transition: left var(--tr-fast);
}

.orch__switch-track.is-on {
  background: var(--c-brand);
}

.orch__switch-track.is-on i {
  left: 14px;
}

.orch__switch:focus-visible {
  outline: none;
  box-shadow: var(--sh-ring);
  border-radius: var(--rd-sm);
}

.orch__switch-label {
  flex: 1;
  min-width: 0;
  color: var(--c-ink-2);
}

.orch__switch em {
  flex: none;
  font-style: normal;
  font-size: 9px;
  color: var(--c-faint);
}

.orch__tip {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
  padding: 7px 9px;
  border-radius: var(--rd-sm);
  background: var(--c-red-soft);
  color: #b42318;
  font-size: var(--fs-2xs);
  line-height: var(--lh-base);
}

.orch__tip.is-muted {
  background: var(--c-bg-2);
  color: var(--c-muted);
  border: 1px dashed var(--c-line);
}

.orch__drill {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.orch__drill div {
  display: flex;
  flex-direction: column;
  gap: 1px;
  padding: 6px 9px;
  border-radius: var(--rd-sm);
  background: var(--c-bg-2);
}

.orch__drill b {
  font-size: var(--fs-xs);
  color: var(--c-ink-2);
}

.orch__drill span {
  font-size: var(--fs-2xs);
  color: var(--c-muted);
}

/* ---------- 弹层 ---------- */
.orch__modal {
  display: flex;
  flex-direction: column;
  gap: 7px;
}

.orch__modal-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--sp-3);
  padding: 7px 10px;
  border-radius: var(--rd-sm);
  background: var(--c-bg-2);
  font-size: var(--fs-xs);
}

.orch__modal-row span {
  flex: none;
  color: var(--c-faint);
}

.orch__modal-row b {
  font-weight: var(--fw-medium);
  color: var(--c-ink-2);
  text-align: right;
}

.orch__modal-note {
  font-size: var(--fs-2xs);
  color: var(--c-faint);
  line-height: var(--lh-base);
}

@media (max-width: 1279px) {
  .orch__grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
