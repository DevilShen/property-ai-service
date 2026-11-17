<script setup lang="ts">
import { computed, ref } from 'vue'
import { WButton, WCard, WEmpty, WLoading, WTag } from '@/components'
import PermissionDenied from '@/components/feedback/PermissionDenied.vue'
import {
  api,
  ApiError,
  AUDIT_ACTIONS,
  AUDIT_RESOURCE_TYPES,
  errorNextStep,
  errorText,
  forbiddenDetailsOf,
  formatIsoMinute,
} from '@/api'
import type { AuditAction, AuditEvent, AuditResourceType } from '@/api'
import { capabilityLabel } from '@/auth/capabilities'
import { useAuthStore } from '@/stores/auth'

/**
 * 审计面板（P2 W8，契约 v0.3.3 §11.9.2 / P2-031）
 * ===========================================================================
 * 数据来源：`GET /api/v1/audit/events?actorId&action&resourceType&from&to&page&pageSize`
 * （仅 `supervisor`/`it`/`admin` 可读，能力 `audit.read`）。
 *
 * 口径：
 * - 一条 `AuditEvent` = 谁（actorId/actorRole）在何时（at）对什么资源（resourceType/resourceId）
 *   做了什么（action）+ traceId + 结果（success/denied/failed）；
 * - P2 为**进程内演示存储**（重启清空，上限 2000 条），页面标注「演示审计」；只回脱敏信息
 *   （`maskedFields` 只列字段名，不含手机号/姓名/金额原文），页面不编造记录；
 * - 403（`E-2001` + `details.requiredCapability=audit.read`）→ 渲染页面内「无权限」态，
 *   说明所需能力与当前角色，并提供切换演示账号（不造假数据）。
 */
const auth = useAuthStore()

const events = ref<AuditEvent[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const loading = ref(false)
const switching = ref(false)
const error = ref<{ code: string; message: string; next: string } | null>(null)
const denied = ref<{ requiredCapability: string; role: string; traceId: string } | null>(null)

const filterActor = ref('')
const filterAction = ref<'' | AuditAction>('')
const filterResourceType = ref<'' | AuditResourceType>('')
const filterFrom = ref('')
const filterTo = ref('')

const canRead = computed(() => auth.hasCapability('audit.read'))
const maxPage = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))

const ACTION_TONES: Partial<Record<AuditAction, 'g' | 'a' | 'r' | 'n' | 'b' | 'v'>> = {
  'access.deny': 'r',
  'ticket.create': 'g',
  'ticket.transition': 'b',
  'ticket.feedback': 'v',
  'mapping.publish': 'b',
  'mapping.rollback': 'a',
  'connector.probe': 'n',
  'recon.run': 'n',
  'owner.verify': 'v',
  'auth.login': 'g',
  'auth.logout': 'n',
}

const RESULT_META: Record<string, { label: string; tone: 'g' | 'a' | 'r' }> = {
  success: { label: '成功', tone: 'g' },
  denied: { label: '越权拒绝', tone: 'r' },
  failed: { label: '失败', tone: 'a' },
}

function resultMeta(result: string): { label: string; tone: 'g' | 'a' | 'r' } {
  return RESULT_META[result] ?? { label: result, tone: 'a' }
}

