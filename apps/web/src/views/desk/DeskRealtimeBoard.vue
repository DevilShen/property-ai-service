<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { WButton, WCard, WEmpty, WIcon, WLoading, WTag, useToast } from '@/components'
import PermissionDenied from '@/components/feedback/PermissionDenied.vue'
import { api, ApiError, DEFAULT_QA_SCORE_RANGE, errorNextStep, errorText, forbiddenDetailsOf, formatIsoMinute } from '@/api'
import type { ActorRole, DeskQaSample, DeskQaStats, DeskQueue, DeskQueueItem, DeskSla } from '@/api'
import { capabilityLabel } from '@/auth/capabilities'
import { ROLE_LABELS } from '@/app/roles'
import { useAuthStore } from '@/stores/auth'

/**
 * 05 坐席工作台 · P4 实时队列 / SLA / 质检（P4-015，契约 v1.2.0 §13）
 * ===========================================================================
 * 数据来源（全部服务端计算，前端只展示/倒计时）：
 * - `GET /api/v1/desk/queue`：等待时长 / SLA 倒计时（超时红）/ 脱敏预览 + 汇总（waiting/avgWait/slaBreach）；
 * - `POST /api/v1/desk/queue/{id}/takeover`：接管（写操作，互斥 → 二次接管 409 人话）；成功后队列移除；
 * - `POST /api/v1/desk/queue/{id}/release`：释放（仅本人接管；状态不允许 409）；
 * - `GET /api/v1/desk/sla?from&to`：avgFirstResponseSeconds / breachRate / byHour[]；
 * - `GET /api/v1/desk/qa/samples` + `POST .../score`（0—100 整数；越界 400、重复 409）+ `GET /api/v1/desk/qa/stats`。
 *
 * 按能力显隐（`@/auth/capabilities` 前端矩阵，真实鉴权在 Java）：
 * - agent/supervisor：队列 + 接管 + 释放 + SLA + 质检评分（supervisor 另可看质检统计）；
 * - it/admin：平台只读子集（队列 / SLA / 质检样本与评分 / 统计），**无接管/释放** → 按钮置灰并说明；
 * - 无 `desk.queue` 角色的接口 403 → 渲染 PermissionDenied（不展示未授权数据）。
 */
const auth = useAuthStore()
const toast = useToast()

const canQueue = computed(() => auth.hasCapability('desk.queue'))
const canTakeover = computed(() => auth.hasCapability('desk.takeover'))
const canRelease = computed(() => auth.hasCapability('desk.release'))
const canSla = computed(() => auth.hasCapability('desk.sla'))
const canQaScore = computed(() => auth.hasCapability('desk.qa.score'))
const canQaStats = computed(() => auth.hasCapability('desk.qa.stats'))
const currentRoleLabel = computed(() => (auth.role ? ROLE_LABELS[auth.role] : '未登录'))
/** 可切换演示账号（能进 /desk 且具备 desk.queue 的角色） */
const SWITCH_ROLES: readonly ActorRole[] = ['agent', 'supervisor']

/* ------------------------------ 队列 ------------------------------ */
const queue = ref<DeskQueue | null>(null)
const queueLoading = ref(false)
const queueError = ref('')
const queueDenied = ref<{ requiredCapability: string; role: string; traceId: string } | null>(null)
const actingId = ref('')
/** 已接管/已释放的会话（用于成功回执与「从队列移除」的即时反馈） */
const takenIds = ref<string[]>([])
const releasedIds = ref<string[]>([])
const receipt = ref('')

/** 倒计时用的当前时间（每秒刷新；服务端已给 waitingSeconds/slaDueAt，前端只做展示） */
const now = ref(Date.now())
let ticker: number | undefined

onMounted(() => {
  ticker = window.setInterval(() => {
    now.value = Date.now()
  }, 1000)
})

onBeforeUnmount(() => {
  if (ticker) window.clearInterval(ticker)
})

interface QueueRow extends DeskQueueItem {
  remainingSeconds: number
  breached: boolean
  waitingText: string
  slaText: string
}

