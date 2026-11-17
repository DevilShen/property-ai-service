<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { WButton, WCard, WEmpty, WLoading, WTag } from '@/components'
import PermissionDenied from '@/components/feedback/PermissionDenied.vue'
import {
  api,
  ApiError,
  errorNextStep,
  errorText,
  EVENT_TYPES,
  forbiddenDetailsOf,
  formatIsoMinute,
} from '@/api'
import type { ActorRole, EventEnvelope, EventStatus, EventType } from '@/api'
import { capabilityLabel } from '@/auth/capabilities'
import { useAuthStore } from '@/stores/auth'
import { useProjectStore } from '@/stores/project'

/**
 * 事件流面板（P5 W19，契约 v1.3.0 §14 / 架构 §24.6 outbox）
 * ===========================================================================
 * 数据来源：`GET /api/v1/events?type&projectId&from&to&page&pageSize`
 * （按 `occurredAt` 倒序分页；只读端点，不落审计）。
 *
 * 口径：
 * - `EventEnvelope{eventId,type,projectId,payload,occurredAt,status}`：payload 为**脱敏**业务载荷
 *   （不含真实手机号/证件号），页面原样展示，不补全、不推断；
 * - `status`：pending 待投递 / published 已投递 / failed 投递失败（可重试）/ dead 死信（超重试上限，
 *   运维待办，**不静默丢失**）；页面按状态给标签，不把 pending 说成「已投递」；
 * - 权限：`event.read`（supervisor/it/admin）；403 → 页面内 PermissionDenied（不展示未授权数据）；
 * - 空态：事件由**真实写操作**产生（建单 → ticket.created、灰度改比例 → gray.policy.updated），
 *   无事件时如实展示空态，不造数。
 */
const auth = useAuthStore()
const project = useProjectStore()
const route = useRoute()

const events = ref<EventEnvelope[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const loading = ref(false)
const switching = ref(false)
const error = ref<{ code: string; message: string; next: string } | null>(null)
const denied = ref<{ requiredCapability: string; role: string; traceId: string } | null>(null)

const filterType = ref<'' | EventType>('')
const filterProjectId = ref('')
const filterFrom = ref('')
const filterTo = ref('')

/** 事件类型 → 中文（顺序与契约 `EventType.enum` / shared `EVENT_TYPES` 一致） */
const EVENT_TYPE_LABELS: Record<EventType, string> = {
  'ticket.created': '工单创建',
  'ticket.transitioned': '工单流转',
  'ticket.feedback': '回访落库',
  'complaint.created': '投诉受理',
  'dunning.sent': '催缴触达',
  'knowledge.changed': '知识变更生效',
  'recon.completed': '对账完成',
  'gray.policy.updated': '灰度策略变更',
}

const EVENT_STATUS_META: Record<EventStatus, { label: string; tone: 'g' | 'a' | 'r' | 'n'; hint: string }> = {
  pending: { label: '待投递', tone: 'a', hint: '已入 outbox，尚未投递给消费方（至少一次投递）' },
  published: { label: '已投递', tone: 'g', hint: '消费方已确认（消费侧幂等）' },
  failed: { label: '投递失败', tone: 'r', hint: '可重试（未超重试上限）' },
  dead: { label: '死信', tone: 'r', hint: '超重试上限进入死信队列，运维待办，不静默丢失' },
}

const canRead = computed(() => auth.hasCapability('event.read'))
const maxPage = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))
/** 项目筛选选项：当前角色可见项目（it/admin 可见全部 3 个；owner/staff 仅本项目） */
const projectOptions = computed(() => project.projects)
/** 是否允许「全部项目」（仅平台级角色：无固定 Actor.projectId） */
const allowAllProjects = computed(() => !auth.actor?.projectId)

function typeLabel(type: EventType): string {
  return EVENT_TYPE_LABELS[type] ?? type
}

function statusMeta(status: EventStatus): { label: string; tone: 'g' | 'a' | 'r' | 'n'; hint: string } {
  return EVENT_STATUS_META[status] ?? EVENT_STATUS_META.pending
}

