<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { WButton, WCard, WEmpty, WIcon, WLoading, WPageHeader, WTag, useToast } from '@/components'
import { COPILOT_WORKSPACES } from '@/app/copilot'
import { api, ApiError, errorNextStep, errorText, forbiddenDetailsOf } from '@/api'
import type { KnowledgeChange, KnowledgeItem, KnowledgeStats } from '@/api'
import { COPILOT_RULE_NOTE } from '../shared'

/**
 * Copilot 工作区 7/8 · 知识共建（P4 W16，契约 v1.2.0 `POST /knowledge/{id}/change`）
 * ===========================================================================
 * - 管家把「AI 答错/答不上」的纠错提交为**知识变更单**（`knowledge.change`）：提交后是
 *   `pending_review`（**不直接生效**），由 it/admin 审核通过才生成新版本；
 * - 目标条目来自 `GET /knowledge?status=published`（服务端只回生效期内），页面不硬编码知识 ID；
 * - 同一知识已有待审变更 → 409 `E-3002`（`details.reason=change_pending`）；非 staff → 403；
 * - 采纳/驳回数字来自 `GET /knowledge/stats`（服务端聚合），前端不自算、不编造积分。
 */
const workspace = COPILOT_WORKSPACES.build
const toast = useToast()

const form = ref({
  question: '',
  original: '',
  supplement: '',
  source: '',
})

const error = ref('')
const submitting = ref(false)
const loading = ref(false)
const denied = ref<{ requiredCapability: string; role: string } | null>(null)

/** 可提交变更的目标知识（published + 生效期，来自 API） */
const candidates = ref<KnowledgeItem[]>([])
const targetId = ref('')
const stats = ref<KnowledgeStats | null>(null)
const statsError = ref('')

/** 本次会话提交的知识变更单（服务端返回，不伪造历史） */
const submissions = ref<Array<{ changeId: string; knowledgeId: string; question: string; status: string; submittedAt: string }>>([])

const target = computed(() => candidates.value.find((item) => item.knowledgeId === targetId.value) ?? null)
const pendingCount = computed(() => submissions.value.filter((item) => item.status === 'pending_review').length)

function apiMessage(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.httpStatus === 409) return '该知识已有待审核变更（409 E-3002），请等待 it/admin 复核后再提交'
    if (err.httpStatus === 400) return `提交参数不合法（400 E-1001）：${errorText(String(err.code))}`
    return `${errorText(String(err.code))}；${errorNextStep(String(err.code))}`
  }
  return '请求失败：请稍后重试或联系知识运营'
}

async function load(): Promise<void> {
  loading.value = true
  denied.value = null
  statsError.value = ''
  try {
    const [{ data: list }, statsResult] = await Promise.all([
      api.knowledge.list({ status: 'published', pageSize: 20 }),
      api.knowledge.stats().catch((err: unknown) => {
        statsError.value = apiMessage(err)
        return null
      }),
    ])
    candidates.value = list.items
    if (!targetId.value && list.items[0]) targetId.value = list.items[0].knowledgeId
    if (statsResult) stats.value = statsResult.data
  } catch (err) {
    const forbidden = forbiddenDetailsOf(err)
    if (forbidden) {
      denied.value = { requiredCapability: forbidden.requiredCapability || 'knowledge.change', role: forbidden.role }
      return
    }
    error.value = apiMessage(err)
  } finally {
    loading.value = false
  }
}

function validate(): boolean {
  if (!targetId.value) {
    error.value = '请选择要修正的知识条目（列表来自 GET /knowledge?status=published）'
    toast.error(error.value)
    return false
  }
  if (!form.value.question.trim()) {
    error.value = '请填写业主问题'
    toast.error('提交失败：业主问题不能为空')
    return false
  }
  if (!form.value.supplement.trim()) {
    error.value = '请填写正确补充（这是知识修正的核心）'
    toast.error('提交失败：正确补充不能为空')
    return false
  }
  error.value = ''
  return true
}

/** 今天 / 一年后（ISO 日期；变更单生效期由审核通过后生效） */
function isoDate(offsetYears = 0): string {
  const now = new Date()
  const date = new Date(now.getFullYear() + offsetYears, now.getMonth(), now.getDate())
  return date.toISOString().slice(0, 10)
}

