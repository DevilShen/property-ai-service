<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { WButton, WCard, WEmpty, WIcon, WLoading, WPageHeader, WTag, useToast } from '@/components'
import { COPILOT_WORKSPACES } from '@/app/copilot'
import { api, errorNextStep, errorText, forbiddenDetailsOf } from '@/api'
import type { CopilotEmotion, CopilotScript, CopilotTone, TicketPushChannel } from '@/api'
import { capabilityLabel } from '@/auth/capabilities'
import { COPILOT_RULE_NOTE, copyText } from '../shared'

/**
 * Copilot 工作区 2/8 · 话术生成（P4 W16，契约 v1.2.0 `POST /copilot/script`）
 * ===========================================================================
 * - 条件：场景（自由输入/预设）× 业主情绪（`CopilotEmotion`）× 渠道（`TicketPushChannel`）；
 * - 结果：**固定 3 版**话术（gentle/standard/firm，页面按「友好 / 标准 / 坚定」展示），
 *   每版带 `forbiddenHits`（禁区词命中）：命中非空时**高亮命中词并提示不可直接外发**；
 * - 只生成不外发（触达必须人工确认；群内不自动回复）——本页没有「发送」按钮，只有复制；
 * - `mock:true` = 平台规则组装（模型调用由 Python 编排层），页面强制标注。
 */
const workspace = COPILOT_WORKSPACES.script
const toast = useToast()

const SCENES = ['装修噪声', '催缴首响', '投诉安抚', '上门预约', '停水通知', '完工回访'] as const

/** 情绪选项来自契约枚举 `CopilotEmotion`（顺序固定，禁止另写一套） */
const EMOTIONS: ReadonlyArray<{ key: CopilotEmotion; label: string }> = [
  { key: 'calm', label: '平静' },
  { key: 'anxious', label: '焦虑' },
  { key: 'angry', label: '激动' },
  { key: 'neutral', label: '未知/中性' },
]

/** 渠道复用推送通道枚举（只影响文案长度/格式，不触达） */
const CHANNELS: ReadonlyArray<{ key: TicketPushChannel; label: string }> = [
  { key: 'wecom', label: '企微私信' },
  { key: 'sms', label: '短信' },
  { key: 'inapp', label: 'App 站内信' },
]

/** 语气档位 → 中文（友好 / 标准 / 坚定）；顺序固定 gentle→standard→firm */
const TONE_META: Readonly<Record<CopilotTone, { label: string; usage: string; tone: 'g' | 'b' | 'a' }>> = {
  gentle: { label: '友好', usage: '适合首次提醒 / 关系良好', tone: 'g' },
  standard: { label: '标准', usage: '适合常规沟通 / 流程告知', tone: 'b' },
  firm: { label: '坚定', usage: '适合反复沟通 / 明确时限', tone: 'a' },
}

const scenario = ref<string>(SCENES[0])
const emotion = ref<CopilotEmotion>('anxious')
const channel = ref<TicketPushChannel>('wecom')
const loading = ref(false)
const result = ref<CopilotScript | null>(null)
const error = ref('')
const denied = ref<{ requiredCapability: string; role: string } | null>(null)
const generatedAt = ref('')

const emotionLabel = computed(() => EMOTIONS.find((item) => item.key === emotion.value)?.label ?? '—')
const channelLabel = computed(() => CHANNELS.find((item) => item.key === channel.value)?.label ?? '—')

interface VersionView {
  tone: CopilotTone
  label: string
  usage: string
  toneTag: 'g' | 'b' | 'a'
  content: string
  hits: string[]
  /** 命中词高亮分段（`hit` 段用 <mark> 标红） */
  segments: Array<{ text: string; hit: boolean }>
}