function payloadText(payload: Record<string, unknown>): string {
  try {
    return JSON.stringify(payload)
  } catch {
    return '—'
  }
}

async function openDefaultProject(): Promise<void> {
  // 首次进入：等可见项目就绪后把筛选默认到当前项目（跨项目 403 由服务端兜底）
  if (project.projects.length === 0) await project.load()
  if (!filterProjectId.value && !allowAllProjects.value) filterProjectId.value = project.activeProjectId
}

async function load(): Promise<void> {
  await openDefaultProject()
  loading.value = true
  error.value = null
  denied.value = null
  try {
    const { data } = await api.event.list({
      type: filterType.value || undefined,
      projectId: filterProjectId.value || undefined,
      from: filterFrom.value ? `${filterFrom.value}T00:00:00+08:00` : undefined,
      to: filterTo.value ? `${filterTo.value}T23:59:59+08:00` : undefined,
      page: page.value,
      pageSize: pageSize.value,
    })
    events.value = data.items
    total.value = data.total
  } catch (caught) {
    events.value = []
    total.value = 0
    const apiError = caught instanceof ApiError ? caught : null
    const code = apiError?.code ?? 'E-9001'
    const reason = typeof apiError?.details.reason === 'string' ? apiError.details.reason : ''
    // 跨项目（E-2002 cross_project）是「数据范围拒绝」，不是「能力缺失」：
    // 必须先于 403 PermissionDenied 判断，否则会被误当 capability_denied 展示（人话也会错）。
    if (reason === 'cross_project') {
      error.value = {
        code,
        message: '跨项目查询事件被拒绝（E-2002 cross_project）：当前演示角色只能读取本项目事件',
        next: '把项目筛选切回本项目；跨项目读取需平台级 it/admin 账号（数据范围由服务端强制）',
      }
      return
    }
    const forbidden = forbiddenDetailsOf(caught)
    if (forbidden) {
      denied.value = {
        requiredCapability: forbidden.requiredCapability || 'event.read',
        role: forbidden.role || (auth.role ?? ''),
        traceId: apiError?.traceId ?? '',
      }
    } else {
      error.value = { code, message: errorText(code), next: errorNextStep(code) }
    }
  } finally {
    loading.value = false
  }
}

function applyFilters(): void {
  page.value = 1
  void load()
}

function resetFilters(): void {
  filterType.value = ''
  filterProjectId.value = allowAllProjects.value ? '' : project.activeProjectId
  filterFrom.value = ''
  filterTo.value = ''
  page.value = 1
  void load()
}

function goPage(next: number): void {
  page.value = Math.min(Math.max(1, next), maxPage.value)
  void load()
}

/** 从无权限态切换演示账号（换 token 后重拉数据，不伪造成功） */
const SWITCH_ROLES: readonly ActorRole[] = ['supervisor', 'it', 'admin']

async function switchAccount(role: ActorRole): Promise<void> {
  if (switching.value) return
  switching.value = true
  try {
    const ok = await auth.loginAsRole(role)
    if (!ok) {
      error.value = {
        code: 'E-9001',
        message: auth.failure?.message ?? '演示账号切换失败',
        next: auth.failure?.next ?? '稍后重试或返回登录页',
      }
      return
    }
    await load()
  } finally {
    switching.value = false
  }
}

onMounted(() => {
  // 深链 `?projectId=`（跨项目时由服务端 403 E-2002 cross_project 兜底，页面转人话）
  const fromQuery = typeof route.query.projectId === 'string' ? route.query.projectId : ''
  if (fromQuery) filterProjectId.value = fromQuery
  void load()
})
</script>

