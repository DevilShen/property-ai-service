<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { WButton, WCard, WEmpty, WLoading, WModal, WTag, useToast } from '@/components'
import {
  api,
  ApiError,
  DEFAULT_MAPPING_TRIAL_SAMPLE,
  errorNextStep,
  errorText,
  formatIsoMinute,
  MAPPING_TRIAL_THRESHOLD,
} from '@/api'
import type { MappingTrialReport, MappingVersion } from '@/api'
import {
  MAPPING_ACTION_LABEL,
  MAPPING_ALLOWED_ACTIONS,
  MAPPING_DIFF_KIND_LABEL,
  MAPPING_STATUS_META,
  formatDateTime,
  formatRate,
} from '../erp-data'

/**
 * 映射版本（P2 W7，契约 v0.3.2 §11.8.2；P5 W19 增连接器筛选与跨连接器隔离）
 * ---------------------------------------------------------------------------
 * 数据来源（全部真实 API）：
 * - `GET /api/v1/mappings?connectorId=`（v1.3.0：按连接器筛选；`data` 直接是数组）；
 * - `GET /api/v1/mappings/{id}` → 详情 `fields[]`（sourceField ↔ targetField / type / required / transform）；
 * - `POST /api/v1/mappings/{id}/trial`（默认 200 样本 → `MappingTrialReport`）；
 * - `POST /api/v1/mappings/{id}/publish`（仅 trial_passed 可发布，否则 409 `E-3002` + details.allowed）；
 * - `POST /api/v1/mappings/{id}/rollback`（回滚到上一个已发布版本）。
 *
 * P5 口径：映射是**连接器作用域**资源（每条映射带 `connectorId`）。跨连接器操作（发布体
 * `connectorId` 与映射自身不一致）→ 403 `E-2002` `details.reason=cross_connector`，
 * 页面把该 reason 转人话并提示「映射不可跨连接器复用」，不伪造成功。
 */
const toast = useToast()

/** 跨连接器隔离的契约 reason（OpenAPI v1.3.0 `cross_connector`；无 shared 常量，按契约字面量） */
const CROSS_CONNECTOR_REASON = 'cross_connector'

const list = ref<MappingVersion[]>([])
const listLoading = ref(false)
const listError = ref<{ code: string; message: string; next: string } | null>(null)
/** P5 W19：连接器筛选（'' = 全部连接器） */
const filterConnectorId = ref('')
const connectorOptions = ref<{ connectorId: string; name: string }[]>([])

const selectedId = ref('')
const detail = ref<MappingVersion | null>(null)
const detailLoading = ref(false)
const detailError = ref<{ code: string; message: string; next: string } | null>(null)

const trialReport = ref<MappingTrialReport | null>(null)
const trialing = ref(false)
const trialError = ref<{ code: string; message: string; next: string } | null>(null)
const trialElapsed = ref(0)
let trialTimer: number | undefined

const publishing = ref(false)
const rollingBack = ref(false)
const actionError = ref<{
  code: string
  message: string
  next: string
  allowed: string[]
  action: 'publish' | 'rollback'
} | null>(null)

const rollbackOpen = ref(false)
const rollbackReason = ref('线上回归/试跑差异，紧急止血回滚')

const sampleSize = DEFAULT_MAPPING_TRIAL_SAMPLE

const selected = computed<MappingVersion | null>(() => detail.value)
const statusMeta = computed(() =>
  selected.value ? MAPPING_STATUS_META[selected.value.status] : MAPPING_STATUS_META.draft,
)
const allowedActions = computed<string[]>(() =>
  selected.value ? [...MAPPING_ALLOWED_ACTIONS[selected.value.status]] : [],
)
const canPublish = computed(() => allowedActions.value.includes('publish'))
const canRollback = computed(() => allowedActions.value.includes('rollback'))
const fields = computed(() => selected.value?.fields ?? [])