/** 秒 → `mm:ss` / `h:mm:ss` */
function durationText(seconds: number): string {
  const clamped = Math.max(0, Math.floor(seconds))
  const h = Math.floor(clamped / 3600)
  const m = Math.floor((clamped % 3600) / 60)
  const s = clamped % 60
  const mm = h > 0 ? String(m).padStart(2, '0') : String(m).padStart(2, '0')
  const ss = String(s).padStart(2, '0')
  return h > 0 ? `${h}:${mm}:${ss}` : `${mm}:${ss}`
}

const queueRows = computed<QueueRow[]>(() =>
  (queue.value?.items ?? []).map((item) => {
    const dueMs = new Date(item.slaDueAt).getTime()
    const remainingSeconds = Number.isFinite(dueMs) ? Math.round((dueMs - now.value) / 1000) : 0
    const breached = remainingSeconds < 0
    return {
      ...item,
      remainingSeconds,
      breached,
      waitingText: durationText(item.waitingSeconds),
      slaText: breached ? `已超时 ${durationText(-remainingSeconds)}` : `剩余 ${durationText(remainingSeconds)}`,
    }
  }),
)

const queueSummary = computed(() => queue.value?.summary ?? null)

async function loadQueue(): Promise<void> {
  if (!canQueue.value) return
  queueLoading.value = true
  queueError.value = ''
  queueDenied.value = null
  try {
    const { data } = await api.desk.queue()
    queue.value = data
  } catch (error) {
    queue.value = null
    const forbidden = forbiddenDetailsOf(error)
    if (forbidden) {
      queueDenied.value = {
        requiredCapability: forbidden.requiredCapability || 'desk.queue',
        role: forbidden.role,
        traceId: error instanceof ApiError ? error.traceId : '',
      }
      return
    }
    queueError.value = errorMessage(error)
  } finally {
    queueLoading.value = false
  }
}

function errorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    const code = String(error.code)
    return `${errorText(code)}；${errorNextStep(code)}`
  }
  return '请求失败：请稍后重试或转人工'
}

/* ------------------------------ 接管 / 释放 ------------------------------ */
/** 接管：409「已被接管」→ 人话 + 刷新队列（不静默失败、不重试覆盖他人） */
async function takeover(row: QueueRow): Promise<void> {
  if (!canTakeover.value) {
    toast.warning(`当前角色「${currentRoleLabel.value}」无接管能力（desk.takeover），仅可查看队列`)
    return
  }
  actingId.value = row.conversationId
  try {
    const { data } = await api.desk.takeover(row.conversationId, { note: '工作台接管' })
    takenIds.value = [data.conversationId, ...takenIds.value.filter((id) => id !== data.conversationId)]
    receipt.value = `已接管 ${data.conversationId}（坐席 ${data.agentId} · ${formatIsoMinute(data.takenAt) || data.takenAt} · 状态 ${data.status}）`
    toast.success(`已接管会话 ${data.conversationId}：队列已移除该会话，可在会话台继续处理`)
    await loadQueue()
  } catch (error) {
    const forbidden = forbiddenDetailsOf(error)
    if (forbidden) {
      queueDenied.value = {
        requiredCapability: forbidden.requiredCapability || 'desk.takeover',
        role: forbidden.role,
        traceId: error instanceof ApiError ? error.traceId : '',
      }
      return
    }
    if (error instanceof ApiError && error.httpStatus === 409) {
      const reason = String(error.details?.reason ?? '')
      const currentAgent = String(error.details?.currentAgent ?? '')
      receipt.value =
        `接管失败：该会话已被${currentAgent ? `坐席 ${currentAgent} ` : '其他坐席'}接管（409 E-3002，reason=${reason}）` +
        ' —— 请刷新队列后选择其他会话，不建议重复抢占。'
      toast.error(receipt.value)
      await loadQueue()
      return
    }
    receipt.value = `接管失败：${errorMessage(error)}`
    toast.error(receipt.value)
  } finally {
    actingId.value = ''
  }
}

