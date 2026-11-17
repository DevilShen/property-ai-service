<script setup lang="ts">
import { computed, ref } from 'vue'
import { WButton, WCard, WIcon, WModal, WPageHeader, WTabs, WTag, useToast } from '@/components'
import { COPILOT_WORKSPACES } from '@/app/copilot'
import { demoNote, successReceipt } from '@/content'
import { api, ApiError, errorNextStep, errorText, forbiddenDetailsOf, formatIsoMinute } from '@/api'
import type { CopilotBroadcastResult } from '@/api'
import { COPILOT_RULE_NOTE, copyText } from '../shared'

/**
 * Copilot 工作区 6/8 · 群服务（B + C）
 * 硬边界：外部群不自动读、不自动回；B 只做人工确认群发 + 会话存档摘要；C 只在群里放入口，AI 在 1v1 承接。
 */
const workspace = COPILOT_WORKSPACES.group
const toast = useToast()

const mode = ref('b')
const tabs = [
  { key: 'b', label: '企微客户群（B）' },
  { key: 'c', label: '个人群入口（C）' },
]

const GROUP_MESSAGES = [
  { who: '王女士 09:12', text: '卫生间又漏水了，楼上一直不管' },
  { who: '管家 09:15', text: '收到，我马上联系工程', mine: true },
  { who: '李先生 09:20', text: '地库月卡什么时候能续' },
  { who: '张先生 09:31', text: '2 号楼电梯哪天维保' },
  { who: '管家 09:42', text: '已安排李师傅，下午上门', mine: true },
]

const DRAFT =
  '摘要：王女士卫生间渗漏复发；李先生等 2 户询问月卡；停水通知未读 38 户；2 号楼电梯 11-16 维保。\n\n建议回复：王女士您好，已安排水电二组今天 16:30 上门，师傅出发前会在群里同步；月卡续费链接我私发您。'

const COMPLIANCE = [
  { ok: true, title: '会话存档', text: '已同意 18/23 · 5 户待授权' },
  { ok: true, title: '人工确认', text: '不能自动发送' },
  { ok: false, title: '群机器人', text: '官方不支持外部群' },
  { ok: true, title: '1v1 客服', text: '可在微信客服承接' },
]

const C_STEPS = [
  { title: '群内放入口', text: '管家手动发小程序码 / 服务号卡片' },
  { title: '业主 1v1 进入', text: '查账单/报修/进度，完成手机号绑定' },
  { title: 'AI 承接', text: '查、办、催都在 1v1 完成' },
  { title: '管家回群摘要', text: '人工把结果/通知发回群，不做自动回复' },
]

const C_GROUP_MESSAGES = [
  '王女士 09:12：卫生间又漏水了，物业管不管？',
  '李先生 09:20：月卡怎么续？',
  '管家 09:25：大家可以用小程序“云栖管家”，报修/查费都在里面',
]

const ANNOUNCE =
  '群公告草稿：各位业主，报修/查费/查进度可扫码进入“云栖管家”，支持语音和大字；紧急漏水/燃气/电梯困人请直接拨打 400，我们会优先处理。'

const SESSIONS = [
  { time: '今天 15:02', owner: '王女士 · 3-2-1802', topic: '费用查询 / 公摊解释', state: '自助解决' },
  { time: '今天 14:36', owner: '李先生 · 4-1-902', topic: '停车月卡续费', state: '转人工' },
  { time: '今天 11:18', owner: '陈女士 · 6-1-1102', topic: '装修申报材料', state: '自助解决' },
]

const broadcastOpen = ref(false)
const wecomOpen = ref(false)
const qrOpen = ref(false)
const sessionsOpen = ref(false)
const taskState = ref<'idle' | 'pending'>('idle')
const announce = ref(ANNOUNCE)

