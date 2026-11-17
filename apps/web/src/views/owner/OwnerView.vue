<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { WButton, WCard, WIcon, WModal, WPageHeader, WTag, useToast } from '@/components'
import {
  BindPhoneModal,
  InvoiceModal,
  PayModal,
  RescheduleModal,
  UrgeModal,
  VoiceModal,
} from '@/components/actions'
import type { BindConfirm, InvoiceConfirm, PayConfirm, RescheduleConfirm, UrgeConfirm, VoiceConfirm } from '@/components/actions'
import { SCREENS } from '@/app/screens'
import { can } from '@/app/permissions'
import { useAppStore } from '@/stores/app'
import { formatMoney } from '@/api'
import type { BillStatus } from '@/api'
import { useOwnerSession } from '@/composables/useOwnerSession'
import {
  ANNOTATION_STEPS,
  BIND_METHODS,
  BILL_PERIOD,
  CALL_CHAIN,
  INTENT_ROUTES,
  INVOICE,
  OWNER,
  PAY,
  REPLAY,
  VOICE,
} from './data'
import ButlerChat from './components/ButlerChat.vue'
import OwnerComplaints from './components/OwnerComplaints.vue'
import StateDemo from '@/content/StateDemo.vue'
import { DEMO_BOUNDARY_NOTE, demoNote, successReceipt } from '@/content'

/**
 * 02 业主端 AI 客服
 * 业主侧只有「一个智能管家会话」：不区分费用查询 / 智能报修入口，
 * 由 AI 在同一输入框内识别意图（账单 / 报修 / 查进度 / 转人工）并走对应能力；
 * 右侧标注 AI 与 ERP 边界、统一入口的意图路由与实时调用链；不接真实支付/短信。
 *
 * P2（契约 v0.3.0）：账单 / 核验 / 建单 / 改约 / 催办全部走真实 API（见 `@/api`），
 * 工单卡展示响应里的 ticketNo/status/team/eta；金额与工单号不再来自本地 mock。
 *
 * 7 类动作弹层已抽到 `@/components/actions`（统一 WModal 外壳 + 统一 props/emits）：
 * 本次接入 缴费 / 电子发票 / 语音 / 手机号绑定 / 改约 / 催办（服务评价在 03 工单进度页使用）。
 * 回执句式统一走 `@/content/action-copy`，不再各写「（演示）」后缀。
 */
const screen = SCREENS.owner
const toast = useToast()
const router = useRouter()
const route = useRoute()
const app = useAppStore()
const session = useOwnerSession()

const chatRef = ref<InstanceType<typeof ButlerChat> | null>(null)

/** 动作级权限占位：缴费跳转只对业主/管理员开放（坐席、IT 等演示视角隐藏入口） */
const allowPay = computed(() => can(app.currentRole, 'owner.pay_jump'))

/**
 * P3 W12：业主视角才展示「我的投诉」入口与卡片（staff 预览 /owner 时接口会按项目范围返回，
 * 不适合套用「我的投诉」口径；staff 处理台在 /desk「投诉处理台」页签或 /complaints）。
 */
const isOwnerRole = computed(() => app.currentRole === 'owner')

function goComplaints(): void {
  void router.push('/complaints')
}

/** 缴费弹层金额：取自 CAP-03 账单响应（未查到账单时不展示金额，不回填本地 mock） */
const payAmount = computed(() => formatMoney(session.bill.value?.total))
/** 缴费弹层账期 / 到期日：取自账单响应（到期日按 ERP 给出的 ISO 日期展示为 `MM-DD 到期`） */
const billPeriod = computed(() => session.bill.value?.period ?? '')
const billDue = computed(() => {
  const dueDate = session.bill.value?.dueDate ?? ''
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(dueDate)
  return match ? `${match[2]}-${match[3]} 到期` : dueDate
})

