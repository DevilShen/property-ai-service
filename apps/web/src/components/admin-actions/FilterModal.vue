<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { WButton, WTag } from '@/components'
import AdminModal from './AdminModal.vue'
import type { FilterField, FilterValues } from './types'

/**
 * 字段驱动筛选弹层（P1-033）
 * ----------------------------------------------------------------
 * - 字段定义数组（select / multi / range 三种类型）决定 UI，六个视图共用同一套筛选交互；
 * - 「确认」emit('apply', values) 由调用方回填到列表/统计；「重置」回到默认并 emit('reset')；
 * - 传入 preview(values) 时，底部实时显示「将筛选出 N 条」，避免确认后才发现是空结果。
 */
const props = withDefaults(
  defineProps<{
    open: boolean
    name?: string
    title?: string
    fields: FilterField[]
    /** 调用方当前生效的条件 */
    modelValue?: FilterValues
    /** 实时预览匹配数量（调用方传入过滤函数） */
    preview?: (values: FilterValues) => number
    /** preview 缺省时的兜底数量 */
    resultCount?: number
    resultUnit?: string
    confirmText?: string
    hint?: string
  }>(),
  {
    name: 'filter',
    title: '筛选',
    modelValue: () => ({}),
    preview: undefined,
    resultCount: undefined,
    resultUnit: '条',
    confirmText: '应用筛选',
    hint: '',
  },
)

const emit = defineEmits<{
  'update:open': [value: boolean]
  apply: [values: FilterValues]
  reset: [values: FilterValues]
  close: []
}>()

const draft = ref<FilterValues>({})

function defaultValue(field: FilterField): string | string[] | [number, number] {
  const current = props.modelValue[field.key]
  if (field.type === 'multi') return Array.isArray(current) ? [...(current as string[])] : []
  if (field.type === 'range') {
    if (Array.isArray(current) && current.length === 2 && typeof current[0] === 'number' && typeof current[1] === 'number') {
      return [current[0], current[1]] as [number, number]
    }
    return [field.min ?? 0, field.max ?? 100] as [number, number]
  }
  return typeof current === 'string' ? current : ''
}

function buildDraft(): FilterValues {
  const next: FilterValues = {}
  for (const field of props.fields) next[field.key] = defaultValue(field)
  return next
}

watch(
  () => props.open,
  (open) => {
    if (open) draft.value = buildDraft()
  },
  { immediate: true },
)

function stringValue(key: string): string {
  const value = draft.value[key]
  return typeof value === 'string' ? value : ''
}

function multiValue(key: string): string[] {
  const value = draft.value[key]
  return Array.isArray(value) ? (value as string[]) : []
}

function rangeValue(field: FilterField, edge: 0 | 1): number {
  const value = draft.value[field.key]
  if (Array.isArray(value) && value.length === 2 && typeof value[edge] === 'number') return value[edge] as number
  return edge === 0 ? (field.min ?? 0) : (field.max ?? 100)
}

function setString(key: string, value: string) {
  draft.value = { ...draft.value, [key]: value }
}

function toggleMulti(key: string, value: string) {
  const current = multiValue(key)
  const next = current.includes(value) ? current.filter((item) => item !== value) : [...current, value]
  draft.value = { ...draft.value, [key]: next }
}

function setRange(field: FilterField, edge: 0 | 1, event: Event) {
  const raw = Number((event.target as HTMLInputElement).value)
  const next: [number, number] = [rangeValue(field, 0), rangeValue(field, 1)]
  if (Number.isFinite(raw)) next[edge] = raw
  if (next[0] > next[1]) {
    if (edge === 0) next[1] = next[0]
    else next[0] = next[1]
  }
  draft.value = { ...draft.value, [field.key]: next }
}

const previewCount = computed<number | undefined>(() => {
  if (props.preview) return props.preview(draft.value)
  return props.resultCount
})

const activeCount = computed(() =>
  props.fields.filter((field) => {
    const value = draft.value[field.key]
    if (field.type === 'multi') return Array.isArray(value) && value.length > 0
    if (field.type === 'range') {
      const min = field.min ?? 0
      const max = field.max ?? 100
      return Array.isArray(value) && (value[0] !== min || value[1] !== max)
    }
    return typeof value === 'string' && value !== ''
  }).length,
)

function confirm() {
  emit('apply', cloneValues(draft.value))
  emit('update:open', false)
}

function reset() {
  const defaults = buildDefaults()
  draft.value = defaults
  emit('reset', cloneValues(defaults))
  emit('update:open', false)
}

function buildDefaults(): FilterValues {
  const next: FilterValues = {}
  for (const field of props.fields) {
    if (field.type === 'multi') next[field.key] = []
    else if (field.type === 'range') next[field.key] = [field.min ?? 0, field.max ?? 100]
    else next[field.key] = ''
  }
  return next
}

function cloneValues(values: FilterValues): FilterValues {
  const next: FilterValues = {}
  for (const [key, value] of Object.entries(values)) {
    next[key] = Array.isArray(value) ? ([...value] as string[] | [number, number]) : value
  }
  return next
}

function close() {
  emit('update:open', false)
}
</script>