<template>
  <section class="ev-panel" data-testid="events-panel">
    <WCard :padded="false">
      <template #head>
        <span class="ev__card-title">事件流（outbox · 共 {{ total }} 条）</span>
        <span class="ev__card-sub">
          GET /api/v1/events?type&projectId&from&to&page · 按 occurredAt 倒序 · 只读（不落审计）
        </span>
      </template>
      <template #extra>
        <WTag tone="b" plain>event.read</WTag>
        <WTag tone="n" plain>至少一次投递 · 消费幂等 · 死信可查</WTag>
        <WButton size="sm" icon="refresh" :loading="loading" data-testid="events-refresh" @click="load">刷新</WButton>
      </template>

      <div class="ev__filters">
        <label class="ev__field">
          <span>事件类型 type</span>
          <select v-model="filterType" data-testid="events-filter-type">
            <option value="">全部类型（{{ EVENT_TYPES.length }} 类）</option>
            <option v-for="type in EVENT_TYPES" :key="type" :value="type">
              {{ typeLabel(type) }}（{{ type }}）
            </option>
          </select>
        </label>
        <label class="ev__field">
          <span>项目 projectId</span>
          <select v-model="filterProjectId" data-testid="events-filter-project">
            <option v-if="allowAllProjects" value="">全部项目（平台级）</option>
            <option v-for="item in projectOptions" :key="item.projectId" :value="item.projectId">
              {{ item.name }}（{{ item.projectId }}）
            </option>
          </select>
        </label>
        <label class="ev__field">
          <span>起始（含）</span>
          <input v-model="filterFrom" type="date" data-testid="events-filter-from" />
        </label>
        <label class="ev__field">
          <span>结束（含）</span>
          <input v-model="filterTo" type="date" data-testid="events-filter-to" />
        </label>
        <WButton size="sm" variant="primary" icon="search" data-testid="events-query" @click="applyFilters">
          查询
        </WButton>
        <WButton size="sm" variant="ghost" data-testid="events-reset" @click="resetFilters">重置</WButton>
        <span class="ev__stat dim" data-testid="events-total">
          共 {{ total }} 条 · 第 {{ page }} / {{ maxPage }} 页
          <template v-if="!canRead">（当前角色不可读事件）</template>
        </span>
      </div>

      <WLoading v-if="loading" center label="正在读取事件流…" />

      <PermissionDenied
        v-else-if="denied"
        data-testid="events-denied"
        :required-capability="denied.requiredCapability"
        :required-role="denied.role"
        :trace-id="denied.traceId"
        :switching="switching"
        :candidate-roles="SWITCH_ROLES"
        :detail="`事件流读取需要能力 ${capabilityLabel(denied.requiredCapability)}（仅 supervisor / it / admin）；当前演示角色「${auth.role ?? '未登录'}」不具备该能力，页面不会展示未授权事件。`"
        @switch="switchAccount"
      />

      <div v-else-if="error" class="ev__state" data-testid="events-error">
        <WEmpty
          icon="info"
          title="事件流读取失败"
          :description="`${error.message}（错误码 ${error.code}）。${error.next}`"
        />
        <WButton size="sm" icon="refresh" @click="load">重试</WButton>
      </div>

      <WEmpty
        v-else-if="events.length === 0"
        data-testid="events-empty"
        icon="activity"
        title="当前筛选没有事件"
        description="事件由真实写操作产生（建单 → ticket.created、工单迁移 → ticket.transitioned、灰度改比例 → gray.policy.updated 等）；无事件时如实展示空态，不造数。可调整类型/项目/时间窗后重查。"
      />

      <table v-else class="ev__table" data-testid="events-table">
        <thead>
          <tr>
            <th scope="col" style="width: 116px">发生时间</th>
            <th scope="col" style="width: 148px">事件类型</th>
            <th scope="col" style="width: 168px">事件 ID</th>
            <th scope="col" style="width: 96px">项目</th>
            <th scope="col" style="width: 88px">投递状态</th>
            <th scope="col">payload（脱敏）</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="event in events"
            :key="event.eventId"
            data-testid="events-row"
            :data-event-id="event.eventId"
            :data-type="event.type"
            :data-status="event.status"
            :data-project="event.projectId"
          >
            <td><span class="dim num">{{ formatIsoMinute(event.occurredAt) }}</span></td>
            <td>
              <WTag tone="b" plain>{{ event.type }}</WTag>
              <span class="ev__type-label">{{ typeLabel(event.type) }}</span>
            </td>
            <td><code class="ev__id" :title="event.eventId">{{ event.eventId }}</code></td>
            <td><code class="ev__project">{{ event.projectId }}</code></td>
            <td>
              <WTag :tone="statusMeta(event.status).tone" :title="statusMeta(event.status).hint" data-testid="events-status">
                {{ statusMeta(event.status).label }}
              </WTag>
            </td>
            <td>
              <code class="ev__payload" :title="payloadText(event.payload)">{{ payloadText(event.payload) }}</code>
            </td>
          </tr>
        </tbody>
      </table>

      <template #footer>
        <div class="ev__foot">
          <span class="dim">
            第 {{ page }} / {{ maxPage }} 页 · 每页 {{ pageSize }} 条（分页约定与其它列表一致）
          </span>
          <div class="ev__pager">
            <WButton
              size="sm"
              variant="ghost"
              :disabled="page <= 1 || loading"
              data-testid="events-prev"
              @click="goPage(page - 1)"
            >
              上一页
            </WButton>
            <WButton
              size="sm"
              variant="ghost"
              :disabled="page * pageSize >= total || loading"
              data-testid="events-next"
              @click="goPage(page + 1)"
            >
              下一页
            </WButton>
          </div>
        </div>
      </template>
    </WCard>

    <p class="ev__note">
      事件口径（契约 v1.3.0 §14 / 架构 §24.6）：outbox 至少一次投递 + 消费幂等 + 死信队列；
      `pending` 待投递、`published` 已投递、`failed` 可重试、`dead` 进入死信（运维待办，不静默丢失）。
      演示环境为进程内事件存储（重启清空），payload 已脱敏；P5 W21—W22 接 Kafka/outbox 持久化。
    </p>
  </section>