const bindOpen = ref(false)
const voiceOpen = ref(false)
const payOpen = ref(false)
const invoiceOpen = ref(false)
const replayOpen = ref(false)
const rescheduleOpen = ref(false)
const urgeOpen = ref(false)
const handedOff = ref(false)

/** 最近一次改约选择（再次打开时作为默认选中） */
const rescheduleSlot = ref('')

const RESCHEDULE_SLOTS = ['今天 17:30—19:00', '明天上午 09:00—11:00', '明天下午 14:00—16:00']

function resetChat() {
  chatRef.value?.reset()
  handedOff.value = false
  toast.info(`会话已重置为初始示例对话${demoNote('重置仅影响本地示例')}`)
}

function onHandoff(summary: string) {
  handedOff.value = true
  toast.success(`已转人工：王琳 工号 2087 · 预计 30 秒接入（交接摘要：${summary}）`)
}

/** 跳工单进度页：带上真实工单号深链（`/progress?ticket=<no>`） */
function onProgress(ticketNo?: string) {
  void router.push(ticketNo ? { path: '/progress', query: { ticket: ticketNo } } : '/progress')
}

// ---------------------------------------------------------------- 弹层回执
function confirmBind(payload: BindConfirm) {
  bindOpen.value = false
  if (payload.method === 'offline') {
    toast.success(`已提交线下核验预约${demoNote('管家将与您联系，不做真实现场核验')}`)
    return
  }
  toast.success(`手机号绑定成功：尾号 ${OWNER.phoneTail}${demoNote('不发送真实短信')}`)
}

/** 连续 3 次失败 → 弹层内部已转人工，这里补一条 Toast 回执 */
function onBindHandoff() {
  toast.error('手机号绑定未完成：连续 3 次失败，已按规则转人工核验（不提示失败原因）')
}

function confirmPay(_payload: PayConfirm) {
  payOpen.value = false
  toast.success(`已跳转物业收费系统微信支付${demoNote('不读取支付数据')}`)
}

function confirmInvoice(payload: InvoiceConfirm) {
  invoiceOpen.value = false
  toast.success(successReceipt('电子发票申请转交 ERP', `${payload.type} · ${payload.title}`))
}

/**
 * 改约：走 CAP-07 `POST /tickets/{no}/transition{action:reschedule}`（写操作幂等）。
 * 会话里还没有工单时退回演示回执（P1 交互保留），并提示先报修。
 */
async function confirmReschedule(payload: RescheduleConfirm) {
  rescheduleOpen.value = false
  rescheduleSlot.value = payload.slot
  if (!chatRef.value?.ticketNo()) {
    toast.success(successReceipt('提交改约申请', `${payload.slot}，管家会与您确认`))
    return
  }
  const result = await chatRef.value.reschedule(payload.slot, payload.note)
  if (result.ok) toast.success(successReceipt('提交改约申请', `${payload.slot}，已写入工单`))
  else toast.error(`改约未完成：${result.message ?? '上游系统暂不可用'}，请稍后重试或转人工`)
}

function confirmVoice(payload: VoiceConfirm) {
  voiceOpen.value = false
  // 语音识别结果直接进入统一会话，由 AI 识别意图（不走单独的语音流程）
  chatRef.value?.ask(payload.text)
  toast.success(`语音已识别并进入会话：${payload.text}${demoNote('不调用真实语音服务')}`)
}

/** 催办：走 CAP-09 `POST /tickets/{no}/transition{action:urge}`（写操作幂等，不做 AI 外呼） */
async function confirmUrge(payload: UrgeConfirm) {
  urgeOpen.value = false
  if (!chatRef.value?.ticketNo()) {
    toast.success(successReceipt('催办', `${payload.reason} · 会优先联系该班组`))
    return
  }
  const result = await chatRef.value.urge(payload.reason)
  if (result.ok) toast.success(successReceipt('催办', `${payload.reason} · 班组主管已收到提醒`))
  else toast.error(`催办未完成：${result.message ?? '上游系统暂不可用'}，请稍后重试或转人工`)
}

