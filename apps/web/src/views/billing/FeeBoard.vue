<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { WButton, WCard, WEmpty, WLoading, WTable, WTag } from '@/components'
import type { TableColumn } from '@/types'
import {
  ARREARS_BUCKETS,
  ARREARS_BUCKET_LABELS,
  api,
  ApiError,
  degradedText,
  errorNextStep,
  errorText,
  forbiddenDetailsOf,
  formatIsoMinute,
  formatMoney,
} from '@/api'
import type { ArrearsBucket, ArrearsListResult, DegradedInfo, DunningFunnel } from '@/api'

/**
 * P3 W11 费用看板（P3-017；契约 v1.1.0 §12.1 / §12.2）
 * ===========================================================================
 * 三块数字**全部来自 API**，无数据/失败态一律留白 + 说明，**不造数**：
 * 1) 欠费分层 D1—D30：`GET /fees/arrears` 的 `summary.buckets`（户数/金额由服务端汇总，前端只格式化）；
 * 2) 按账期趋势：聚合 `/fees/arrears` 返回行的**户数**（按房号去重）——金额不逐期前端加总
 *    （AGENTS.md §3「金额以 API 为准」；逐期金额暂无服务端汇总口径）；
 * 3) 催缴漏斗：`GET /dunning/funnel`（reached/responded/promised/paid + 4 个转化率 + `period`）。
 *
 * 权限：`/fees/arrears` 由 `bill.query` + 数据范围校验、`/dunning/funnel` 由 `dunning.task` 校验；
 * 任一 403 → 该块渲染无权限态（不渲染未授权金额），不影响另一块已授权的数据。
 */

const PAGE_SIZE = 100

/* ------------------------------------------------------------ 欠费分层 + 趋势 */

const arrears = ref<ArrearsListResult | null>(null)
const arrearsLoading = ref(false)
const arrearsError = ref('')
const arrearsDegraded = ref<DegradedInfo | undefined>(undefined)
const arrearsForbidden = ref<{ requiredCapability: string; role: string } | null>(null)

function codeOf(error: unknown): string {
  return error instanceof ApiError ? error.code : 'E-9001'
}

function degradedOf(error: unknown): DegradedInfo | undefined {
  return error instanceof ApiError ? error.degraded : undefined
}

function money(amount?: number | null): string {
  const text = formatMoney(amount)
  return text ? `¥${text}` : '—'
}

interface BucketCard {
  bucket: ArrearsBucket
  rooms: number | null
  amount: number | null
}

/** 四层卡：顺序固定 D1→D30，数据取 API `summary.buckets`（未加载显示「—」） */
const bucketCards = computed<BucketCard[]>(() => {
  const stats = arrears.value?.summary.buckets ?? []
  return ARREARS_BUCKETS.map((bucket) => {
    const stat = stats.find((item) => item.bucket === bucket)
    return { bucket, rooms: stat ? stat.rooms : null, amount: stat ? stat.amount : null }
  })
})
const totalRooms = computed<number | null>(() => arrears.value?.summary.totalRooms ?? null)
const totalAmount = computed<number | null>(() => arrears.value?.summary.totalAmount ?? null)

interface TrendRow {
  period: string
  /** 该账期内各分层的欠费户数（按房号去重，来自 API 返回行） */
  buckets: Record<ArrearsBucket, number>
  rooms: number
}

/**
 * 按账期 × 分层的户数趋势：**只做计数，不做金额加总**；
 * 户数按 `roomCode` 去重（同一户多账期分别计数，符合「按账期分层」口径）。
 */
const trend = computed<TrendRow[]>(() => {
  const byPeriod = new Map<string, Map<ArrearsBucket, Set<string>>>()
  for (const item of arrears.value?.items ?? []) {
    const bucketMap = byPeriod.get(item.period) ?? new Map<ArrearsBucket, Set<string>>()
    const rooms = bucketMap.get(item.bucket) ?? new Set<string>()
    rooms.add(item.roomCode)
    bucketMap.set(item.bucket, rooms)
    byPeriod.set(item.period, bucketMap)
  }
  return [...byPeriod.entries()]
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([period, bucketMap]) => {
      const counts = ARREARS_BUCKETS.reduce(
        (acc, bucket) => ({ ...acc, [bucket]: bucketMap.get(bucket)?.size ?? 0 }),
        {} as Record<ArrearsBucket, number>,
      )
      return { period, buckets: counts, rooms: Object.values(counts).reduce((sum, value) => sum + value, 0) }
    })
})

