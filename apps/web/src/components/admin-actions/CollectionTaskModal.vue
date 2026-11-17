<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { WButton, WTag, useToast } from '@/components'
import AdminModal from './AdminModal.vue'
import type { CollectionChannel, CollectionTaskPayload } from './types'

/**
 * 催缴任务弹层（P1-033）
 * ----------------------------------------------------------------
 * 分层多选 + 渠道 + 发送时间 + 话术预览 + 红线提示；
 * 确认后 emit('created')，由调用方 Toast 回执并登记（mock，不真实发送、不碰钱）。
 */
interface TierOption {
  label: string
  count: number
}

const props = withDefaults(
  defineProps<{
    open: boolean
    name?: string
    title?: string
    subject?: { name: string; room: string; tier: string; period: string; amount: string }
    tiers?: TierOption[]
    channels?: CollectionChannel[]
    defaultChannels?: string[]
    script?: string
    tone?: string
  }>(),
  {
    name: 'collection-task',
    title: '生成催缴任务',
    subject: () => ({ name: '—', room: '—', tier: '—', period: '—', amount: '—' }),
    tiers: () => [],
    channels: () => [
      { key: 'wecom', label: '企微私信' },
      { key: 'sms', label: '短信' },
      { key: 'call', label: '电话外呼（红线）', disabled: true, note: '22:00—8:00 不外呼 · 单户每周 ≤2 次 · 需主管确认' },
    ],
    defaultChannels: () => ['wecom', 'sms'],
    script: '',
    tone: '',
  },
)

const emit = defineEmits<{
  'update:open': [value: boolean]
  created: [payload: CollectionTaskPayload]
  close: []
}>()

const toast = useToast()

const SEND_TIMES = ['今天 18:00（推送前需人工确认）', '明天 10:00', '本周六 10:00（避开工作日打扰）']

const selectedTiers = ref<string[]>([])
const selectedChannels = ref<string[]>([])
const sendAt = ref(SEND_TIMES[0])

const subjectTier = computed(() => props.subject.tier)

function initialTiers(): string[] {
  const labels = props.tiers.map((item) => item.label)
  if (subjectTier.value !== '—' && labels.includes(subjectTier.value)) return [subjectTier.value]
  return labels.length ? [labels[0]!] : []
}

watch(
  () => props.open,
  (open) => {
    if (open) {
      selectedTiers.value = initialTiers()
      selectedChannels.value = props.defaultChannels.filter((key) => !props.channels.find((item) => item.key === key)?.disabled)
      sendAt.value = SEND_TIMES[0]
    }
  },
  { immediate: true },
)

function toggleTier(label: string) {
  selectedTiers.value = selectedTiers.value.includes(label)
    ? selectedTiers.value.filter((item) => item !== label)
    : [...selectedTiers.value, label]
}

function toggleChannel(channel: CollectionChannel) {
  if (channel.disabled) {
    toast.warning(`「${channel.label}」不允许在此处开启：${channel.note ?? '红线渠道需主管确认'}（AI 不做语音外呼）`)
    return
  }
  selectedChannels.value = selectedChannels.value.includes(channel.key)
    ? selectedChannels.value.filter((item) => item !== channel.key)
    : [...selectedChannels.value, channel.key]
}

function confirm() {
  if (!selectedTiers.value.length) {
    toast.warning('至少选择一个催缴分层，避免对全量业主无差别打扰')
    return
  }
  if (!selectedChannels.value.length) {
    toast.warning('至少选择一个可用渠道（企微私信 / 短信）')
    return
  }
  const payload: CollectionTaskPayload = {
    tiers: [...selectedTiers.value],
    channels: [...selectedChannels.value],
    sendAt: sendAt.value,
    script: props.script,
  }
  emit('created', payload)
  emit('update:open', false)
}
</script>