/** 释放：taken_over → waiting（回队列），失败给人话（仅本人可释放 / 状态不允许 409） */
async function release(conversationId: string): Promise<void> {
  if (!canRelease.value) {
    toast.warning(`当前角色「${currentRoleLabel.value}」无释放能力（desk.release）`)
    return
  }
  actingId.value = conversationId
  try {
    const { data } = await api.desk.release(conversationId)
    takenIds.value = takenIds.value.filter((id) => id !== conversationId)
    releasedIds.value = [data.conversationId, ...releasedIds.value.filter((id) => id !== data.conversationId)]
    receipt.value = `已释放 ${data.conversationId}（${formatIsoMinute(data.releasedAt) || data.releasedAt} · 状态 ${data.status}）`
    toast.success(`已释放会话 ${data.conversationId}：${data.status === 'waiting' ? '已回到队列' : '会话已结束'}`)
    await loadQueue()
  } catch (error) {
    if (error instanceof ApiError && error.httpStatus === 409) {
      receipt.value = `释放失败：该会话当前状态不允许释放（409 E-3002）—— 可能已被释放或已结束，可刷新队列确认。`
      toast.error(receipt.value)
      await loadQueue()
      return
    }
    receipt.value = `释放失败：${errorMessage(error)}`
    toast.error(receipt.value)
  } finally {
    actingId.value = ''
  }
}

/** 展示「已接管/已释放」回执卡（成功动作的可见反馈，队列已刷新） */
const recentActions = computed(() => [
  ...takenIds.value.map((id) => ({ id, label: '已接管' })),
  ...releasedIds.value.map((id) => ({ id, label: '已释放' })),
])

/* ------------------------------ SLA ------------------------------ */
const sla = ref<DeskSla | null>(null)
const slaLoading = ref(false)
const slaError = ref('')
const slaFrom = ref('')
const slaTo = ref('')

const slaHours = computed(() =>
  (sla.value?.byHour ?? []).filter((row) => row.avgFirstResponseSeconds > 0 || row.breaches > 0),
)

async function loadSla(): Promise<void> {
  if (!canSla.value) return
  slaLoading.value = true
  slaError.value = ''
  try {
    const { data } = await api.desk.sla({ from: slaFrom.value || undefined, to: slaTo.value || undefined })
    sla.value = data
  } catch (error) {
    sla.value = null
    slaError.value = errorMessage(error)
  } finally {
    slaLoading.value = false
  }
}

/* ------------------------------ 质检 ------------------------------ */
const samples = ref<DeskQaSample[]>([])
const samplesTotal = ref(0)
const samplesLoading = ref(false)
const samplesError = ref('')
const qaStats = ref<DeskQaStats | null>(null)
const qaStatsError = ref('')
const scoreDrafts = ref<Record<string, number>>({})
const scoringId = ref('')
const qaReceipt = ref('')

async function loadSamples(): Promise<void> {
  if (!canQaScore.value) return
  samplesLoading.value = true
  samplesError.value = ''
  try {
    const { data } = await api.desk.qaSamples({ pageSize: 20 })
    samples.value = data.items
    samplesTotal.value = data.total
    const drafts: Record<string, number> = { ...scoreDrafts.value }
    for (const item of data.items) {
      if (drafts[item.sampleId] === undefined) drafts[item.sampleId] = 80
    }
    scoreDrafts.value = drafts
  } catch (error) {
    samples.value = []
    samplesError.value = errorMessage(error)
  } finally {
    samplesLoading.value = false
  }
}

async function loadQaStats(): Promise<void> {
  if (!canQaStats.value) return
  qaStatsError.value = ''
  try {
    const { data } = await api.desk.qaStats()
    qaStats.value = data
  } catch (error) {
    qaStats.value = null
    qaStatsError.value = errorMessage(error)
  }
}

function scoreOf(sampleId: string): number {
  return scoreDrafts.value[sampleId] ?? 80
}

function setScore(sampleId: string, value: number): void {
  const min = DEFAULT_QA_SCORE_RANGE.min
  const max = DEFAULT_QA_SCORE_RANGE.max
  scoreDrafts.value = {
    ...scoreDrafts.value,
    [sampleId]: Math.max(min, Math.min(max, Number.isFinite(value) ? Math.round(value) : min)),
  }
}

