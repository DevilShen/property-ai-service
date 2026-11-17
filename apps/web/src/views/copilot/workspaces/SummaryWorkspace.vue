<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { WButton, WCard, WEmpty, WIcon, WLoading, WModal, WPageHeader, WTag, useToast } from '@/components'
import PermissionDenied from '@/components/feedback/PermissionDenied.vue'
import { capabilityLabel } from '@/auth/capabilities'
import { COPILOT_WORKSPACES } from '@/app/copilot'
import { ROLE_LABELS } from '@/app/roles'
import { demoNote, successReceipt } from '@/content'
import {
  api,
  ApiError,
  TICKET_CATEGORY_LABELS,
  degradedText,
  errorNextStep,
  errorText,
  forbiddenDetailsOf,
  formatIsoMinute,
  mockBadgeText,
} from '@/api'
import type {
  ActorRole,
  CopilotSummary,
  DegradedInfo,
  HandoffSummary,
  KnowledgeDetail,
  KnowledgeRef,
} from '@/api'
import { useAuthStore } from '@/stores/auth'
import { COPILOT_RULE_NOTE, copyText } from '../shared'

/** Copilot 工作区 3/8 · 工单摘要（50 条群消息 → 5 行摘要 + 3 条待办） */
const workspace = COPILOT_WORKSPACES.summary
const toast = useToast()

const RAW_MESSAGES = [
  '[09:12] 王女士：我家卫生间又漏水了，楼上一直不管',
  '[09:15] 管家：收到，我马上联系工程',
  '[09:20] 李先生：地库月卡什么时候能续',
  '[09:26] 物业：11-15 09:00—12:00 计划停水，请提前储水',
  '[09:31] 张先生：2 号楼电梯维保是哪天',
  '[09:40] 王女士：照片我发群里了，你们看一下',
  '[09:42] 管家：已安排李师傅，预计下午上门',
  '…（其余 43 条，演示样例）',
]

const SUMMARY_LINES = [
  '3-2-1802 卫生间渗漏复发，已派水电二组，预计今天 16:30 上门。',
  '2 户询问地库月卡续费，指向收费系统，需管家跟进。',
  '11-15 计划停水通知已发，未读 38 户，需二次触达。',
  '2 号楼电梯 11-16 维保，已公告，无需重复通知。',
  '群内共 3 条催办、1 条投诉倾向，均已转对应责任人。',
]

const TASK_ITEMS = [
  { id: 't1', text: '回访王女士，确认上门时间', done: true, owner: '管家' },
  { id: 't2', text: '给 2 户发送月卡续费链接', done: false, owner: '收费' },
  { id: 't3', text: '停水通知未读二次触达', done: false, owner: '管家' },
]

const source = ref(RAW_MESSAGES.join('\n'))
const loading = ref(false)
const generated = ref(true)
const generatedAt = ref('0.8s')
const todos = ref(TASK_ITEMS.map((item) => ({ ...item })))
const sent = ref(false)
const ticketOpen = ref(false)

const doneCount = computed(() => todos.value.filter((item) => item.done).length)

/* ---------------- P2：工单摘要（转人工同屏摘要 API） ---------------- */
/** 工单摘要数据源：`GET /api/v1/tickets/{ticketNo}/handoff-summary`（CAP-18） */
const handoff = ref<HandoffSummary | null>(null)
const handoffLoading = ref(false)
const handoffError = ref('')
/**
 * 403 无权限态（P2 W9 / task-33）：copilot 工作区对 it/admin 可见，
 * 但 `ticket.status` / `handoff.human` 只对管家/坐席/主管开放 → 摘要接口 403。
 * 口径：工作区照常可用（其余是群消息演示样例），但摘要卡渲染「无权限」态并给可切换账号，
 * **不展示未授权摘要、不静默失败**；切换账号/重新加载都有可见反馈。
 */
