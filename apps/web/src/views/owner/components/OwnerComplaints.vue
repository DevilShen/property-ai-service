<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { WButton, WEmpty, WLoading, WModal, WTag, useToast } from '@/components'
import { useAuthStore } from '@/stores/auth'
import { successReceipt } from '@/content'
import { actionSeed, useIdempotencyKey } from '@/composables/useIdempotencyKey'
import { capabilityLabel } from '@/auth/capabilities'
import {
  api,
  ApiError,
  COMPLAINT_LEVELS,
  errorNextStep,
  errorText,
  forbiddenDetailsOf,
  formatIsoMinute,
  mockBadgeText,
} from '@/api'
import type { Complaint, ComplaintLevel } from '@/api'
import {
  COMPLAINT_CATEGORY_OPTIONS,
  COMPLAINT_LEVEL_TONES,
  OWNER_STATUS_LABELS,
  categoryLabel,
  levelLabelShort,
  maskRoomCode,
  ownerProgressView,
  ownerRoomOfActorId,
} from '@/views/complaint/shared'

/**
 * 业主端「我的投诉」（P3-021 业主入口 + 进度，契约 v1.1.0 §12.3）
 * ===========================================================================
 * 口径（README §7.2 / 交付物 C）：
 * - owner 只能提交/查看**本户**投诉（服务端 `ComplaintService` 强制数据范围，跨房 403 `E-2002`）；
 * - 业主侧**只展示业主可见文案**：状态 / 首响时间 / 处理说明；
 *   **不展示**内部升级人（`escalatedTo`）、audit[] 里的岗位姓名与升级原因（隐私口径 AGENTS.md NEVER #8）；
 * - 提交与重开都是写操作：带 `Idempotency-Key`（同一次动作重试复用同键），成功/失败都给回执；
 * - 不编造数据：`slaDueAt` / 状态 / 进度节点全部来自 API；投诉为演示存储（重启清空）。
 *
 * 组件复用：`/owner` 内嵌卡片（OwnerView）与 `/complaints`（owner 直达）共用本组件。
 */
const auth = useAuthStore()
const toast = useToast()
const idempotency = useIdempotencyKey()

const ownRoom = computed(() => ownerRoomOfActorId(auth.actor?.actorId))

/* ------------------------------------------------------------------ 列表 */

const items = ref<Complaint[]>([])
const loading = ref(false)
const error = ref('')
const deniedText = ref('')
const degradedNote = ref('')
const nowMs = ref(Date.now())
let tickTimer: number | undefined

async function loadMine(): Promise<void> {
  loading.value = true
  error.value = ''
  deniedText.value = ''
  degradedNote.value = ''
  try {
    // 服务端按 owner 数据范围过滤本户；一次取 100 条（契约 pageSize 上限）够演示与自查
    const { data, degraded } = await api.complaint.list({ page: 1, pageSize: 100 })
    items.value = data.items
    if (degraded) degradedNote.value = '列表来自缓存快照（已降级），不代表实时结果。'
  } catch (caught) {
    items.value = []
    const denied = forbiddenDetailsOf(caught)
    if (denied) deniedText.value = `投诉记录需要能力 ${capabilityLabel(denied.requiredCapability)}（当前演示角色不具备）。`
    else error.value = apiMessage(caught)
  } finally {
    loading.value = false
  }
}

/**
 * 用户点「刷新」：请求本身可能很快返回且数据未变（无 loading 采样），
 * 因此按 P3 门禁口径**必须补可见回执**——成功 Toast「已刷新 · N 条投诉（时间）」，
 * 失败给错误 Toast；不伪造条数，条数取自本次 `GET /complaints` 的返回。
 */
async function refresh(): Promise<void> {
  await loadMine()
  if (deniedText.value || error.value) {
    toast.error(`投诉列表刷新未完成：${deniedText.value || error.value}`)
    return
  }
  const stamp = new Date().toLocaleTimeString('zh-CN', { hour12: false })
  const degradedSuffix = degradedNote.value ? ' · 列表来自缓存快照（已降级）' : ''
  toast.success(`已刷新 · ${items.value.length} 条投诉${degradedSuffix}（${stamp}）`)
}

onMounted(() => {
  tickTimer = window.setInterval(() => {
    nowMs.value = Date.now()
  }, 1000)
  void loadMine()
})

onBeforeUnmount(() => {
  if (tickTimer) window.clearInterval(tickTimer)
})

/* ------------------------------------------------------------------ 提交 */