/** 评分（0—100 整数）：越界就地拦截 + 服务端 400/409 人话；成功后刷新列表与统计 */
async function submitScore(sample: DeskQaSample): Promise<void> {
  if (!canQaScore.value) {
    toast.warning(`当前角色「${currentRoleLabel.value}」无质检评分能力（desk.qa.score）`)
    return
  }
  const score = scoreOf(sample.sampleId)
  if (!Number.isInteger(score) || score < DEFAULT_QA_SCORE_RANGE.min || score > DEFAULT_QA_SCORE_RANGE.max) {
    toast.warning(`评分必须是 ${DEFAULT_QA_SCORE_RANGE.min}—${DEFAULT_QA_SCORE_RANGE.max} 的整数`)
    return
  }
  scoringId.value = sample.sampleId
  try {
    const { data } = await api.desk.qaScore(sample.sampleId, { score, note: '工作台质检评分' })
    qaReceipt.value = `已评分 ${data.sampleId}：${data.score} 分（状态 ${data.status}）`
    toast.success(`已提交质检评分 ${data.sampleId}：${data.score} 分`)
    await Promise.all([loadSamples(), loadQaStats()])
  } catch (error) {
    if (error instanceof ApiError && error.httpStatus === 409) {
      qaReceipt.value = `评分失败：该抽样已评分（409 E-3002）—— 同一抽样不可重复评分，请刷新列表。`
      toast.error(qaReceipt.value)
      await Promise.all([loadSamples(), loadQaStats()])
      return
    }
    if (error instanceof ApiError && error.httpStatus === 400) {
      qaReceipt.value = `评分失败：分数需为 ${DEFAULT_QA_SCORE_RANGE.min}—${DEFAULT_QA_SCORE_RANGE.max} 的整数（400 E-1001）`
      toast.error(qaReceipt.value)
      return
    }
    qaReceipt.value = `评分失败：${errorMessage(error)}`
    toast.error(qaReceipt.value)
  } finally {
    scoringId.value = ''
  }
}

async function switchAccount(role: ActorRole): Promise<void> {
  const ok = await auth.loginAsRole(role)
  if (!ok) {
    toast.error(`演示账号切换失败：${auth.failure?.message ?? '登录接口不可用'}`)
    return
  }
  toast.info(`已切换演示账号：${ROLE_LABELS[role]}（demo，非真实鉴权），正在重新读取队列/SLA/质检…`)
  await reloadAll()
}

async function reloadAll(): Promise<void> {
  await Promise.all([loadQueue(), loadSla(), loadSamples(), loadQaStats()])
}

function retryQueue(): void {
  toast.warning(`正在重新读取坐席队列（desk.queue）`)
  void loadQueue()
}

onMounted(() => {
  void reloadAll()
})
</script>