function fillError(error: unknown): { code: string; message: string; next: string } {
  const apiError = error instanceof ApiError ? error : null
  const code = apiError?.code ?? 'E-9001'
  // P5 W19：跨连接器操作 → 契约 details.reason=cross_connector，转成人话（不是笼统的「无权限」）
  if (code === 'E-2002' && apiError?.details.reason === CROSS_CONNECTOR_REASON) {
    return {
      code,
      message: '映射属于单一连接器，不能跨连接器复用/发布（E-2002 cross_connector）',
      next: '请切换到该映射所属连接器后再操作；跨连接器请复制一份映射并单独试跑/发布',
    }
  }
  return { code, message: errorText(code), next: errorNextStep(code) }
}

/** 连接器筛选下拉选项（名称来自 GET /connectors，计数来自 GET /mappings 全量口径） */
const totalByConnector = ref<Record<string, number>>({})
const totalAll = ref(0)

async function loadConnectorOptions(): Promise<void> {
  try {
    const { data } = await api.connector.list()
    connectorOptions.value = data.map((item) => ({ connectorId: item.connectorId, name: item.name }))
  } catch {
    // 连接器列表失败不阻塞映射列表（筛选下拉退化为空）
    connectorOptions.value = []
  }
}

/** 全量映射计数（用于筛选下拉展示「该连接器 N 条」；不参与当前列表数据） */
async function loadCounts(): Promise<void> {
  try {
    const { data } = await api.mapping.list()
    const counts: Record<string, number> = {}
    for (const item of data) counts[item.connectorId] = (counts[item.connectorId] ?? 0) + 1
    totalByConnector.value = counts
    totalAll.value = data.length
  } catch {
    totalByConnector.value = {}
    totalAll.value = 0
  }
}

function allowListOf(error: unknown): string[] {
  const apiError = error instanceof ApiError ? error : null
  const allowed = apiError?.details.allowed
  return Array.isArray(allowed) ? allowed.filter((item): item is string => typeof item === 'string') : []
}

function allowedText(allowed: string[]): string {
  if (allowed.length === 0) return ''
  return `当前状态允许的动作：${allowed.map((action) => MAPPING_ACTION_LABEL[action] ?? action).join(' / ')}`
}

async function loadList(): Promise<void> {
  listLoading.value = true
  listError.value = null
  try {
    const { data } = await api.mapping.list({ connectorId: filterConnectorId.value || undefined })
    list.value = data
    const selectedStillVisible = data.some((item) => item.id === selectedId.value)
    if (data.length === 0) {
      // 当前筛选无映射：清空详情，不沿用上一个连接器的详情（避免串数据）
      selectedId.value = ''
      detail.value = null
      trialReport.value = null
      actionError.value = null
    } else if (!selectedStillVisible) {
      selectedId.value = ''
      await selectMapping(data[0]!.id)
    } else {
      // 列表刷新后同步详情（发布/回滚会改状态）
      await loadDetail()
    }
  } catch (error) {
    list.value = []
    listError.value = fillError(error)
  } finally {
    listLoading.value = false
  }
}

/** 切换连接器筛选：清空选中 → 重新按 connectorId 拉取（跨连接器隔离，不保留上一个连接器的详情） */
async function applyConnectorFilter(): Promise<void> {
  selectedId.value = ''
  detail.value = null
  trialReport.value = null
  trialError.value = null
  actionError.value = null
  await loadList()
}

async function loadDetail(): Promise<void> {
  if (!selectedId.value) return
  detailLoading.value = true
  detailError.value = null
  try {
    const { data } = await api.mapping.get(selectedId.value)
    detail.value = data
  } catch (error) {
    detail.value = null
    detailError.value = fillError(error)
  } finally {
    detailLoading.value = false
  }
}

async function selectMapping(mappingId: string): Promise<void> {
  selectedId.value = mappingId
  trialReport.value = null
  trialError.value = null
  actionError.value = null
  await loadDetail()
}

function stopTrialTimer(): void {
  if (trialTimer !== undefined) {
    window.clearInterval(trialTimer)
    trialTimer = undefined
  }
}