function actionTone(action: AuditAction): 'g' | 'a' | 'r' | 'n' | 'b' | 'v' {
  return ACTION_TONES[action] ?? 'n'
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  denied.value = null
  try {
    const { data } = await api.audit.events({
      actorId: filterActor.value.trim() || undefined,
      action: filterAction.value || undefined,
      resourceType: filterResourceType.value || undefined,
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
    // 403：渲染「无权限」态（所需能力 audit.read + 当前角色 + 可切换账号）
    const forbidden = forbiddenDetailsOf(caught)
    if (forbidden) {
      denied.value = {
        requiredCapability: forbidden.requiredCapability || 'audit.read',
        role: forbidden.role || (auth.role ?? ''),
        traceId: caught instanceof ApiError ? caught.traceId : '',
      }
    } else {
      const apiError = caught instanceof ApiError ? caught : null
      const code = apiError?.code ?? 'E-9001'
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
  filterActor.value = ''
  filterAction.value = ''
  filterResourceType.value = ''
  filterFrom.value = ''
  filterTo.value = ''
  page.value = 1
  void load()
}

function goPage(next: number): void {
  page.value = Math.min(Math.max(1, next), maxPage.value)
  void load()
}

/** 从无权限态切换演示账号：换 token 后重新拉数据（不伪造成功） */
async function switchAccount(role: 'owner' | 'steward' | 'agent' | 'supervisor' | 'it' | 'admin'): Promise<void> {
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
    void load()
  } finally {
    switching.value = false
  }
}

void load()
</script>

<template>
  <section class="erp-audit" data-testid="erp-audit-panel">
    <WCard :padded="false">
      <template #head>
        <span class="erp__card-title">审计事件</span>
        <span class="erp__card-sub">
          谁 · 何时 · 对什么资源 · 做了什么 · traceId · 结果（演示审计：进程内存储，重启清空，仅脱敏信息）
        </span>
      </template>
      <template #extra>
        <WTag tone="v" plain>仅 supervisor / it / admin（audit.read）</WTag>
        <WButton size="sm" icon="refresh" :loading="loading" data-testid="erp-audit-refresh" @click="load">
          刷新
        </WButton>
      </template>

      <div class="erp-audit__filters">
        <label class="erp-audit__field">
          <span>操作人 actorId</span>
          <input
            v-model="filterActor"
            type="text"
            placeholder="如 steward-01"
            data-testid="erp-audit-filter-actor"
            @keydown.enter.prevent="applyFilters"
          />
        </label>
        <label class="erp-audit__field">
          <span>动作 action</span>
          <select v-model="filterAction" data-testid="erp-audit-filter-action">
            <option value="">全部动作</option>
            <option v-for="action in AUDIT_ACTIONS" :key="action" :value="action">{{ action }}</option>
          </select>
        </label>
        <label class="erp-audit__field">
          <span>资源类型 resourceType</span>
          <select v-model="filterResourceType" data-testid="erp-audit-filter-resource">
            <option value="">全部资源</option>
            <option v-for="type in AUDIT_RESOURCE_TYPES" :key="type" :value="type">{{ type }}</option>
          </select>
        </label>
        <label class="erp-audit__field">
          <span>起（含）</span>
          <input v-model="filterFrom" type="date" data-testid="erp-audit-filter-from" />
        </label>
        <label class="erp-audit__field">
          <span>止（含）</span>
          <input v-model="filterTo" type="date" data-testid="erp-audit-filter-to" />
        </label>
        <WButton size="sm" variant="primary" icon="search" data-testid="erp-audit-query" @click="applyFilters">
          查询
        </WButton>
        <WButton size="sm" variant="ghost" @click="resetFilters">重置</WButton>
        <span class="erp-audit__stat dim" data-testid="erp-audit-total">共 {{ total }} 条事件</span>
      </div>

      <WLoading v-if="loading" center label="正在读取审计事件…" />

      <PermissionDenied
        v-else-if="denied"
        data-testid="erp-audit-denied"
        :required-capability="denied.requiredCapability"
        :required-role="denied.role"
        :trace-id="denied.traceId"
        :switching="switching"
        :detail="`审计读取需要能力 ${capabilityLabel(denied.requiredCapability)}；当前演示角色「${auth.role ?? '未登录'}」不具备该能力，页面不会展示未授权数据。`"
        @switch="switchAccount"
      />

      <div v-else-if="error" class="erp__state" data-testid="erp-audit-error">
        <WEmpty
          icon="info"
          title="审计事件读取失败"
          :description="`${error.message}（错误码 ${error.code}）。${error.next}`"
        />
        <WButton size="sm" icon="refresh" @click="load">重试</WButton>
      </div>

      <WEmpty
        v-else-if="events.length === 0"
        data-testid="erp-audit-empty"
        icon="report"
        title="当前筛选没有审计事件"
        description="审计只记录真实发生的写操作/越权拒绝/登录登出（不造数）。P2 为进程内演示存储，重启后清空；可先触发一次写操作或调整筛选后重查。"
      />

      <table v-else class="erp-audit__table" data-testid="erp-audit-table">
        <thead>
          <tr>
            <th scope="col" style="width: 118px">时间</th>
            <th scope="col" style="width: 140px">操作</th>
            <th scope="col" style="width: 128px">操作者</th>
            <th scope="col" style="width: 84px">角色</th>
            <th scope="col">资源</th>
            <th scope="col" style="width: 84px">结果</th>
            <th scope="col" style="width: 210px">traceId</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="event in events"
            :key="event.eventId"
            data-testid="erp-audit-row"
            :data-event-id="event.eventId"
            :data-action="event.action"
            :data-result="event.result"
          >
            <td><span class="dim num">{{ formatIsoMinute(event.at) }}</span></td>
            <td>
              <WTag :tone="actionTone(event.action)" plain>{{ event.action }}</WTag>
            </td>
            <td>
              <span class="erp-audit__actor">{{ event.actorId }}</span>
            </td>
            <td><span class="dim">{{ event.actorRole }}</span></td>
            <td>
              <span class="erp-audit__resource">
                <code>{{ event.resourceType }}</code>
                <span>{{ event.resourceId || '—' }}</span>
              </span>
              <span v-if="event.maskedFields?.length" class="erp-audit__masked">
                已脱敏字段：{{ event.maskedFields.join(' / ') }}
              </span>
            </td>
            <td>
              <WTag :tone="resultMeta(event.result).tone">{{ resultMeta(event.result).label }}</WTag>
            </td>
            <td><code class="erp-audit__trace" :title="event.traceId">{{ event.traceId }}</code></td>
          </tr>
        </tbody>
      </table>

      <template #footer>
        <div class="erp-audit__foot">
          <span class="dim">
            第 {{ page }} / {{ maxPage }} 页 · 每页 {{ pageSize }} 条
            <template v-if="!canRead">（当前角色不可读审计）</template>
          </span>
          <div class="erp-audit__pager">
            <WButton
              size="sm"
              variant="ghost"
              :disabled="page <= 1 || loading"
              data-testid="erp-audit-prev"
              @click="goPage(page - 1)"
            >
              上一页
            </WButton>
            <WButton
              size="sm"
              variant="ghost"
              :disabled="page * pageSize >= total || loading"
              data-testid="erp-audit-next"
              @click="goPage(page + 1)"
            >
              下一页
            </WButton>
          </div>
        </div>
      </template>
    </WCard>

    <p class="erp-audit__note">
      审计口径（契约 v0.3.3 §11.9.2 / 架构 §11.3）：写操作（建单 / 迁移 / 回访、映射发布回滚、探测、对账、业主核验、
      登录 / 登出、403 拒绝）必须落审计；P2 为进程内演示存储，P5 接 ES / 对象存储。页面只读展示，不提供修改入口。
    </p>
  </section>
</template>

<style scoped>
.erp-audit {
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
  min-width: 0;
}

.erp__card-title {
  font-size: 12.5px;
  font-weight: var(--fw-semibold);
  color: var(--c-ink);
}

.erp__card-sub {
  margin-left: 6px;
  font-size: var(--fs-2xs);
  color: var(--c-faint);
}

.erp__state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--sp-2);
  padding: var(--sp-3);
}

.erp-audit__filters {
  display: flex;
  align-items: flex-end;
  gap: var(--sp-2);
  flex-wrap: wrap;
  padding: 10px 12px;
  border-bottom: 1px solid var(--c-line-2);
}

.erp-audit__field {
  display: flex;
  flex-direction: column;
  gap: 3px;
  font-size: 9px;
  color: var(--c-faint);
}

.erp-audit__field select,
.erp-audit__field input {
  height: 28px;
  min-width: 128px;
  padding: 0 8px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-sm);
  background: var(--c-card);
  font-size: var(--fs-2xs);
  color: var(--c-ink);
}

