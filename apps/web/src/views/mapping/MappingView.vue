<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { WButton, WCard, WEmpty, WLoading, WModal, WPageHeader, WTable, WTag, useToast } from '@/components'
import { TrialRunModal, type TrialDiff, type TrialRunPayload } from '@/components/admin-actions'
import { SCREENS } from '@/app/screens'
import StateDemo from '@/content/StateDemo.vue'
import { demoNote } from '@/content'
import type { TableColumn } from '@/types'

/**
 * 08 字段与状态映射（P1-020）
 * 字段 / 状态码 / 字典三类映射表 + 200 条试跑 + 版本发布与回滚 + 标准契约预览。
 * 只写映射配置，不接真实 ERP；试跑结果与连通性均为 mock。
 * P1-033：试跑统一走 TrialRunModal（进度条 / 差异清单 / 可重跑），不再用静态结果弹层。
 */
const screen = SCREENS.mapping
const toast = useToast()

type FieldRow = {
  key: string
  std: string
  type: string
  required: boolean
  erp: string
  rule: string
  sample: string
}

type StatusRow = { key: string; erp: string; std: string; note: string; fallback: string }
type DictRow = { key: string; name: string; items: string; status: '已确认' | '待确认'; owner: string }

const CAPABILITIES = ref([
  { id: 'room', name: '房档查询', count: 8 },
  { id: 'verify', name: '业主身份核验', count: 3 },
  { id: 'bill', name: '账单查询', count: 14 },
  { id: 'ticket', name: '工单创建', count: 12 },
  { id: 'status', name: '工单进度', count: 6 },
  { id: 'pay', name: '缴费跳转', count: 5 },
  { id: 'invoice', name: '开票申请', count: 4 },
  { id: 'complaint', name: '投诉单创建', count: 9 },
  { id: 'notice', name: '通知推送', count: 5 },
  { id: 'visit', name: '回访记录', count: 4 },
])

/** 200 条试跑的两条差异（mock，来自原型：房号别名 / 报修类型字典） */
const TRIAL_DIFFS: TrialDiff[] = [
  {
    title: '房号别名未收录',
    detail: '「3栋2单元1802」未命中 normalizeRoom() 别名表；补录别名后重跑即可通过',
    expected: '3-2-1802',
    actual: '3栋2单元1802',
    level: 'high',
  },
  {
    title: '报修类型缺失',
    detail: '「外墙渗水」无对应 repair_type 字典项；建议补字典项或落「其他」并转人工',
    expected: '给排水 / 土建',
    actual: '—',
    level: 'mid',
  },
]

