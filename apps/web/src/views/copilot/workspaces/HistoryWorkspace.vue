<script setup lang="ts">
import { computed, ref } from 'vue'
import { WButton, WCard, WIcon, WModal, WPageHeader, WTable, WTabs, WTag, useToast } from '@/components'
import type { TableColumn } from '@/types'
import { COPILOT_WORKSPACES } from '@/app/copilot'
import { successReceipt } from '@/content'
import { downloadCsv } from '../shared'

/** Copilot 工作区 8/8 · 历史会话（筛选 + 列表 + 跳转回溯摘要） */
const workspace = COPILOT_WORKSPACES.history
const toast = useToast()

const FILTERS = [
  { key: 'all', label: '全部', badge: '86' },
  { key: 'human', label: '转人工', badge: '12' },
  { key: 'solved', label: '已解决', badge: '61' },
  { key: 'complain', label: '投诉', badge: '4' },
  { key: 'dunning', label: '催缴', badge: '9' },
]

type Bucket = 'human' | 'solved' | 'complain' | 'dunning'

interface HistoryRow {
  time: string
  owner: string
  intent: string
  duration: string
  result: string
  bucket: Bucket
  tone: 'g' | 'b' | 'a' | 'r' | 'n'
  traceId: string
}

const ROWS: HistoryRow[] = [
  { time: '今天 15:02', owner: '王女士 · 3-2-1802', intent: '费用查询 / 公摊解释', duration: '1m 42s', result: '自助解决', bucket: 'solved', tone: 'g', traceId: 'tr-20261114-1502-8821' },
  { time: '今天 14:36', owner: '李先生 · 4-1-902', intent: '停车月卡续费', duration: '2m 05s', result: '转人工', bucket: 'human', tone: 'b', traceId: 'tr-20261114-1436-4410' },
  { time: '今天 11:18', owner: '陈女士 · 6-1-1102', intent: '装修申报材料', duration: '3m 12s', result: '自助解决', bucket: 'solved', tone: 'g', traceId: 'tr-20261114-1118-2207' },
  { time: '今天 10:05', owner: '赵先生 · 2-3-601', intent: '欠费争议 / 分期', duration: '6m 40s', result: '已承诺', bucket: 'dunning', tone: 'a', traceId: 'tr-20261114-1005-9931' },
  { time: '昨天 17:22', owner: '周女士 · 5-2-1503', intent: '停水通知未收到', duration: '1m 08s', result: '自助解决', bucket: 'solved', tone: 'g', traceId: 'tr-20261113-1722-5518' },
  { time: '昨天 15:41', owner: '孙先生 · 1-1-302', intent: '投诉 / 楼道堆物', duration: '4m 26s', result: '转主管', bucket: 'complain', tone: 'r', traceId: 'tr-20261113-1541-7733' },
  { time: '昨天 09:12', owner: '吴女士 · 3-1-1101', intent: '缴费 / 发票', duration: '2m 18s', result: '自助解决', bucket: 'solved', tone: 'g', traceId: 'tr-20261113-0912-1288' },
]

const filter = ref('all')
const keyword = ref('')
const traceOpen = ref(false)
const activeTrace = ref<HistoryRow | null>(null)

const columns: TableColumn[] = [
  { key: 'time', title: '时间', width: '92px' },
  { key: 'owner', title: '业主 / 房号' },
  { key: 'intent', title: '意图', width: '150px' },
  { key: 'duration', title: '时长', width: '76px' },
  { key: 'result', title: '结果', width: '92px' },
  { key: 'actions', title: '操作', width: '76px' },
]

const filtered = computed<Record<string, unknown>[]>(() => {
  const text = keyword.value.trim().toLowerCase()
  return ROWS.filter((row) => (filter.value === 'all' ? true : row.bucket === filter.value))
    .filter((row) => !text || `${row.owner}${row.intent}${row.traceId}`.toLowerCase().includes(text))
    .map((row) => ({ ...row }))
})

function openTrace(row: Record<string, unknown>) {
  activeTrace.value = row as unknown as HistoryRow
  traceOpen.value = true
}

function exportList() {
  downloadCsv(
    '云栖苑-Copilot历史会话-近7天-演示.csv',
    ['时间', '业主/房号', '意图', '时长', '结果', 'traceId'],
    ROWS.map((row) => [row.time, row.owner, row.intent, row.duration, row.result, row.traceId]),
  )
  toast.success(successReceipt('导出会话清单 CSV', '近 7 天示例会话'))
}