.erp-audit__field select:focus,
.erp-audit__field input:focus {
  outline: none;
  border-color: var(--c-brand);
  box-shadow: var(--sh-ring);
}

.erp-audit__stat {
  margin-left: auto;
  font-size: var(--fs-2xs);
}

.erp-audit__table {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--fs-sm);
}

.erp-audit__table th {
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

.erp-audit__table td {
  padding: 7px 12px;
  border-bottom: 1px solid #f0f3f5;
  color: var(--c-ink-2);
  vertical-align: middle;
}

.erp-audit__table tbody tr:last-child td {
  border-bottom: 0;
}

.erp-audit__table tbody tr:hover td {
  background: var(--c-bg-2);
}

.erp-audit__table code {
  font-family: var(--font-mono);
  font-size: 9.5px;
  color: var(--c-ink-2);
}

.erp-audit__actor {
  font-family: var(--font-mono);
  font-size: 9.5px;
  color: var(--c-ink-2);
}

.erp-audit__resource {
  display: inline-flex;
  align-items: baseline;
  gap: 6px;
}

.erp-audit__resource span {
  font-size: 9.5px;
  color: var(--c-muted);
}

.erp-audit__masked {
  display: block;
  margin-top: 2px;
  font-size: 9px;
  color: var(--c-faint);
}

.erp-audit__trace {
  display: inline-block;
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: bottom;
  color: var(--c-faint) !important;
}

.erp-audit__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
}

.erp-audit__pager {
  display: flex;
  gap: var(--sp-2);
}

.erp-audit__note {
  font-size: 9.5px;
  color: var(--c-faint);
  line-height: 1.6;
}
</style>