const FIELD_MAPS: Record<string, FieldRow[]> = {
  ticket: [
    { key: 'roomCode', std: 'roomCode 房号', type: 'string', required: true, erp: 'bd_house.fnumber', rule: 'normalizeRoom()：统一为「楼-单元-房」，别名表兜底', sample: '3-2-1802' },
    { key: 'projectId', std: 'projectId 项目', type: 'string', required: true, erp: 'wo.pk_org', rule: '字典 org_dict → 标准项目 ID', sample: 'P-1024' },
    { key: 'category', std: 'category 报修类型', type: 'enum', required: true, erp: 'wo.def1', rule: '字典 repair_type（6 类）· 1 项待确认', sample: '给排水' },
    { key: 'urgency', std: 'urgency 紧急度', type: 'enum', required: true, erp: 'wo.priority', rule: '0→普通 · 1→紧急 · 2→特急', sample: '紧急' },
    { key: 'description', std: 'description 现象描述', type: 'text', required: true, erp: 'wo.content', rule: 'trim · 截断 500 字 · 保留原始对话链接', sample: '卫生间漏水…' },
    { key: 'images', std: 'images 现场图片', type: 'array', required: false, erp: 'wo.attach', rule: 'split(";") → 上传附件服务，失败不阻塞建单', sample: '2 张' },
    { key: 'reporterPhone', std: 'reporterPhone 联系电话', type: 'string', required: true, erp: 'wo.phone', rule: '入仓前 AES 加密，AI 侧只持有掩码', sample: '138****8821' },
    { key: 'expectTime', std: 'expectTime 期望上门', type: 'datetime', required: true, erp: 'wo.expect_date', rule: '时段取中值 · 时区 Asia/Shanghai', sample: '11-14 16:30' },
    { key: 'teamId', std: 'teamId 派单班组', type: 'string', required: false, erp: 'wo.team_id', rule: '字典 team_dict；缺失时按片区规则兜底', sample: 'T-0207' },
    { key: 'source', std: 'source 来源', type: 'enum', required: true, erp: 'const', rule: '固定写 "AI"，用于区分人工单', sample: 'AI' },
    { key: 'aiCreated', std: 'aiCreated AI 建单标识', type: 'int', required: true, erp: 'const', rule: '固定写 1；抽检通过后转 0', sample: '1' },
    { key: 'ticketNo', std: 'ticketNo 工单号（回写）', type: 'string', required: false, erp: 'wo.code', rule: 'ERP 生成后回写，用于进度查询与三次节点推送', sample: 'WO-2026…' },
    { key: 'callbackFlag', std: 'callbackFlag 回访标记', type: 'int', required: false, erp: 'wo.callback', rule: '0→待回访 · 1→已回访 · 空→不纳入回访队列', sample: '0' },
    { key: 'contactName', std: 'contactName 报修人', type: 'string', required: true, erp: 'wo.contact_name', rule: '与房档关系校验；租户按规则确认', sample: '王女士' },
  ],
  bill: [
    { key: 'roomCode', std: 'roomCode 房号', type: 'string', required: true, erp: 'ar_house.fnumber', rule: 'normalizeRoom() 别名表兜底', sample: '3-2-1802' },
    { key: 'period', std: 'period 账期', type: 'string', required: true, erp: 'ar_period.code', rule: 'YYYY-MM，缺失时取最近账期', sample: '2026-11' },
    { key: 'due', std: 'due 应缴金额', type: 'number', required: true, erp: 'ar_bill.due', rule: '分→元，保留 2 位；与费项求和校验', sample: '408.00' },
    { key: 'items', std: 'items 费项明细', type: 'array', required: true, erp: 'ar_bill.detail', rule: '字典 fee_type（物业费/公摊电费/车位费/水费）', sample: '3 项' },
    { key: 'paid', std: 'paid 已缴金额', type: 'number', required: false, erp: 'ar_bill.paid', rule: '只读；与收费系统对账差异 ≤2%', sample: '0.00' },
    { key: 'ownerPhone', std: 'ownerPhone 收费对象', type: 'string', required: false, erp: 'ar_bill.phone', rule: '掩码后展示，明文不出连接器', sample: '138****8821' },
  ],
  room: [
    { key: 'roomCode', std: 'roomCode 房号', type: 'string', required: true, erp: 'bd_house.fnumber', rule: '别名表：3栋2单元1802 → 3-2-1802', sample: '3-2-1802' },
    { key: 'building', std: 'building 楼栋', type: 'string', required: true, erp: 'bd_house.building', rule: '两位补零', sample: '03' },
    { key: 'area', std: 'area 建筑面积', type: 'number', required: true, erp: 'bd_house.area', rule: '保留 2 位；用于公摊与收费口径', sample: '100.00' },
    { key: 'ownerName', std: 'ownerName 业主姓名', type: 'string', required: false, erp: 'bd_owner.name', rule: '脱敏：仅显示姓 + 称谓', sample: '王女士' },
  ],
}

const STATUS_ROWS: StatusRow[] = [
  { key: 's0', erp: '0', std: '待派单', note: 'ERP 新建工单默认态', fallback: '映射到待派单，30 分钟未派单提醒' },
  { key: 's1', erp: '1', std: '已派单', note: '已指派班组 / 责任人', fallback: '—' },
  { key: 's2', erp: '2', std: '处理中', note: '上门或施工中', fallback: '—' },
  { key: 's3', erp: '3', std: '已完工', note: '完工时间取 ERP 最后更新时间', fallback: '触发回访队列' },
  { key: 's9', erp: '9', std: '已取消', note: '含业主取消与误单', fallback: '不推送第三次节点' },
  { key: 'sOther', erp: '未覆盖（如 4/5/8）', std: '转人工', note: 'ERP 新增状态未同步', fallback: '一律转人工，不猜测含义' },
]