const trendTruncated = computed(() => (arrears.value?.total ?? 0) > (arrears.value?.items.length ?? 0))

const trendColumns: TableColumn[] = [
  { key: 'period', title: '账期', width: '96px' },
  { key: 'D1', title: 'D1 · 1—7 天', width: '96px', align: 'right' },
  { key: 'D7', title: 'D7 · 8—15 天', width: '96px', align: 'right' },
  { key: 'D15', title: 'D15 · 16—30 天', width: '104px', align: 'right' },
  { key: 'D30', title: 'D30 · 30 天以上', width: '112px', align: 'right' },
  { key: 'rooms', title: '合计户数', width: '92px', align: 'right' },
]
const trendRows = computed<Record<string, unknown>[]>(() =>
  trend.value.map((row) => ({
    period: row.period,
    D1: row.buckets.D1,
    D7: row.buckets.D7,
    D15: row.buckets.D15,
    D30: row.buckets.D30,
    rooms: row.rooms,
  })),
)

async function loadArrears(): Promise<void> {
  arrearsLoading.value = true
  arrearsError.value = ''
  try {
    const { data, degraded } = await api.fee.arrears({ page: 1, pageSize: PAGE_SIZE })
    arrears.value = data
    arrearsDegraded.value = degraded
    arrearsForbidden.value = null
  } catch (error) {
    arrears.value = null
    arrearsDegraded.value = degradedOf(error)
    const denied = forbiddenDetailsOf(error)
    if (denied) {
      arrearsForbidden.value = { requiredCapability: denied.requiredCapability, role: denied.role }
      return
    }
    arrearsError.value = `${errorText(codeOf(error))}；${errorNextStep(codeOf(error))}`
  } finally {
    arrearsLoading.value = false
  }
}

/* ------------------------------------------------------------------ 催缴漏斗 */

const funnel = ref<DunningFunnel | null>(null)
const funnelLoading = ref(false)
const funnelError = ref('')
const funnelDegraded = ref<DegradedInfo | undefined>(undefined)
const funnelForbidden = ref<{ requiredCapability: string; role: string } | null>(null)
/** 统计窗（可手填；省略 = 服务端默认最近 7 天，日期口径以 API 返回 `period` 为准） */
const funnelFrom = ref('')
const funnelTo = ref('')

const FUNNEL_STAGES: readonly { key: 'reached' | 'responded' | 'promised' | 'paid'; label: string; hint: string }[] = [
  { key: 'reached', label: '触达', hint: '窗口内成功触达户数' },
  { key: 'responded', label: '响应', hint: '有响应户数' },
  { key: 'promised', label: '承诺缴费', hint: '登记承诺户数' },
  { key: 'paid', label: '已缴费', hint: '窗口内缴费户数（ERP 实收为准）' },
]

/** 转化率展示：服务端给小数（保留 4 位），前端只格式化百分数，不重算比率 */
function pct(value?: number | null): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '—'
  return `${(value * 100).toFixed(1)}%`
}

const funnelRows = computed(() => {
  const data = funnel.value
  const conv = data?.conversion
  return FUNNEL_STAGES.map((stage, index) => {
    const prev = index > 0 ? FUNNEL_STAGES[index - 1]!.key : null
    const rate = !data || !conv || !prev
      ? null
      : prev === 'reached' && stage.key === 'responded'
        ? conv.reachedToResponded
        : prev === 'responded' && stage.key === 'promised'
          ? conv.respondedToPromised
          : prev === 'promised' && stage.key === 'paid'
            ? conv.promisedToPaid
            : null
    return { ...stage, count: data ? data[stage.key] : null, rate }
  })
})