function copyTrace() {
  if (!activeTrace.value) return
  const text = `traceId: ${activeTrace.value.traceId}\n会话: ${activeTrace.value.owner} · ${activeTrace.value.intent}\n结果: ${activeTrace.value.result}`
  void navigator.clipboard
    .writeText(text)
    .then(() => toast.success('traceId 与摘要已复制'))
    .catch(() => toast.error('复制失败，请检查剪贴板权限'))
}
</script>

<template>
  <div class="cop-page" data-page="copilot">
    <WPageHeader :eyebrow="`04 · Copilot · ${workspace.label}`" :title="workspace.label" :description="workspace.description">
      <template #actions>
        <WTag tone="n">近 7 天 · 86 条</WTag>
        <WTag tone="a" plain>演示样例：会话存储接入后替换为真实列表</WTag>
        <WButton size="sm" icon="download" @click="exportList">导出</WButton>
      </template>
    </WPageHeader>

    <WCard title="会话筛选" subtitle="按结果筛选，或按业主 / 房号 / traceId 搜索">
      <div class="hs-toolbar">
        <WTabs v-model="filter" :tabs="FILTERS" size="sm" />
        <div class="hs-search">
          <WIcon name="search" :size="14" />
          <input v-model="keyword" type="search" placeholder="搜业主 / 房号 / 意图" aria-label="搜索会话" />
        </div>
        <WTag tone="n" plain>{{ filtered.length }} 条</WTag>
      </div>
    </WCard>

    <WCard title="会话列表" subtitle="点击「链路」查看全链路回溯摘要（演示环境为本地摘要）">
      <WTable :columns="columns" :rows="filtered" row-key="traceId" empty-text="没有匹配的会话，换个关键词或筛选条件试试">
        <template #owner="{ value }">
          <span>{{ value }}</span>
        </template>
        <template #result="{ value }">
          <WTag :tone="value === '自助解决' ? 'g' : value === '转人工' ? 'b' : value === '已承诺' ? 'a' : 'r'" plain>
            {{ value }}
          </WTag>
        </template>
        <template #actions="{ row }">
          <WButton size="sm" variant="ghost" @click="openTrace(row)">链路</WButton>
        </template>
      </WTable>
    </WCard>

    <WModal v-model:open="traceOpen" title="全链路回溯摘要（演示）" width="560px">
      <div class="cop-kv"><span>traceId</span><b class="cop-code">{{ activeTrace?.traceId }}</b></div>
      <div class="cop-kv"><span>会话</span><b>{{ activeTrace?.owner }}</b></div>
      <div class="cop-kv"><span>意图</span><b>{{ activeTrace?.intent }}</b></div>
      <div class="cop-kv"><span>结果</span><b>{{ activeTrace?.result }}</b></div>
      <div class="hr" style="height: 1px; background: var(--c-line-2); margin: 8px 0" />
      <div class="cop-kv"><span>知识引用</span><b>K-2031 · v2026-11（生效中）</b></div>
      <div class="cop-kv"><span>能力调用</span><b>bill.query · 620ms · 用友 NC nc-1.8.2</b></div>
      <div class="cop-kv"><span>护栏判断</span><b>L1 只读 · 无承诺 · 脱敏 3 项</b></div>
      <p class="cop-note" style="margin-top: 8px">
        <WIcon name="shield" :size="13" />
        <span>演示环境展示摘要；真实环境接 trace 与会话回放，证据包可导出 CSV。</span>
      </p>
      <template #footer>
        <WButton size="sm" icon="copy" @click="copyTrace">复制 traceId</WButton>
        <WButton size="sm" @click="traceOpen = false">关闭</WButton>
      </template>
    </WModal>
  </div>
</template>

<style scoped>
.hs-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.hs-search {
  display: flex;
  align-items: center;
  gap: 6px;
  height: 30px;
  padding: 0 9px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-md);
  background: var(--c-bg-2);
  color: var(--c-faint);
}

.hs-search:focus-within {
  border-color: var(--c-brand);
  background: var(--c-card);
  box-shadow: var(--sh-ring);
}

.hs-search input {
  width: 180px;
  border: 0;
  outline: none;
  background: transparent;
  font-size: var(--fs-xs);
  color: var(--c-ink-2);
}
</style>