const DICT_ROWS: DictRow[] = [
  { key: 'org', name: 'org_dict 组织/项目字典', items: '28 个项目 → 标准 projectId', status: '已确认', owner: '张敏' },
  { key: 'repair', name: 'repair_type 报修类型', items: '给排水 / 电气 / 土建 / 门窗 / 电梯 / 公共设施', status: '待确认', owner: '李工' },
  { key: 'team', name: 'team_dict 派单班组', items: '12 个班组 → 责任片区', status: '已确认', owner: '王强' },
  { key: 'urgency', name: 'urgency 紧急度', items: '0 普通 / 1 紧急 / 2 特急', status: '已确认', owner: '张敏' },
]

const fieldColumns: TableColumn[] = [
  { key: 'std', title: '标准字段', width: '164px' },
  { key: 'type', title: '类型', width: '62px' },
  { key: 'required', title: '必填', width: '46px' },
  { key: 'erp', title: '用友字段', width: '146px' },
  { key: 'rule', title: '转换 / 校验规则' },
  { key: 'sample', title: '示例', width: '100px' },
]

const statusColumns: TableColumn[] = [
  { key: 'erp', title: 'ERP 状态码', width: '140px' },
  { key: 'std', title: '标准状态', width: '90px' },
  { key: 'note', title: '说明' },
  { key: 'fallback', title: '未覆盖时处理', width: '220px' },
]

const dictColumns: TableColumn[] = [
  { key: 'name', title: '字典' },
  { key: 'items', title: '映射项' },
  { key: 'status', title: '状态', width: '90px' },
  { key: 'owner', title: '责任人', width: '80px' },
]

const tabs = [
  { key: 'field', label: '字段映射', badge: '14/14' },
  { key: 'status', label: '状态码映射', badge: '6/6' },
  { key: 'dict', label: '字典映射', badge: '4' },
]

const VERSIONS = [
  { version: 'v1.8.1', note: '修正 urgency 映射，补录 3 个房号别名', who: '李工 · 11-08', state: '可回滚' },
  { version: 'v1.7.6', note: '新增 bill.query 映射 14 字段', who: '张敏 · 10-21', state: '已归档' },
  { version: 'v1.7.2', note: '状态码 0/1/2/3/9 对齐，未覆盖转人工', who: '李工 · 09-30', state: '已归档' },
]

const activeTab = ref('field')
const activeCapability = ref('ticket')
const loading = ref(true)
const currentVersion = ref('v1.8.2')
const connectorName = ref('用友 NC Cloud')
const connectorVersion = ref('nc-1.8.2')
const connectorOpen = ref(false)
const testOpen = ref(false)
const interfaceOpen = ref(false)
const fieldKeyword = ref('')
const savedAt = ref('14:52 · 张敏')
const lastTest = ref({ passed: 198, total: 200 })
const trialCompleteAt = ref('')
const timers: number[] = []

const capability = computed(
  () => CAPABILITIES.value.find((item) => item.id === activeCapability.value) ?? CAPABILITIES.value[3]!,
)

const fields = computed(() => FIELD_MAPS[activeCapability.value] ?? [])

const filteredFields = computed(() => {
  const text = fieldKeyword.value.trim()
  if (!text) return fields.value
  return fields.value.filter((row) => `${row.std}${row.erp}`.includes(text))
})

const fieldRows = computed<Record<string, unknown>[]>(() =>
  filteredFields.value.map((row) => ({ ...row } as unknown as Record<string, unknown>)),
)

const statusRows = computed<Record<string, unknown>[]>(() =>
  STATUS_ROWS.map((row) => ({ ...row } as unknown as Record<string, unknown>)),
)

const dictRows = computed<Record<string, unknown>[]>(() =>
  DICT_ROWS.map((row) => ({ ...row } as unknown as Record<string, unknown>)),
)

const quality = computed(() => {
  const total = fields.value.length
  const required = fields.value.filter((row) => row.required).length
  return { total, required, coverage: total ? 100 : 0 }
})

function fieldOf(row: unknown): FieldRow {
  return row as FieldRow
}

function statusOf(row: unknown): StatusRow {
  return row as StatusRow
}

function dictOf(row: unknown): DictRow {
  return row as DictRow
}

function schedule(ms: number, task: () => void) {
  const timer = window.setTimeout(task, ms)
  timers.push(timer)
}