async function loadFunnel(): Promise<void> {
  funnelLoading.value = true
  funnelError.value = ''
  try {
    const { data, degraded } = await api.dunning.funnel({
      from: /^\d{4}-\d{2}-\d{2}$/.test(funnelFrom.value) ? funnelFrom.value : undefined,
      to: /^\d{4}-\d{2}-\d{2}$/.test(funnelTo.value) ? funnelTo.value : undefined,
    })
    funnel.value = data
    funnelDegraded.value = degraded
    funnelForbidden.value = null
  } catch (error) {
    funnel.value = null
    funnelDegraded.value = degradedOf(error)
    const denied = forbiddenDetailsOf(error)
    if (denied) {
      funnelForbidden.value = { requiredCapability: denied.requiredCapability, role: denied.role }
      return
    }
    funnelError.value = `${errorText(codeOf(error))}；${errorNextStep(codeOf(error))}`
  } finally {
    funnelLoading.value = false
  }
}

onMounted(() => {
  void loadArrears()
  void loadFunnel()
})
</script>

<template>
  <section class="fboard" data-testid="fee-board">
    <div class="fboard__metabar">
      <span>费用看板：欠费分层 / 按账期趋势 / 催缴漏斗（数字全部来自 API，无数据不造数）</span>
      <WTag tone="g" plain>ERP 口径 · 前端不自算</WTag>
    </div>

    <!-- ① 欠费分层 D1—D30（summary.buckets） -->
    <WCard title="欠费分层" subtitle="逾期天数分层：户数与欠费金额由收费系统汇总（summary.buckets）">
      <template #extra>
        <WTag v-if="arrearsDegraded" tone="r" plain data-testid="board-degraded-tag">已降级</WTag>
      </template>
      <div data-testid="board-arrears">
        <div v-if="arrearsForbidden" class="fboard__state" data-testid="board-arrears-forbidden">
          <WEmpty
            icon="shield"
            title="当前角色无欠费台账权限"
            :description="`欠费分层需要能力 ${arrearsForbidden.requiredCapability}（当前演示角色「${arrearsForbidden.role || '未知'}」不具备，后端 403 E-2001）；本块不渲染任何金额，可切换管家 / 主管演示账号后重试。`"
            compact
          >
            <WButton size="sm" @click="loadArrears">重新校验一次</WButton>
          </WEmpty>
        </div>
        <div v-else-if="arrearsError" class="fboard__state" data-testid="board-arrears-error">
          <WEmpty icon="wallet" title="欠费分层暂时无法加载" :description="arrearsError" compact>
            <WButton size="sm" @click="loadArrears">重新加载</WButton>
          </WEmpty>
        </div>
        <template v-else>
          <WLoading v-if="arrearsLoading && !arrears" label="正在读取欠费分层…" />
          <template v-else>
            <div class="fboard__buckets">
              <div
                v-for="card in bucketCards"
                :key="card.bucket"
                class="fboard__bucket"
                :data-bucket="card.bucket"
                :data-testid="`board-bucket-${card.bucket}`"
              >
                <span class="fboard__bucket-lab">{{ ARREARS_BUCKET_LABELS[card.bucket] }}</span>
                <b class="num">{{ card.rooms === null ? '—' : card.rooms }}<small>户</small></b>
                <span class="fboard__bucket-amt">欠费合计 {{ card.amount === null ? '—' : money(card.amount) }}</span>
              </div>
            </div>
            <p class="fboard__note">
              <span data-testid="board-total-rooms">当前筛选 {{ totalRooms ?? '—' }} 户</span>
              ·
              <span data-testid="board-total-amount">欠费合计 {{ totalAmount === null ? '—' : money(totalAmount) }}</span>
              · 户数/金额均为 API 汇总（非前端求和）
            </p>
            <p v-if="arrearsDegraded" class="fboard__degradebar" data-testid="board-arrears-degraded">
              {{ degradedText(arrearsDegraded) }}
            </p>
          </template>
        </template>
      </div>
    </WCard>

    <!-- ② 按账期趋势（户数按房号去重；金额不逐期前端加总） -->
    <WCard title="按账期 × 分层趋势" subtitle="户数趋势来自 /fees/arrears 返回行（按房号去重）；金额不逐期前端加总">
      <div data-testid="board-trend">
        <WLoading v-if="arrearsLoading && !arrears" label="正在统计账期趋势…" />
        <WTable
          v-else
          :columns="trendColumns"
          :rows="trendRows"
          dense
          row-key="period"
          empty-text="暂无欠费分层行数据；接口失败或未授权时不展示趋势（不造数）。"
          data-testid="board-trend-table"
        >
          <template #empty>
            <WEmpty
              icon="chart"
              title="暂无账期趋势数据"
              :description="
                arrearsForbidden
                  ? '当前角色无欠费台账权限，趋势不渲染（不造数）。'
                  : arrearsError
                    ? '欠费分层接口失败，趋势不渲染（不造数）。'
                    : '当前没有欠费分层行；欠费为 0 或未逾期时趋势为空。'
              "
              compact
            />
          </template>
          <template #period="{ row }"><span class="num" data-testid="board-trend-period">{{ row.period }}</span></template>
          <template #footer>
            <div class="fboard__foot">
              <span>口径：每个账期按逾期分层统计户数（房号去重）；同一户多账期分别计数。</span>
              <span v-if="trendTruncated" class="fboard__warn" data-testid="board-trend-truncated">
                行数超过分页上限（pageSize={{ PAGE_SIZE }}），趋势仅统计前 {{ PAGE_SIZE }} 行。
              </span>
            </div>
          </template>
        </WTable>
      </div>
    </WCard>

    <!-- ③ 催缴漏斗（/dunning/funnel） -->
    <WCard title="催缴转化漏斗" subtitle="触达 → 响应 → 承诺 → 缴费；四个转化率来自服务端（分子/分母，保留 4 位小数）">
      <template #extra>
        <div class="fboard__filters">
          <input v-model="funnelFrom" class="fboard__input num" type="date" aria-label="统计起始日" data-testid="board-funnel-from" />
          <span>至</span>
          <input v-model="funnelTo" class="fboard__input num" type="date" aria-label="统计截止日" data-testid="board-funnel-to" />
          <WButton size="sm" :loading="funnelLoading" data-testid="board-funnel-refresh" @click="loadFunnel">查询</WButton>
        </div>
      </template>
      <div data-testid="board-funnel">
        <div v-if="funnelForbidden" class="fboard__state" data-testid="board-funnel-forbidden">
          <WEmpty
            icon="shield"
            title="当前角色无催缴统计权限"
            :description="`催缴漏斗需要能力 ${funnelForbidden.requiredCapability}（当前演示角色「${funnelForbidden.role || '未知'}」不具备，后端 403 E-2001）；本块不渲染任何数字。`"
            compact
          />
        </div>
        <div v-else-if="funnelError" class="fboard__state" data-testid="board-funnel-error">
          <WEmpty icon="chart" title="催缴漏斗暂时无法加载" :description="funnelError" compact>
            <WButton size="sm" @click="loadFunnel">重新加载</WButton>
          </WEmpty>
        </div>
        <WLoading v-else-if="funnelLoading && !funnel" label="正在读取催缴漏斗…" />
        <template v-else-if="funnel">
          <div class="fboard__stages">
            <div v-for="stage in funnelRows" :key="stage.key" class="fboard__stage" :data-stage="stage.key">
              <span class="fboard__stage-lab">{{ stage.label }}</span>
              <b class="num" :data-testid="`board-funnel-${stage.key}`">{{ stage.count === null ? '—' : stage.count }}<small>户</small></b>
              <span class="fboard__stage-rate" :data-testid="`board-funnel-rate-${stage.key}`">
                {{ stage.rate === null ? '—' : `转化 ${pct(stage.rate)}` }}
              </span>
              <span class="fboard__stage-hint">{{ stage.hint }}</span>
            </div>
          </div>
          <div class="fboard__conv">
            <div>
              <span>触达 → 响应</span>
              <b class="num" data-testid="board-funnel-conv-reachedToResponded">{{ pct(funnel.conversion.reachedToResponded) }}</b>
            </div>
            <div>
              <span>响应 → 承诺</span>
              <b class="num" data-testid="board-funnel-conv-respondedToPromised">{{ pct(funnel.conversion.respondedToPromised) }}</b>
            </div>
            <div>
              <span>承诺 → 缴费</span>
              <b class="num" data-testid="board-funnel-conv-promisedToPaid">{{ pct(funnel.conversion.promisedToPaid) }}</b>
            </div>
            <div>
              <span>触达 → 缴费</span>
              <b class="num" data-testid="board-funnel-conv-reachedToPaid">{{ pct(funnel.conversion.reachedToPaid) }}</b>
            </div>
          </div>
          <p class="fboard__note" data-testid="board-funnel-period">
            统计窗 {{ funnel.period.from }} ~ {{ funnel.period.to }}（含端点，业务日）· 生成于
            {{ formatIsoMinute(funnel.generatedAt) }} · 口径：触达 → 响应 → 承诺 → 缴费，户数由服务端统计
          </p>
          <p v-if="funnelDegraded" class="fboard__degradebar" data-testid="board-funnel-degraded">
            {{ degradedText(funnelDegraded) }}
          </p>
        </template>
        <WEmpty
          v-else
          icon="chart"
          title="暂无漏斗数据"
          description="统计窗内没有触达/响应/承诺/缴费记录时漏斗为空；不造数。"
          compact
        />
      </div>
    </WCard>
  </section>