const submitOpen = ref(false)
const submitting = ref(false)
const submitError = ref('')
const form = reactive({
  roomCode: '',
  category: 'noise',
  level: 'L3' as ComplaintLevel,
  content: '',
  contactTail4: '',
})

function openSubmit(): void {
  form.roomCode = ownRoom.value
  form.category = 'noise'
  form.level = 'L3'
  form.content = ''
  form.contactTail4 = ''
  submitError.value = ''
  submitOpen.value = true
}

async function submit(): Promise<void> {
  submitError.value = ''
  if (!form.roomCode.trim()) {
    submitError.value = '请填写房号（本户房号，如 3-2-1802）'
    return
  }
  if (!form.content.trim()) {
    submitError.value = '请填写投诉内容（服务端强制 roomCode + category + content）'
    return
  }
  if (form.contactTail4.trim() && !/^\d{4}$/.test(form.contactTail4.trim())) {
    submitError.value = '联系方式只收手机号后四位（4 位数字），不接收完整号码'
    return
  }
  submitting.value = true
  const seed = actionSeed('complaint.create', {
    roomCode: form.roomCode.trim(),
    category: form.category,
    level: form.level,
    content: form.content.trim(),
    contactTail4: form.contactTail4.trim(),
  })
  try {
    const { data } = await api.complaint.create(
      {
        roomCode: form.roomCode.trim(),
        category: form.category,
        level: form.level,
        content: form.content.trim(),
        contactTail4: form.contactTail4.trim() || undefined,
      },
      idempotency.ensure(seed),
    )
    idempotency.settle()
    submitOpen.value = false
    toast.success(
      successReceipt(
        '投诉已受理，物业将人工处理',
        `${data.complaintId} · ${levelLabelShort(data.level)} · 首响 SLA 截止 ${formatIsoMinute(data.slaDueAt)}`,
      ),
    )
    await loadMine()
    openProgress(items.value.find((item) => item.complaintId === data.complaintId) ?? data)
  } catch (caught) {
    submitError.value = submitMessage(caught)
    toast.error(`投诉提交未完成：${submitError.value}`)
  } finally {
    submitting.value = false
  }
}

/** 提交失败 → 业主可执行的人话（跨房 E-2002 / 缺要素 E-1001 / 鉴权 401 各有口径） */
function submitMessage(caught: unknown): string {
  const denied = forbiddenDetailsOf(caught)
  if (denied) {
    if (denied.reason === 'out_of_scope') {
      return `只能提交本户（${ownRoom.value || '本户房号'}）的投诉：跨房提交被数据范围校验拒绝（E-2002），请改回本户房号后重试。`
    }
    return `无权限提交投诉：需要能力 ${capabilityLabel(denied.requiredCapability)}（E-2001）。`
  }
  if (caught instanceof ApiError) {
    if (caught.httpStatus === 400) return `投诉要素不完整：${errorNextStep(caught.code)}`
    return `${errorText(caught.code)}；${errorNextStep(caught.code)}`
  }
  return '请求失败：请稍后重试或转人工'
}

/* ------------------------------------------------------------- 进度 / 重开 */

const progressOpen = ref(false)
const selected = ref<Complaint | null>(null)
const reopening = ref(false)
const reopenError = ref('')

const progress = computed(() =>
  selected.value ? ownerProgressView(selected.value, nowMs.value) : null,
)
const canReopen = computed(() => selected.value?.status === 'closed' && auth.hasCapability('complaint.read'))

function openProgress(item: Complaint): void {
  selected.value = item
  reopenError.value = ''
  progressOpen.value = true
}

/** 业主重开已结案投诉（`action=reopen`，本人；服务端校验本户数据范围与 version） */
async function reopen(): Promise<void> {
  const current = selected.value
  if (!current) return
  reopening.value = true
  reopenError.value = ''
  try {
    const { data } = await api.complaint.transition(current.complaintId, {
      action: 'reopen',
      version: current.version,
      note: '业主重新打开：问题仍然存在',
    })
    selected.value = data
    toast.success(successReceipt('投诉已重新打开', `${data.complaintId} · 物业将继续跟进`))
    await loadMine()
  } catch (caught) {
    reopenError.value = apiMessage(caught)
    toast.error(`重开未完成：${reopenError.value}`)
  } finally {
    reopening.value = false
  }
}

/* ------------------------------------------------------------------ 工具 */

function apiMessage(caught: unknown): string {
  if (caught instanceof ApiError) {
    const denied = forbiddenDetailsOf(caught)
    if (denied) return `无权限：需要能力 ${capabilityLabel(denied.requiredCapability)}（E-2001）`
    return `${errorText(caught.code)}；${errorNextStep(caught.code)}`
  }
  return '请求失败：请稍后重试或转人工'
}