async function submit(): Promise<void> {
  if (!validate()) return
  submitting.value = true
  error.value = ''
  try {
    const change: KnowledgeChange = (
      await api.knowledge.change(targetId.value, {
        title: target.value?.title,
        content: form.value.supplement.trim(),
        effectiveFrom: isoDate(),
        effectiveTo: isoDate(1),
        reason: `管家纠错：${form.value.question.trim()}${form.value.source ? `（来源：${form.value.source}）` : ''}`,
      })
    ).data
    submissions.value = [
      {
        changeId: change.changeId,
        knowledgeId: change.knowledgeId,
        question: form.value.question.trim(),
        status: change.status,
        submittedAt: change.submittedAt,
      },
      ...submissions.value,
    ]
    toast.success(`已提交知识变更单 ${change.changeId}（状态 ${change.status}）：不直接生效，需 it/admin 审核`)
    form.value = { question: '', original: '', supplement: '', source: '' }
  } catch (err) {
    const forbidden = forbiddenDetailsOf(err)
    if (forbidden) {
      denied.value = { requiredCapability: forbidden.requiredCapability || 'knowledge.change', role: forbidden.role }
      return
    }
    error.value = apiMessage(err)
    toast.error(error.value)
  } finally {
    submitting.value = false
  }
}

function resetForm(): void {
  form.value = { question: '', original: '', supplement: '', source: '' }
  error.value = ''
  toast.info('表单已清空')
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="cop-page" data-page="copilot" data-testid="copilot-build">
    <WPageHeader :eyebrow="`04 · Copilot · ${workspace.label}`" :title="workspace.label" :description="workspace.description">
      <template #actions>
        <WTag tone="a">{{ COPILOT_RULE_NOTE }}</WTag>
        <WTag v-if="stats" tone="g">采纳 {{ stats.adopted }} 条</WTag>
        <WTag v-if="pendingCount" tone="a" plain>本次待审 {{ pendingCount }}</WTag>
        <WButton size="sm" variant="primary" icon="plus" :loading="submitting" data-testid="copilot-build-submit" @click="submit">
          提交纠错
        </WButton>
      </template>
    </WPageHeader>

    <p v-if="denied" class="cop-note" data-testid="copilot-build-denied">
      <WIcon name="lock" :size="13" />
      <span>
        无权限：知识共建需要能力 {{ denied.requiredCapability }}（当前角色「{{ denied.role || '未知' }}」不具备）。
        未展示未授权知识列表。
      </span>
    </p>

    <WLoading v-if="loading" center label="正在读取可提交变更的知识条目…" />

    <div v-else-if="!denied" class="bd-grid">
      <WCard title="提交一条纠错（知识变更单）" subtitle="提交后进入 pending_review，由 it/admin 审核通过才生效（不直接改答案）">
        <div class="cop-field">
          <label for="copilot-build-target">目标知识（GET /knowledge?status=published）</label>
          <select id="copilot-build-target" v-model="targetId" data-testid="copilot-build-target">
            <option v-if="!candidates.length" value="">暂无可提交变更的知识（列表为空）</option>
            <option v-for="item in candidates" :key="item.knowledgeId" :value="item.knowledgeId">
              {{ item.knowledgeId }} {{ item.version }} · {{ item.title }}
            </option>
          </select>
          <p v-if="target" class="cop-dim" data-testid="copilot-build-target-meta">
            当前版本 {{ target.version }} · 生效 {{ target.effectiveFrom }} → {{ target.effectiveTo || '长期' }} ·
            责任人 {{ target.owner }}
          </p>
        </div>
        <div class="cop-field" style="margin-top: 8px">
          <label for="copilot-build-question">业主问题 <em class="bd-required">*</em></label>
          <input id="copilot-build-question" v-model="form.question" class="cop-input" type="text" placeholder="例如：装修押金扣了 800 元，明细在哪里看？" data-testid="copilot-build-question" />
        </div>
        <div class="cop-field" style="margin-top: 8px">
          <label for="copilot-build-original">AI 原回答（只作纠错说明，不入知识正文）</label>
          <textarea id="copilot-build-original" v-model="form.original" class="cop-textarea" />
        </div>
        <div class="cop-field" style="margin-top: 8px">
          <label for="copilot-build-supplement">正确正文 <em class="bd-required">*</em>（将作为变更单 content 提交）</label>
          <textarea id="copilot-build-supplement" v-model="form.supplement" class="cop-textarea" data-testid="copilot-build-supplement" />
        </div>
        <div class="cop-field" style="margin-top: 8px">
          <label for="copilot-build-source">来源 / 依据（写入变更原因，便于审核）</label>
          <input id="copilot-build-source" v-model="form.source" class="cop-input" type="text" placeholder="例如：工程 SOP · 2026 版 / 收费类" />
        </div>
        <p v-if="error" class="bd-error" role="alert" data-testid="copilot-build-error">
          <WIcon name="info" :size="13" />
          {{ error }}
        </p>
        <div class="cop-actions" style="margin-top: 10px">
          <WButton size="sm" variant="primary" :loading="submitting" data-testid="copilot-build-submit-2" @click="submit">提交纠错</WButton>
          <WButton size="sm" variant="ghost" data-testid="copilot-build-reset" @click="resetForm">清空</WButton>
        </div>
      </WCard>

      <div class="bd-side">
        <WCard title="本次会话提交" :subtitle="`${submissions.length} 条 · 服务端返回状态`" data-testid="copilot-build-submissions">
          <WEmpty
            v-if="submissions.length === 0"
            compact
            icon="edit"
            title="还没有提交"
            description="提交后这里显示服务端返回的变更单 ID 与状态（pending_review → approved/rejected 由 it/admin 审核）。"
          />
          <div v-for="item in submissions" :key="item.changeId" class="cop-list-item">
            <div class="bd-sub">
              <b>{{ item.question }}</b>
              <span class="cop-dim">{{ item.changeId }} · {{ item.knowledgeId }}</span>
            </div>
            <WTag :tone="item.status === 'approved' ? 'g' : item.status === 'rejected' ? 'r' : 'a'" plain>
              {{ item.status === 'approved' ? '已通过' : item.status === 'rejected' ? '已驳回' : '待审核' }}
            </WTag>
          </div>
        </WCard>

        <WCard title="采纳看板" subtitle="GET /knowledge/stats（服务端聚合，前端不自算）" data-testid="copilot-build-stats">
          <p v-if="statsError" class="cop-dim" data-testid="copilot-build-stats-error">{{ statsError }}</p>
          <template v-else-if="stats">
            <div class="cop-kv"><span>采纳率</span><b>{{ (stats.adoptionRate * 100).toFixed(1) }}%</b></div>
            <div class="cop-kv"><span>被采纳 / 被驳回</span><b>{{ stats.adopted }} / {{ stats.rejected }}</b></div>
            <div class="cop-kv"><span>30 天内到期</span><b>{{ stats.expiredSoon }} 条</b></div>
            <div class="cop-kv"><span>未命中缺口 TopN</span><b>{{ stats.gaps.length }} 个主题</b></div>
            <div v-for="gap in stats.gaps.slice(0, 3)" :key="gap.topic" class="cop-list-item">
              <span>{{ gap.topic }}</span>
              <WTag tone="a" plain>问 {{ gap.asked }} / 未命中 {{ gap.unanswered }}</WTag>
            </div>
          </template>
        </WCard>
      </div>
    </div>
  </div>
</template>

<style scoped>
.bd-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(0, 1fr);
  gap: 12px;
  align-items: start;
}

.bd-side {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.bd-required {
  color: var(--c-red);
  font-style: normal;
}

.bd-error {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 8px;
  padding: 7px 9px;
  border: 1px solid #f3cfcc;
  border-radius: var(--rd-md);
  background: var(--c-red-soft);
  color: var(--c-red);
  font-size: 10.5px;
}

.bd-sub {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}

.bd-sub b {
  font-size: 11px;
  font-weight: 500;
  color: var(--c-ink-2);
}

@media (max-width: 1279px) {
  .bd-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