</template>

<style scoped>
.fboard {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
  min-width: 0;
}

.fboard__metabar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  border: 1px solid var(--c-line-2);
  border-radius: var(--rd-md);
  background: var(--c-bg-2);
  font-size: var(--fs-2xs);
  color: var(--c-muted);
}

.fboard__metabar > span:first-child {
  margin-right: auto;
}

.fboard__state {
  padding: 8px 0;
}

.fboard__buckets {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--sp-3);
}

.fboard__bucket {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 12px 14px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-xl);
  background: var(--c-card);
  box-shadow: var(--sh-md);
}

.fboard__bucket-lab {
  font-size: var(--fs-xs);
  color: var(--c-muted);
}

.fboard__bucket b {
  font-size: var(--fs-2xl);
  font-weight: var(--fw-semibold);
  color: var(--c-ink);
  line-height: 1.15;
}

.fboard__bucket b small {
  margin-left: 2px;
  font-size: var(--fs-xs);
  font-weight: var(--fw-medium);
  color: var(--c-muted);
}

.fboard__bucket-amt {
  font-size: var(--fs-2xs);
  color: var(--c-faint);
}

.fboard__note {
  margin: 8px 0 0;
  font-size: var(--fs-2xs);
  line-height: var(--lh-base);
  color: var(--c-muted);
}