const handoffDenied = ref<{ requiredCapability: string; role: string; traceId: string } | null>(null)
const switchingHandoff = ref(false)
const auth = useAuthStore()
const currentRoleLabel = computed(() => (auth.role ? ROLE_LABELS[auth.role] : '未登录'))
/** 可切换账号白名单：能进 /copilot 且具备 ticket.status 的角色（owner 不可见 copilot，排除） */
const COPILOT_SWITCH_ROLES: readonly ActorRole[] = ['steward', 'agent', 'supervisor']
const handoffDegraded = ref<DegradedInfo | undefined>(undefined)
const handoffDegradedText = computed(() => degradedText(handoffDegraded.value))

/** 摘要要素字典 → 中文（契约 `TicketCategory` / `TicketUrgency` / `IssueScope`） */
const ELEMENT_LABELS: Readonly<Record<string, string>> = {
  category: '类型',
  urgency: '等级',
  location: '位置',
  downstairs: '影响范围',
  duration: '持续时长',
  slot: '预约时段',
}
const URGENCY_LABELS: Readonly<Record<string, string>> = { normal: '一般', urgent: '紧急', emergency: '应急' }
const SCOPE_LABELS: Readonly<Record<string, string>> = {
  none: '未漏到楼下',
  downstairs: '已漏到楼下',
  unknown: '不确定',
}

const handoffElements = computed<Array<{ label: string; value: string }>>(() => {
  const data = handoff.value
  if (!data) return []
  return Object.entries(data.elements).map(([key, value]) => {
    let text = value
    if (key === 'category') text = TICKET_CATEGORY_LABELS[value as keyof typeof TICKET_CATEGORY_LABELS] ?? value
    if (key === 'urgency') text = URGENCY_LABELS[value] ?? value
    if (key === 'downstairs') text = SCOPE_LABELS[value] ?? value
    return { label: ELEMENT_LABELS[key] ?? key, value: text }
  })
})

/**
 * 取一张在办工单的转人工摘要：先读工单列表（`GET /api/v1/tickets`），
 * 优先 DISPATCHED/ON_SITE/ACCEPTED，再读 handoff-summary；失败保留演示摘要并标注降级。
 */
async function loadHandoffSummary() {
  handoffLoading.value = true
  handoffError.value = ''
  handoffDenied.value = null
  try {
    const { data, degraded } = await api.ticket.list({ pageSize: 20 })
    handoffDegraded.value = degraded
    const active =
      data.items.find((item) => item.status === 'DISPATCHED') ??
      data.items.find((item) => item.status === 'ON_SITE') ??
      data.items.find((item) => item.status === 'ACCEPTED') ??
      data.items[0]
    if (!active) {
      handoffError.value = '工单列表为空，无法生成工单摘要（请先在业主端完成报修）'
      return
    }
    const summary = await api.ticket.handoffSummary(active.ticketNo)
    handoff.value = summary.data
    handoffDegraded.value = summary.degraded ?? handoffDegraded.value
  } catch (error) {
    const forbidden = forbiddenDetailsOf(error)
    if (forbidden) {
      // 403：摘要卡渲染无权限说明 + 可切换账号（不展示未授权摘要，也不把演示样例冒充真实摘要）
      handoff.value = null
      handoffDenied.value = {
        requiredCapability: forbidden.requiredCapability || 'ticket.status',
        role: forbidden.role || (auth.role ?? ''),
        traceId: error instanceof ApiError ? error.traceId : '',
      }
      return
    }
    const code = error instanceof Error && 'code' in error ? String(error.code) : 'E-9001'
    handoffError.value = `工单摘要接口暂不可用（${errorText(code)}），下方保留演示摘要`
  } finally {
    handoffLoading.value = false
  }
}

/** 无权限态「重新加载」：先 toast 说明（避免点完零反馈），再重新校验一次 */
function retryHandoff() {
  const capability = handoffDenied.value?.requiredCapability || 'ticket.status'
  toast.warning(
    `无权限：需要 ${capabilityLabel(capability)} 能力，当前演示角色「${currentRoleLabel.value}」不具备；` +
      '请切换管家 / 坐席账号后重试（已重新校验一次）',
  )
  void loadHandoffSummary()
}

