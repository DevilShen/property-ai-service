<script setup lang="ts">
import { computed, ref } from 'vue'
import { WButton, WCard, WEmpty, WIcon, WLoading, WPageHeader, WTag, useToast } from '@/components'
import { COPILOT_WORKSPACES } from '@/app/copilot'
import { api, ApiError, errorNextStep, errorText, forbiddenDetailsOf } from '@/api'
import type { CopilotPortrait } from '@/api'
import { capabilityLabel } from '@/auth/capabilities'
import { COPILOT_RULE_NOTE, copyText } from '../shared'

/**
 * Copilot 工作区 4/8 · 业主画像（P4 W16，契约 v1.2.0 `GET /copilot/portrait?roomCode=`）
 * ===========================================================================
 * 脱敏红线（契约 `CopilotPortrait` / AGENTS.md NEVER #8）：
 * - 只展示**掩码姓名**（`ownerNameMasked`）、房号、运营标签、服务历史**计数**、风险/沟通禁忌；
 * - **不展示**手机号、身份证、欠费金额明细（服务端不下发，前端也不拼）；历史只回计数；
 * - 数据范围：staff 限本项目（owner 无 Copilot 能力 → 403 `E-2001`）；房号不存在 → 404 `E-3001`；
 * - `mock:true` = 派生演示画像（规则组装，模型调用由 Python 编排层），页面强制标注。
 */
const workspace = COPILOT_WORKSPACES.profile
const toast = useToast()

/** 示例房号（来自 mock/data/households.json 的 20 户样本；仅作输入建议，不是预置结果） */
const PRESETS = ['5-1-403', '3-1-501', '6-1-1102'] as const

const roomCode = ref('5-1-403')
const loading = ref(false)
const portrait = ref<CopilotPortrait | null>(null)
const error = ref('')
const denied = ref<{ requiredCapability: string; role: string; traceId: string } | null>(null)
const queriedRoom = ref('')

const historyRows = computed(() => {
  const data = portrait.value
  if (!data) return []
  return [
    { label: '工单数（近 12 个月）', value: data.history.tickets },
    { label: '账单/缴费交互次数', value: data.history.bills },
    { label: '投诉次数', value: data.history.complaints },
  ]
})

const riskRows = computed(() => portrait.value?.risks ?? [])
const tagRows = computed(() => portrait.value?.tags ?? [])

function apiMessage(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.httpStatus === 404) return '房号不存在（404 E-3001）：请核对「楼栋-单元-房间」，或先查房档'
    return `${errorText(err.code)}；${errorNextStep(err.code)}`
  }
  return '请求失败：请稍后重试或转人工'
}

async function loadPortrait(): Promise<void> {
  const code = roomCode.value.trim()
  if (!code) {
    toast.warning('请先输入房号（格式「楼栋-单元-房间」，如 5-1-403）')
    return
  }
  loading.value = true
  error.value = ''
  denied.value = null
  try {
    const { data } = await api.copilot.portrait(code)
    portrait.value = data
    queriedRoom.value = data.roomCode
    toast.success(`已读取 ${data.roomCode} 脱敏画像（无手机号/金额明细）`)
  } catch (err) {
    portrait.value = null
    queriedRoom.value = code
    const forbidden = forbiddenDetailsOf(err)
    if (forbidden) {
      denied.value = {
        requiredCapability: forbidden.requiredCapability || 'copilot.use',
        role: forbidden.role,
        traceId: err instanceof ApiError ? err.traceId : '',
      }
      return
    }
    error.value = apiMessage(err)
  } finally {
    loading.value = false
  }
}

async function copyPortrait(): Promise<void> {
  const data = portrait.value
  if (!data) return
  const text = [
    `业主画像（脱敏）· ${data.roomCode} · ${data.ownerNameMasked}`,
    `标签：${data.tags.join(' / ') || '无'}`,
    `服务历史：工单 ${data.history.tickets} / 账单交互 ${data.history.bills} / 投诉 ${data.history.complaints}`,
    `风险与禁忌：${data.risks.join('；') || '无'}`,
    `口径：${COPILOT_RULE_NOTE}`,
  ].join('\n')
  const ok = await copyText(text)
  if (ok) toast.success('画像已复制（脱敏版，不含手机号/金额）')
  else toast.error('复制失败，请检查剪贴板权限')
}
</script>