<template>
  <AdminModal :open="open" :name="name" :title="title" width="560px" @update:open="emit('update:open', $event)" @close="emit('close')">
    <div class="adm-kv">
      <span>对象</span><b>{{ subject.name }} · {{ subject.room }}</b>
    </div>
    <div class="adm-kv">
      <span>分层 / 账期</span><b>{{ subject.tier }} · {{ subject.period }} · {{ subject.amount }}</b>
    </div>
    <div class="adm-kv" v-if="tone">
      <span>话术语气</span><b>{{ tone }}</b>
    </div>

    <section class="adm-block">
      <header class="adm-block__h"><b>催缴分层（可多选）</b><span>越往后，人介入越深；90 天+ 只整理证据链</span></header>
      <div class="adm-chips">
        <button
          v-for="(item, index) in tiers"
          :key="item.label"
          type="button"
          class="adm-chip"
          :class="{ 'is-on': selectedTiers.includes(item.label) }"
          :aria-pressed="selectedTiers.includes(item.label)"
          :data-testid="`admin-collection-tier-${index}`"
          @click="toggleTier(item.label)"
        >
          {{ item.label }}<em>{{ item.count }}</em>
        </button>
        <span v-if="!tiers.length" class="adm-muted">当前对象分层：{{ subject.tier }}</span>
      </div>
    </section>

    <section class="adm-block">
      <header class="adm-block__h"><b>触达渠道</b><span>群内不自动回复；不碰钱（缴费只跳转）</span></header>
      <div class="adm-chips">
        <button
          v-for="channel in channels"
          :key="channel.key"
          type="button"
          class="adm-chip"
          :class="{ 'is-on': selectedChannels.includes(channel.key), 'is-off': channel.disabled }"
          :aria-pressed="selectedChannels.includes(channel.key)"
          :data-testid="`admin-collection-channel-${channel.key}`"
          @click="toggleChannel(channel)"
        >
          {{ channel.label }}
        </button>
      </div>
    </section>

    <label class="adm-field">
      <span>发送时间</span>
      <select v-model="sendAt" data-testid="admin-collection-sendat">
        <option v-for="item in SEND_TIMES" :key="item" :value="item">{{ item }}</option>
      </select>
    </label>

    <section class="adm-block">
      <header class="adm-block__h"><b>话术预览</b><WTag tone="v" plain>AI 生成 · 人工确认后发送</WTag></header>
      <p class="adm-script" data-testid="admin-collection-script">{{ script || '（未选择对象，暂无话术）' }}</p>
    </section>

    <p class="adm-redline">
      <WTag tone="r" plain>红线</WTag>
      不做群内自动回复 · 不碰钱（缴费只跳转，减免/滞纳金豁免转人工）· 22:00—8:00 不外呼 · 单户每周 ≤2 次 · 不公开欠费名单。
    </p>

    <template #footer>
      <WButton size="sm" variant="ghost" @click="emit('update:open', false)">取消</WButton>
      <WButton size="sm" variant="primary" icon="check" data-testid="admin-collection-confirm" @click="confirm">确认生成任务</WButton>
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

.adm-block {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.adm-block__h {
  display: flex;
  align-items: baseline;
  gap: 6px;
  flex-wrap: wrap;
  font-size: var(--fs-2xs);
  color: var(--c-faint);
}

.adm-block__h b {
  font-size: var(--fs-xs);
  font-weight: var(--fw-semibold);
  color: var(--c-ink-2);
}

.adm-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.adm-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 24px;
  padding: 0 10px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-pill);
  background: var(--c-card);
  font-size: var(--fs-2xs);
  color: var(--c-muted);
  transition: border-color var(--tr-fast), background-color var(--tr-fast), color var(--tr-fast);
}

.adm-chip em {
  font-style: normal;
  color: var(--c-faint);
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

.adm-chip.is-off {
  border-style: dashed;
  color: var(--c-faint);
}

.adm-chip:focus-visible {
  outline: none;
  box-shadow: var(--sh-ring);
}

.adm-muted {
  font-size: var(--fs-2xs);
  color: var(--c-faint);
}

.adm-field {
  display: flex;
  flex-direction: column;
  gap: 3px;
  font-size: var(--fs-2xs);
  color: var(--c-faint);
}

.adm-field select {
  height: 30px;
  padding: 0 9px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-md);
  background: var(--c-card);
  font-size: var(--fs-sm);
  color: var(--c-ink);
}

.adm-field select:focus {
  outline: none;
  border-color: var(--c-brand);
  box-shadow: var(--sh-ring);
}

.adm-script {
  padding: 8px 10px;
  border: 1px solid var(--c-line-2);
  border-radius: var(--rd-md);
  background: var(--c-bg-2);
  font-size: var(--fs-xs);
  color: var(--c-ink-2);
  line-height: var(--lh-loose);
}

.adm-redline {
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