/** 从无权限态切换演示账号：换演示 JWT（`auth.role` 变化由 watch 触发按新身份重取；失败保持原账号并提示） */
async function switchHandoffAccount(role: ActorRole) {
  if (switchingHandoff.value) return
  switchingHandoff.value = true
  try {
    const ok = await auth.loginAsRole(role)
    if (!ok) {
      toast.error(`演示账号切换失败：${auth.failure?.message ?? '登录接口不可用'}`)
      return
    }
    toast.info(`已切换演示账号：${ROLE_LABELS[role]}（demo，非真实鉴权），正在重新读取工单摘要…`)
  } finally {
    switchingHandoff.value = false
  }
}

/* ---------------- P4 W16：工单摘要（POST /copilot/summary，cites + 待办） ---------------- */
/**
 * 契约 `CopilotSummary` 口径（README §7.4）：
 * - `summary` ≤5 行、`todos` ≤3 条、`cites` 为知识引用（**可空**：空则页面提示「无出处，需人工确认」）；
 * - `mock:true` = 平台规则组装（模型调用由 Python 编排层），页面必须标注，不得说成真实模型输出；
 * - 入参 `ticketNo` 或 `messages` 二选一（都缺 → 400 `E-1001`）；服务端脱敏手机号/身份证。
 */
const summaryTicketNo = ref('')
const summaryMessages = ref('')
const summaryLoading = ref(false)
const summaryResult = ref<CopilotSummary | null>(null)
const summaryError = ref('')
const summaryDenied = ref<{ requiredCapability: string; role: string; traceId: string } | null>(null)
const summaryTicketOptions = ref<Array<{ ticketNo: string; label: string }>>([])
const summaryTicketError = ref('')
/** 引用详情弹层（知识 ID/版本可点开；内容来自 `GET /knowledge/{id}`，不补写） */
const citeOpen = ref(false)
const citeLoading = ref(false)
const citeError = ref('')
const citeRef = ref<KnowledgeRef | null>(null)
const citeDetail = ref<KnowledgeDetail | null>(null)

const summaryDegraded = ref<DegradedInfo | undefined>(undefined)
const summaryDegradedText = computed(() => degradedText(summaryDegraded.value))
const summaryMockText = computed(() =>
  summaryResult.value?.mock ? COPILOT_RULE_NOTE : '实时编排结果（平台规则组装）',
)

/** 工单下拉（读 `GET /tickets`，用于「选工单 → 摘要」；失败保留粘贴消息路径） */
async function loadSummaryTickets(): Promise<void> {
  summaryTicketError.value = ''
  try {
    const { data } = await api.ticket.list({ pageSize: 20 })
    summaryTicketOptions.value = data.items.map((item) => ({
      ticketNo: item.ticketNo,
      label: `${item.ticketNo} · ${item.roomCode} · ${item.statusText}`,
    }))
  } catch (error) {
    const code = error instanceof Error && 'code' in error ? String(error.code) : 'E-9001'
    summaryTicketError.value = `工单列表暂不可用（${errorText(code)}）；仍可粘贴消息生成摘要`
  }
}

/** 生成摘要：选工单 或 粘贴消息（二选一；两者都空 → 就地提示，不发请求） */
async function generateCopilotSummary(): Promise<void> {
  const ticketNo = summaryTicketNo.value.trim()
  const lines = summaryMessages.value
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean)
  if (!ticketNo && lines.length === 0) {
    toast.warning('请先选择工单，或粘贴至少 1 条消息（ticketNo 与 messages 二选一）')
    return
  }
  summaryLoading.value = true
  summaryError.value = ''
  summaryDenied.value = null
  summaryDegraded.value = undefined
  try {
    const { data, degraded } = await api.copilot.summary(
      ticketNo ? { ticketNo } : { messages: lines },
    )
    summaryResult.value = data
    summaryDegraded.value = degraded
    toast.success(
      `已生成摘要：${data.summary.length} 行 + ${data.todos.length} 条待办` +
        (data.cites.length ? ` · 引用 ${data.cites.length} 条` : ' · 无出处，需人工确认'),
    )
  } catch (error) {
    summaryResult.value = null
    const forbidden = forbiddenDetailsOf(error)
    if (forbidden) {
      summaryDenied.value = {
        requiredCapability: forbidden.requiredCapability || 'copilot.use',
        role: forbidden.role || auth.role || '',
        traceId: error instanceof ApiError ? error.traceId : '',
      }
      return
    }
    const code = error instanceof Error && 'code' in error ? String(error.code) : 'E-9001'
    summaryError.value = `摘要生成失败（${errorText(code)}；${errorNextStep(code)}）`
  } finally {
    summaryLoading.value = false
  }
}