/** 试跑 200 样本（进度态用真实耗时秒数，不伪造百分比/结果） */
async function runTrial(): Promise<void> {
  if (!selectedId.value || trialing.value) return
  trialing.value = true
  trialReport.value = null
  trialError.value = null
  actionError.value = null
  trialElapsed.value = 0
  const startedAt = Date.now()
  stopTrialTimer()
  trialTimer = window.setInterval(() => {
    trialElapsed.value = Math.floor((Date.now() - startedAt) / 1000)
  }, 250)
  try {
    const { data } = await api.mapping.trial(
      selectedId.value,
      sampleSize,
      `ui-trial-${selectedId.value}-${Date.now().toString(36)}`,
    )
    trialReport.value = data
    const passedText = data.passed ? '通过' : '未通过'
    toast.success(
      `试跑${passedText}：${data.checked} 样本 · 差异 ${data.diffCount} 条 · diffRate ${formatRate(data.diffRate)}（阈值 ${formatRate(data.threshold)}）`,
    )
    // 试跑通过会把 draft/rolled_back 推进到 trial_passed：刷新列表与详情
    await loadList()
  } catch (error) {
    trialError.value = fillError(error)
    toast.error(`试跑未完成：${trialError.value.message}；${trialError.value.next}`)
  } finally {
    trialing.value = false
    stopTrialTimer()
  }
}

async function publish(): Promise<void> {
  if (!selectedId.value || publishing.value || !canPublish.value) {
    if (selected.value && !canPublish.value) {
      toast.warning(`当前版本状态「${statusMeta.value.label}」不能发布：需先试跑通过（trial_passed）`)
    }
    return
  }
  publishing.value = true
  actionError.value = null
  try {
    const { data } = await api.mapping.publish(
      selectedId.value,
      `ui-publish-${selectedId.value}-${Date.now().toString(36)}`,
    )
    toast.success(`映射已发布：${data.name} ${data.version}（状态已变更为已发布）`)
    await loadList()
  } catch (error) {
    actionError.value = { ...fillError(error), allowed: allowListOf(error), action: 'publish' }
    toast.error(`发布未完成：${actionError.value.message}；${actionError.value.next}`)
  } finally {
    publishing.value = false
  }
}

function openRollback(): void {
  if (!canRollback.value) {
    toast.warning(`当前版本状态「${statusMeta.value.label}」没有回滚对象：仅已发布版本可回滚`)
    return
  }
  actionError.value = null
  rollbackOpen.value = true
}

async function confirmRollback(): Promise<void> {
  if (!selectedId.value || rollingBack.value) return
  rollingBack.value = true
  actionError.value = null
  try {
    const { data } = await api.mapping.rollback(
      selectedId.value,
      { reason: rollbackReason.value.trim() || undefined },
      `ui-rollback-${selectedId.value}-${Date.now().toString(36)}`,
    )
    rollbackOpen.value = false
    toast.success(`已回滚：当前生效版本 ${data.version}（${MAPPING_STATUS_META[data.status].label}）`)
    await loadList()
  } catch (error) {
    rollbackOpen.value = false
    actionError.value = { ...fillError(error), allowed: allowListOf(error), action: 'rollback' }
    toast.error(`回滚未完成：${actionError.value.message}；${actionError.value.next}`)
  } finally {
    rollingBack.value = false
  }
}

function statusOf(row: MappingVersion) {
  return MAPPING_STATUS_META[row.status]
}

loadList()
void loadConnectorOptions()
void loadCounts()

onBeforeUnmount(stopTrialTimer)
</script>

