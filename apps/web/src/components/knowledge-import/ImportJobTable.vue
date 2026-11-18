<script setup lang="ts">
import { computed } from 'vue'
import { WButton, WCard, WEmpty, WTable, WTag } from '@/components'
import { formatIsoMinute } from '@/api'
import type { KnowledgeImportJob, KnowledgeImportJobStatus } from '@/api'
import type { TableColumn } from '@/types'
import {
  IMPORT_SOURCE_LABELS,
  IMPORT_STATUS_LABELS,
  IMPORT_STATUS_OPTIONS,
  IMPORT_STATUS_SHORT_LABELS,
  IMPORT_STATUS_TONES,
  fileExtOf,
  isImportInFlight,
  jobSizeText,
} from './import-copy'

/**
 * 导入任务列表（task-87；`GET /api/v1/knowledge/imports?status&page&pageSize`）
 * ===========================================================================
 * 纯展示 + 事件上传（数据/请求/轮询在 `KnowledgeImportPanel`）：
 * - 每行字段全部来自服务端（jobId/status/sourceType/title/fileName/sizeBytes/charCount/preview.totalChunks/
 *   createdBy/createdAt），前端不补造任务；
 * - 状态 8 态中文人话 + tone（shared 枚举一一对应）；in-flight 行打「进行中」标记（面板会轮询该任务）；
 * - 操作：打开（预览就绪 → 预览页；失败/完成 → 详情卡），不做行内写操作（取消/提交在详情/预览里二次确认）；
 * - 分页由服务端执行；筛选切换回第 1 页。
 */
const props = defineProps<{
  jobs: KnowledgeImportJob[]
  total: number
  page: number
  pageSize: number
  loading: boolean
  error: string
  status: KnowledgeImportJobStatus | ''
}>()

const emit = defineEmits<{
  refresh: []
  open: [job: KnowledgeImportJob]
  filter: [status: KnowledgeImportJobStatus | '']
  page: [next: number]
}>()

const totalPages = computed(() => Math.max(1, Math.ceil(props.total / props.pageSize)))
const canPrev = computed(() => props.page > 1 && !props.loading)
const canNext = computed(() => props.page < totalPages.value && !props.loading)
const rows = computed<Record<string, unknown>[]>(
  () => props.jobs.map((job) => ({ ...job })) as unknown as Record<string, unknown>[],
)

function jobOf(row: unknown): KnowledgeImportJob {
  return row as KnowledgeImportJob
}

function statusLabel(job: KnowledgeImportJob): string {
  return IMPORT_STATUS_LABELS[job.status] ?? job.status
}

function statusShort(job: KnowledgeImportJob): string {
  return IMPORT_STATUS_SHORT_LABELS[job.status] ?? job.status
}

function sourceLabel(job: KnowledgeImportJob): string {
  const base = IMPORT_SOURCE_LABELS[job.sourceType] ?? job.sourceType
  const ext = job.fileName ? fileExtOf(job.fileName) : ''
  return ext ? `${base} · ${ext.toUpperCase()}` : base
}

function titleText(job: KnowledgeImportJob): string {
  return job.title || job.fileName || '（未命名任务）'
}

function onFilter(event: Event): void {
  const value = (event.target as HTMLSelectElement).value as KnowledgeImportJobStatus | ''
  emit('filter', value)
}

const columns: TableColumn[] = [
  { key: 'jobId', title: '任务 ID', width: '150px' },
  { key: 'status', title: '状态', width: '96px' },
  { key: 'source', title: '来源', width: '120px' },
  { key: 'title', title: '标题 / 文件' },
  { key: 'size', title: '大小 / 块', width: '132px' },
  { key: 'createdBy', title: '创建人', width: '86px' },
  { key: 'createdAt', title: '创建时间', width: '96px' },
  { key: 'action', title: '操作', width: '92px' },
]
</script>