function ownerStatusLabel(item: Complaint): string {
  return OWNER_STATUS_LABELS[item.status] ?? item.status
}

function firstResponseText(item: Complaint): string {
  return item.firstResponseAt ? `已首响 ${formatIsoMinute(item.firstResponseAt)}` : '等待物业首次响应'
}

defineExpose({ loadMine })
</script>

<template>
  <div class="own-cmp" data-testid="own-complaints">
    <header class="own-cmp__head">
      <div class="own-cmp__title">
        <b>我的投诉</b>
        <WTag tone="n" plain>{{ mockBadgeText }}</WTag>
        <WTag tone="b" plain>人工处理</WTag>
      </div>
      <div class="own-cmp__acts">
        <WButton size="sm" variant="primary" icon="edit" data-testid="own-cmp-open-submit" @click="openSubmit">
          提交投诉
        </WButton>
        <WButton size="sm" variant="ghost" data-testid="own-cmp-refresh" :loading="loading" @click="refresh">
          刷新
        </WButton>
      </div>
    </header>

    <p class="own-cmp__hint" data-testid="own-cmp-hint">
      本户房号 {{ ownRoom || '未识别（请用业主演示账号登录）' }} · 只展示状态 / 首响时间 / 处理说明；
      内部升级与处理人信息不对业主展示。投诉必须人工处置，AI 不做自动回复与责任认定。
    </p>

    <p v-if="degradedNote" class="own-cmp__note" data-testid="own-cmp-degraded">{{ degradedNote }}</p>

    <div v-if="loading" class="own-cmp__state" data-testid="own-cmp-loading">
      <WLoading label="正在读取本户投诉（GET /api/v1/complaints，服务端按本户过滤）…" />
    </div>

    <p v-else-if="deniedText" class="own-cmp__note own-cmp__note--warn" data-testid="own-cmp-denied">
      <WTag tone="a" plain>无权限</WTag>{{ deniedText }}
    </p>

    <p v-else-if="error" class="own-cmp__note own-cmp__note--warn" data-testid="own-cmp-error">
      <WTag tone="r" plain>读取失败</WTag>{{ error }}
      <WButton size="sm" variant="ghost" data-testid="own-cmp-retry" @click="refresh">重试</WButton>
    </p>

    <WEmpty
      v-else-if="items.length === 0"
      compact
      icon="info"
      data-testid="own-cmp-empty"
      title="还没有投诉记录"
      description="如遇噪音、停车、费用争议、服务态度等问题，可点「提交投诉」登记；提交后物业人工受理并给出首响时间。"
    >
      <template #default>
        <WButton size="sm" variant="primary" data-testid="own-cmp-empty-submit" @click="openSubmit">提交投诉</WButton>
      </template>
    </WEmpty>

    <ul v-else class="own-cmp__list" data-testid="own-cmp-list">
      <li
        v-for="item in items"
        :key="item.complaintId"
        class="own-cmp__row"
        data-testid="own-cmp-row"
        :data-complaint-id="item.complaintId"
        :data-status="item.status"
      >
        <div class="own-cmp__row-main">
          <div class="own-cmp__row-top">
            <b class="num">{{ item.complaintId }}</b>
            <WTag :tone="COMPLAINT_LEVEL_TONES[item.level]" plain>{{ levelLabelShort(item.level) }}</WTag>
            <WTag tone="b" plain data-testid="own-cmp-row-status">{{ ownerStatusLabel(item) }}</WTag>
          </div>
          <p class="own-cmp__row-text">{{ categoryLabel(item.category) }} · {{ item.content }}</p>
          <div class="own-cmp__row-meta">
            <span>{{ maskRoomCode(item.roomCode) }}</span>
            <span data-testid="own-cmp-row-first-response">{{ firstResponseText(item) }}</span>
            <span>提交 {{ formatIsoMinute(item.createdAt) }}</span>
          </div>
        </div>
        <WButton size="sm" variant="ghost" data-testid="own-cmp-progress-open" @click="openProgress(item)">
          查看进度
        </WButton>
      </li>
    </ul>

    <!-- ======================== 提交投诉弹层 ======================== -->
    <WModal v-model:open="submitOpen" title="提交投诉（本户）" width="560px">
      <div class="own-cmp__form" data-testid="own-cmp-submit-modal">
        <label>
          <span>房号（默认本户，仅可提交本户）</span>
          <input v-model="form.roomCode" type="text" data-testid="own-cmp-room" placeholder="如 3-2-1802" />
        </label>
        <div class="own-cmp__form-row">
          <label>
            <span>投诉分类</span>
            <select v-model="form.category" data-testid="own-cmp-category">
              <option v-for="option in COMPLAINT_CATEGORY_OPTIONS" :key="option.value" :value="option.value">
                {{ option.label }}
              </option>
            </select>
          </label>
          <label>
            <span>紧急级别（服务端按级别算首响 SLA）</span>
            <select v-model="form.level" data-testid="own-cmp-level">
              <option v-for="level in COMPLAINT_LEVELS" :key="level" :value="level">{{ levelLabelShort(level) }}</option>
            </select>
          </label>
        </div>
        <label>
          <span>投诉内容（服务端落地前脱敏；请描述时间 / 地点 / 经过 / 诉求）</span>
          <textarea
            v-model="form.content"
            rows="4"
            data-testid="own-cmp-content"
            placeholder="如：楼上夜间装修噪音持续一周，物业上门一次未解决，希望本周内给出处理方案"
          />
        </label>
        <label>
          <span>回访手机号后四位（可选，仅后四位，不收完整号码）</span>
          <input v-model="form.contactTail4" type="text" maxlength="4" data-testid="own-cmp-contact-tail" placeholder="如 8821" />
        </label>
        <p v-if="submitError" class="own-cmp__note own-cmp__note--warn" data-testid="own-cmp-submit-error">{{ submitError }}</p>
        <p class="own-cmp__fine">提交即生成投诉号与首响 SLA 截止时间；不适用的房号会被数据范围校验拒绝（E-2002），不伪造成功回执。</p>
      </div>
      <template #footer>
        <WButton size="sm" variant="ghost" data-testid="own-cmp-submit-cancel" @click="submitOpen = false">取消</WButton>
        <WButton size="sm" variant="primary" data-testid="own-cmp-submit" :loading="submitting" @click="submit">
          提交投诉
        </WButton>
      </template>
    </WModal>

    <!-- ======================== 进度弹层（业主可见文案） ======================== -->
    <WModal v-model:open="progressOpen" title="投诉进度" width="560px">
      <div v-if="selected && progress" class="own-cmp__progress" data-testid="own-cmp-progress">
        <div class="own-cmp__progress-head">
          <b class="num">{{ selected.complaintId }}</b>
          <WTag :tone="progress.statusTone" data-testid="own-cmp-detail-status">{{ progress.statusLabel }}</WTag>
          <WTag :tone="COMPLAINT_LEVEL_TONES[selected.level]" plain>{{ levelLabelShort(selected.level) }}</WTag>
        </div>

        <dl class="own-cmp__rows">
          <div>
            <dt>房号</dt>
            <dd>{{ maskRoomCode(selected.roomCode) }}</dd>
          </div>
          <div>
            <dt>首响时间</dt>
            <dd data-testid="own-cmp-detail-first-response">{{ progress.firstResponseText }}</dd>
          </div>
          <div>
            <dt>首响承诺</dt>
            <dd :class="{ 'is-bad': progress.slaBreached }" data-testid="own-cmp-detail-sla">{{ progress.slaText }}</dd>
          </div>
          <div>
            <dt>处理说明</dt>
            <dd data-testid="own-cmp-detail-resolution">
              {{ progress.resolution || '物业尚未提交处理说明（首次响应/结案后展示）' }}
            </dd>
          </div>
          <div v-if="selected.ticketNo">
            <dt>关联工单</dt>
            <dd>
              <RouterLink class="own-cmp__link" :to="{ path: '/progress', query: { ticket: selected.ticketNo } }">
                {{ selected.ticketNo }}（查看处理进度）
              </RouterLink>
            </dd>
          </div>
        </dl>

        <div class="own-cmp__timeline" data-testid="own-cmp-progress-timeline">
          <header>处理进度（仅业主可见节点）</header>
          <ol>
            <li v-for="(node, index) in progress.timeline" :key="`${node.at}-${index}`">
              <span class="own-cmp__dot" aria-hidden="true" />
              <div>
                <b>{{ node.label }}</b>
                <time class="num">{{ node.at }}</time>
              </div>
            </li>
          </ol>
          <p v-if="!progress.timeline.length" class="own-cmp__fine">暂无进度节点。</p>
        </div>

        <p v-if="reopenError" class="own-cmp__note own-cmp__note--warn" data-testid="own-cmp-reopen-error">{{ reopenError }}</p>
        <p class="own-cmp__fine">
          业主侧只展示状态 / 首响时间 / 处理说明；升级层级与内部处理人不对业主展示。评价与赔付认定由人工按流程处理。
        </p>
      </div>
      <template #footer>
        <WButton
          v-if="canReopen"
          size="sm"
          variant="danger"
          data-testid="own-cmp-reopen"
          :loading="reopening"
          @click="reopen"
        >
          问题仍存在 · 重新打开
        </WButton>
        <WButton size="sm" data-testid="own-cmp-progress-close" @click="progressOpen = false">关闭</WButton>
      </template>
    </WModal>
  </div>