onMounted(() => {
  schedule(420, () => {
    loading.value = false
  })
})

onBeforeUnmount(() => {
  timers.forEach((timer) => window.clearTimeout(timer))
})

function selectCapability(id: string) {
  activeCapability.value = id
  activeTab.value = 'field'
  fieldKeyword.value = ''
}

function runTest() {
  testOpen.value = true
}

function onTrialComplete(payload: TrialRunPayload) {
  lastTest.value = { passed: payload.passed, total: payload.total }
  trialCompleteAt.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
}

/** 新增能力映射：先落一条草稿能力并切过去，字段表为空时提示复用标准模板（真实状态变化，不是空回执） */
function addCapability() {
  const index = CAPABILITIES.value.length - 9
  const next = { id: `custom-${Date.now()}`, name: `新增能力映射 ${index}（草稿）`, count: 0 }
  CAPABILITIES.value = [...CAPABILITIES.value, next]
  selectCapability(next.id)
  toast.success(`已新增能力映射草稿：默认复用「标准物业模板」，保存草稿后可试运行 200 条` + demoNote('未写入连接器'))
}

function publish() {
  if (currentVersion.value === 'v1.8.3') {
    toast.info('当前映射已是最新版本；如需变更请先编辑映射表')
    return
  }
  currentVersion.value = 'v1.8.3'
  savedAt.value = '16:20 · 张敏'
  toast.success(`映射 ${currentVersion.value} 已发布：AI 侧能力名与话术不变，旧版本可一键回滚`)
}

function rollback(version: string) {
  currentVersion.value = version
  toast.success(`已回滚到映射 ${version}：连接器版本不变，AI 侧无感` + demoNote('仅本地版本记录'))
}

function switchConnector(name: string, version: string) {
  connectorName.value = name
  connectorVersion.value = version
  connectorOpen.value = false
  toast.info(`已切换到连接器：${name}（${version}），映射表按该连接器字段重新加载`)
}

function saveDraft() {
  savedAt.value = '16:2' + Math.min(9, Number(savedAt.value.charAt(4)) + 1)
  toast.success('映射草稿已保存；发布前需先通过「试运行 200 条」校验')
}

function openInterface() {
  interfaceOpen.value = true
}
</script>