/** 点开 cites：读知识详情（ID/版本/责任人/生效期 + 正文），失败就地提示 */
async function openCite(ref_: KnowledgeRef): Promise<void> {
  citeRef.value = ref_
  citeDetail.value = null
  citeError.value = ''
  citeOpen.value = true
  citeLoading.value = true
  try {
    const { data } = await api.knowledge.get(ref_.knowledgeId)
    citeDetail.value = data
  } catch (error) {
    const code = error instanceof Error && 'code' in error ? String(error.code) : 'E-9001'
    citeError.value = `知识详情暂不可用（${errorText(code)}）；引用四要素仍按摘要返回展示`
  } finally {
    citeLoading.value = false
  }
}

function clearSummary(): void {
  summaryTicketNo.value = ''
  summaryMessages.value = ''
  summaryResult.value = null
  summaryError.value = ''
  summaryDenied.value = null
  toast.info('已清空摘要输入与结果')
}

onMounted(() => {
  void loadHandoffSummary()
  void loadSummaryTickets()
})

/** 演示账号 / 角色切换 → 按新身份重新读取摘要（P2 W9：同一路由内切账号不重挂载，需重取；403 进无权限态） */
watch(
  () => auth.role,
  (next, prev) => {
    if (!next || next === prev) return
    void loadHandoffSummary()
  },
)

function generate() {
  if (!source.value.trim()) {
    generated.value = false
    toast.warning('消息输入为空，无法生成摘要')
    return
  }
  loading.value = true
  setTimeout(() => {
    loading.value = false
    generated.value = true
    generatedAt.value = '0.8s'
    toast.success(successReceipt('重新生成摘要', '5 行 + 3 条待办'))
  }, 650)
}

function clearInput() {
  source.value = ''
  generated.value = false
  toast.info('已清空消息输入')
}

async function copySummary() {
  const text = `AI 摘要\n${SUMMARY_LINES.map((line, index) => `${index + 1}. ${line}`).join('\n')}`
  const ok = await copyText(text)
  if (ok) toast.success('摘要已复制')
  else toast.error('复制失败')
}

function toggleTodo(id: string) {
  todos.value = todos.value.map((item) => (item.id === id ? { ...item, done: !item.done } : item))
}

function sendToManager() {
  sent.value = true
  toast.success(`已发送给项目经理` + demoNote('未真实推送'))
}

function createTicket() {
  ticketOpen.value = false
  toast.success(successReceipt('创建工单并派给水电二组', 'WO-20261114-0901'))
}
</script>