function segmentsOf(content: string, hits: string[]): Array<{ text: string; hit: boolean }> {
  const clean = hits.map((hit) => hit.trim()).filter(Boolean)
  if (clean.length === 0) return [{ text: content, hit: false }]
  // 用命中词切分正文（长词优先，避免短词抢先匹配）
  const ordered = [...clean].sort((a, b) => b.length - a.length)
  const escaped = ordered.map((hit) => hit.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'))
  const pattern = new RegExp(`(${escaped.join('|')})`, 'g')
  return content
    .split(pattern)
    .filter((part) => part !== '')
    .map((part) => ({ text: part, hit: ordered.includes(part) }))
}

const versions = computed<VersionView[]>(() => {
  const script = result.value
  if (!script) return []
  return script.versions.map((version) => {
    const meta = TONE_META[version.tone]
    return {
      tone: version.tone,
      label: meta.label,
      usage: meta.usage,
      toneTag: meta.tone,
      content: version.content,
      hits: version.forbiddenHits ?? [],
      segments: segmentsOf(version.content, version.forbiddenHits ?? []),
    }
  })
})

const hasForbidden = computed(() => versions.value.some((version) => version.hits.length > 0))

async function generate(): Promise<void> {
  const scene = scenario.value.trim()
  if (!scene) {
    toast.warning('请先填写场景（如「装修噪声」「催缴首响」）')
    return
  }
  loading.value = true
  error.value = ''
  denied.value = null
  try {
    const { data } = await api.copilot.script({ scenario: scene, emotion: emotion.value, channel: channel.value })
    result.value = data
    generatedAt.value = new Date().toISOString()
    if (data.versions.some((version) => (version.forbiddenHits ?? []).length > 0)) {
      toast.warning('本次话术命中禁区词（已在正文高亮）：改写后才能对外使用，不得直接复制外发')
    } else {
      toast.success(`已生成 3 版话术（${data.versions.length} 版 · 禁区扫描通过）；发送前仍需人工确认事实`)
    }
  } catch (err) {
    result.value = null
    const forbidden = forbiddenDetailsOf(err)
    if (forbidden) {
      denied.value = { requiredCapability: forbidden.requiredCapability || 'copilot.use', role: forbidden.role }
      return
    }
    const code = err instanceof Error && 'code' in err ? String(err.code) : 'E-9001'
    error.value = `话术生成失败（${errorText(code)}；${errorNextStep(code)}）`
  } finally {
    loading.value = false
  }
}

async function copyVersion(version: VersionView): Promise<void> {
  if (version.hits.length > 0) {
    toast.warning(`「${version.label}版」命中禁区词（${version.hits.join(' / ')}）：请先改写再外发，已取消复制`)
    return
  }
  const ok = await copyText(version.content)
  if (ok) toast.success(`已复制${version.label}版话术；发送前请人工确认房号/金额/时间点`)
  else toast.error('复制失败，请检查浏览器剪贴板权限')
}

onMounted(() => {
  // 首屏不自动生成：避免把预置结果当成用户请求（生成是写操作，落审计）
})
</script>

<template>
  <div class="cop-page" data-page="copilot" data-testid="copilot-script">
    <WPageHeader :eyebrow="`04 · Copilot · ${workspace.label}`" :title="workspace.label" :description="workspace.description">
      <template #actions>
        <WTag tone="a">{{ COPILOT_RULE_NOTE }}</WTag>
        <WTag tone="r">只生成不外发</WTag>
        <WButton size="sm" variant="primary" icon="sparkles" :loading="loading" data-testid="copilot-script-generate" @click="generate">
          生成 3 版话术
        </WButton>
      </template>
    </WPageHeader>

    <WCard title="生成条件" subtitle="场景 × 业主情绪 × 渠道 三组条件共同决定语气与结构（POST /api/v1/copilot/script）">
      <div class="sc-row">
        <span class="sc-label">场景</span>
        <div class="cop-chips">
          <button
            v-for="item in SCENES"
            :key="item"
            type="button"
            class="cop-chip"
            :class="{ 'is-on': scenario === item }"
            :data-testid="`copilot-script-scene-${item}`"
            @click="scenario = item"
          >
            {{ item }}
          </button>
        </div>
      </div>
      <div class="sc-row">
        <span class="sc-label">业主情绪</span>
        <div class="cop-chips">
          <button
            v-for="item in EMOTIONS"
            :key="item.key"
            type="button"
            class="cop-chip"
            :class="{ 'is-on': emotion === item.key }"
            :data-testid="`copilot-script-emotion-${item.key}`"
            @click="emotion = item.key"
          >
            {{ item.label }}
          </button>
        </div>
      </div>
      <div class="sc-row">
        <span class="sc-label">渠道</span>
        <div class="cop-chips">
          <button
            v-for="item in CHANNELS"
            :key="item.key"
            type="button"
            class="cop-chip"
            :class="{ 'is-on': channel === item.key }"
            :data-testid="`copilot-script-channel-${item.key}`"
            @click="channel = item.key"
          >
            {{ item.label }}
          </button>
        </div>
      </div>
      <div class="cop-field" style="margin-top: 8px">
        <label for="copilot-script-scenario">自定义场景（可编辑；留空则不可生成）</label>
        <input id="copilot-script-scenario" v-model="scenario" class="sc-input" data-testid="copilot-script-scenario" />
      </div>
      <div class="cop-actions" style="margin-top: 8px">
        <WButton size="sm" variant="primary" icon="sparkles" :loading="loading" data-testid="copilot-script-submit" @click="generate">
          生成 3 版话术
        </WButton>
        <WTag tone="n" plain>场景：{{ scenario || '—' }} · 情绪：{{ emotionLabel }} · 渠道：{{ channelLabel }}</WTag>
      </div>
    </WCard>

    <p v-if="denied" class="cop-note" data-testid="copilot-script-denied">
      <WIcon name="lock" :size="13" />
      <span>
        无权限：话术生成需要能力 {{ capabilityLabel(denied.requiredCapability) }}（当前角色「{{ denied.role || '未知' }}」不具备）。
        未展示任何未授权结果。
      </span>
    </p>
    <p v-else-if="error" class="cop-note" data-testid="copilot-script-error">
      <WIcon name="refresh" :size="13" />
      <span>{{ error }}</span>
    </p>

    <WCard v-if="loading" title="生成中">
      <WLoading center label="正在按场景/情绪/渠道组装 3 版话术并做禁区扫描…" />
    </WCard>

    <WCard
      v-else-if="!result"
      title="3 版话术"
      subtitle="选好条件后点「生成 3 版话术」；接口不返回结果前不展示示例话术"
    >
      <WEmpty
        icon="chat"
        title="还没有生成话术"
        :description="error ? '上次请求失败，可调整条件后重试' : '生成结果固定 3 版（友好 / 标准 / 坚定），并逐版做禁区扫描。'"
        data-testid="copilot-script-empty"
      />
    </WCard>

    <WCard
      v-else
      title="3 版话术"
      :subtitle="`场景：${scenario} · 情绪：${emotionLabel} · 渠道：${channelLabel}`"
      data-testid="copilot-script-result"
    >
      <template #extra>
        <WTag v-if="hasForbidden" tone="r" data-testid="copilot-script-forbidden">命中禁区：不可直接用</WTag>
        <WTag v-else tone="g">禁区扫描通过</WTag>
        <WTag v-if="result.mock" tone="a" plain>演示编排（mock）</WTag>
      </template>
      <div class="sc-versions">
        <div v-for="version in versions" :key="version.tone" class="sc-version" :class="`is-${version.tone}`">
          <div class="sc-version__head">
            <WTag :tone="version.toneTag" data-testid="copilot-script-tone">{{ version.label }}版</WTag>
            <span class="cop-dim">{{ version.usage }}</span>
            <div class="cop-actions-right">
              <WButton size="sm" icon="copy" :data-testid="`copilot-script-copy-${version.tone}`" @click="copyVersion(version)">复制</WButton>
            </div>
          </div>
          <p class="sc-version__text" data-testid="copilot-script-content">
            <template v-for="(segment, index) in version.segments" :key="index">
              <mark v-if="segment.hit" class="sc-hit">{{ segment.text }}</mark>
              <template v-else>{{ segment.text }}</template>
            </template>
          </p>
          <p v-if="version.hits.length" class="sc-version__hits" data-testid="copilot-script-hits">
            <WIcon name="shield" :size="12" />
            <span>禁区命中：{{ version.hits.join(' / ') }} —— 命中即提示改写，<b>不可直接复制外发</b>。</span>
          </p>
        </div>
      </div>
      <div class="cop-note sc-tip">
        <WIcon name="shield" :size="13" />
        <span>{{ COPILOT_RULE_NOTE }}；生成 ≠ 外发：禁承诺减免/赔偿、禁威胁、禁泄露他人信息，触达必须人工确认。</span>
      </div>
    </WCard>

    <WCard title="使用说明" subtitle="话术只是草稿，发送前请人工确认事实">
      <div class="cop-list-item"><span>金额、房号、时间点必须可在 ERP / 知识库复核</span><WTag tone="n" plain>硬要求</WTag></div>
      <div class="cop-list-item"><span>群内不发欠费信息，只做私信触达（不做群内自动回复）</span><WTag tone="a" plain>红线</WTag></div>
      <div class="cop-list-item"><span>涉及减免、赔偿、定责一律转人工</span><WTag tone="r" plain>L3</WTag></div>
    </WCard>
  </div>
</template>

<style scoped>
.sc-row {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 5px 0;
}

.sc-input {
  width: 100%;
  height: 28px;
  padding: 0 9px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-sm);
  background: var(--c-card);
  font-size: var(--fs-xs);
  color: var(--c-ink-2);
}