function copyTrace() {
  void navigator.clipboard
    .writeText('tr-20261114-1502-8821 · /owner 会话回放')
    .then(() => toast.success(`traceId 已复制到剪贴板${demoNote('仅本地剪贴板，不写入后端')}`))
    .catch(() => toast.error('复制未完成：请检查浏览器剪贴板权限后重试'))
}

/**
 * 原型说明 §2.2 深链：`?action=voice|bindphone|pay|invoice|reschedule|urge`
 * 直接打开对应操作弹层（演示用，不影响正常进入页面）。
 */
const ACTION_OPENERS: Record<string, () => void> = {
  pay: () => (payOpen.value = true),
  invoice: () => (invoiceOpen.value = true),
  voice: () => (voiceOpen.value = true),
  bindphone: () => (bindOpen.value = true),
  bind: () => (bindOpen.value = true),
  reschedule: () => (rescheduleOpen.value = true),
  urge: () => (urgeOpen.value = true),
}

/* ------------------------------------ 本户费用与催缴提醒（只读，P3 W11） */

/**
 * 数据口径（契约 CAP-03 `GET /api/v1/bills?roomCode&period`）：
 * - 金额 / 状态 / 到期日全部来自收费系统响应，前端只格式化；**未核验时不展示任何金额**；
 * - 与 ButlerChat 共用 `useOwnerSession()` 模块级会话态：左侧会话核验后账单卡与本卡同步更新；
 * - 催缴提醒只做「本户账单是否未缴/逾期」的只读提示：owner 无 `dunning.task` 能力（README §7.2），
 *   **业主端不显示催缴工作台入口**；P3 触达走 mock-channel（未真实外发），本页不伪造「已提醒/已送达」。
 * - 「去缴费」只开占位弹层：**不接真实支付/发票、不生成支付单、不碰钱**（AGENTS.md NEVER #4）。
 */
const BILL_STATUS_TEXT: Readonly<Record<BillStatus, string>> = {
  unpaid: '未缴',
  partial: '部分缴',
  paid: '已缴清',
  overdue: '逾期',
  void: '作废',
}
const ownBill = computed(() => session.bill.value)
const ownBillStatus = computed(() => (ownBill.value?.status ? BILL_STATUS_TEXT[ownBill.value.status] : ''))
const ownTotal = computed(() => formatMoney(ownBill.value?.total))
const ownOutstanding = computed(() => formatMoney(ownBill.value?.outstanding))
const payPlaceholderOpen = ref(false)
const payPlaceholderReceipt = ref('')

/** 未核验时引导走到左侧会话的核验流程（不绕过 owner.verify） */
function askOwnBill(): void {
  chatRef.value?.ask('查一下我这个月的物业费账单')
}

function openPayPlaceholder(): void {
  payPlaceholderReceipt.value = ''
  payPlaceholderOpen.value = true
}

/** 回执占位：只在页面内生成文本；不产生支付单、不调支付接口、不碰钱 */
function confirmPayPlaceholder(): void {
  const bill = ownBill.value
  payPlaceholderReceipt.value =
    `占位回执 · ${bill?.roomCode ?? OWNER.room} · ${bill?.period ?? BILL_PERIOD} · 应缴 ${ownTotal.value ? `¥${ownTotal.value}` : '—'}` +
    ' · 未生成支付单、未发起扣款（演示环境不接真实支付/发票）'
  toast.info(`缴费入口仅作演示：未生成支付单、不碰钱${demoNote('不接真实支付/发票')}`)
}

onMounted(() => {
  const action = String(route.query.action ?? '')
  ACTION_OPENERS[action]?.()
})
</script>