<template>
  <div class="cop-page" data-page="copilot">
    <WPageHeader :eyebrow="`04 · Copilot · ${workspace.label}`" :title="workspace.label" :description="workspace.description">
      <template #actions>
        <WTag tone="v">50 条 → 5 行</WTag>
        <WTag v-if="sent" tone="g">已发送主管</WTag>
        <WButton size="sm" variant="primary" icon="sparkles" :loading="loading" @click="generate">生成摘要</WButton>
      </template>
    </WPageHeader>

    <div class="sum-grid">
      <WCard title="群消息 / 通话记录" subtitle="3 栋业主群 · 50 条（演示样例 8 条）">
        <template #extra>
          <WTag tone="n" plain>{{ source.trim() ? '待压缩' : '已清空' }}</WTag>
        </template>
        <textarea v-model="source" class="cop-textarea sum-raw" aria-label="群消息输入" />
        <div class="cop-actions sum-actions">
          <WButton size="sm" variant="ghost" @click="clearInput">清空</WButton>
          <WButton size="sm" variant="primary" :loading="loading" @click="generate">生成摘要</WButton>
        </div>
      </WCard>

      <div class="sum-right">
        <!-- P4 W16：工单摘要（POST /copilot/summary）：≤5 行摘要 + ≤3 待办 + cites（知识引用可点开） -->
        <WCard
          title="Copilot 摘要 · 规则组装"
          subtitle="选工单或粘贴消息 → 5 行摘要 + 3 条待办 + 知识引用"
          data-testid="copilot-summary"
        >
          <template #extra>
            <WTag v-if="summaryResult?.mock" tone="a">{{ COPILOT_RULE_NOTE }}</WTag>
            <WTag v-else-if="summaryResult" tone="g">已生成</WTag>
            <WTag v-else tone="n" plain>待生成</WTag>
          </template>

          <div class="cop-field">
            <label for="copilot-summary-ticket">选工单（读 GET /tickets）</label>
            <select id="copilot-summary-ticket" v-model="summaryTicketNo" data-testid="copilot-summary-ticket">
              <option value="">不选工单（改为粘贴消息）</option>
              <option v-for="item in summaryTicketOptions" :key="item.ticketNo" :value="item.ticketNo">
                {{ item.label }}
              </option>
            </select>
            <p v-if="summaryTicketError" class="cop-dim" data-testid="copilot-summary-ticket-error">{{ summaryTicketError }}</p>
          </div>

          <div class="cop-field" style="margin-top: 8px">
            <label for="copilot-summary-messages">或粘贴会话/群消息（每行 1 条，已脱敏；不要贴手机号）</label>
            <textarea
              id="copilot-summary-messages"
              v-model="summaryMessages"
              class="cop-textarea"
              placeholder="例：王女士：卫生间又漏水了&#10;管家：收到，马上联系工程"
              data-testid="copilot-summary-messages"
            />
          </div>

          <div class="cop-actions" style="margin-top: 8px">
            <WButton
              size="sm"
              variant="primary"
              icon="sparkles"
              :loading="summaryLoading"
              data-testid="copilot-summary-generate"
              @click="generateCopilotSummary"
            >
              生成摘要
            </WButton>
            <WButton size="sm" variant="ghost" data-testid="copilot-summary-clear" @click="clearSummary">清空</WButton>
            <WTag tone="n" plain>POST /api/v1/copilot/summary</WTag>
          </div>

          <p v-if="summaryDegradedText" class="cop-note" data-testid="copilot-summary-degraded">
            <WIcon name="refresh" :size="13" />
            <span>{{ summaryDegradedText }}</span>
          </p>

          <!-- 403：不展示未授权摘要（copilot.use） -->
          <div v-if="summaryDenied" class="cop-note" data-testid="copilot-summary-denied">
            <WIcon name="lock" :size="13" />
            <span>
              无权限：摘要需要能力 {{ summaryDenied.requiredCapability }}（当前角色「{{ summaryDenied.role || '未知' }}」不具备）。
              未展示未授权内容；可切换演示账号后重试。
            </span>
            <WButton size="sm" variant="primary" style="margin-left: 6px" @click="generateCopilotSummary">重新加载</WButton>
          </div>
          <p v-else-if="summaryError" class="cop-note" data-testid="copilot-summary-error">
            <WIcon name="refresh" :size="13" />
            <span>{{ summaryError }}</span>
          </p>

          <WLoading v-if="summaryLoading" center label="正在按规则组装摘要（不进模型）…" />

          <template v-else-if="summaryResult">
            <div class="sum-block">
              <div class="sum-block__head">
                <b>摘要</b>
                <WTag tone="b" plain>{{ summaryResult.summary.length }} 行（≤5）</WTag>
              </div>
              <ol class="sum-lines" data-testid="copilot-summary-lines">
                <li v-for="(line, index) in summaryResult.summary" :key="index">{{ line }}</li>
              </ol>
            </div>

            <div class="sum-block">
              <div class="sum-block__head">
                <b>待办</b>
                <WTag tone="a" plain>{{ summaryResult.todos.length }} 条（≤3）</WTag>
              </div>
              <div
                v-for="(todo, index) in summaryResult.todos"
                :key="index"
                class="cop-list-item"
                data-testid="copilot-summary-todo"
              >
                <span>{{ index + 1 }}. {{ todo }}</span>
                <WTag tone="n" plain>人工确认</WTag>
              </div>
              <p v-if="!summaryResult.todos.length" class="cop-dim">暂无待办（接口未返回待办条目）</p>
            </div>

            <div class="sum-block">
              <div class="sum-block__head">
                <b>知识引用（cites）</b>
                <WTag :tone="summaryResult.cites.length ? 'g' : 'a'" plain>
                  {{ summaryResult.cites.length ? `${summaryResult.cites.length} 条` : '无出处' }}
                </WTag>
              </div>
              <div v-if="summaryResult.cites.length" class="sum-cites" data-testid="copilot-summary-cites">
                <button
                  v-for="cite in summaryResult.cites"
                  :key="`${cite.knowledgeId}-${cite.version}`"
                  type="button"
                  class="cop-chip"
                  data-testid="copilot-summary-cite"
                  @click="openCite(cite)"
                >
                  {{ cite.knowledgeId }} {{ cite.version }} · {{ cite.title }}
                </button>
              </div>
              <p v-else class="cop-note" data-testid="copilot-summary-no-cite">
                <WIcon name="shield" :size="13" />
                <span>本次摘要无知识出处：涉及结论/金额/时限时必须人工确认，不得直接对客引用。</span>
              </p>
            </div>

            <p class="cop-note" style="margin-top: 6px">
              <WIcon name="info" :size="13" />
              <span>{{ summaryMockText }}；写入/外发仍走人工确认，演示不回写 ERP。</span>
            </p>
          </template>

          <WEmpty
            v-else
            icon="report"
            title="还没有 Copilot 摘要"
            description="选择工单或粘贴消息后点「生成摘要」；接口返回 ≤5 行摘要 + ≤3 条待办 + 知识引用。"
            data-testid="copilot-summary-empty"
          />
        </WCard>

        <!-- P2：工单摘要工作区读真实转人工摘要（CAP-18），不是本地硬编码 -->
        <WCard
          title="工单摘要 · 转人工同屏"
          :subtitle="
            handoff
              ? `${handoff.ticketNo} · ${handoff.statusText}`
              : handoffDenied
                ? '403 无权限：不展示未授权摘要'
                : '读取 GET /tickets/{no}/handoff-summary'
          "
          data-testid="copilot-handoff"
        >
          <template #extra>
            <WTag v-if="handoff?.mock" tone="a">{{ mockBadgeText }}</WTag>
            <WTag v-else-if="handoff" tone="g">实时摘要</WTag>
            <WTag v-else-if="handoffDenied" tone="n" plain>无权限 403</WTag>
            <WTag v-else tone="n" plain>降级</WTag>
          </template>
          <WLoading v-if="handoffLoading" center label="正在读取工单摘要…" />
          <!-- 403 无权限态（P2 W9）：显示所需能力/当前角色，可切换演示账号；重试有 toast 反馈 -->
          <PermissionDenied
            v-else-if="handoffDenied"
            compact
            data-testid="copilot-handoff-denied"
            :required-capability="handoffDenied.requiredCapability"
            :required-role="handoffDenied.role"
            :switching="switchingHandoff"
            :candidate-roles="COPILOT_SWITCH_ROLES"
            :detail="`工单摘要需要能力 ${capabilityLabel(handoffDenied.requiredCapability)}；当前演示角色「${currentRoleLabel}」不具备该能力，本卡片不读取未授权摘要（右侧「AI 摘要」为群消息演示样例，两者不要混用）。`"
            @switch="switchHandoffAccount"
          >
            <template #actions>
              <WButton size="sm" variant="primary" data-testid="copilot-handoff-retry" @click="retryHandoff">重新加载</WButton>
            </template>
          </PermissionDenied>
          <p v-else-if="handoffError" class="cop-note" data-testid="copilot-handoff-degraded">
            <WIcon name="refresh" :size="13" />
            <span>{{ handoffError }}；下方「AI 摘要」为群消息演示样例，两者不要混用。</span>
          </p>
          <template v-if="handoff && !handoffLoading">
            <div class="cop-kv"><span>房号</span><b>{{ handoff.roomCode }} · {{ handoff.ownerNameMasked }}</b></div>
            <div class="cop-kv"><span>标题</span><b>{{ handoff.title }}</b></div>
            <div class="cop-kv"><span>状态</span><b>{{ handoff.statusText }}（{{ handoff.status }}）</b></div>
            <ol class="sum-lines" style="margin-top: 6px">
              <li v-for="(line, index) in handoff.summary" :key="index">{{ line }}</li>
            </ol>
            <div class="cop-kv" style="margin-top: 6px"><span>情绪</span><b>{{ handoff.emotion ?? '未判定' }}</b></div>
            <div v-for="item in handoffElements" :key="item.label" class="cop-kv">
              <span>{{ item.label }}</span><b>{{ item.value }}</b>
            </div>
            <div v-if="handoff.suggestedActions.length" class="cop-actions" style="margin-top: 6px">
              <WTag v-for="action in handoff.suggestedActions" :key="action" tone="v" plain>{{ action }}</WTag>
            </div>
            <div v-if="handoff.pendingActions?.length" class="cop-kv">
              <span>未完成动作</span><b>{{ handoff.pendingActions.join(' / ') }}</b>
            </div>
            <p class="cop-note" style="margin-top: 6px">
              <WIcon name="clock" :size="13" />
              <span>生成时间 {{ formatIsoMinute(handoff.generatedAt) }} · 已脱敏；写操作仍走人工确认。</span>
            </p>
            <p v-if="handoffDegradedText" class="cop-note" data-testid="copilot-handoff-degraded-level">
              <WIcon name="refresh" :size="13" />
              <span>{{ handoffDegradedText }}</span>
            </p>
          </template>
        </WCard>

        <WCard v-if="loading" title="生成中">
          <WLoading center label="正在压缩 50 条消息…" />
        </WCard>
        <WCard v-else-if="!generated" title="AI 摘要">
          <WEmpty icon="report" title="还没有摘要" description="点击「生成摘要」，把群消息压成 5 行 + 3 条待办。" />
        </WCard>
        <WCard v-else title="AI 摘要（群消息 · 演示样例）" :subtitle="`耗时 ${generatedAt} · 已脱敏`">
          <template #extra>
            <WTag tone="v">已生成</WTag>
            <WTag tone="n" plain>演示样例</WTag>
          </template>
          <ol class="sum-lines">
            <li v-for="(line, index) in SUMMARY_LINES" :key="index">{{ line }}</li>
          </ol>
        </WCard>

        <WCard title="待办" :subtitle="`${doneCount}/${todos.length} 已完成`">
          <div v-for="todo in todos" :key="todo.id" class="cop-list-item">
            <button type="button" class="sum-box" :class="{ 'is-on': todo.done }" :aria-label="`切换待办：${todo.text}`" @click="toggleTodo(todo.id)">
              <WIcon v-if="todo.done" name="check" :size="10" />
            </button>
            <span :class="{ 'sum-done': todo.done }">{{ todo.text }}</span>
            <WTag tone="n" plain>{{ todo.owner }}</WTag>
          </div>
        </WCard>

        <div class="cop-actions sum-actions">
          <WButton size="sm" variant="primary" icon="plus" @click="ticketOpen = true">创建工单</WButton>
          <WButton size="sm" icon="copy" @click="copySummary">复制摘要</WButton>
          <WButton size="sm" variant="ghost" icon="arrow-right" @click="sendToManager">发送主管</WButton>
        </div>
      </div>
    </div>

    <WModal v-model:open="citeOpen" title="知识引用详情" width="560px">
      <div v-if="citeRef" class="cop-kv"><span>知识 ID / 版本</span><b>{{ citeRef.knowledgeId }} · {{ citeRef.version }}</b></div>
      <div v-if="citeRef" class="cop-kv"><span>标题</span><b>{{ citeRef.title }}</b></div>
      <div v-if="citeRef" class="cop-kv"><span>责任人</span><b>{{ citeRef.owner || '—' }}</b></div>
      <WLoading v-if="citeLoading" center label="正在读取知识详情…" />
      <template v-else-if="citeDetail">
        <div class="cop-kv"><span>生效期</span><b>{{ citeDetail.effectiveFrom }} → {{ citeDetail.effectiveTo || '长期' }}</b></div>
        <div class="cop-kv"><span>状态</span><b>{{ citeDetail.status }}</b></div>
        <div class="cop-quote" style="margin-top: 8px">{{ citeDetail.content }}</div>
      </template>
      <p v-else-if="citeError" class="cop-note" data-testid="copilot-summary-cite-error">
        <WIcon name="refresh" :size="13" />
        <span>{{ citeError }}</span>
      </p>
      <template #footer>
        <WButton size="sm" variant="ghost" @click="citeOpen = false">关闭</WButton>
      </template>
    </WModal>

    <WModal v-model:open="ticketOpen" title="创建工单" width="480px">
      <div class="cop-kv"><span>来源</span><b>群消息摘要（50 条 → 5 行）</b></div>
      <div class="cop-kv"><span>房号</span><b>3-2-1802</b></div>
      <div class="cop-kv"><span>类型 / 等级</span><b>给排水 · 渗漏 / 紧急</b></div>
      <div class="cop-kv"><span>派单建议</span><b>水电二组 · 李建国</b></div>
      <div class="cop-kv"><span>预计上门</span><b>今天 16:30</b></div>
      <p class="cop-note" style="margin-top: 8px">
        <WIcon name="shield" :size="13" />
        <span>创建后将写入 ERP 并标记「AI 创建」；责任认定与赔付不在工单内判断。</span>
      </p>
      <template #footer>
        <WButton size="sm" variant="ghost" @click="ticketOpen = false">取消</WButton>
        <WButton size="sm" variant="primary" @click="createTicket">确认创建</WButton>
      </template>
    </WModal>
  </div>