<template>
  <div class="cop-page" data-page="copilot" data-testid="copilot-portrait">
    <WPageHeader :eyebrow="`04 · Copilot · ${workspace.label}`" :title="workspace.label" :description="workspace.description">
      <template #actions>
        <WTag tone="b" plain>脱敏展示</WTag>
        <WTag tone="a">{{ COPILOT_RULE_NOTE }}</WTag>
        <WButton v-if="portrait" size="sm" icon="copy" data-testid="copilot-portrait-copy" @click="copyPortrait">复制画像</WButton>
      </template>
    </WPageHeader>

    <WCard title="按房号查询" subtitle="GET /api/v1/copilot/portrait?roomCode= · 只回掩码姓名/标签/计数/禁忌">
      <div class="pf-search">
        <WIcon name="search" :size="15" />
        <input
          v-model="roomCode"
          class="pf-search__input"
          type="text"
          placeholder="楼栋-单元-房间，如 5-1-403"
          aria-label="房号"
          data-testid="copilot-portrait-room"
          @keyup.enter="loadPortrait"
        />
        <WButton variant="primary" size="sm" :loading="loading" data-testid="copilot-portrait-submit" @click="loadPortrait">
          查询画像
        </WButton>
      </div>
      <div class="cop-chips" style="margin-top: 8px">
        <button
          v-for="preset in PRESETS"
          :key="preset"
          type="button"
          class="cop-chip"
          :data-testid="`copilot-portrait-preset-${preset}`"
          @click="roomCode = preset; loadPortrait()"
        >
          {{ preset }}
        </button>
      </div>
      <div class="cop-note" style="margin-top: 8px">
        <WIcon name="shield" :size="13" />
        <span>
          画像为**只读脱敏派生数据**：不含手机号 / 身份证 / 欠费金额明细；服务历史只回计数。
          手机号/身份信息请走业主身份核验流程，不在 Copilot 展示。
        </span>
      </div>
    </WCard>

    <p v-if="denied" class="cop-note" data-testid="copilot-portrait-denied">
      <WIcon name="lock" :size="13" />
      <span>
        无权限：画像需要能力 {{ capabilityLabel(denied.requiredCapability) }}（当前角色「{{ denied.role || '未知' }}」不具备）。
        未展示任何未授权画像内容。
      </span>
    </p>
    <p v-else-if="error" class="cop-note" data-testid="copilot-portrait-error">
      <WIcon name="refresh" :size="13" />
      <span>{{ error }}</span>
    </p>

    <WCard v-if="loading" title="读取中">
      <WLoading center label="正在读取脱敏画像（不含手机号/金额）…" />
    </WCard>

    <WCard v-else-if="!portrait" title="业主画像" subtitle="输入房号后查询；未返回结果前不展示示例画像">
      <WEmpty
        icon="idcard"
        title="还没有画像"
        :description="error ? '上次查询失败，可换房号重试' : '示例房号：5-1-403 / 3-1-501 / 6-1-1102（20 户样本内）。'"
        data-testid="copilot-portrait-empty"
      />
    </WCard>

    <template v-else>
      <div class="cop-grid-2 pf-grid">
        <WCard title="基础档案（脱敏）" data-testid="copilot-portrait-basic">
          <template #extra>
            <WTag v-if="portrait.mock" tone="a" plain>演示派生</WTag>
          </template>
          <div class="cop-kv"><span>房号</span><b data-testid="copilot-portrait-roomcode">{{ portrait.roomCode }}</b></div>
          <div class="cop-kv"><span>业主（掩码）</span><b data-testid="copilot-portrait-name">{{ portrait.ownerNameMasked }}</b></div>
          <div class="cop-kv"><span>手机号</span><b>不展示（脱敏）</b></div>
          <div class="cop-kv"><span>身份证</span><b>不展示（脱敏）</b></div>
          <div class="cop-kv"><span>欠费金额</span><b>不展示（脱敏）</b></div>
        </WCard>

        <WCard title="运营标签" data-testid="copilot-portrait-tags">
          <div v-if="tagRows.length" class="cop-chips">
            <WTag v-for="tag in tagRows" :key="tag" tone="v">{{ tag }}</WTag>
          </div>
          <p v-else class="cop-dim">暂无运营标签（接口返回空数组，不编造）。</p>
        </WCard>

        <WCard title="服务历史（计数）" subtitle="只回计数，不回明细/金额" data-testid="copilot-portrait-history">
          <div v-for="row in historyRows" :key="row.label" class="cop-kv">
            <span>{{ row.label }}</span><b>{{ row.value }} 次</b>
          </div>
        </WCard>

        <WCard title="风险与沟通禁忌" data-testid="copilot-portrait-risks">
          <template #extra>
            <WTag tone="r" plain>{{ riskRows.length }} 项</WTag>
          </template>
          <template v-if="riskRows.length">
            <div v-for="risk in riskRows" :key="risk" class="cop-list-item">
              <span>{{ risk }}</span>
            </div>
          </template>
          <p v-else class="cop-dim">暂无风险/禁忌记录（接口返回空数组，不编造）。</p>
        </WCard>
      </div>

      <div class="cop-note">
        <WIcon name="info" :size="13" />
        <span>查询房号 {{ queriedRoom }} · 数据来自派生规则组装；不含手机号/身份证/欠费金额明细。</span>
      </div>
    </template>
  </div>
</template>

<style scoped>
.pf-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.pf-search {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 9px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-md);
  background: var(--c-card);
}

.pf-search__input {
  flex: 1;
  min-width: 0;
  height: 26px;
  border: 0;
  background: transparent;
  font-size: var(--fs-xs);
  color: var(--c-ink-2);
}

.pf-search__input:focus-visible {
  outline: none;
}

@media (max-width: 1279px) {
  .pf-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