<template>
  <div class="dr" data-testid="desk-p4">
    <!-- 权限说明（按能力显隐；it/admin 只读子集） -->
    <div class="dr__caps">
      <WTag tone="n" plain>当前角色：{{ currentRoleLabel }}</WTag>
      <WTag v-if="canTakeover" tone="g" plain>可接管/释放</WTag>
      <WTag v-else tone="a" plain>只读：无接管/释放能力</WTag>
      <WTag tone="n" plain>desk.queue / desk.sla / desk.qa.score{{ canQaStats ? ' / desk.qa.stats' : '' }}</WTag>
      <WButton size="sm" variant="ghost" icon="refresh" :loading="queueLoading || slaLoading || samplesLoading" @click="reloadAll">
        刷新队列 / SLA / 质检
      </WButton>
    </div>

    <!-- ============ 队列卡：等待时长 / SLA 倒计时（超时红）/ 预览 / 接管 / 释放 ============ -->
    <WCard
      title="实时队列（P4 坐席队列）"
      :subtitle="
        queueSummary
          ? `等待 ${queueSummary.waiting} 个 · 平均等待 ${durationText(queueSummary.avgWaitSeconds)} · 超 SLA ${queueSummary.slaBreach} 个`
          : 'GET /api/v1/desk/queue（只列 waiting，按等待时长降序）'
      "
      data-testid="desk-p4-queue"
    >
      <template #extra>
        <WTag v-if="queueSummary && queueSummary.slaBreach > 0" tone="r">超 SLA {{ queueSummary.slaBreach }}</WTag>
        <WTag tone="n" plain>{{ queueRows.length }} 条在队</WTag>
      </template>

      <!-- 403：无 desk.queue 能力 → PermissionDenied（不展示未授权队列） -->
      <PermissionDenied
        v-if="queueDenied"
        compact
        data-testid="desk-p4-queue-denied"
        :required-capability="queueDenied.requiredCapability"
        :required-role="queueDenied.role"
        :switching="false"
        :candidate-roles="SWITCH_ROLES"
        :detail="`实时队列需要能力 ${capabilityLabel(queueDenied.requiredCapability)}；当前角色「${currentRoleLabel}」不具备（坐席/主管可接管，it/admin 平台只读）。`"
        @switch="switchAccount"
      >
        <template #actions>
          <WButton size="sm" variant="primary" data-testid="desk-p4-queue-retry" @click="retryQueue">重新加载</WButton>
        </template>
      </PermissionDenied>

      <p v-else-if="queueError" class="dr__note" data-testid="desk-p4-queue-error">
        <WIcon name="refresh" :size="13" />
        <span>{{ queueError }}</span>
        <WButton size="sm" variant="ghost" @click="loadQueue">重试</WButton>
      </p>

      <WLoading v-if="queueLoading" center label="正在读取坐席队列（等待时长/SLA 由服务端计算）…" />

      <template v-else-if="!queueDenied">
        <div v-if="receipt" class="dr__receipt" data-testid="desk-p4-receipt">
          <WIcon name="info" :size="13" />
          <span>{{ receipt }}</span>
        </div>
        <p v-if="recentActions.length" class="dr__note" data-testid="desk-p4-actions">
          最近动作：<b v-for="action in recentActions" :key="`${action.label}-${action.id}`">{{ action.label }} {{ action.id }}　</b>
        </p>

        <WEmpty
          v-if="queueRows.length === 0"
          compact
          icon="headset"
          title="队列为空"
          description="当前没有 waiting 会话（已接管/已结束的会话不在队列）。"
          data-testid="desk-p4-queue-empty"
        />
        <div v-else class="dr__queue">
          <div
            v-for="row in queueRows"
            :key="row.conversationId"
            class="dr-card"
            :class="{ 'is-breached': row.breached }"
            data-testid="desk-p4-queue-card"
            :data-conv="row.conversationId"
            :data-breached="row.breached"
          >
            <div class="dr-card__head">
              <b>{{ row.ownerNameMasked }} · {{ row.roomCode }}</b>
              <WTag :tone="row.breached ? 'r' : 'g'" data-testid="desk-p4-sla">
                {{ row.breached ? 'SLA 已超时' : 'SLA 正常' }}
              </WTag>
            </div>
            <div class="dr-card__meta">
              <span>已等待 <b class="num">{{ row.waitingText }}</b></span>
              <span class="dr-card__sla" :class="{ 'is-red': row.breached }">{{ row.slaText }}</span>
              <WTag tone="n" plain>{{ row.channel }}</WTag>
            </div>
            <p class="dr-card__preview" data-testid="desk-p4-preview">{{ row.preview }}</p>
            <div class="dr-card__foot">
              <span class="cop-dim">会话 {{ row.conversationId }} · 预览已脱敏</span>
              <WButton
                v-if="canTakeover"
                size="sm"
                variant="primary"
                icon="headset"
                :loading="actingId === row.conversationId"
                data-testid="desk-p4-takeover"
                @click="takeover(row)"
              >
                接管
              </WButton>
              <WTag v-else tone="n" plain>只读：无 desk.takeover</WTag>
            </div>
          </div>
        </div>
      </template>
    </WCard>

    <!-- ============ 已接管（本次会话）：释放回到队列（写操作，仅本人） ============ -->
    <WCard
      v-if="takenIds.length"
      title="已接管（本次会话）"
      subtitle="POST /desk/queue/{id}/release · 仅当前接管人可释放，释放后回到队列"
      data-testid="desk-p4-taken"
    >
      <div v-for="id in takenIds" :key="id" class="dr__taken-row">
        <b class="mono">{{ id }}</b>
        <WTag tone="g" plain>已接管</WTag>
        <WButton
          v-if="canRelease"
          size="sm"
          variant="ghost"
          icon="refresh"
          :loading="actingId === id"
          data-testid="desk-p4-release"
          @click="release(id)"
        >
          释放
        </WButton>
        <WTag v-else tone="n" plain>只读：无 desk.release</WTag>
      </div>
    </WCard>

    <!-- ============ SLA 看板：avg / breachRate / byHour 全来自 API ============ -->
    <WCard
      title="SLA 看板"
      subtitle="首响均值 / 超时率 / 分时明细（口径：首响=坐席第一条人工回复，演示以接管时间近似）"
      data-testid="desk-p4-sla-board"
    >
      <template #extra>
        <WButton size="sm" variant="ghost" :loading="slaLoading" data-testid="desk-p4-sla-refresh" @click="loadSla">刷新</WButton>
      </template>
      <div class="dr__filters">
        <label>起始日<input v-model="slaFrom" type="date" data-testid="desk-p4-sla-from" /></label>
        <label>结束日<input v-model="slaTo" type="date" data-testid="desk-p4-sla-to" /></label>
        <WButton size="sm" variant="primary" :loading="slaLoading" data-testid="desk-p4-sla-load" @click="loadSla">读取 SLA</WButton>
      </div>
      <p v-if="slaError" class="dr__note" data-testid="desk-p4-sla-error">
        <WIcon name="refresh" :size="13" /><span>{{ slaError }}</span>
      </p>
      <WLoading v-if="slaLoading" center label="正在读取 SLA 指标…" />
      <template v-else-if="sla">
        <div class="dr__stats" data-testid="desk-p4-sla-stats">
          <div class="cop-stat">
            <b data-testid="desk-p4-sla-avg">{{ sla.avgFirstResponseSeconds }}s</b>
            <span>平均首响（服务端）</span>
          </div>
          <div class="cop-stat">
            <b data-testid="desk-p4-sla-breach">{{ (sla.breachRate * 100).toFixed(1) }}%</b>
            <span>超时率</span>
          </div>
          <div class="cop-stat">
            <b>{{ slaHours.length }}</b>
            <span>有数据小时数</span>
          </div>
        </div>
        <div class="dr__hours" data-testid="desk-p4-sla-hours">
          <div v-for="row in slaHours" :key="row.hour" class="dr__hour">
            <b>{{ String(row.hour).padStart(2, '0') }} 时</b>
            <span>均值 {{ row.avgFirstResponseSeconds }}s</span>
            <span :class="{ 'is-red': row.breaches > 0 }">超时 {{ row.breaches }}</span>
          </div>
          <p v-if="!slaHours.length" class="cop-dim">该窗口无首响/超时数据（服务端按 0 记，不编造）。</p>
        </div>
      </template>
    </WCard>

    <!-- ============ 质检：样本 → 评分 0—100 → 统计 ============ -->
    <div class="dr__qa">
      <WCard
        title="质检抽样与评分"
        :subtitle="`GET /desk/qa/samples · 共 ${samplesTotal} 条（agent 只看自己的抽样）`"
        data-testid="desk-p4-qa"
      >
        <template #extra>
          <WTag tone="n" plain>{{ DEFAULT_QA_SCORE_RANGE.min }}—{{ DEFAULT_QA_SCORE_RANGE.max }} 整数</WTag>
        </template>
        <p v-if="qaReceipt" class="dr__receipt" data-testid="desk-p4-qa-receipt">
          <WIcon name="info" :size="13" /><span>{{ qaReceipt }}</span>
        </p>
        <p v-if="samplesError" class="dr__note" data-testid="desk-p4-qa-error">
          <WIcon name="refresh" :size="13" /><span>{{ samplesError }}</span>
          <WButton size="sm" variant="ghost" @click="loadSamples">重试</WButton>
        </p>
        <WLoading v-if="samplesLoading" center label="正在读取质检抽样…" />
        <WEmpty
          v-else-if="samples.length === 0"
          compact
          icon="list-check"
          title="暂无质检抽样"
          description="抽样规则：已有接管坐席的会话（超 SLA 优先）；先在队列接管会话后会出现样本。"
          data-testid="desk-p4-qa-empty"
        />
        <table v-else class="dr__table" data-testid="desk-p4-qa-table">
          <thead>
            <tr><th>抽样 ID</th><th>会话</th><th>坐席</th><th>状态</th><th>得分</th><th>评分</th></tr>
          </thead>
          <tbody>
            <tr v-for="sample in samples" :key="sample.sampleId" data-testid="desk-p4-qa-row" :data-sample="sample.sampleId">
              <td class="mono">{{ sample.sampleId }}</td>
              <td>{{ sample.conversationId }}</td>
              <td>{{ sample.agentId }}</td>
              <td>
                <WTag :tone="sample.status === 'scored' ? 'g' : 'a'">{{ sample.status === 'scored' ? '已评分' : '待评分' }}</WTag>
              </td>
              <td data-testid="desk-p4-qa-score-value">{{ sample.score ?? '—' }}</td>
              <td>
                <template v-if="sample.status === 'pending'">
                  <input
                    class="dr__score"
                    type="number"
                    :min="DEFAULT_QA_SCORE_RANGE.min"
                    :max="DEFAULT_QA_SCORE_RANGE.max"
                    :value="scoreOf(sample.sampleId)"
                    data-testid="desk-p4-qa-score-input"
                    aria-label="质检分数"
                    @input="setScore(sample.sampleId, Number(($event.target as HTMLInputElement).value))"
                  />
                  <WButton
                    size="sm"
                    variant="primary"
                    :loading="scoringId === sample.sampleId"
                    data-testid="desk-p4-qa-score-submit"
                    @click="submitScore(sample)"
                  >
                    提交评分
                  </WButton>
                </template>
                <WTag v-else tone="n" plain>已评分不可重复</WTag>
              </td>
            </tr>
          </tbody>
        </table>
      </WCard>

      <WCard
        title="质检统计"
        :subtitle="canQaStats ? 'GET /desk/qa/stats（supervisor/it/admin）' : '需要能力 desk.qa.stats（agent 不可见）'"
        data-testid="desk-p4-qa-stats"
      >
        <template v-if="canQaStats">
          <p v-if="qaStatsError" class="dr__note" data-testid="desk-p4-qa-stats-error">
            <WIcon name="refresh" :size="13" /><span>{{ qaStatsError }}</span>
          </p>
          <template v-else-if="qaStats">
            <div class="dr__stats">
              <div class="cop-stat"><b data-testid="desk-p4-qa-samples">{{ qaStats.samples }}</b><span>抽样数</span></div>
              <div class="cop-stat"><b data-testid="desk-p4-qa-scored">{{ qaStats.scored }}</b><span>已评分</span></div>
              <div class="cop-stat"><b data-testid="desk-p4-qa-avg">{{ qaStats.avgScore }}</b><span>平均分</span></div>
            </div>
            <div v-for="row in qaStats.byAgent" :key="row.agentId" class="cop-list-item" data-testid="desk-p4-qa-agent">
              <span>{{ row.agentId }}</span>
              <WTag tone="n" plain>{{ row.samples }} 抽样</WTag>
              <WTag tone="v" plain>均分 {{ row.avgScore }}</WTag>
            </div>
          </template>
        </template>
        <p v-else class="cop-dim" data-testid="desk-p4-qa-stats-denied">
          当前角色「{{ currentRoleLabel }}」无质检统计能力（desk.qa.stats）：仅主管 / IT / 管理员可见，坐席只能提交自己的评分。
        </p>
      </WCard>
    </div>
  </div>