<template>
  <div class="w-page mapping-page" data-page="mapping" data-testid="page-root">
    <WPageHeader :eyebrow="screen.eyebrow" :title="screen.title" :description="screen.description">
      <template #actions>
        <WButton size="sm" icon="plug" @click="connectorOpen = true">
          {{ connectorName }} · {{ connectorVersion }}
        </WButton>
        <WButton size="sm" icon="refresh" data-testid="mapping-trial-open" @click="runTest">试运行 200 条</WButton>
        <WButton size="sm" variant="primary" icon="check" @click="publish">发布映射 v1.8.3</WButton>
      </template>
    </WPageHeader>

    <div class="mapping__toolbar">
      <div class="mapping__tabs" role="tablist">
        <button
          v-for="item in tabs"
          :key="item.key"
          type="button"
          role="tab"
          class="mapping__tab"
          :class="{ 'is-on': activeTab === item.key }"
          :aria-selected="activeTab === item.key"
          @click="activeTab = item.key"
        >
          {{ item.label }} <em>{{ item.badge }}</em>
        </button>
      </div>
      <WTag tone="g" plain>沙箱已连通 · 42ms</WTag>
      <span class="dim">当前发布 {{ currentVersion }} · 上次保存 {{ savedAt }}</span>
      <WButton size="sm" variant="ghost" icon="edit" @click="saveDraft">保存草稿</WButton>
    </div>

    <WLoading v-if="loading" center label="正在加载映射配置与试跑结果…" />

    <div v-else class="mapping__split">
      <WCard class="mapping__caps" :padded="false">
        <template #head><span class="mapping__card-title">标准能力 · 18</span></template>
        <div class="mapping__cap-body">
          <button
            v-for="item in CAPABILITIES"
            :key="item.id"
            type="button"
            class="mapping__cap"
            :class="{ 'is-on': activeCapability === item.id }"
            @click="selectCapability(item.id)"
          >
            <span>{{ item.name }}</span>
            <em>{{ item.count }}</em>
          </button>
          <div class="mapping__cap-hr" />
          <button type="button" class="mapping__cap is-add" data-testid="mapping-capability-add" @click="addCapability">
            新增能力映射
          </button>
          <p class="mapping__cap-foot">
            <b>18 / 18</b> 项能力已配置<br />
            复用「标准物业模板」3 套<br />
            新增 ERP 平均 6.5 天
          </p>
        </div>
      </WCard>

      <WCard class="mapping__main" :padded="false">
        <template #head>
          <span class="mapping__card-title">{{ capability.name }} · {{ activeTab === 'field' ? '字段映射' : activeTab === 'status' ? '状态码映射' : '字典映射' }}</span>
          <WTag :tone="activeTab === 'dict' ? 'a' : 'g'">{{ activeTab === 'dict' ? '2 项待确认' : '已启用' }}</WTag>
          <span class="mapping__card-sub">AI 建单时写入 ERP，工单带「AI 创建」标识，前两周 100% 人工抽检</span>
        </template>
        <template #extra>
          <input v-if="activeTab === 'field'" v-model="fieldKeyword" class="mapping__search" type="search" placeholder="搜字段 / ERP 字段" />
          <WButton size="sm" icon="report" @click="openInterface">查看接口</WButton>
        </template>

        <WTable v-if="activeTab === 'field'" :columns="fieldColumns" :rows="fieldRows" row-key="key" dense empty-text="该能力暂无差异字段；可清空搜索或切换其它能力。">
          <template #std="{ row }">
            <span class="mapping__std">{{ fieldOf(row).std }}</span>
          </template>
          <template #type="{ row }">
            <span class="dim">{{ fieldOf(row).type }}</span>
          </template>
          <template #required="{ row }">
            <span :class="fieldOf(row).required ? 'mapping__req' : 'dim'">{{ fieldOf(row).required ? '*' : '○' }}</span>
          </template>
          <template #erp="{ row }">
            <code class="mapping__code">{{ fieldOf(row).erp }}</code>
          </template>
          <template #rule="{ row }">
            <span class="mapping__rule">{{ fieldOf(row).rule }}</span>
          </template>
          <template #sample="{ row }">
            <code class="mapping__code">{{ fieldOf(row).sample }}</code>
          </template>
          <template #empty>
            <WEmpty compact icon="mapping" title="该能力暂无差异字段" description="复用「标准物业模板」即可；如需新增字段请走变更单。" />
          </template>
        </WTable>

        <WTable v-else-if="activeTab === 'status'" :columns="statusColumns" :rows="statusRows" row-key="key" dense>
          <template #erp="{ row }">
            <code class="mapping__code">{{ statusOf(row).erp }}</code>
          </template>
          <template #std="{ row }">
            <WTag :tone="statusOf(row).std === '转人工' ? 'a' : 'g'">{{ statusOf(row).std }}</WTag>
          </template>
          <template #note="{ row }">
            <span class="mapping__rule">{{ statusOf(row).note }}</span>
          </template>
          <template #fallback="{ row }">
            <span class="mapping__rule">{{ statusOf(row).fallback }}</span>
          </template>
          <template #footer>
            状态码映射：ERP 0/1/2/3/9 → 待派单 / 已派单 / 处理中 / 已完工 / 已取消。遇到未覆盖状态一律转人工，不猜测。
          </template>
        </WTable>

        <WTable v-else :columns="dictColumns" :rows="dictRows" row-key="key" dense>
          <template #name="{ row }">
            <span class="mapping__std">{{ dictOf(row).name }}</span>
          </template>
          <template #items="{ row }">
            <span class="mapping__rule">{{ dictOf(row).items }}</span>
          </template>
          <template #status="{ row }">
            <WTag :tone="dictOf(row).status === '已确认' ? 'g' : 'a'">{{ dictOf(row).status }}</WTag>
          </template>
          <template #owner="{ row }">
            <span>{{ dictOf(row).owner }}</span>
          </template>
          <template #footer>
            字典映射来自 ERP 基础资料；「待确认」项发布前必须由责任人确认，避免“新价已执行、AI 还在报旧值”。
          </template>
        </WTable>

        <div class="mapping__changelog">
          <div class="mapping__cl-item">
            <b>{{ currentVersion }}</b>
            <span>新增 aiCreated / source 字段，用于 AI 工单 100% 抽检</span>
            <em>张敏 · {{ savedAt }}</em>
          </div>
          <div v-for="item in VERSIONS" :key="item.version" class="mapping__cl-item">
            <b>{{ item.version }}</b>
            <span>{{ item.note }}</span>
            <em>{{ item.who }}</em>
            <WButton v-if="item.state === '可回滚'" size="sm" variant="ghost" @click="rollback(item.version)">回滚</WButton>
          </div>
        </div>

        <div class="mapping__foot">
          <span>
            试运行记录：200 条中 {{ lastTest.passed }} 条通过 · {{ lastTest.total - lastTest.passed }} 条差异已定位到房号别名与字典项
            <template v-if="trialCompleteAt">（最近一次 {{ trialCompleteAt }}）</template>
          </span>
          <span class="mapping__foot-note">写操作需单独授权并标记「AI 创建」；试跑差异必须人工确认后才可发布。</span>
        </div>
      </WCard>

      <div class="mapping__side">
        <WCard title="映射质量" subtitle="发布前校验">
          <template #extra><WTag :tone="lastTest.passed >= 190 ? 'g' : 'a'">{{ lastTest.passed >= 190 ? '可发布' : '需复核' }}</WTag></template>
          <div class="mapping__q-row"><span class="dim">字段覆盖率</span><b class="num">{{ quality.coverage }}%</b></div>
          <div class="mapping__bar"><i :style="{ width: `${quality.coverage}%` }" /></div>
          <div class="mapping__check"><span>必填字段校验</span><b class="num">{{ quality.required }} / {{ quality.required }}</b></div>
          <div class="mapping__check"><span>类型与长度校验</span><b class="num">{{ quality.total }} / {{ quality.total }}</b></div>
          <div class="mapping__check"><span>字典项待确认</span><b class="num is-warn">2</b></div>
          <div class="mapping__check"><span>沙箱连通性</span><b class="num">42ms</b></div>
          <div class="mapping__hr" />
          <div class="mapping__q-row"><span class="dim">最近试跑 200 条</span><b class="num is-ok">{{ lastTest.passed }} 通过</b></div>
          <div class="mapping__bar"><i :style="{ width: `${(lastTest.passed / lastTest.total) * 100}%` }" /></div>
          <p class="mapping__tip">2 条失败：房号「3栋2单元1802」别名未收录；「外墙渗水」无对应报修类型。</p>
        </WCard>

        <WCard title="标准契约预览" subtitle="canonical">
          <template #extra><WTag tone="v">v1.4</WTag></template>
          <pre class="mapping__pre">// POST /canonical/v1/ticket.create
{
  "projectId": "P-1024",
  "roomCode": "3-2-1802",
  "category": "给排水",
  "urgency": "紧急",
  "images": ["img_01"],
  "source": "AI"
}</pre>
          <p class="mapping__tip">同一份请求，可由用友 / 金蝶 / 明源任一连接器执行；AI 侧无厂商字段。</p>
          <div class="mapping__hr" />
          <div class="mapping__q-row"><span class="dim">当前发布</span><b>{{ currentVersion }} · {{ savedAt }}</b></div>
          <div v-for="item in VERSIONS" :key="`v-${item.version}`" class="mapping__vrow">
            <b>{{ item.version }}</b>
            <span>{{ item.state }}</span>
          </div>
        </WCard>
      </div>
    </div>

    <WModal v-model:open="connectorOpen" title="切换连接器" width="480px">
      <div class="mapping__modal">
        <button
          v-for="item in [
            { name: '用友 NC Cloud', version: 'nc-1.8.2' },
            { name: '金蝶 EAS', version: 'eas-1.3.0' },
            { name: '明源云', version: 'my-0.9.4' },
            { name: '自研 ERP', version: 'self-2.1.0' },
            { name: '新视窗', version: 'v0.3 沙箱' },
          ]"
          :key="item.name"
          type="button"
          class="mapping__conn"
          :class="{ 'is-on': connectorName === item.name }"
          @click="switchConnector(item.name, item.version)"
        >
          <b>{{ item.name }}</b>
          <WTag tone="n">{{ item.version }}</WTag>
        </button>
        <p class="mapping__modal-note">切换后映射表按该连接器字段重新加载；字段差异走变更单，不改 AI 侧标准契约。</p>
      </div>
    </WModal>

    <TrialRunModal
      v-model:open="testOpen"
      title="试运行结果（200 条样本）"
      :total="lastTest.total"
      :passed="lastTest.passed"
      :diffs="TRIAL_DIFFS"
      hint="试跑只读、不写 ERP；差异未清零前禁止发布映射。样本来自云栖苑 20 户示例数据（演示数据）。"
      @complete="onTrialComplete"
    />

    <WModal v-model:open="interfaceOpen" title="ticket.create 接口详情" width="540px">
      <div class="mapping__modal">
        <pre class="mapping__pre">POST /canonical/v1/ticket.create