<template>
  <section class="erp-map" data-testid="erp-mapping-panel">
    <!-- P5 W19：按连接器筛选（GET /mappings?connectorId=）+ 跨连接器隔离提示 -->
    <div class="erp-map__toolbar">
      <label class="erp-map__filter">
        <span>连接器筛选（GET /mappings?connectorId=）</span>
        <select
          v-model="filterConnectorId"
          data-testid="erp-mapping-filter-connector"
          @change="applyConnectorFilter"
        >
          <option value="">全部连接器（{{ totalAll }} 条）</option>
          <option v-for="option in connectorOptions" :key="option.connectorId" :value="option.connectorId">
            {{ option.name }}（{{ totalByConnector[option.connectorId] ?? 0 }} 条）
          </option>
        </select>
      </label>
      <WTag
        :tone="filterConnectorId ? 'b' : 'n'"
        plain
        data-testid="erp-mapping-filter-active"
        :data-connector="filterConnectorId || 'all'"
      >
        {{ filterConnectorId || '全部连接器' }} · {{ list.length }} 条
      </WTag>
      <p class="erp-map__isolation" data-testid="erp-mapping-isolation-hint">
        跨连接器隔离：映射是**连接器作用域**资源，每条映射只属于一个 `connectorId`；跨连接器操作会返回
        403 E-2002（cross_connector）——映射不可跨连接器复用或发布，请切到该映射所属连接器后再试跑/发布。
      </p>
    </div>

    <div class="erp-map__split">
      <WCard :padded="false" class="erp-map__list">
        <template #head>
          <span class="erp__card-title">映射版本（{{ list.length }}）</span>
          <span class="erp__card-sub">GET /api/v1/mappings</span>
        </template>
        <template #extra>
          <WButton size="sm" icon="refresh" :loading="listLoading" data-testid="erp-mapping-refresh" @click="loadList">
            刷新
          </WButton>
        </template>

        <WLoading v-if="listLoading && list.length === 0" center label="正在读取映射版本列表…" />

        <div v-else-if="listError" class="erp__state" data-testid="erp-mapping-list-error">
          <WEmpty
            icon="info"
            title="映射版本读取失败"
            :description="`${listError.message}（错误码 ${listError.code}）。${listError.next}`"
          />
          <WButton size="sm" icon="refresh" @click="loadList">重试</WButton>
        </div>

        <WEmpty
          v-else-if="list.length === 0"
          icon="mapping"
          title="暂无映射版本"
          description="映射版本由 BFF 注册表提供；无记录时不造数，请先完成连接器接入与映射配置。"
        />

        <div v-else class="erp-map__items">
          <button
            v-for="item in list"
            :key="item.id"
            type="button"
            class="erp-map__item"
            :class="{ 'is-on': selectedId === item.id }"
            data-testid="erp-mapping-row"
            :data-mapping="item.id"
            :data-status="item.status"
            :data-connector="item.connectorId"
            @click="selectMapping(item.id)"
          >
            <div class="erp-map__item-head">
              <b>{{ item.name }}</b>
              <WTag :tone="statusOf(item).tone">{{ statusOf(item).label }}</WTag>
            </div>
            <div class="erp-map__item-meta">
              <code>{{ item.id }}</code>
              <span>{{ item.version }}</span>
              <span>{{ item.fieldCount }} 字段</span>
              <span class="dim">{{ formatIsoMinute(item.updatedAt) }}</span>
            </div>
            <div class="erp-map__item-conn" data-testid="erp-mapping-row-connector">
              <span>连接器</span>
              <code>{{ item.connectorId }}</code>
            </div>
          </button>
        </div>
      </WCard>

      <div class="erp-map__main">
        <WCard :padded="false" data-testid="erp-mapping-detail">
          <template #head>
            <span class="erp__card-title">
              映射详情{{ selected ? ` · ${selected.name}` : '' }}
            </span>
            <span class="erp__card-sub">发布前必须试跑通过（diffRate ≤ {{ formatRate(MAPPING_TRIAL_THRESHOLD) }}）</span>
          </template>
          <template #extra>
            <WTag v-if="selected" :tone="statusMeta.tone" data-testid="erp-mapping-status">
              {{ statusMeta.label }}
            </WTag>
            <WButton
              size="sm"
              icon="refresh"
              :loading="trialing"
              :disabled="!selectedId"
              data-testid="erp-mapping-trial"
              @click="runTrial"
            >
              {{ trialing ? `试跑中（${trialElapsed}s）` : `试跑 ${sampleSize} 样本` }}
            </WButton>
            <WButton
              size="sm"
              variant="primary"
              icon="check"
              :loading="publishing"
              :disabled="!canPublish"
              data-testid="erp-mapping-publish"
              @click="publish"
            >
              发布
            </WButton>
            <WButton
              size="sm"
              icon="refresh"
              :disabled="!canRollback"
              data-testid="erp-mapping-rollback"
              @click="openRollback"
            >
              回滚
            </WButton>
          </template>

          <WLoading v-if="detailLoading" center label="正在读取映射字段明细…" />

          <div v-else-if="detailError" class="erp__state" data-testid="erp-mapping-detail-error">
            <WEmpty
              icon="info"
              title="映射详情读取失败"
              :description="`${detailError.message}（错误码 ${detailError.code}）。${detailError.next}`"
            />
            <WButton size="sm" icon="refresh" @click="loadDetail">重试</WButton>
          </div>

          <WEmpty
            v-else-if="!selected"
            icon="mapping"
            title="请选择一个映射版本"
            description="左侧列表来自 GET /api/v1/mappings；选中后可查看字段明细、试跑、发布与回滚。"
          />

          <template v-else>
            <div class="erp-map__meta" data-testid="erp-mapping-meta">
              <div><span>映射 ID</span><b><code>{{ selected.id }}</code></b></div>
              <div><span>连接器</span><b>{{ selected.connectorId }}</b></div>
              <div><span>版本</span><b class="num">{{ selected.version }}</b></div>
              <div><span>字段数</span><b class="num">{{ selected.fieldCount }}</b></div>
              <div><span>更新时间</span><b>{{ formatDateTime(selected.updatedAt) }}</b></div>
              <div><span>发布人</span><b>{{ selected.operator || '—' }}</b></div>
              <div><span>允许动作</span><b>{{ allowedActions.map((a) => MAPPING_ACTION_LABEL[a] ?? a).join(' / ') || '—' }}</b></div>
              <div><span>生效时间</span><b>{{ selected.publishedAt ? formatDateTime(selected.publishedAt) : '未发布' }}</b></div>
            </div>

            <table class="erp-map__fields" data-testid="erp-mapping-fields">
              <thead>
                <tr>
                  <th scope="col" style="width: 168px">ERP 源字段</th>
                  <th scope="col" style="width: 148px">标准字段</th>
                  <th scope="col" style="width: 78px">类型</th>
                  <th scope="col" style="width: 52px">必填</th>
                  <th scope="col">转换规则</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="field in fields" :key="`${field.sourceField}-${field.targetField}`" data-testid="erp-mapping-field-row">
                  <td><code>{{ field.sourceField }}</code></td>
                  <td>{{ field.targetField }}</td>
                  <td><span class="dim">{{ field.type }}</span></td>
                  <td><span :class="field.required ? 'erp-map__req' : 'dim'">{{ field.required ? '*' : '○' }}</span></td>
                  <td><span class="erp__evidence">{{ field.transform || '—' }}</span></td>
                </tr>
              </tbody>
            </table>
            <p v-if="fields.length === 0" class="erp-map__note">
              详情响应未返回字段明细（fields 为空）；不造字段，请核对映射配置。
            </p>
          </template>
        </WCard>

        <!-- 试跑进度 / 报告 / 差异 -->
        <WCard v-if="trialing" data-testid="erp-mapping-trial-progress" :padded="false">
          <div class="erp-map__trial-progress">
            <WLoading :label="`正在回放 ${sampleSize} 条样本逐字段比对（已 ${trialElapsed}s）…`" />
            <div class="erp-map__bar"><i /></div>
            <p class="dim">试跑只读、不改业务主数据；通过后版本推进 trial_passed，失败不推进状态。</p>
          </div>
        </WCard>

        <WCard v-if="trialError" data-testid="erp-mapping-trial-error">
          <template #head><span class="erp__card-title">试跑未完成</span></template>
          <template #extra><WTag tone="a" plain>{{ trialError.code }}</WTag></template>
          <p class="erp-map__fail">{{ trialError.message }}（错误码 {{ trialError.code }}）。{{ trialError.next }}</p>
        </WCard>

        <WCard v-if="trialReport" data-testid="erp-mapping-trial-report" :padded="false">
          <template #head>
            <span class="erp__card-title">试跑报告 · {{ trialReport.runId }}</span>
            <span class="erp__card-sub">
              {{ formatDateTime(trialReport.startedAt) }} → {{ formatDateTime(trialReport.finishedAt) }}
            </span>
          </template>
          <template #extra>
            <WTag :tone="trialReport.passed ? 'g' : 'a'" data-testid="erp-mapping-trial-passed">
              {{ trialReport.passed ? '通过' : '未通过' }}
            </WTag>
          </template>
          <div class="erp-map__trial">
            <div class="erp-map__trial-stats">
              <div><span>样本 checked</span><b class="num">{{ trialReport.checked }}</b></div>
              <div><span>一致 matched</span><b class="num">{{ trialReport.matched }}</b></div>
              <div><span>差异 diffCount</span><b class="num" :class="{ 'is-warn': trialReport.diffCount > 0 }">{{ trialReport.diffCount }}</b></div>
              <div><span>差异率 diffRate</span><b class="num" data-testid="erp-mapping-trial-diff-rate">{{ formatRate(trialReport.diffRate) }}</b></div>
              <div><span>阈值 threshold</span><b class="num">{{ formatRate(trialReport.threshold) }}</b></div>
              <div><span>结论 passed</span><b :class="trialReport.passed ? 'is-ok' : 'is-warn'">{{ trialReport.passed }}</b></div>
            </div>
            <div v-if="trialReport.items.length" class="erp-map__diffs" data-testid="erp-mapping-trial-diff">
              <b>差异清单（{{ trialReport.items.length }} 条，样本主键不含敏感信息）</b>
              <div v-for="item in trialReport.items" :key="`${item.key}-${item.kind}-${item.targetField}`" class="erp-map__diff" data-testid="erp-mapping-trial-diff-item">
                <code>{{ item.key }}</code>
                <WTag tone="a" plain>{{ MAPPING_DIFF_KIND_LABEL[item.kind] }}</WTag>
                <span>{{ item.sourceField || '—' }} → {{ item.targetField || '—' }}</span>
                <span class="dim">期望 {{ item.expected ?? '—' }} / 实际 {{ item.actual ?? '—' }}</span>
              </div>
            </div>
            <p v-else class="dim">差异清单为空：{{ trialReport.checked }} 条样本逐字段一致。</p>
          </div>
        </WCard>

        <!-- 409 等非法操作：错误码人话 + allowed 提示 -->
        <WCard v-if="actionError" data-testid="erp-mapping-action-error">
          <template #head>
            <span class="erp__card-title">{{ actionError.action === 'publish' ? '发布' : '回滚' }}未完成</span>
          </template>
          <template #extra><WTag tone="a" plain>{{ actionError.code }}</WTag></template>
          <p class="erp-map__fail">{{ actionError.message }}（错误码 {{ actionError.code }}）。{{ actionError.next }}</p>
          <p v-if="actionError.allowed.length" class="erp-map__allowed" data-testid="erp-mapping-allowed">
            {{ allowedText(actionError.allowed) }}
          </p>
        </WCard>
      </div>
    </div>

    <WModal v-model:open="rollbackOpen" title="回滚映射版本" width="480px">
      <div class="erp-map__rollback">
        <p>
          回滚把当前已发布版本置为「已回滚」，并让上一个已发布版本重新生效（整体版本，≤1 分钟生效；不回写业务主数据）。
        </p>
        <label class="erp-map__label" for="erp-rollback-reason">回滚原因（写发布审计）</label>
        <input id="erp-rollback-reason" v-model="rollbackReason" class="erp-map__input" data-testid="erp-mapping-rollback-reason" />
        <p class="dim">目标版本：上一个已发布版本（toVersion 省略；不可用时会返回 409 E-3002 并给出 allowed 提示）。</p>
      </div>
      <template #footer>
        <WButton size="sm" variant="ghost" @click="rollbackOpen = false">取消</WButton>
        <WButton size="sm" variant="primary" :loading="rollingBack" data-testid="erp-mapping-rollback-confirm" @click="confirmRollback">
          确认回滚
        </WButton>
      </template>
    </WModal>
  </section>