</template>

<style scoped>
.sum-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1.15fr);
  gap: 12px;
}

.sum-raw {
  min-height: 210px;
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--c-ink-2);
  line-height: 1.75;
}

.sum-actions {
  margin-top: 9px;
}

.sum-right {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.sum-lines {
  display: flex;
  flex-direction: column;
  gap: 6px;
  counter-reset: sum;
  font-size: var(--fs-sm);
  line-height: 1.7;
  color: var(--c-ink-2);
}

.sum-lines li {
  position: relative;
  padding-left: 20px;
  counter-increment: sum;
}

.sum-lines li::before {
  content: counter(sum);
  position: absolute;
  left: 0;
  top: 2px;
  display: grid;
  place-items: center;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: var(--c-brand-soft);
  color: var(--c-brand);
  font-size: 9px;
  font-weight: 600;
}

.sum-box {
  display: grid;
  place-items: center;
  width: 15px;
  height: 15px;
  flex: none;
  margin-top: 2px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-xs);
  background: var(--c-card);
  color: #fff;
}

.sum-box.is-on {
  border-color: var(--c-brand);
  background: var(--c-brand);
}

.sum-box:focus-visible {
  outline: none;
  box-shadow: var(--sh-ring);
}

.sum-done {
  color: var(--c-faint);
  text-decoration: line-through;
}

/* ---------- P4 W16：Copilot 摘要卡（POST /copilot/summary） ---------- */
.sum-block {
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed var(--c-line-2);
}

.sum-block__head {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 5px;
  font-size: var(--fs-xs);
  color: var(--c-ink-2);
}

.sum-block__head b {
  font-weight: var(--fw-semibold);
}

.sum-cites {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}

@media (max-width: 1279px) {
  .sum-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