</template>

<style scoped>
.dr {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
}

.dr__caps {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  padding: 8px 10px;
  border: 1px solid var(--c-line-2);
  border-radius: var(--rd-md);
  background: var(--c-bg-2);
}

.dr__caps .w-btn {
  margin-left: auto;
}

.dr__note,
.dr__receipt {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  margin: 6px 0;
  padding: 6px 8px;
  border-radius: var(--rd-sm);
  font-size: 10.5px;
  line-height: 1.6;
}

.dr__note {
  background: #fdf8ef;
  color: #8a5a12;
}

.dr__receipt {
  background: var(--c-brand-soft);
  color: #0a725f;
}

.dr__taken-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border-bottom: 1px dashed var(--c-line-2);
  font-size: 10.5px;
  color: var(--c-ink-2);
}

.dr__taken-row:last-child {
  border-bottom: 0;
}

.dr__taken-row .w-btn {
  margin-left: auto;
}

.dr__queue {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 10px;
}

.dr-card {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 10px 12px;
  border: 1px solid var(--c-line-2);
  border-radius: var(--rd-lg);
  background: var(--c-card);
}

.dr-card.is-breached {
  border-color: #f3b4ae;
  background: #fef6f5;
}

.dr-card__head {
  display: flex;
  align-items: center;
  gap: 6px;
}