</template>

<style scoped>
.erp-map {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
  min-width: 0;
}

/* ---------- P5 W19：连接器筛选 + 跨连接器隔离提示 ---------- */
.erp-map__toolbar {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  flex-wrap: wrap;
  padding: 9px 12px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-xl);
  background: var(--c-card);
  box-shadow: var(--sh-md);
}

.erp-map__filter {
  display: flex;
  flex-direction: column;
  gap: 3px;
  font-size: 9px;
  color: var(--c-faint);
}

.erp-map__filter select {
  height: 28px;
  min-width: 220px;
  padding: 0 8px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-sm);
  background: var(--c-card);
  font-size: var(--fs-2xs);
  color: var(--c-ink);
}

.erp-map__filter select:focus {
  outline: none;
  border-color: var(--c-brand);
  box-shadow: var(--sh-ring);
}

.erp-map__isolation {
  flex: 1 1 380px;
  min-width: 0;
  font-size: 9.5px;
  color: var(--c-muted);
  line-height: 1.55;
}

.erp-map__item-conn {
  display: flex;
  align-items: center;
  gap: 5px;
  padding-top: 4px;
  border-top: 1px dashed var(--c-line-2);
  font-size: 9px;
  color: var(--c-faint);
}

.erp-map__item-conn code {
  font-family: var(--font-mono);
  font-size: 9px;
  color: var(--c-ink-2);
}

