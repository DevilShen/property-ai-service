import type { IconName } from '@/types'

/**
 * 02 业主端 · 演示数据（原型：云栖苑 王女士 3-2-1802）
 *
 * P2 起（契约 v0.3.0）本文件**只保留演示身份与交互文案**：
 * - 账单金额/费项/差额/知识引用 → `GET /api/v1/bills?roomCode=3-2-1802&period=2026-11`（不在此伪造）；
 * - 核验结论 → `POST /api/v1/owner/verify`；
 * - 工单号/状态/派单班组/ETA → `POST /api/v1/tickets` 响应（不再写死 `WO-20261114-0832`）。
 * 仍属演示的只有：会话样例、动作弹层文案、右侧“这一屏背后”的讲解标注。
 */

export const OWNER = {
  name: '王女士',
  room: '3-2-1802',
  area: '100㎡',
  phoneTail: '8821',
  bindState: '微信已绑定',
  project: '云栖苑',
}

/** 演示账期（`GET /api/v1/bills` 的 period 入参；金额由 ERP 返回） */
export const BILL_PERIOD = '2026-11'

/** 演示工单（mock-erp 种子单，DISPATCHED）：无 `?ticket=` 深链时的进度页默认单 */
export const DEMO_TICKET_NO = 'WO-20261114-0832'

export interface RepairStep {
  key: string
  question: string
  options: string[]
}

/** 5 问报修：位置 → 是否漏到楼下 → 持续多久 → 上门时间 → 补充说明 */
export const REPAIR_STEPS: RepairStep[] = [
  { key: 'location', question: '这就帮您安排。已带出 3-2-1802，漏水位置是？', options: ['卫生间', '厨房', '阳台', '外墙'] },
  { key: 'downstairs', question: '是否已经漏到楼下？', options: ['是', '否', '不确定'] },
  { key: 'duration', question: '漏水持续多久了？', options: ['刚刚发现', '半天', '超过一周'] },
  { key: 'slot', question: '今天家里有人吗？希望师傅什么时候上门？', options: ['今天 16:00—18:00', '明天上午', '尽快'] },
  { key: 'note', question: '还有需要补充的吗？（可留空，直接提交）', options: [] },
]

export const REPAIR = {
  firstMessage: '卫生间漏水了，能安排人来看看吗',
  /** 建单成功后的收尾话术（工单号/状态由 API 响应渲染在工单卡上） */
  closing: '接单、出发、完工都会在这里提醒您。',
}

export interface AnnotationStep {
  no: number
  title: string
  desc: string
  tags: Array<{ text: string; tone: 'g' | 'b' | 'a' | 'v' | 'n' }>
}

export const ANNOTATION_STEPS: AnnotationStep[] = [
  {
    no: 1,
    title: '身份核验前置',
    desc: '账户功能需绑定手机号，支持微信授权 / 手动 + 短信 / 线下核验；一般咨询免绑定。连续 3 次失败转人工，不提示错因。',
    tags: [],
  },
  {
    no: 2,
    title: '标准能力调用',
    desc: 'AI 不写 SQL、不认厂商表结构，只调用 bill.query；由用友连接器翻译成 ERP 查询（P2 为 mock-erp 测试替身）。',
    tags: [
      { text: 'bill.query', tone: 'b' },
      { text: 'mock-erp · yonyou-nc', tone: 'n' },
    ],
  },
  {
    no: 3,
    title: '答案强制溯源',
    desc: '金额、口径、条款均绑定知识条目与版本；无出处则拒答并转人工。进大模型前先脱敏手机号。',
    tags: [
      { text: 'K-002 V1.1', tone: 'g' },
      { text: '脱敏 3 项', tone: 'n' },
    ],
  },
  {
    no: 4,
    title: '只引导，不碰钱',
    desc: '缴费跳转支付系统闭环，AI 不写财务数据；减免、赔偿、定责一律转人工。',
    tags: [
      { text: 'L3 硬拦截', tone: 'a' },
      { text: '0 错误承诺', tone: 'n' },
    ],
  },
]