连接器：{{ connectorName }} · {{ connectorVersion }}
鉴权：OAuth2 Client Credentials
超时：800ms · 重试 1 次
写入标识：aiCreated=1 · source=AI</pre>
        <p class="mapping__modal-note">演示环境不发起真实请求；真实接口在接入单套 ERP 只读联调后启用。</p>
      </div>
      <template #footer>
        <WButton size="sm" variant="primary" @click="interfaceOpen = false">关闭</WButton>
      </template>
    </WModal>

    <StateDemo screen="mapping" />
  </div>
</template>

<style scoped>
.mapping-page {
  min-width: 0;
}

.mapping__toolbar {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  flex-wrap: wrap;
}

.mapping__tabs {
  display: inline-flex;
  gap: 2px;
  padding: 3px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-md);
  background: var(--c-bg-2);
}

.mapping__tab {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 26px;
  padding: 0 11px;
  border-radius: var(--rd-sm);
  font-size: var(--fs-sm);
  color: var(--c-muted);
  transition: background-color var(--tr-fast), color var(--tr-fast);
}

.mapping__tab em {
  font-style: normal;
  font-size: 9px;
  color: var(--c-faint);
}

.mapping__tab.is-on {
  background: var(--c-card);
  color: var(--c-brand);
  font-weight: var(--fw-semibold);
  box-shadow: var(--sh-sm);
}