/* ---------------- P4 W16：群服务人工确认群发（POST /copilot/group/broadcast） ---------------- */
/**
 * 红线（AGENTS.md NEVER #5）：**不做群内自动回复**；群发必须人工确认（`confirmed:true`），
 * 经 `services/mock-channel` 受理留痕（`status:mocked`）——**不代表已真实送达业主**。
 * 未勾选「已人工确认」前不发请求；内容禁止含他人房号/欠费信息（前端提示，服务端仍脱敏）。
 */
const broadcastContent = ref(DRAFT)
const broadcastRooms = ref('')
const broadcastConfirmed = ref(false)
const broadcasting = ref(false)
const broadcastResult = ref<CopilotBroadcastResult | null>(null)
const broadcastError = ref('')
const broadcastDenied = ref<{ requiredCapability: string; role: string } | null>(null)

const broadcastTargets = computed(() =>
  broadcastRooms.value
    .split(/[,，\s]+/)
    .map((item) => item.trim())
    .filter(Boolean),
)

const broadcastReceiptText = computed(() => {
  const receipt = broadcastResult.value
  if (!receipt) return ''
  return `mock 通道受理 ${receipt.pushIds.length} 条（${receipt.pushIds.join(' / ')}）· 受理时间 ${formatIsoMinute(receipt.sentAt) || receipt.sentAt}`
})

async function sendBroadcast(): Promise<void> {
  const content = broadcastContent.value.trim()
  if (!content) {
    toast.warning('群发内容不能为空')
    return
  }
  if (!broadcastConfirmed.value) {
    toast.warning('必须勾选「群成员已人工确认」后才能提交群发（接口要求 confirmed:true）')
    return
  }
  broadcasting.value = true
  broadcastError.value = ''
  broadcastDenied.value = null
  try {
    const { data } = await api.copilot.broadcast({
      content,
      targetRooms: broadcastTargets.value.length ? broadcastTargets.value : undefined,
      confirmed: true,
    })
    broadcastResult.value = data
    taskState.value = 'pending'
    toast.success(
      `群发已提交：mock 通道受理 ${data.pushIds.length} 条（status=${data.status}）；不代表已真实送达业主`,
    )
  } catch (err) {
    broadcastResult.value = null
    const forbidden = forbiddenDetailsOf(err)
    if (forbidden) {
      broadcastDenied.value = { requiredCapability: forbidden.requiredCapability || 'copilot.broadcast', role: forbidden.role }
      return
    }
    const code = err instanceof Error && 'code' in err ? String(err.code) : 'E-9001'
    broadcastError.value = `群发失败（${errorText(code)}；${errorNextStep(code)}）`
    if (err instanceof ApiError && err.httpStatus === 400) {
      broadcastError.value = `群发失败（${errorText(String(err.code))}）：请检查是否已人工确认、内容是否为空`
    }
  } finally {
    broadcasting.value = false
  }
}

function openBroadcast(): void {
  broadcastOpen.value = true
  broadcastError.value = ''
  broadcastDenied.value = null
}

const boundary = ['❌ 群内自动回复', '✅ 人工确认群发', '✅ 会话存档摘要', '✅ AI 1v1 承接', '✅ 群入口卡片']

const draftPreview = computed(() => DRAFT.split('\n').filter(Boolean))

function refresh() {
  toast.success(successReceipt('刷新群消息与存档状态'))
}

function regenerateDraft() {
  toast.success(successReceipt('重新生成摘要与 2 版回复草稿'))
}

async function copyDraft() {
  const ok = await copyText(DRAFT)
  if (ok) toast.success('草稿已复制，请到企微手机端人工确认')
  else toast.error('复制失败')
}

function copyAnnounce() {
  void copyText(announce.value).then((ok) => {
    if (ok) toast.success('群公告已复制，请管家手工发到个人微信群')
    else toast.error('复制失败')
  })
}

function generateCard() {
  qrOpen.value = false
  toast.success(`已生成群入口卡片` + demoNote('请管家人工发送到个人群'))
}
</script>