export interface CallChainItem {
  cap: string
  meta: string
  cost: string
  state: 'done' | 'idle'
}

export const CALL_CHAIN: CallChainItem[] = [
  { cap: 'owner.verify', meta: '手机号后四位 · 5 分钟会话', cost: '12ms', state: 'done' },
  { cap: 'bill.query', meta: 'mock-erp · yonyou-nc', cost: '38ms', state: 'done' },
  { cap: 'kb.cite', meta: '知识条目 · K-002 V1.1', cost: '4ms', state: 'done' },
  { cap: 'pay.jump', meta: '待用户点击 · 不预取', cost: '—', state: 'idle' },
]

export const BIND_METHODS: Array<{ key: string; title: string; desc: string; icon: IconName }> = [
  { key: 'wechat', title: '微信授权绑定', desc: '一次授权，自动关联房号与手机号；仅用于账户功能', icon: 'user' },
  { key: 'sms', title: '手动 + 短信验证', desc: '输入手机号，收验证码后绑定（演示不发真实短信）', icon: 'chat' },
  { key: 'offline', title: '线下核验', desc: '管家现场核验身份证与房本后开通，适合老年业主', icon: 'shield' },
]

export const VOICE = {
  title: '语音入口',
  hint: '对着手机说需求即可，识别后直接进入同一个智能管家会话；也可让家人代说。',
  lines: ['查一下这个月物业费', '卫生间漏水了，帮我报修', '转人工'],
}

/** 统一输入区的「试试这样说」：都是自然语言原话，不区分业务类别 */
export const SUGGESTIONS: Array<{ label: string; text: string }> = [
  { label: '这个月物业费怎么算的？', text: '这个月物业费怎么算的？' },
  { label: '卫生间漏水了', text: '卫生间漏水了，能安排人来看看吗' },
  { label: '转人工', text: '转人工' },
]

/** 统一入口的意图路由（同一输入框，AI 先判意图再决定调用哪个能力） */
export const INTENT_ROUTES: Array<{ say: string; intent: string; route: string; cap: string }> = [
  { say: '这个月物业费怎么算的？', intent: '账单解释', route: 'owner.verify → bill.query', cap: '实时账单 + 知识出处' },
  { say: '卫生间漏水了', intent: '报修受理', route: 'ticket.create', cap: '5 问结构化 → 建单派单' },
  { say: '师傅什么时候来？', intent: '进度查询', route: 'ticket.get', cap: '节点时间轴 + 催办' },
  { say: '转人工', intent: '人工兜底', route: 'handoff-summary', cap: '带交接摘要转座席' },
]

export const HANDOFF = {
  seat: '王琳 · 工号 2087',
  queue: '当前排队 2 人 · 预计 30 秒接入',
}

export const REPLAY = [
  { time: '15:02:10', text: '业主进入会话（微信服务号）', meta: '身份：未核验' },
  { time: '15:02:18', text: 'owner.verify 核验手机号后四位', meta: '200 · 12ms' },
  { time: '15:02:19', text: 'bill.query 查询 2026-11 账单', meta: 'mock-erp · 38ms' },
  { time: '15:02:21', text: '护栏：金额口径绑定知识出处 K-002 V1.1，手机号脱敏', meta: '通过' },
  { time: '15:02:22', text: '回复渲染：账单卡 + 依据解释', meta: '待业主点击缴费' },
]

export const PAY = {
  target: '微信支付（物业收费系统）',
  note: 'AI 只做跳转，不读取、不写入支付与财务数据；退款、减免、滞纳金豁免一律转人工。',
}

export const INVOICE = {
  title: '电子发票',
  note: '发票由 ERP 收费系统开具，AI 仅转交申请；抬头与税号需业主确认。',
  types: ['个人', '单位'],
}
