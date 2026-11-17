<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { WButton, WTag, useToast } from '@/components'
import AdminModal from './AdminModal.vue'
import type { DegradeOption } from './types'

/**
 * 降级策略弹层（P1-033）
 * ----------------------------------------------------------------
 * 缺能力时不假装支持：三选一（跳转 ERP / 转企微 / 落人工池）+ 原因说明 + 备注，
 * 确认后给出可追溯的回执编号（DRG-日期-序号），并把策略 emit 给调用方。
 */
const props = withDefaults(
  defineProps<{
    open: boolean
    name?: string
    title?: string
    /** 缺失的能力名，例如 invoice.apply 发票申请 */
    capability: string
    /** 为什么触发降级，例如「金蝶 EAS 未开通 invoice.apply」 */
    reason: string
    options?: DegradeOption[]
    hint?: string
  }>(),
  {
    name: 'degrade',
    title: '降级策略',
    options: () => [
      { key: 'erp-jump', label: '跳转 ERP 自助办理', description: '给出 ERP 入口与操作指引，AI 不写 ERP、不承诺结果', tone: 'g' },
      { key: 'wecom', label: '转企微人工', description: '转给管家在企微 1v1 承接，群内不自动回复', tone: 'b' },
      { key: 'pool', label: '落人工池', description: '进入人工待办池，按 SLA 分派，AI 只做资料整理', tone: 'a' },
    ],
    hint: '',
  },
)

const emit = defineEmits<{
  'update:open': [value: boolean]
  confirm: [payload: { strategy: string; strategyLabel: string; note: string; receiptCode: string }]
  close: []
}>()

const toast = useToast()

const selected = ref(props.options[0]?.key ?? '')
const note = ref('')
const receipt = ref<{ code: string; label: string; note: string } | null>(null)
let seed = 0

watch(
  () => props.open,
  (open) => {
    if (open) {
      selected.value = props.options[0]?.key ?? ''
      note.value = ''
      receipt.value = null
    }
  },
  { immediate: true },
)

const selectedOption = computed(() => props.options.find((option) => option.key === selected.value))

function receiptCode(): string {
  seed += 1
  const now = new Date()
  const pad = (value: number) => String(value).padStart(2, '0')
  const stamp = `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}`
  return `DRG-${stamp}-${String(seed).padStart(3, '0')}`
}

function confirm() {
  const option = selectedOption.value
  if (!option) {
    toast.warning('请先选择一个降级去向')
    return
  }
  const code = receiptCode()
  receipt.value = { code, label: option.label, note: note.value.trim() }
  emit('confirm', { strategy: option.key, strategyLabel: option.label, note: note.value.trim(), receiptCode: code })
  toast.success(`降级已确认：${option.label} · 回执 ${code}（mock，未触发真实 ERP 写操作）`)
}
</script>

<template>
  <AdminModal :open="open" :name="name" :title="title" width="540px" @update:open="emit('update:open', $event)" @close="emit('close')">
    <div class="adm-kv">
      <span>缺失能力</span><b>{{ capability }}</b>
    </div>
    <div class="adm-kv">
      <span>原因</span><b>{{ reason }}</b>
    </div>

    <div class="adm-degrade" role="radiogroup" aria-label="降级去向">
      <button
        v-for="option in options"
        :key="option.key"
        type="button"
        role="radio"
        class="adm-degrade__option"
        :class="[`is-${option.tone ?? 'g'}`, { 'is-on': selected === option.key }]"
        :aria-checked="selected === option.key"
        :data-testid="`admin-degrade-option-${option.key}`"
        @click="selected = option.key"
      >
        <b>{{ option.label }}</b>
        <span>{{ option.description }}</span>
      </button>
    </div>

    <label class="adm-textarea">
      <span>补充说明（可选，会写入回执与留痕）</span>
      <textarea v-model="note" rows="2" placeholder="例如：已电话告知业主到 ERP 自助开票，工单挂起等待回执" />
    </label>

    <div v-if="receipt" class="adm-receipt" data-testid="admin-degrade-receipt">
      <WTag tone="g" plain>回执</WTag>
      <b class="mono">{{ receipt.code }}</b>
      <span>去向：{{ receipt.label }}</span>
      <span v-if="receipt.note">说明：{{ receipt.note }}</span>
      <em>已留痕（谁、何时、为何降级）；不写真实 ERP，不承诺结果。</em>
    </div>

    <p class="adm-note">
      <WTag tone="r" plain>红线</WTag>
      {{ hint || '缺能力就降级：不允许「界面有、实际不能用」的假支持；降级必须留痕并转人工兜底。' }}
    </p>

    <template #footer>
      <WButton size="sm" variant="ghost" @click="emit('update:open', false)">关闭</WButton>
      <WButton
        size="sm"
        variant="primary"
        :disabled="!!receipt"
        data-testid="admin-degrade-confirm"
        @click="confirm"
      >
        {{ receipt ? '已确认' : '确认降级' }}
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
  text-align: right;
}

.adm-degrade {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.adm-degrade__option {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px 10px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-sm);
  background: var(--c-card);
  text-align: left;
  transition: border-color var(--tr-fast), background-color var(--tr-fast);
}

.adm-degrade__option b {
  font-size: var(--fs-xs);
  color: var(--c-ink-2);
}

.adm-degrade__option span {
  font-size: var(--fs-2xs);
  color: var(--c-muted);
  line-height: var(--lh-base);
}

.adm-degrade__option:hover {
  border-color: var(--c-brand);
}

.adm-degrade__option.is-on {
  border-color: var(--c-brand);
  background: var(--c-brand-soft);
}

.adm-degrade__option.is-on b {
  color: var(--c-brand);
}

.adm-degrade__option:focus-visible {
  outline: none;
  box-shadow: var(--sh-ring);
}

.adm-textarea {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: var(--fs-2xs);
  color: var(--c-faint);
}

.adm-textarea textarea {
  padding: 7px 9px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-md);
  background: var(--c-card);
  font-family: inherit;
  font-size: var(--fs-sm);
  color: var(--c-ink);
  resize: vertical;
}

.adm-textarea textarea:focus {
  outline: none;
  border-color: var(--c-brand);
  box-shadow: var(--sh-ring);
}

.adm-receipt {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  padding: 8px 10px;
  border: 1px solid #bfe7db;
  border-radius: var(--rd-sm);
  background: var(--c-success-soft);
  font-size: var(--fs-2xs);
  color: #0a725f;
}

.adm-receipt b {
  font-family: var(--font-mono);
  color: var(--c-brand);
}

.adm-receipt em {
  font-style: normal;
  flex-basis: 100%;
  color: var(--c-muted);
}

.adm-note {
  display: flex;
  align-items: flex-start;
  gap: 4px;
  flex-wrap: wrap;
  padding: 7px 9px;
  border-radius: var(--rd-sm);
  background: var(--c-red-soft);
  color: #b42318;
  font-size: var(--fs-2xs);
  line-height: var(--lh-base);
}
</style>