.sc-label {
  flex: none;
  width: 60px;
  padding-top: 4px;
  font-size: 10px;
  font-weight: 600;
  color: var(--c-muted);
}

.sc-versions {
  display: flex;
  flex-direction: column;
  gap: 9px;
}

.sc-version {
  padding: 10px 12px;
  border: 1px solid var(--c-line-2);
  border-radius: var(--rd-lg);
  background: var(--c-bg-2);
}

.sc-version.is-gentle {
  border-color: #cfe8de;
  background: #f3fbf8;
}

.sc-version.is-firm {
  border-color: #f0d9b6;
  background: #fdf8ef;
}

.sc-version__head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.sc-version__text {
  font-size: var(--fs-sm);
  line-height: 1.8;
  color: var(--c-ink-2);
}

.sc-hit {
  padding: 0 2px;
  border-radius: 2px;
  background: #fde2e1;
  color: #b42318;
  font-weight: 600;
}

.sc-version__hits {
  display: flex;
  align-items: flex-start;
  gap: 5px;
  margin-top: 6px;
  padding: 5px 7px;
  border-radius: var(--rd-sm);
  background: #fdecec;
  color: #b42318;
  font-size: 10px;
  line-height: 1.6;
}

.sc-tip {
  margin-top: 10px;
}
</style>