.erp-map__split {
  display: grid;
  grid-template-columns: 316px minmax(0, 1fr);
  gap: var(--sp-3);
  align-items: start;
}

.erp-map__main {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
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

.erp__evidence {
  font-size: var(--fs-2xs);
  color: var(--c-muted);
  line-height: var(--lh-base);
}

.erp-map__items {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px;
}

.erp-map__item {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 7px 9px;
  border: 1px solid transparent;
  border-radius: var(--rd-md);
  background: var(--c-card);
  text-align: left;
  transition: background-color var(--tr-fast), border-color var(--tr-fast);
}

.erp-map__item:hover {
  background: var(--c-bg-2);
}

.erp-map__item.is-on {
  border-color: var(--c-brand);
  background: var(--c-brand-soft);
}

.erp-map__item:focus-visible {
  outline: none;
  box-shadow: var(--sh-ring);
}

.erp-map__item-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
}

.erp-map__item-head b {
  font-size: var(--fs-xs);
  color: var(--c-ink-2);
}

.erp-map__item-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  font-size: 9.5px;
  color: var(--c-muted);
}

.erp-map__item-meta code {
  font-family: var(--font-mono);
  color: var(--c-faint);
}

.erp-map__meta {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 6px;
  padding: 10px 12px;
  border-bottom: 1px solid var(--c-line-2);
}