.fboard__degradebar {
  margin: 8px 0 0;
  padding: 6px 10px;
  border: 1px dashed var(--c-amber);
  border-radius: var(--rd-md);
  background: var(--c-amber-soft);
  color: #9a4b05;
  font-size: var(--fs-2xs);
}

.fboard__foot {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.fboard__warn {
  color: #9a4b05;
}

/* ---------- 漏斗 ---------- */
.fboard__filters {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-2xs);
  color: var(--c-muted);
}

.fboard__input {
  height: 26px;
  padding: 0 8px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-sm);
  background: var(--c-card);
  font-size: var(--fs-2xs);
  color: var(--c-ink);
}

.fboard__input:focus {
  outline: none;
  border-color: var(--c-brand);
  box-shadow: var(--sh-ring);
}

.fboard__stages {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--sp-3);
}

.fboard__stage {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 12px 14px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-xl);
  background: linear-gradient(180deg, #f7fbfa, var(--c-card));
}

.fboard__stage-lab {
  font-size: var(--fs-xs);
  color: var(--c-muted);
}

.fboard__stage b {
  font-size: var(--fs-2xl);
  font-weight: var(--fw-semibold);
  color: var(--c-ink);
  line-height: 1.15;
}

.fboard__stage b small {
  margin-left: 2px;
  font-size: var(--fs-xs);
  font-weight: var(--fw-medium);
  color: var(--c-muted);
}

.fboard__stage-rate {
  font-size: var(--fs-2xs);
  color: var(--c-brand);
  font-weight: var(--fw-semibold);
}

.fboard__stage-hint {
  font-size: var(--fs-2xs);
  color: var(--c-faint);
}

.fboard__conv {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--sp-3);
  margin-top: 10px;
}

.fboard__conv > div {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 10px;
  border: 1px solid var(--c-line-2);
  border-radius: var(--rd-md);
  background: var(--c-bg-2);
  font-size: var(--fs-2xs);
  color: var(--c-muted);
}

.fboard__conv b {
  font-size: var(--fs-md);
  color: var(--c-ink);
}
</style>