<template>
  <AdminModal :open="open" :name="name" :title="title" width="520px" @update:open="emit('update:open', $event)" @close="emit('close')">
    <div v-for="field in fields" :key="field.key" class="adm-field" :data-testid="`admin-filter-field-${field.key}`">
      <label class="adm-field__label">
        <span>{{ field.label }}</span>
        <em v-if="field.hint">{{ field.hint }}</em>
      </label>

      <select
        v-if="field.type === 'select'"
        class="adm-select"
        :data-testid="`admin-filter-${field.key}`"
        :value="stringValue(field.key)"
        @change="setString(field.key, ($event.target as HTMLSelectElement).value)"
      >
        <option value="">{{ field.emptyLabel ?? '全部' }}</option>
        <option v-for="option in field.options ?? []" :key="option.value" :value="option.value">
          {{ option.label }}
        </option>
      </select>

      <div v-else-if="field.type === 'multi'" class="adm-chips">
        <button
          v-for="option in field.options ?? []"
          :key="option.value"
          type="button"
          class="adm-chip"
          :class="{ 'is-on': multiValue(field.key).includes(option.value) }"
          :aria-pressed="multiValue(field.key).includes(option.value)"
          :data-testid="`admin-filter-${field.key}-${option.value}`"
          @click="toggleMulti(field.key, option.value)"
        >
          {{ option.label }}
        </button>
        <span v-if="!multiValue(field.key).length" class="adm-field__empty">不限（可多选）</span>
      </div>

      <div v-else class="adm-range">
        <input
          class="adm-range__input num"
          type="number"
          :min="field.min"
          :max="field.max"
          :step="field.step ?? 1"
          :value="rangeValue(field, 0)"
          :data-testid="`admin-filter-${field.key}-min`"
          @input="setRange(field, 0, $event)"
        />
        <span class="adm-range__sep">—</span>
        <input
          class="adm-range__input num"
          type="number"
          :min="field.min"
          :max="field.max"
          :step="field.step ?? 1"
          :value="rangeValue(field, 1)"
          :data-testid="`admin-filter-${field.key}-max`"
          @input="setRange(field, 1, $event)"
        />
        <span class="adm-range__unit">{{ field.unit }}</span>
      </div>
    </div>

    <div class="adm-filter__foot">
      <WTag tone="n" plain>已设 {{ activeCount }} 个条件</WTag>
      <span v-if="previewCount !== undefined" class="adm-filter__preview" data-testid="admin-filter-preview">
        将筛选出 <b class="num">{{ previewCount }}</b> {{ resultUnit }}
      </span>
      <span v-else class="adm-filter__preview">确认后列表与统计同步更新</span>
    </div>

    <p v-if="hint" class="adm-note">{{ hint }}</p>

    <template #footer>
      <WButton size="sm" variant="ghost" data-testid="admin-filter-reset" @click="reset">重置</WButton>
      <WButton size="sm" variant="ghost" @click="close">取消</WButton>
      <WButton size="sm" variant="primary" data-testid="admin-filter-confirm" @click="confirm">{{ confirmText }}</WButton>
    </template>
  </AdminModal>
</template>

<style scoped>
.adm-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.adm-field__label {
  display: flex;
  align-items: baseline;
  gap: 6px;
  font-size: var(--fs-2xs);
  color: var(--c-faint);
}

.adm-field__label em {
  font-style: normal;
  color: var(--c-faint);
  opacity: 0.85;
}

.adm-field__empty {
  font-size: var(--fs-2xs);
  color: var(--c-faint);
}

.adm-select,
.adm-range__input {
  height: 30px;
  padding: 0 9px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-md);
  background: var(--c-card);
  font-size: var(--fs-sm);
  color: var(--c-ink);
}

.adm-select:focus,
.adm-range__input:focus {
  outline: none;
  border-color: var(--c-brand);
  box-shadow: var(--sh-ring);
}

.adm-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.adm-chip {
  height: 24px;
  padding: 0 10px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-pill);
  background: var(--c-card);
  font-size: var(--fs-2xs);
  color: var(--c-muted);
  transition: border-color var(--tr-fast), background-color var(--tr-fast), color var(--tr-fast);
}

.adm-chip:hover {
  border-color: var(--c-brand);
  color: var(--c-brand);
}

.adm-chip.is-on {
  border-color: var(--c-brand);
  background: var(--c-brand-soft);
  color: var(--c-brand);
  font-weight: var(--fw-semibold);
}

.adm-chip:focus-visible {
  outline: none;
  box-shadow: var(--sh-ring);
}

.adm-range {
  display: flex;
  align-items: center;
  gap: 6px;
}

.adm-range__input {
  width: 108px;
}

.adm-range__sep {
  color: var(--c-faint);
}

.adm-range__unit {
  font-size: var(--fs-2xs);
  color: var(--c-muted);
}

.adm-filter__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-2);
  flex-wrap: wrap;
  padding: 8px 10px;
  border-radius: var(--rd-sm);
  background: var(--c-bg-2);
  font-size: var(--fs-2xs);
  color: var(--c-muted);
}

.adm-filter__preview b {
  color: var(--c-brand);
  font-weight: var(--fw-semibold);
}

.adm-note {
  font-size: var(--fs-2xs);
  color: var(--c-faint);
  line-height: var(--lh-base);
}
</style>