.erp-map__meta div {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.erp-map__meta span {
  font-size: 9px;
  color: var(--c-faint);
}

.erp-map__meta b {
  font-size: var(--fs-xs);
  color: var(--c-ink-2);
  font-weight: var(--fw-medium);
}

.erp-map__meta code {
  font-family: var(--font-mono);
  font-size: 9.5px;
}

.erp-map__fields {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--fs-sm);
}

.erp-map__fields th {
  padding: 7px 12px;
  background: var(--c-bg-2);
  border-bottom: 1px solid var(--c-line);
  color: var(--c-muted);
  font-size: 9.5px;
  font-weight: var(--fw-semibold);
  letter-spacing: 0.6px;
  text-align: left;
  white-space: nowrap;
}

.erp-map__fields td {
  padding: 6px 12px;
  border-bottom: 1px solid #f0f3f5;
  color: var(--c-ink-2);
  vertical-align: middle;
}

.erp-map__fields tbody tr:last-child td {
  border-bottom: 0;
}

.erp-map__fields code {
  font-family: var(--font-mono);
  font-size: 9.5px;
  padding: 1px 4px;
  border-radius: var(--rd-xs);
  background: var(--c-bg-2);
  border: 1px solid var(--c-line-2);
  white-space: nowrap;
}

.erp-map__req {
  color: var(--c-red);
  font-weight: var(--fw-semibold);
}