</template>

<style scoped>
.ev-panel {
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
  min-width: 0;
}

.ev__card-title {
  font-size: 12.5px;
  font-weight: var(--fw-semibold);
  color: var(--c-ink);
}

.ev__card-sub {
  margin-left: 6px;
  font-size: var(--fs-2xs);
  color: var(--c-faint);
}

.ev__filters {
  display: flex;
  align-items: flex-end;
  gap: var(--sp-2);
  flex-wrap: wrap;
  padding: 10px 12px;
  border-bottom: 1px solid var(--c-line-2);
}

.ev__field {
  display: flex;
  flex-direction: column;
  gap: 3px;
  font-size: 9px;
  color: var(--c-faint);
}

.ev__field select,
.ev__field input {
  height: 28px;
  min-width: 132px;
  padding: 0 8px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-sm);
  background: var(--c-card);
  font-size: var(--fs-2xs);
  color: var(--c-ink);
}

.ev__field select:focus,
.ev__field input:focus {
  outline: none;
  border-color: var(--c-brand);
  box-shadow: var(--sh-ring);
}

.ev__stat {
  margin-left: auto;
  font-size: var(--fs-2xs);
}

.ev__table {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--fs-sm);
}

.ev__table th {
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

.ev__table td {
  padding: 7px 12px;
  border-bottom: 1px solid #f0f3f5;
  color: var(--c-ink-2);
  vertical-align: middle;
}

.ev__table tbody tr:last-child td {
  border-bottom: 0;
}

.ev__table tbody tr:hover td {
  background: var(--c-bg-2);
}

.ev__table code {
  font-family: var(--font-mono);
  font-size: 9px;
}

.ev__type-label {
  margin-left: 5px;
  font-size: 9.5px;
  color: var(--c-faint);
}

.ev__id {
  display: inline-block;
  max-width: 160px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: bottom;
}

.ev__project {
  color: var(--c-ink-2);
}

.ev__payload {
  display: inline-block;
  max-width: 380px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: bottom;
  color: var(--c-faint);
}

.ev__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
}

.ev__pager {
  display: flex;
  gap: var(--sp-2);
}

.ev__state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--sp-2);
  padding: var(--sp-3);
}

.ev__note {
  font-size: 9.5px;
  color: var(--c-faint);
  line-height: 1.6;
}
</style>