</template>

<style scoped>
.own-cmp {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.own-cmp__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  flex-wrap: wrap;
}

.own-cmp__title {
  display: flex;
  align-items: center;
  gap: 6px;
}

.own-cmp__title b {
  font-size: 12.5px;
  color: var(--c-ink);
}

.own-cmp__acts {
  display: flex;
  gap: 6px;
}

.own-cmp__hint,
.own-cmp__fine {
  margin: 0;
  font-size: 9.5px;
  line-height: var(--lh-base);
  color: var(--c-faint);
}

.own-cmp__state {
  padding: 16px 0;
}

.own-cmp__note {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  margin: 0;
  padding: 8px 10px;
  border: 1px dashed var(--c-line);
  border-radius: var(--rd-md);
  background: var(--c-bg-2);
  font-size: 10px;
  color: var(--c-ink-2);
}

.own-cmp__note--warn {
  border-color: var(--c-amber);
  background: var(--c-amber-soft);
}

.own-cmp__list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.own-cmp__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 8px 10px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-md);
  background: var(--c-card);
}

.own-cmp__row-main {
  min-width: 0;
  flex: 1;
}

.own-cmp__row-top {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.own-cmp__row-top b {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--c-ink);
}

.own-cmp__row-text {
  margin: 3px 0;
  font-size: 10px;
  line-height: var(--lh-base);
  color: var(--c-ink-2);
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.own-cmp__row-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 10px;
  font-size: 9px;
  color: var(--c-faint);
}