.dr-card__head b {
  font-size: var(--fs-xs);
  color: var(--c-ink);
}

.dr-card__head .w-tag {
  margin-left: auto;
}

.dr-card__meta {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 10px;
  color: var(--c-muted);
}

.dr-card__sla {
  font-weight: 600;
  color: #0a725f;
}

.dr-card__sla.is-red,
.dr__hour .is-red {
  color: #b42318;
}

.dr-card__preview {
  font-size: 10.5px;
  line-height: 1.6;
  color: var(--c-ink-2);
}

.dr-card__foot {
  display: flex;
  align-items: center;
  gap: 6px;
  padding-top: 5px;
  border-top: 1px dashed var(--c-line-2);
}

.dr-card__foot .w-btn:first-of-type {
  margin-left: auto;
}

.dr__filters {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: 8px;
  margin-bottom: 8px;
}

.dr__filters label {
  display: flex;
  flex-direction: column;
  gap: 3px;
  font-size: 9.5px;
  color: var(--c-faint);
}

.dr__filters input {
  height: 26px;
  padding: 0 8px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-sm);
  background: var(--c-card);
  font-size: var(--fs-xs);
  color: var(--c-ink-2);
}

.dr__stats {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(110px, 1fr));
  gap: 10px;
}

.dr__hours {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}