.mapping__tab.is-on em {
  color: var(--c-brand);
}

.mapping__tab:focus-visible {
  outline: none;
  box-shadow: var(--sh-ring);
}

.mapping__split {
  display: grid;
  grid-template-columns: 186px minmax(0, 1fr) 300px;
  gap: var(--sp-3);
  align-items: start;
}

.mapping__card-title {
  font-size: 12.5px;
  font-weight: var(--fw-semibold);
  color: var(--c-ink);
}

.mapping__card-sub {
  margin-left: 6px;
  font-size: var(--fs-2xs);
  color: var(--c-faint);
}

/* ---------- 能力列表 ---------- */
.mapping__cap-body {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px;
}

.mapping__cap {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  width: 100%;
  padding: 6px 8px;
  border-radius: var(--rd-sm);
  font-size: var(--fs-xs);
  color: var(--c-ink-2);
  text-align: left;
  transition: background-color var(--tr-fast), color var(--tr-fast);
}

.mapping__cap em {
  flex: none;
  font-style: normal;
  font-size: 9px;
  color: var(--c-faint);
}

.mapping__cap:hover {
  background: var(--c-bg-2);
}

.mapping__cap.is-on {
  background: var(--c-brand-soft);
  color: var(--c-brand);
  font-weight: var(--fw-semibold);
}

.mapping__cap:focus-visible {
  outline: none;
  box-shadow: var(--sh-ring);
}

.mapping__cap.is-add {
  color: var(--c-brand-alt);
}

.mapping__cap-hr {
  height: 1px;
  margin: 5px 2px;
  background: var(--c-line-2);
}

.mapping__cap-foot {
  margin-top: 6px;
  padding: 8px;
  border-radius: var(--rd-sm);
  background: var(--c-bg-2);
  font-size: var(--fs-2xs);
  color: var(--c-muted);
  line-height: var(--lh-loose);
}

.mapping__cap-foot b {
  color: var(--c-brand);
}

/* ---------- 映射表 ---------- */
.mapping__search {
  width: 150px;
  height: 26px;
  padding: 0 9px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-sm);
  background: var(--c-card);
  font-size: var(--fs-2xs);
  color: var(--c-ink);
}

