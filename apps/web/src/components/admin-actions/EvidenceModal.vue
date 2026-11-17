<script setup lang="ts">
import { computed } from 'vue'
import { WButton, WTag, useToast } from '@/components'
import AdminModal from './AdminModal.vue'
import { downloadCsv } from './csv'
import { useClipboard } from './clipboard'
import type { CsvRow } from './types'

/**
 * 证据包弹层（P1-033）
 * ----------------------------------------------------------------
 * 投诉举证 / 审计抽查 / badcase 复盘三合一的「一次导出」：
 * trace 摘要 + 知识引用（ID/版本/生效期/责任人）+ 调用瀑布摘要 + 材料清单；
 * traceId 一键复制（走 clipboard，失败提示权限），CSV 真实下载（BOM + UTF-8，已脱敏说明）。
 */
interface KvRow {
  label: string
  value: string
}

interface WaterfallRow {
  name: string
  ms: string
  sub?: string
}

const props = withDefaults(
  defineProps<{
    open: boolean
    name?: string
    title?: string
    traceId: string
    summary: KvRow[]
    knowledge: KvRow[]
    waterfall: WaterfallRow[]
    packItems?: string[]
    /** 文件名主体 */
    baseName?: string
    hint?: string
  }>(),
  { name: 'evidence', title: '导出证据包', packItems: () => [], baseName: '证据包', hint: '' },
)

const emit = defineEmits<{
  'update:open': [value: boolean]
  exported: [payload: { filename: string; rowCount: number }]
  close: []
}>()

const toast = useToast()
const { copy } = useClipboard()

const totalRows = computed(() => props.summary.length + props.knowledge.length + props.waterfall.length + props.packItems.length)

async function copyTrace() {
  await copy(props.traceId, ` trace id（${props.traceId}）`)
}

function exportPack() {
  const rows: CsvRow[] = [
    ...props.summary.map((row) => ({ section: 'trace 摘要', label: row.label, value: row.value })),
    ...props.knowledge.map((row) => ({ section: '知识引用', label: row.label, value: row.value })),
    ...props.waterfall.map((row) => ({ section: '调用瀑布', label: row.name, value: `${row.ms}${row.sub ? ` · ${row.sub}` : ''}` })),
    ...props.packItems.map((item, index) => ({ section: '材料清单', label: String(index + 1).padStart(2, '0'), value: item })),
  ]
  const result = downloadCsv({
    baseName: props.baseName,
    columns: [
      { key: 'section', title: '分组' },
      { key: 'label', title: '字段' },
      { key: 'value', title: '内容' },
    ],
    rows,
  })
  toast.success(`证据包已导出 ${result.rowCount} 行：${result.filename}（业主字段均为脱敏值）`)
  emit('exported', { filename: result.filename, rowCount: result.rowCount })
}
</script>

<template>
  <AdminModal :open="open" :name="name" :title="title" width="580px" @update:open="emit('update:open', $event)" @close="emit('close')">
    <div class="adm-kv">
      <span>trace id</span>
      <b class="mono" data-testid="admin-evidence-trace-id">{{ traceId }}</b>
    </div>

    <section class="adm-section" data-testid="admin-evidence-summary">
      <header class="adm-section__h"><b>trace 摘要</b><WTag tone="g" plain>可举证</WTag></header>
      <div v-for="row in summary" :key="row.label" class="adm-row">
        <span>{{ row.label }}</span><b>{{ row.value }}</b>
      </div>
    </section>

    <section class="adm-section" data-testid="admin-evidence-knowledge">
      <header class="adm-section__h"><b>知识引用</b><WTag tone="v" plain>版本可追溯</WTag></header>
      <div v-for="row in knowledge" :key="row.label" class="adm-row">
        <span>{{ row.label }}</span><b>{{ row.value }}</b>
      </div>
    </section>

    <section class="adm-section" data-testid="admin-evidence-waterfall">
      <header class="adm-section__h"><b>调用瀑布摘要</b><WTag tone="n">{{ waterfall.length }} 跳</WTag></header>
      <div v-for="row in waterfall" :key="row.name" class="adm-wf">
        <span>{{ row.name }}</span>
        <i><em :style="{ width: `${Math.min(100, Number(row.ms.replace(/[^\d.]/g, '')) / 12)}%` }" /></i>
        <b class="num">{{ row.ms }}</b>
      </div>
    </section>

    <section class="adm-section" data-testid="admin-evidence-pack">
      <header class="adm-section__h"><b>证据包清单</b><WTag tone="n">{{ packItems.length }} 项</WTag></header>
      <div v-for="(item, index) in packItems" :key="item" class="adm-pack-row">
        <b>{{ String(index + 1).padStart(2, '0') }}</b>{{ item }}
      </div>
    </section>

    <p class="adm-note">
      共 {{ totalRows }} 行材料 · 业主相关字段均为脱敏值 · 导出留痕（谁导出、何时导出）；
      {{ hint || '真实 trace/日志平台在 P2 接入，本页数据为 mock。' }}
    </p>

    <template #footer>
      <WButton size="sm" icon="copy" data-testid="admin-evidence-copy-trace" @click="copyTrace">复制 traceId</WButton>
      <WButton size="sm" variant="ghost" @click="emit('update:open', false)">关闭</WButton>
      <WButton size="sm" variant="primary" icon="download" data-testid="admin-evidence-export" @click="exportPack">
        导出 CSV
      </WButton>
    </template>
  </AdminModal>
</template>

<style scoped>
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
}

.mono {
  font-family: var(--font-mono);
  color: var(--c-brand);
}

.adm-section {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 8px 10px;
  border: 1px solid var(--c-line-2);
  border-radius: var(--rd-sm);
}

.adm-section__h {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 2px;
  font-size: var(--fs-2xs);
  color: var(--c-muted);
}

.adm-section__h b {
  font-size: var(--fs-xs);
  font-weight: var(--fw-semibold);
  color: var(--c-ink-2);
}

.adm-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  font-size: var(--fs-2xs);
}

.adm-row span {
  flex: none;
  color: var(--c-faint);
}

.adm-row b {
  font-weight: var(--fw-medium);
  color: var(--c-ink-2);
  text-align: right;
}

.adm-wf {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-2xs);
  color: var(--c-muted);
}

.adm-wf > span {
  flex: none;
  width: 92px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.adm-wf i {
  flex: 1;
  height: 5px;
  border-radius: var(--rd-pill);
  background: var(--c-line-2);
  overflow: hidden;
}

.adm-wf em {
  display: block;
  height: 100%;
  border-radius: var(--rd-pill);
  background: var(--c-brand-gradient);
}

.adm-wf b {
  flex: none;
  width: 46px;
  text-align: right;
  color: var(--c-ink-2);
}

.adm-pack-row {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-2xs);
  color: var(--c-muted);
}

.adm-pack-row b {
  color: var(--c-faint);
  font-weight: var(--fw-regular);
}

.adm-note {
  font-size: var(--fs-2xs);
  color: var(--c-faint);
  line-height: var(--lh-base);
}
</style>