.erp-map__note {
  padding: 8px 12px;
  font-size: var(--fs-2xs);
  color: var(--c-amber);
}

.erp-map__trial,
.erp-map__rollback {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px 12px;
}

.erp-map__trial-progress {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 12px;
}

/* 进度条为不确定态（真实结果未返回前不伪造百分比） */
.erp-map__bar {
  height: 5px;
  border-radius: var(--rd-pill);
  background: var(--c-line-2);
  overflow: hidden;
}

.erp-map__bar i {
  display: block;
  width: 38%;
  height: 100%;
  border-radius: var(--rd-pill);
  background: var(--c-brand-gradient);
  animation: erp-map-slide 1.1s ease-in-out infinite alternate;
}

@keyframes erp-map-slide {
  from {
    transform: translateX(-12%);
  }

  to {
    transform: translateX(180%);
  }
}

.erp-map__trial-stats {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 6px;
}

.erp-map__trial-stats div {
  display: flex;
  flex-direction: column;
  gap: 1px;
  padding: 7px 9px;
  border-radius: var(--rd-sm);
  background: var(--c-bg-2);
}

.erp-map__trial-stats span {
  font-size: 9px;
  color: var(--c-faint);
}

.erp-map__trial-stats b {
  font-size: var(--fs-sm);
  color: var(--c-ink-2);
}

.erp-map__trial-stats b.is-warn {
  color: var(--c-amber);
}

.erp-map__trial-stats b.is-ok {
  color: var(--c-brand);
}

.erp-map__diffs {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 8px 10px;
  border: 1px solid var(--c-line-2);
  border-radius: var(--rd-md);
  background: var(--c-bg-2);
  font-size: var(--fs-2xs);
  color: var(--c-muted);
}

.erp-map__diffs b {
  color: var(--c-ink-2);
}

.erp-map__diff {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  flex-wrap: wrap;
}

.erp-map__diff code {
  font-family: var(--font-mono);
  font-size: 9px;
  color: var(--c-ink-2);
}

.erp-map__fail {
  padding: 8px 12px;
  font-size: var(--fs-xs);
  color: var(--c-amber);
  line-height: var(--lh-base);
}

.erp-map__allowed {
  margin: 0 12px 10px;
  padding: 6px 9px;
  border-radius: var(--rd-sm);
  background: var(--c-amber-soft);
  color: #9a4b05;
  font-size: var(--fs-2xs);
}

.erp-map__label {
  font-size: var(--fs-2xs);
  color: var(--c-muted);
}

.erp-map__input {
  height: 30px;
  padding: 0 9px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-sm);
  background: var(--c-card);
  font-size: var(--fs-xs);
  color: var(--c-ink);
}

.erp-map__input:focus {
  outline: none;
  border-color: var(--c-brand);
  box-shadow: var(--sh-ring);
}

@media (max-width: 1279px) {
  .erp-map__split {
    grid-template-columns: minmax(0, 1fr);
  }

  .erp-map__meta,
  .erp-map__trial-stats {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