<template>
  <div class="cop-page" data-page="copilot">
    <WPageHeader :eyebrow="`04 · Copilot · ${workspace.label}`" :title="workspace.label" :description="workspace.description">
      <template #actions>
        <WTag tone="r">无群机器人</WTag>
        <WTag tone="g">人工确认发送</WTag>
        <WButton size="sm" variant="ghost" icon="refresh" @click="refresh">刷新</WButton>
      </template>
    </WPageHeader>

    <WTabs v-model="mode" :tabs="tabs" size="sm" />

    <template v-if="mode === 'b'">
      <div class="gr-grid">
        <WCard title="3栋业主群 · 企微客户群" subtitle="存档已同意 18/23">
          <template #extra>
            <WTag tone="n" plain>外部群</WTag>
          </template>
          <div class="gr-chat">
            <div v-for="(message, index) in GROUP_MESSAGES" :key="index" class="gr-msg" :class="{ 'is-mine': message.mine }">
              <b>{{ message.who }}</b>
              <span>{{ message.text }}</span>
            </div>
            <div class="gr-sys">外部群无机器人 · 消息来自会话存档</div>
          </div>
          <div class="cop-note" style="margin-top: 8px">
            <WIcon name="shield" :size="13" />
            <span>外部客户群不支持群机器人 / 自动回复；以下内容仅供人工参考，发送前必须成员确认。</span>
          </div>
        </WCard>

        <div class="gr-mid">
          <WCard title="AI 摘要与回复草稿" subtitle="人工确认后发送">
            <template #extra>
              <WTag tone="v">AI 生成</WTag>
            </template>
            <div class="gr-draft">
              <p v-for="(line, index) in draftPreview" :key="index">{{ line }}</p>
            </div>
            <div class="cop-actions" style="margin-top: 8px">
              <WButton size="sm" @click="regenerateDraft">重新生成</WButton>
              <WButton size="sm" @click="copyDraft">复制草稿</WButton>
            </div>
          </WCard>

          <WCard
            title="群发任务（人工确认）"
            :subtitle="
              broadcastResult
                ? `最近受理 ${formatIsoMinute(broadcastResult.sentAt) || '—'} · status=${broadcastResult.status}`
                : taskState === 'pending'
                  ? '今日 1/1 · 待成员确认'
                  : '今日 0/1 · 未发起'
            "
            data-testid="copilot-broadcast"
          >
            <template #extra>
              <WTag v-if="broadcastResult" tone="a" plain>mock 通道受理</WTag>
              <WTag :tone="taskState === 'pending' ? 'a' : 'n'" plain>{{ taskState === 'pending' ? '待人工确认' : '未发起' }}</WTag>
            </template>
            <div class="cop-actions">
              <WButton size="sm" variant="primary" icon="plus" data-testid="copilot-broadcast-open" @click="openBroadcast">创建群发任务</WButton>
              <WButton size="sm" variant="ghost" icon="arrow-right" @click="wecomOpen = true">打开企微</WButton>
            </div>
            <!-- 回执：mock 通道受理，不代表已真实送达（AGENTS.md NEVER #4/#5） -->
            <div v-if="broadcastResult" class="cop-note" data-testid="copilot-broadcast-receipt">
              <WIcon name="info" :size="13" />
              <span>
                {{ broadcastReceiptText }}；<b>mock 通道受理，不代表已真实送达</b>（未触达真实企微/微信）。
              </span>
            </div>
            <p v-if="broadcastDenied" class="cop-note" data-testid="copilot-broadcast-denied">
              <WIcon name="lock" :size="13" />
              <span>
                无权限：群发需要能力 {{ broadcastDenied.requiredCapability }}（当前角色「{{ broadcastDenied.role || '未知' }}」不具备）。
              </span>
            </p>
            <p v-else-if="broadcastError" class="cop-note" data-testid="copilot-broadcast-error">
              <WIcon name="refresh" :size="13" />
              <span>{{ broadcastError }}</span>
            </p>
          </WCard>
        </div>

        <WCard title="合规状态" subtitle="先合规，再自动化">
          <div v-for="item in COMPLIANCE" :key="item.title" class="gr-compliance">
            <span :class="item.ok ? 'is-ok' : 'is-no'">{{ item.ok ? '✅' : '❌' }}</span>
            <b>{{ item.title }}</b>
            <span class="gr-compliance-text">{{ item.text }}</span>
          </div>
          <div class="cop-note" style="margin-top: 8px">
            <WIcon name="shield" :size="13" />
            <span>不读取未同意客户的聊天；不把群消息原文写入知识库；摘要脱敏后留痕。</span>
          </div>
        </WCard>
      </div>
    </template>

    <template v-else>
      <div class="gr-grid is-c">
        <WCard title="3栋业主群（个人微信群）" subtitle="仅人工 · AI 不读不发">
          <template #extra>
            <WTag tone="a" plain>仅人工</WTag>
          </template>
          <div class="gr-chat">
            <div v-for="(message, index) in C_GROUP_MESSAGES" :key="index" class="gr-msg">
              <span>{{ message }}</span>
            </div>
            <div class="gr-sys">AI 不读取、不自动回复 · 管家手动发入口</div>
          </div>
          <div class="cop-note" style="margin-top: 8px">
            <WIcon name="info" :size="13" />
            <span>个人微信群无官方群接口，不做机器人；群内只发小程序码 / 服务号卡片，AI 在 1v1 承接。</span>
          </div>
        </WCard>

        <div class="gr-c-right">
          <WCard title="入口 → 1v1 承接 4 步">
            <div class="gr-steps">
              <div v-for="(step, index) in C_STEPS" :key="step.title" class="gr-step">
                <span class="gr-step-n">{{ index + 1 }}</span>
                <div>
                  <b>{{ step.title }}</b>
                  <span>{{ step.text }}</span>
                </div>
              </div>
            </div>
          </WCard>

          <WCard title="群入口卡片" subtitle="小程序码为演示图，正式码由知识运营在后台生成">
            <div class="gr-qr">
              <div class="gr-qr__code" aria-hidden="true" />
              <div class="gr-qr__meta">
                <b>云栖管家 · 小程序码</b>
                <span>扫码进入 1v1；群内仅做入口，不读群消息</span>
                <WButton size="sm" icon="plus" @click="qrOpen = true">生成卡片</WButton>
              </div>
            </div>
          </WCard>
        </div>
      </div>

      <WCard title="1v1 承接情况" subtitle="今日 12 次 · 群公告草稿可编辑">
        <div class="cop-field">
          <label>群公告草稿</label>
          <textarea v-model="announce" class="cop-textarea" />
        </div>
        <div class="cop-actions" style="margin-top: 8px">
          <WButton size="sm" icon="copy" @click="copyAnnounce">复制群公告</WButton>
          <WButton size="sm" variant="ghost" icon="chat" @click="sessionsOpen = true">查看 1v1 会话</WButton>
        </div>
      </WCard>
    </template>

    <div class="gr-boundary">
      <span v-for="item in boundary" :key="item" class="gr-boundary__item">{{ item }}</span>
    </div>

    <WModal v-model:open="broadcastOpen" title="创建群发（人工确认 → mock 通道受理）" width="560px">
      <div class="cop-kv"><span>目标群</span><b>3栋业主群（企微客户群）</b></div>
      <div class="cop-kv"><span>发送方式</span><b>成员手机端人工确认后提交（confirmed:true）</b></div>
      <div class="cop-field" style="margin-top: 8px">
        <label for="copilot-broadcast-content">群发正文（可编辑；禁止含他人房号/欠费信息）</label>
        <textarea id="copilot-broadcast-content" v-model="broadcastContent" class="cop-textarea" data-testid="copilot-broadcast-content" />
      </div>
      <div class="cop-field" style="margin-top: 8px">
        <label for="copilot-broadcast-rooms">目标房号（可选，逗号/空格分隔；留空=按群成员解析）</label>
        <input id="copilot-broadcast-rooms" v-model="broadcastRooms" class="bc-input" data-testid="copilot-broadcast-rooms" placeholder="例：3-2-1802, 3-2-1803" />
      </div>
      <label class="bc-confirm" for="copilot-broadcast-confirm">
        <input id="copilot-broadcast-confirm" v-model="broadcastConfirmed" type="checkbox" data-testid="copilot-broadcast-confirm" />
        <span>群成员已人工确认内容（未勾选不可提交；接口要求 confirmed=true）</span>
      </label>
      <p class="cop-note" style="margin-top: 8px">
        <WIcon name="shield" :size="13" />
        <span>
          不做群内自动回复；提交后仅经 mock 通道受理留痕（status=mocked），<b>不代表已真实送达业主</b>；
          {{ COPILOT_RULE_NOTE }}。
        </span>
      </p>
      <p v-if="broadcastResult" class="cop-note" data-testid="copilot-broadcast-modal-receipt">
        <WIcon name="info" :size="13" />
        <span>{{ broadcastReceiptText }}；mock 通道受理，不代表已真实送达。</span>
      </p>
      <p v-if="broadcastDenied" class="cop-note" data-testid="copilot-broadcast-modal-denied">
        <WIcon name="lock" :size="13" />
        <span>无权限：群发需要能力 {{ broadcastDenied.requiredCapability }}（当前角色「{{ broadcastDenied.role || '未知' }}」不具备）。</span>
      </p>
      <p v-else-if="broadcastError" class="cop-note" data-testid="copilot-broadcast-modal-error">
        <WIcon name="refresh" :size="13" />
        <span>{{ broadcastError }}</span>
      </p>
      <template #footer>
        <WButton size="sm" variant="ghost" @click="broadcastOpen = false">关闭</WButton>
        <WButton
          size="sm"
          variant="primary"
          icon="users"
          :loading="broadcasting"
          :disabled="!broadcastConfirmed"
          data-testid="copilot-broadcast-submit"
          @click="sendBroadcast"
        >
          确认群发（mock 通道）
        </WButton>
      </template>
    </WModal>

    <WModal v-model:open="wecomOpen" title="打开企微群发助手" width="460px">
      <p class="cop-muted" style="font-size: var(--fs-sm); line-height: 1.8">
        演示环境只展示界面：真实环境将唤起企业微信客户群群发助手，由成员在手机端确认后发送；外部群不支持机器人自动发送。
      </p>
      <template #footer>
        <WButton size="sm" @click="wecomOpen = false">知道了</WButton>
      </template>
    </WModal>

    <WModal v-model:open="qrOpen" title="生成群入口卡片" width="460px">
      <div class="gr-qr__code gr-qr__code--big" aria-hidden="true" />
      <p class="cop-dim" style="margin-top: 8px">卡片含小程序码 + “报修/查费/查进度”入口说明；由管家手工发到个人微信群。</p>
      <template #footer>
        <WButton size="sm" variant="ghost" @click="qrOpen = false">取消</WButton>
        <WButton size="sm" variant="primary" @click="generateCard">确认生成</WButton>
      </template>
    </WModal>

    <WModal v-model:open="sessionsOpen" title="1v1 会话列表（演示）" width="620px">
      <div class="gr-sessions">
        <div v-for="session in SESSIONS" :key="session.time" class="cop-list-item">
          <span class="cop-dim">{{ session.time }}</span>
          <span>{{ session.owner }} · {{ session.topic }}</span>
          <WTag :tone="session.state === '转人工' ? 'b' : 'g'" plain>{{ session.state }}</WTag>
        </div>
      </div>
      <p class="cop-dim" style="margin-top: 8px">AI 仅在 1v1 会话中承接，不进入群聊读取或回复。</p>
      <template #footer>
        <WButton size="sm" @click="sessionsOpen = false">关闭</WButton>
      </template>
    </WModal>
  </div>