.dr__hour {
  display: flex;
  flex-direction: column;
  gap: 1px;
  padding: 5px 8px;
  border: 1px solid var(--c-line-2);
  border-radius: var(--rd-sm);
  background: var(--c-bg-2);
  font-size: 9.5px;
  color: var(--c-muted);
}

.dr__hour b {
  font-size: 10px;
  color: var(--c-ink-2);
}

.dr__qa {
  display: grid;
  grid-template-columns: minmax(0, 1.4fr) minmax(0, 1fr);
  gap: var(--sp-3);
  align-items: start;
}

.dr__table {
  width: 100%;
  border-collapse: collapse;
  font-size: 10.5px;
}

.dr__table th,
.dr__table td {
  padding: 6px 8px;
  border-bottom: 1px solid var(--c-line-2);
  text-align: left;
  vertical-align: middle;
  color: var(--c-ink-2);
}

.dr__table th {
  font-size: 9.5px;
  font-weight: 600;
  color: var(--c-faint);
  background: var(--c-bg-2);
}

.dr__score {
  width: 64px;
  height: 24px;
  margin-right: 5px;
  padding: 0 6px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-sm);
  font-size: var(--fs-xs);
  color: var(--c-ink-2);
}

.mono {
  font-family: var(--font-mono);
}

@media (max-width: 1279px) {
  .dr__qa {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