.mapping__search:focus {
  outline: none;
  border-color: var(--c-brand);
  box-shadow: var(--sh-ring);
}

.mapping__std {
  font-size: var(--fs-xs);
  color: var(--c-ink-2);
  font-weight: var(--fw-medium);
}

.mapping__req {
  color: var(--c-red);
  font-weight: var(--fw-semibold);
}

.mapping__code {
  font-family: var(--font-mono);
  font-size: 9.5px;
  color: var(--c-ink-2);
  padding: 1px 4px;
  border-radius: var(--rd-xs);
  background: var(--c-bg-2);
  border: 1px solid var(--c-line-2);
  white-space: nowrap;
}

.mapping__rule {
  font-size: var(--fs-2xs);
  color: var(--c-muted);
  line-height: var(--lh-base);
}

.mapping__changelog {
  display: flex;
  flex-direction: column;
  border-top: 1px solid var(--c-line-2);
  background: var(--c-bg-2);
}

.mapping__cl-item {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  padding: 6px 12px;
  border-bottom: 1px dashed var(--c-line-2);
  font-size: var(--fs-2xs);
  color: var(--c-muted);
}

.mapping__cl-item b {
  flex: none;
  color: var(--c-ink-2);
}

.mapping__cl-item span {
  flex: 1;
  min-width: 0;
}

.mapping__cl-item em {
  flex: none;
  font-style: normal;
  color: var(--c-faint);
}

.mapping__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
  flex-wrap: wrap;
  padding: 9px 12px;
  border-top: 1px solid var(--c-line-2);
  font-size: var(--fs-2xs);
  color: var(--c-muted);
}

.mapping__foot-note {
  color: var(--c-amber);
}

/* ---------- 右侧 ---------- */
.mapping__side {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
}

.mapping__q-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--sp-2);
  font-size: var(--fs-xs);
}

.mapping__bar {
  height: 5px;
  margin: 5px 0 9px;
  border-radius: var(--rd-pill);
  background: var(--c-line-2);
  overflow: hidden;
}

.mapping__bar i {
  display: block;
  height: 100%;
  border-radius: var(--rd-pill);
  background: var(--c-brand-gradient);
}

.mapping__check {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-2);
  padding: 3px 0;
  font-size: var(--fs-xs);
  color: var(--c-muted);
}

.mapping__check b {
  font-weight: var(--fw-medium);
  color: var(--c-ink-2);
}

.mapping__check b.is-warn {
  color: var(--c-amber);
}

.mapping__check b.is-ok {
  color: var(--c-brand);
}

.mapping__hr {
  height: 1px;
  margin: 8px 0;
  background: var(--c-line-2);
}

.mapping__tip {
  margin-top: 7px;
  padding: 6px 8px;
  border-radius: var(--rd-sm);
  background: var(--c-amber-soft);
  color: #9a4b05;
  font-size: var(--fs-2xs);
  line-height: var(--lh-base);
}

.mapping__pre {
  margin: 0;
  padding: 9px 10px;
  border-radius: var(--rd-sm);
  background: #f7fafc;
  border: 1px solid var(--c-line-2);
  font-family: var(--font-mono);
  font-size: 9.5px;
  line-height: 1.65;
  color: var(--c-ink-2);
  overflow-x: auto;
  white-space: pre;
}

.mapping__vrow {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-2);
  padding: 4px 0;
  font-size: var(--fs-2xs);
  color: var(--c-muted);
}

.mapping__vrow b {
  font-size: 9.5px;
  color: var(--c-ink-2);
}

/* ---------- 弹层 ---------- */
.mapping__modal {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.mapping__conn {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-2);
  padding: 8px 10px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-sm);
  background: var(--c-card);
  font-size: var(--fs-xs);
  color: var(--c-ink-2);
  text-align: left;
}

.mapping__conn.is-on {
  border-color: var(--c-brand);
  background: var(--c-brand-soft);
  color: var(--c-brand);
  font-weight: var(--fw-semibold);
}

.mapping__conn:focus-visible {
  outline: none;
  box-shadow: var(--sh-ring);
}

.mapping__modal-note {
  font-size: var(--fs-2xs);
  color: var(--c-faint);
  line-height: var(--lh-base);
}

@media (max-width: 1279px) {
  .mapping__split {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
