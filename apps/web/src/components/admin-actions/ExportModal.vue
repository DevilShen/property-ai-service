<script setup lang="ts">
import { computed } from 'vue'
import { WButton, WTag, useToast } from '@/components'
import AdminModal from './AdminModal.vue'
import { buildCsvFilename, downloadCsv } from './csv'
import type { CsvColumn, CsvRow } from './types'

/**
 * 通用 CSV 导出弹层（P1-033）
 * ----------------------------------------------------------------
 * - 先给用户看清「导出什么、多少行、哪些字段、文件名」，再确认；
 * - 确认后真实触发浏览器下载（Blob + a.download，BOM + UTF-8），并 Toast「已导出 N 行」；
 * - 文件名统一 `xxx-20261114.csv`（主体由调用方传入，日期取当天）。
 */
const props = withDefaults(
  defineProps<{
    open: boolean
    name?: string
    title?: string
    /** 文件名主体，例如「欠费台账」→ 欠费台账-20261114.csv */
    baseName: string
    columns: CsvColumn[]
    rows: CsvRow[]
    summary?: string
    hint?: string
    /** 覆盖文件名（可选，默认 baseName + 今天） */
    filename?: string
  }>(),
  { name: 'export', title: '导出 CSV', summary: '', hint: '', filename: '' },
)

const emit = defineEmits<{
  'update:open': [value: boolean]
  exported: [payload: { filename: string; rowCount: number }]
  close: []
}>()

const toast = useToast()

const filename = computed(() => props.filename || buildCsvFilename(props.baseName))
const rowCount = computed(() => props.rows.length)
const canExport = computed(() => props.rows.length > 0 && props.columns.length > 0)

function confirm() {
  if (!canExport.value) {
    toast.warning('当前没有可导出的数据：请调整筛选条件后重试')
    return
  }

  const result = downloadCsv({ baseName: props.baseName, columns: props.columns, rows: props.rows, filename: filename.value })
  toast.success(`已导出 ${result.rowCount} 行：${result.filename}（含 BOM，Excel 直接打开不乱码）`)
  emit('exported', { filename: result.filename, rowCount: result.rowCount })
  emit('update:open', false)
}
</script>

<template>
  <AdminModal :open="open" :name="name" :title="title" width="520px" @update:open="emit('update:open', $event)" @close="emit('close')">
    <div class="adm-export">
      <div class="adm-kv" data-testid="admin-export-filename">
        <span>文件名</span><b class="mono">{{ filename }}</b>
      </div>
      <div class="adm-kv" data-testid="admin-export-rows">
        <span>导出行数</span><b class="num">{{ rowCount }} 行</b>
      </div>
      <div class="adm-kv">
        <span>编码</span><b>UTF-8 · 带 BOM（Excel 直接打开）</b>
      </div>
      <p v-if="summary" class="adm-note">{{ summary }}</p>
    </div>

    <div class="adm-fields">
      <span class="adm-fields__lab">字段（{{ columns.length }}）</span>
      <WTag v-for="column in columns" :key="column.key" tone="n" plain>{{ column.title }}</WTag>
    </div>

    <p v-if="!canExport" class="adm-warn">当前筛选结果为空，导出按钮不可用；请放宽筛选条件。</p>
    <p v-if="hint" class="adm-note">{{ hint }}</p>

    <template #footer>
      <WButton size="sm" variant="ghost" @click="emit('update:open', false)">取消</WButton>
      <WButton
        size="sm"
        variant="primary"
        icon="download"
        :disabled="!canExport"
        data-testid="admin-export-confirm"
        @click="confirm"
      >
        确认导出
      </WButton>
    </template>
  </AdminModal>
</template>

<style scoped>
.adm-export {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.adm-kv {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--sp-3);
  padding: 7px 10px;
  border-radius: var(--rd-sm);
  background: var(--c-bg-2);
  font-size: var(--fs-xs);
}

.adm-kv span {
  flex: none;
  color: var(--c-faint);
}

.adm-kv b {
  font-weight: var(--fw-medium);
  color: var(--c-ink-2);
  text-align: right;
  word-break: break-all;
}

.mono {
  font-family: var(--font-mono);
  font-size: var(--fs-2xs);
}

.adm-fields {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
  padding: 8px 10px;
  border: 1px dashed var(--c-line);
  border-radius: var(--rd-sm);
}

.adm-fields__lab {
  margin-right: 4px;
  font-size: var(--fs-2xs);
  color: var(--c-faint);
}

.adm-note {
  font-size: var(--fs-2xs);
  color: var(--c-faint);
  line-height: var(--lh-base);
}

.adm-warn {
  padding: 6px 9px;
  border-radius: var(--rd-sm);
  background: var(--c-amber-soft);
  color: #9a4b05;
  font-size: var(--fs-2xs);
  line-height: var(--lh-base);
}
</style>