</template>

<style scoped>
.gr-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.05fr) minmax(0, 1.1fr) minmax(0, 0.9fr);
  gap: 12px;
}

.gr-grid.is-c {
  grid-template-columns: minmax(0, 1fr) minmax(0, 1.1fr);
}

.gr-mid,
.gr-c-right {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.gr-chat {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.gr-msg {
  display: flex;
  flex-direction: column;
  gap: 1px;
  padding: 5px 8px;
  border-radius: var(--rd-sm);
  background: var(--c-bg-2);
  font-size: 10.5px;
  line-height: 1.5;
  color: var(--c-ink-2);
}

.gr-msg.is-mine {
  background: var(--c-brand-soft);
}

.gr-msg b {
  font-size: 9.5px;
  font-weight: 500;
  color: var(--c-faint);
}

.gr-sys {
  padding: 5px 8px;
  border: 1px dashed var(--c-line);
  border-radius: var(--rd-sm);
  font-size: 9.5px;
  color: var(--c-faint);
  text-align: center;
}

.gr-draft {
  display: flex;
  flex-direction: column;
  gap: 5px;
  font-size: 11px;
  line-height: 1.7;
  color: var(--c-ink-2);
}

.gr-compliance {
  display: flex;
  align-items: baseline;
  gap: 6px;
  padding: 4px 0;
  font-size: 10.5px;
  border-bottom: 1px dashed var(--c-line-2);
}

.gr-compliance:last-of-type {
  border-bottom: 0;
}

.gr-compliance b {
  font-weight: 500;
  color: var(--c-ink-2);
}

.gr-compliance-text {
  margin-left: auto;
  color: var(--c-faint);
  font-size: 9.5px;
}

.gr-steps {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.gr-step {
  display: flex;
  gap: 8px;
  align-items: flex-start;
}

.gr-step-n {
  display: grid;
  place-items: center;
  width: 20px;
  height: 20px;
  flex: none;
  border: 1px solid #bfdad2;
  border-radius: 50%;
  color: var(--c-brand);
  font-size: 9.5px;
  font-weight: 600;
}

.gr-step b {
  display: block;
  font-size: 11px;
  font-weight: 600;
  color: var(--c-ink-2);
}

.gr-step span {
  display: block;
  font-size: 10px;
  color: var(--c-muted);
  line-height: 1.55;
}

.gr-qr {
  display: flex;
  gap: 10px;
  align-items: center;
}

.gr-qr__code {
  width: 84px;
  height: 84px;
  flex: none;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-md);
  background:
    repeating-linear-gradient(90deg, #0e1d2b 0 4px, transparent 4px 8px),
    repeating-linear-gradient(0deg, #0e1d2b 0 4px, transparent 4px 8px),
    #fff;
  background-blend-mode: multiply;
  opacity: 0.85;
}

.gr-qr__code--big {
  width: 128px;
  height: 128px;
  margin: 0 auto;
}

.gr-qr__meta {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.gr-qr__meta b {
  font-size: 11px;
  color: var(--c-ink-2);
}

.gr-qr__meta span {
  font-size: 9.5px;
  color: var(--c-faint);
  line-height: 1.5;
}

.gr-boundary {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  padding: 8px 10px;
  border: 1px solid var(--c-line-2);
  border-radius: var(--rd-md);
  background: var(--c-bg-2);
}

.gr-boundary__item {
  font-size: 10px;
  color: var(--c-muted);
}

@media (max-width: 1439px) {
  .gr-grid {
    grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  }
}

@media (max-width: 1279px) {
  .gr-grid,
  .gr-grid.is-c {
    grid-template-columns: minmax(0, 1fr);
  }
}

/* ---------- P4 W16：人工确认群发（POST /copilot/group/broadcast） ---------- */
.bc-input {
  width: 100%;
  height: 28px;
  padding: 0 9px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-sm);
  background: var(--c-card);
  font-size: var(--fs-xs);
  color: var(--c-ink-2);
}

.bc-confirm {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-top: 10px;
  padding: 7px 9px;
  border: 1px dashed #e7c9a1;
  border-radius: var(--rd-md);
  background: #fdf8ef;
  font-size: 10.5px;
  line-height: 1.5;
  color: #8a5a12;
}
</style>