<template>
  <div class="w-page" data-page="owner" data-testid="page-root">
    <WPageHeader :eyebrow="screen.eyebrow" :title="screen.title" :description="screen.description">
      <template #actions>
        <WTag v-if="handedOff" tone="b">已转人工</WTag>
        <WTag tone="b" plain>对客灰度 30% · 第 8 周</WTag>
        <WTag tone="a" plain>演示数据</WTag>
        <WButton v-if="isOwnerRole" size="sm" icon="list-check" data-testid="own-complaints-entry" @click="goComplaints">
          我的投诉
        </WButton>
        <WButton size="sm" icon="user" @click="bindOpen = true">手机号绑定</WButton>
        <WButton size="sm" icon="chat" @click="voiceOpen = true">语音入口</WButton>
        <WButton size="sm" variant="ghost" icon="activity" @click="replayOpen = true">会话回放</WButton>
      </template>
    </WPageHeader>

    <div class="own-grid">
      <!-- 统一会话：一个入口 -->
      <div class="own-phone-wrap">
        <div class="own-phone-cap">
          <b>业主端 · 智能管家</b>
          <span>一个入口 · 一句话说需求，AI 自动识别账单 / 报修 / 转人工</span>
          <WButton size="sm" variant="ghost" @click="resetChat">重置演示</WButton>
        </div>

        <ButlerChat
          ref="chatRef"
          :allow-pay="allowPay"
          @pay="payOpen = true"
          @invoice="invoiceOpen = true"
          @bind="bindOpen = true"
          @handoff="onHandoff"
          @progress="onProgress"
          @reschedule="rescheduleOpen = true"
          @urge="urgeOpen = true"
          @voice="voiceOpen = true"
        />

        <p class="own-phone-foot">
          <WIcon name="info" :size="11" />
          <span>{{ DEMO_BOUNDARY_NOTE }}</span>
        </p>
      </div>

      <!-- 右侧标注 -->
      <div class="own-anno">
        <div class="own-anno__grid">
          <!-- P3 W11：本户欠费 / 催缴提醒（只读）+「去缴费」占位（不碰钱） -->
          <WCard title="本户费用与催缴提醒" subtitle="只读 · 金额与状态来自收费系统；业主端不显示催缴工作台">
            <template #extra>
              <WTag tone="g" plain>只读</WTag>
            </template>
            <div class="own-reminder" data-testid="own-reminder">
              <template v-if="ownBill">
                <div class="own-reminder__head">
                  <b class="num" data-testid="own-reminder-amount">{{ ownTotal ? `¥${ownTotal}` : '—' }}</b>
                  <span>{{ ownBill.period || BILL_PERIOD }} 期应缴 · {{ ownBill.roomCode || OWNER.room }}</span>
                </div>
                <div class="own-reminder__tiles">
                  <div>
                    <b class="num" data-testid="own-reminder-outstanding">{{ ownOutstanding ? `¥${ownOutstanding}` : '—' }}</b>
                    <span>本户欠费</span>
                  </div>
                  <div>
                    <b data-testid="own-reminder-status">{{ ownBillStatus || '—' }}</b>
                    <span>账单状态</span>
                  </div>
                  <div>
                    <b class="num">{{ ownBill.overdueDays ?? 0 }}<small>天</small></b>
                    <span>逾期天数</span>
                  </div>
                </div>
                <p class="own-reminder__note" data-testid="own-reminder-note">
                  本户账单状态由收费系统返回（{{ ownBill.dueDate ? `到期日 ${ownBill.dueDate}` : '到期日未返回' }}）。
                  如果管家已提醒您缴费，请以本页账单核对；催缴提醒由管家人工确认后发送，演示环境通道未接入（status=mocked，
                  不代表已送达）。业主端不提供催缴任务工作台（owner 无 dunning.task 能力）。
                </p>
                <div class="own-reminder__acts">
                  <WButton v-if="allowPay" size="sm" variant="primary" data-testid="own-pay-jump" @click="openPayPlaceholder">
                    去缴费
                  </WButton>
                  <WButton size="sm" variant="ghost" data-testid="own-bill-ask" @click="askOwnBill">查看账单解释</WButton>
                </div>
              </template>
              <template v-else>
                <p class="own-reminder__note" data-testid="own-reminder-pending">
                  为保护隐私，请先在左侧会话核验业主手机号后四位（5 分钟会话）；核验后本卡展示本户应缴 / 欠费 / 账单状态。
                  未核验时本页不展示任何金额，也不生成催缴结论。
                </p>
                <WButton size="sm" data-testid="own-reminder-verify" @click="askOwnBill">核验并查看本户账单</WButton>
              </template>
            </div>
          </WCard>

          <WCard title="这一屏背后" subtitle="AI 与 ERP 的边界">
            <template #extra>
              <WTag tone="v" plain>Canonical</WTag>
            </template>
            <div class="own-steps">
              <div v-for="step in ANNOTATION_STEPS" :key="step.no" class="own-step">
                <span class="own-step__idx">{{ step.no }}</span>
                <div class="own-step__body">
                  <b>{{ step.title }}</b>
                  <p>{{ step.desc }}</p>
                  <div v-if="step.tags.length" class="own-step__tags">
                    <WTag v-for="tag in step.tags" :key="tag.text" :tone="tag.tone" plain>{{ tag.text }}</WTag>
                  </div>
                </div>
              </div>
            </div>
          </WCard>

          <WCard title="一个入口 · 意图路由" subtitle="同一输入框，先判意图再调用能力">
            <template #extra>
              <WTag tone="b" plain>无需选类型</WTag>
            </template>
            <div class="own-intent">
              <div v-for="item in INTENT_ROUTES" :key="item.route" class="own-intent__row">
                <span class="own-intent__say">“{{ item.say }}”</span>
                <WTag tone="n" plain>{{ item.intent }}</WTag>
                <b>{{ item.route }}</b>
                <em>{{ item.cap }}</em>
              </div>
            </div>
            <div class="own-tip" style="margin-top: 8px">
              <WIcon name="sparkles" :size="12" />
              <span>意图识别由编排层完成：账单类先核验身份，报修类走 5 问结构化，识别不到即转人工。</span>
            </div>
          </WCard>

          <WCard title="实时调用链" subtitle="15:02:18 · 一次账单查询">
            <div v-for="call in CALL_CHAIN" :key="call.cap" class="own-callrow">
              <span class="own-dot" :class="{ 'is-idle': call.state === 'idle' }" />
              <b>{{ call.cap }}</b>
              <em>{{ call.meta }}</em>
              <i>{{ call.cost }}</i>
            </div>
            <div class="own-tip" style="margin-top: 8px">
              <WIcon name="shield" :size="12" />
              <span>AI 侧不存业务数据，页面展示的每个数字均由 ERP 实时返回。</span>
            </div>
            <div class="own-l3">
              <WTag tone="g" plain>L1 只能说</WTag>
              <WTag tone="b" plain>L2 可以做</WTag>
              <WTag tone="a" plain>L3 绝不承诺</WTag>
            </div>
          </WCard>

          <WCard title="断链与降级" subtitle="接口异常时按此降级，不编造数字">
            <div class="own-tip">
              <WIcon name="info" :size="12" />
              <span>bill.query 超时 → 提示“账单暂不可查”，转人工；不展示缓存金额，不做推测。</span>
            </div>
            <div class="own-tip" style="margin-top: 6px">
              <WIcon name="shield" :size="12" />
              <span>命中减免/赔偿/定责 → 不答复，直接转人工并附交接摘要。</span>
            </div>
            <div class="own-tip" style="margin-top: 6px">
              <WIcon name="refresh" :size="12" />
              <span>意图识别置信度低 → 不猜类别，回问一句或直接给「转人工」兜底。</span>
            </div>
            <WButton size="sm" variant="ghost" icon="activity" style="margin-top: 8px" @click="replayOpen = true">
              查看会话回放
            </WButton>
          </WCard>
        </div>
      </div>
    </div>

    <!-- P3 W12：我的投诉（业主本户提交 / 列表 / 进度；只展示业主可见文案，不含内部升级与处理人） -->
    <WCard v-if="isOwnerRole" class="own-complaints-card">
      <OwnerComplaints />
    </WCard>

    <!-- 会话回放（本屏专属，不属 7 类动作弹层） -->
    <WModal v-model:open="replayOpen" title="会话回放（trace 摘要）" width="600px">
      <div class="own-replay">
        <div v-for="item in REPLAY" :key="item.time" class="own-replay-row">
          <span>{{ item.time }}</span>
          <b>{{ item.text }}</b>
          <em>{{ item.meta }}</em>
        </div>
      </div>
      <div class="own-tip" style="margin-top: 8px">
        <WIcon name="lock" :size="12" />
        <span>回放可看到知识引用、接口调用与护栏判断；敏感字段已脱敏。完整回溯见「全链路回溯」（10 屏）。</span>
      </div>
      <template #footer>
        <WButton size="sm" icon="copy" @click="copyTrace">复制 traceId</WButton>
        <WButton size="sm" @click="replayOpen = false">关闭</WButton>
      </template>
    </WModal>

    <!-- 7 类动作弹层：统一来自 @/components/actions -->
    <PayModal
      v-model:open="payOpen"
      :amount="payAmount"
      :target="PAY.target"
      :month="billPeriod"
      :due="billDue"
      :disclaimer="PAY.note"
      @confirm="confirmPay"
    />

    <InvoiceModal
      v-model:open="invoiceOpen"
      :default-title="OWNER.name"
      :note="INVOICE.note"
      @confirm="confirmInvoice"
    />

    <VoiceModal v-model:open="voiceOpen" :hint="VOICE.hint" :lines="VOICE.lines" @confirm="confirmVoice" />

    <BindPhoneModal
      v-model:open="bindOpen"
      :methods="BIND_METHODS"
      :demo-tail="OWNER.phoneTail"
      @confirm="confirmBind"
      @handoff="onBindHandoff"
    />

    <RescheduleModal
      v-model:open="rescheduleOpen"
      :slots="RESCHEDULE_SLOTS"
      :current="rescheduleSlot"
      @confirm="confirmReschedule"
    />

    <UrgeModal v-model:open="urgeOpen" @confirm="confirmUrge" />

    <!-- P3 W11：缴费跳转占位（不接真实支付/发票，不产生支付单、不碰钱） -->
    <WModal v-model:open="payPlaceholderOpen" title="去缴费（演示占位）" width="560px">
      <div class="own-payph" data-testid="own-pay-placeholder">
        <p class="own-payph__note" data-testid="own-pay-placeholder-note">
          演示环境：不接真实支付/发票，缴费入口仅作演示
        </p>
        <dl class="own-payph__rows">
          <div>
            <dt>缴费对象</dt>
            <dd>{{ ownBill?.roomCode || OWNER.room }} · 业主本人</dd>
          </div>
          <div>
            <dt>账期</dt>
            <dd class="num">{{ ownBill?.period || BILL_PERIOD }}</dd>
          </div>
          <div>
            <dt>应缴金额</dt>
            <dd class="num">{{ ownTotal ? `¥${ownTotal}` : '—' }}</dd>
          </div>
        </dl>
        <p v-if="payPlaceholderReceipt" class="own-payph__receipt" data-testid="own-pay-receipt">{{ payPlaceholderReceipt }}</p>
        <p class="own-payph__tip">
          本操作<strong>不产生支付单、不发起扣款、不读取或写入财务数据</strong>；实际缴费请到物业收费系统办理，
          缴费结果以收费系统实收为准（本页不碰钱）。
        </p>
      </div>
      <template #footer>
        <WButton size="sm" variant="ghost" data-testid="own-pay-cancel" @click="payPlaceholderOpen = false">关闭</WButton>
        <WButton size="sm" variant="primary" data-testid="own-pay-confirm" @click="confirmPayPlaceholder">
          生成占位回执
        </WButton>
      </template>
    </WModal>

    <StateDemo screen="owner" />
  </div>
</template>

<style src="./owner.css"></style>