.own-cmp__form {
  display: flex;
  flex-direction: column;
  gap: 9px;
}

.own-cmp__form label {
  display: flex;
  flex-direction: column;
  gap: 3px;
  font-size: 10px;
  color: var(--c-faint);
}

.own-cmp__form-row {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 9px;
}

.own-cmp__form input,
.own-cmp__form select,
.own-cmp__form textarea {
  padding: 7px 9px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-sm);
  background: var(--c-card);
  font-family: inherit;
  font-size: var(--fs-xs);
  color: var(--c-ink-2);
}

.own-cmp__form textarea {
  line-height: var(--lh-base);
  resize: vertical;
}

.own-cmp__form input:focus-visible,
.own-cmp__form select:focus-visible,
.own-cmp__form textarea:focus-visible {
  outline: none;
  border-color: var(--c-brand);
  box-shadow: var(--sh-ring);
}

.own-cmp__progress {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.own-cmp__progress-head {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.own-cmp__progress-head b {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--c-ink);
}

.own-cmp__rows {
  display: flex;
  flex-direction: column;
  gap: 5px;
  margin: 0;
}

.own-cmp__rows > div {
  display: flex;
  gap: 10px;
  font-size: 10px;
}

.own-cmp__rows dt {
  flex: none;
  width: 56px;
  color: var(--c-faint);
}

.own-cmp__rows dd {
  margin: 0;
  color: var(--c-ink-2);
  line-height: var(--lh-base);
  word-break: break-word;
}

.own-cmp__rows dd.is-bad {
  color: var(--c-red);
}

.own-cmp__link {
  color: var(--c-brand);
  text-decoration: none;
}

.own-cmp__link:hover {
  text-decoration: underline;
}

.own-cmp__timeline {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding-top: 8px;
  border-top: 1px dashed var(--c-line);
}

.own-cmp__timeline header {
  font-size: 9.5px;
  color: var(--c-faint);
}

.own-cmp__timeline ol {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.own-cmp__timeline li {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  font-size: 10px;
}

.own-cmp__timeline li > div {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.own-cmp__timeline b {
  color: var(--c-ink-2);
}

.own-cmp__timeline time {
  font-size: 9px;
  color: var(--c-faint);
}

.own-cmp__dot {
  width: 8px;
  height: 8px;
  margin-top: 4px;
  flex: none;
  border-radius: 50%;
  background: var(--c-brand);
}
</style>
