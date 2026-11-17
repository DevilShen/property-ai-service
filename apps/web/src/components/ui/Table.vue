<script setup lang="ts">
import type { TableColumn } from '@/types'
import WLoading from './Loading.vue'
import WEmpty from './Empty.vue'

/**
 * 共享表格：轻量封装，列配置 + 行数据 + 具名插槽（插槽名 = 列 key）。
 * 无数据时展示加载/空态；文案可由调用方通过 `emptyText` 或 empty 插槽覆盖。
 */
withDefaults(
  defineProps<{
    columns: TableColumn[]
    rows: Record<string, unknown>[]
    rowKey?: string
    loading?: boolean
    emptyText?: string
    /** 紧凑行高，用于看板/清单类密集表格 */
    dense?: boolean
  }>(),
  { rowKey: '', loading: false, emptyText: '当前筛选条件下没有记录，可调整筛选后重试', dense: false },
)

function formatCell(value: unknown): string {
  if (value === null || value === undefined || value === '') return '—'
  return String(value)
}
</script>

<template>
  <div class="w-table-wrap">
    <table class="w-table" :class="{ 'is-dense': dense }">
      <thead>
        <tr>
          <th
            v-for="column in columns"
            :key="column.key"
            :style="{ width: column.width, textAlign: column.align ?? 'left' }"
            scope="col"
          >
            {{ column.title }}
          </th>
        </tr>
      </thead>
      <tbody>
        <tr v-if="loading">
          <td :colspan="columns.length" class="w-table__state">
            <WLoading label="加载中…" />
          </td>
        </tr>
        <tr v-else-if="rows.length === 0">
          <td :colspan="columns.length" class="w-table__state">
            <slot name="empty">
              <WEmpty title="暂无数据" :description="emptyText" />
            </slot>
          </td>
        </tr>
        <tr v-for="(row, rowIndex) in rows" v-else :key="rowKey ? String(row[rowKey]) : rowIndex">
          <td
            v-for="column in columns"
            :key="column.key"
            :style="{ textAlign: column.align ?? 'left' }"
            :class="{ tnum: column.align === 'right' }"
          >
            <slot :name="column.key" :row="row" :value="row[column.key]" :index="rowIndex">
              {{ formatCell(row[column.key]) }}
            </slot>
          </td>
        </tr>
      </tbody>
    </table>
    <div v-if="$slots.footer" class="w-table__foot"><slot name="footer" /></div>
  </div>
</template>

<style scoped>
.w-table-wrap {
  overflow-x: auto;
}

.w-table {
  width: 100%;
  border-collapse: separate;
  border-spacing: 0;
  font-size: var(--fs-sm);
}

.w-table th {
  position: sticky;
  top: 0;
  z-index: 1;
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

.w-table td {
  padding: 8px 12px;
  border-bottom: 1px solid #f0f3f5;
  color: var(--c-ink-2);
  vertical-align: middle;
}

.w-table.is-dense td {
  padding: 6px 10px;
}

.w-table tbody tr:last-child td {
  border-bottom: 0;
}

.w-table tbody tr:hover td {
  background: var(--c-bg-2);
}

.w-table .tnum {
  font-variant-numeric: tabular-nums;
}

.w-table__state {
  padding: 0 !important;
}

.w-table__foot {
  padding: 10px 12px;
  border-top: 1px solid var(--c-line-2);
  font-size: var(--fs-xs);
  color: var(--c-faint);
}
</style>