<template>
  <WCard
    title="导入任务列表"
    subtitle="GET /api/v1/knowledge/imports · 仅本项目可见（服务端项目隔离）· 进程内演示存储"
    data-testid="kimp-jobs"
  >
    <template #extra>
      <WTag tone="b" plain>{{ total }} 个任务</WTag>
      <WTag tone="n" plain>演示数据（mock 任务存储，重启回滚）</WTag>
      <WButton size="sm" icon="refresh" :loading="loading" data-testid="kimp-jobs-refresh" @click="emit('refresh')">
        刷新
      </WButton>
    </template>

    <div class="kimp-jobs__bar">
      <label class="kimp-field">
        <span>状态</span>
        <select :value="status" data-testid="kimp-jobs-status" @change="onFilter">
          <option value="">全部状态</option>
          <option v-for="option in IMPORT_STATUS_OPTIONS" :key="option.value" :value="option.value">
            {{ option.label }}
          </option>
        </select>
      </label>
      <span class="dim" data-testid="kimp-jobs-page">
        第 {{ page }}/{{ totalPages }} 页 · 每页 {{ pageSize }} 条
      </span>
    </div>

    <p v-if="loading && jobs.length === 0" class="kimp-note" data-testid="kimp-jobs-loading">
      <WButton size="sm" :loading="true">加载中</WButton>
      正在读取导入任务（GET /api/v1/knowledge/imports）…
    </p>
    <p v-else-if="error" class="kimp-note kimp-note--warn" data-testid="kimp-jobs-error">
      <WTag tone="r" plain>列表不可用</WTag>{{ error }}
      <WButton size="sm" variant="ghost" @click="emit('refresh')">重试</WButton>
    </p>
    <p v-else-if="jobs.length === 0" class="kimp-note" data-testid="kimp-jobs-empty">
      <WTag tone="n" plain>暂无任务</WTag>
      还没有导入任务：选择上方任一方式创建任务；解析失败/已完成的任务也会留在这里（可回溯失败原因）。
    </p>

    <WTable
      :columns="columns"
      :rows="rows"
      row-key="jobId"
      dense
      :loading="loading && jobs.length === 0"
      :empty-text="status ? '当前状态下没有导入任务：可切换状态或刷新' : '还没有导入任务'"
    >
      <template #jobId="{ row }">
        <span class="num kimp-jobs__id" :data-testid="`kimp-job-id-${jobOf(row).jobId}`">{{ jobOf(row).jobId }}</span>
      </template>
      <template #status="{ row }">
        <span class="kimp-jobs__status" :data-testid="`kimp-job-status-${jobOf(row).jobId}`">
          <WTag :tone="IMPORT_STATUS_TONES[jobOf(row).status]">{{ statusShort(jobOf(row)) }}</WTag>
          <WTag v-if="isImportInFlight(jobOf(row).status)" tone="b" plain>进行中</WTag>
        </span>
        <span class="kimp-jobs__status-text">{{ statusLabel(jobOf(row)) }}</span>
      </template>
      <template #source="{ row }">
        <span>{{ sourceLabel(jobOf(row)) }}</span>
      </template>
      <template #title="{ row }">
        <div class="kimp-jobs__title">
          <b :data-testid="`kimp-job-title-${jobOf(row).jobId}`">{{ titleText(jobOf(row)) }}</b>
          <span v-if="jobOf(row).fileName && jobOf(row).fileName !== jobOf(row).title" class="dim">
            {{ jobOf(row).fileName }}
          </span>
        </div>
      </template>
      <template #size="{ row }">
        <span class="dim num">{{ jobSizeText(jobOf(row)) }}</span>
      </template>
      <template #createdBy="{ row }">
        <span>{{ jobOf(row).createdBy }}</span>
      </template>
      <template #createdAt="{ row }">
        <span class="dim num">{{ formatIsoMinute(jobOf(row).createdAt) || '—' }}</span>
      </template>
      <template #action="{ row }">
        <WButton size="sm" variant="ghost" :data-testid="`kimp-job-open-${jobOf(row).jobId}`" @click="emit('open', jobOf(row))">
          {{ jobOf(row).status === 'preview_ready' ? '预览' : '详情' }}
        </WButton>
      </template>
      <template #empty>
        <WEmpty
          compact
          icon="book"
          title="还没有导入任务"
          description="选择上方方式创建任务：粘贴文本 → 预览切分块 → 提交；上传文件/ZIP 后同样先预览再提交。"
        />
      </template>
      <template #footer>
        <div class="kimp-jobs__foot">
          <span>
            任务编号 IMP-YYYYMMDD-NNNN · 状态机 pending→parsing→preview_ready→committing→indexing→completed
            （failed/cancelled 为终态）
          </span>
          <span class="kimp-jobs__pager">
            <WButton size="sm" :disabled="!canPrev" data-testid="kimp-jobs-prev" @click="emit('page', page - 1)">
              上一页
            </WButton>
            <span class="num">{{ page }} / {{ totalPages }}</span>
            <WButton size="sm" :disabled="!canNext" data-testid="kimp-jobs-next" @click="emit('page', page + 1)">
              下一页
            </WButton>
          </span>
        </div>
      </template>
    </WTable>
  </WCard>
</template>

<style scoped>
.kimp-jobs__bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 10px;
}

.kimp-field {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-2xs);
  color: var(--c-faint);
}

.kimp-field select {
  height: 28px;
  padding: 0 8px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-sm);
  background: var(--c-card);
  font-size: var(--fs-xs);
  color: var(--c-ink-2);
}

.kimp-jobs__id {
  font-family: var(--font-mono);
  font-size: var(--fs-2xs);
  color: var(--c-ink-2);
}

.kimp-jobs__status {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.kimp-jobs__status-text {
  display: block;
  margin-top: 2px;
  font-size: 9px;
  color: var(--c-faint);
}

.kimp-jobs__title {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}

.kimp-jobs__title b {
  font-size: var(--fs-xs);
  font-weight: var(--fw-medium);
  color: var(--c-ink-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.kimp-jobs__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 10px;
  font-size: 9.5px;
  color: var(--c-faint);
}

.kimp-jobs__pager {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.kimp-note {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 8px;
  padding: 8px 10px;
  border-radius: var(--rd-sm);
  background: var(--c-bg-2);
  font-size: var(--fs-2xs);
  color: var(--c-muted);
  line-height: var(--lh-base);
}

.kimp-note--warn {
  border-left: 2px solid var(--c-red, #c0392b);
  background: var(--c-amber-soft);
  color: #9a4b05;
}
</style>
