<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, RouterView, useRouter } from 'vue-router'
import { WButton, WCard, WIcon, WTag, useToast } from '@/components'
import { COPILOT_WORKSPACES, COPILOT_WORKSPACE_ORDER } from '@/app/copilot'
import { api, errorText, forbiddenDetailsOf } from '@/api'
import type { CopilotWorkspaceId, CopilotWorkspaceStatus } from '@/api'
import { capabilityLabel } from '@/auth/capabilities'
import {
  COPILOT_RULE_NOTE,
  COPILOT_STATUS_LABELS,
  COPILOT_STATUS_TONES,
  DEMO_DATA_NOTE,
  WORKSPACE_API_IDS,
  downloadCsv,
} from './shared'
import StateDemo from '@/content/StateDemo.vue'
import { successReceipt } from '@/content'
// 预热：静态引入 8 个工作区，保证左栏切换在点击后立即提交路由（URL 与面板同步），
// 同时避免切换瞬间出现空 pane；路由仍按子路由懒加载同一模块（构建后落在同一 chunk）。
import KnowledgeWorkspace from './workspaces/KnowledgeWorkspace.vue'
import ScriptWorkspace from './workspaces/ScriptWorkspace.vue'
import SummaryWorkspace from './workspaces/SummaryWorkspace.vue'
import ProfileWorkspace from './workspaces/ProfileWorkspace.vue'
import DailyWorkspace from './workspaces/DailyWorkspace.vue'
import GroupWorkspace from './workspaces/GroupWorkspace.vue'
import BuildWorkspace from './workspaces/BuildWorkspace.vue'
import HistoryWorkspace from './workspaces/HistoryWorkspace.vue'

/** 仅用于保持预热引用，防止打包器摇掉这些模块 */
const WORKSPACE_PRELOAD = [
  KnowledgeWorkspace,
  ScriptWorkspace,
  SummaryWorkspace,
  ProfileWorkspace,
  DailyWorkspace,
  GroupWorkspace,
  BuildWorkspace,
  HistoryWorkspace,
]
void WORKSPACE_PRELOAD

/**
 * 04 管家 Copilot · 壳（P4 W16：工作区目录/状态来自 `GET /copilot/workspaces`）
 * ---------------------------------------------------------------------------
 * - 左栏 8 个工作区（P1 设计口径，路由驱动）+ 每个工作区的**契约状态**（available/degraded/planned），
 *   状态来自 `GET /api/v1/copilot/workspaces`（8 项固定顺序），**不在页面硬编码**；
 * - 中间 pane 用 `<KeepAlive>` 包裹：切换工作区不丢状态（输入/已生成结果/筛选条件保留），
 *   满足「切换不丢状态」；URL 仍与面板同步（子路由）；
 * - 目录接口失败/403 时左栏保留静态入口并标注「目录接口不可用」，不伪造状态；
 * - 本壳不渲染 h1（h1 由各工作区 pane 提供），避免 e2e 首屏标题断言漂移。
 */
const router = useRouter()
const toast = useToast()

const workspaces = COPILOT_WORKSPACE_ORDER.map((key) => COPILOT_WORKSPACES[key])

const quickLinks = ['summary', 'script', 'profile', 'daily'] as const

/* ---------------- P4：工作区目录 / 状态（GET /copilot/workspaces） ---------------- */
const catalog = ref<Record<string, { name: string; status: CopilotWorkspaceStatus; description: string }>>({})
const catalogLoading = ref(false)
const catalogError = ref('')
const catalogDenied = ref<{ requiredCapability: string; role: string } | null>(null)
const catalogTotal = ref(0)

interface WorkspaceStatusView {
  /** 该设计工作区消费的契约工作区 ID（契约顺序） */
  ids: readonly CopilotWorkspaceId[]
  /** 聚合状态：任一 planned → planned；否则任一 degraded → degraded；全 available → available */
  status: CopilotWorkspaceStatus | null
  /** 契约里的中文名（对拍用；空态时不编造） */
  names: string[]
}

/**
 * 设计工作区 → 契约状态聚合：
 * - 契约 `planned`（未上线）优先展示为未上线，入口置灰提示；
 * - 任一 `degraded` → 部分降级；全部 `available` → 已接真实数据；目录未取到 → null（不编造）。
 */
function statusOf(key: keyof typeof COPILOT_WORKSPACES): WorkspaceStatusView {
  const ids = WORKSPACE_API_IDS[key] ?? []
  const rows = ids.map((id) => catalog.value[id]).filter(Boolean)
  const names = rows.map((row) => row.name)
  if (rows.length === 0) return { ids, status: null, names }
  if (rows.some((row) => row.status === 'planned')) return { ids, status: 'planned', names }
  if (rows.some((row) => row.status === 'degraded')) return { ids, status: 'degraded', names }
  return { ids, status: 'available', names }
}

const catalogStatus = computed<Record<string, WorkspaceStatusView>>(() => {
  const result: Record<string, WorkspaceStatusView> = {}
  for (const workspace of workspaces) result[workspace.key] = statusOf(workspace.key)
  return result
})

async function loadCatalog(): Promise<void> {
  catalogLoading.value = true
  catalogError.value = ''
  catalogDenied.value = null
  try {
    const { data } = await api.copilot.workspaces()
    const map: Record<string, { name: string; status: CopilotWorkspaceStatus; description: string }> = {}
    for (const item of data.items) {
      map[item.workspaceId] = { name: item.name, status: item.status, description: item.description }
    }
    catalog.value = map
    catalogTotal.value = data.total
  } catch (error) {
    catalog.value = {}
    catalogTotal.value = 0
    const forbidden = forbiddenDetailsOf(error)
    if (forbidden) {
      catalogDenied.value = { requiredCapability: forbidden.requiredCapability, role: forbidden.role }
      return
    }
    const code = error instanceof Error && 'code' in error ? String(error.code) : 'E-9001'
    catalogError.value = `工作区目录暂不可用（${errorText(code)}）`
  } finally {
    catalogLoading.value = false
  }
}

function retryCatalog(): void {
  toast.warning('正在重新读取 Copilot 工作区目录（GET /api/v1/copilot/workspaces）')
  void loadCatalog()
}

const catalogBadge = computed(() => {
  if (catalogDenied.value) return '目录无权限'
  if (catalogError.value) return '目录不可用'
  return `${catalogTotal.value || workspaces.length} / 8 · 状态来自接口`
})

const sidebarProfile = {
  name: '王女士',
  room: '3-2-1802 · 业主本人',
  meta: '入住 2021 · 微信已绑定',
  stats: [
    { value: '6', label: '历史报修' },
    { value: '1', label: '投诉' },
    { value: '¥0', label: '欠费' },
    { value: '提前 3 天', label: '缴费习惯' },
  ],
  history: [
    { date: '10-28', text: '报修 厨房下水慢 · 已完工' },
    { date: '08-12', text: '投诉 楼道堆物 · 已闭环' },
    { date: '06-03', text: '咨询 车位续租 · 已办理' },
  ],
}

const groupSummary = [
  { tag: '① 押金退还', text: '3-2-1802 询问装修押金进度，需回访。' },
  { tag: '② 月卡续费', text: '2 户询问地库月卡，指向收费系统。' },
  { tag: '③ 停水通知', text: '11-15 09:00—12:00 计划停水，需回执。' },
  { tag: '④ 电梯维保', text: '2 号楼电梯 11-16 维保，已公告。' },
]

function openWorkspace(path: string) {
  void router.push(path)
}

function exportWeekly() {
  downloadCsv(
    '云栖苑-Copilot周报-演示.csv',
    ['指标', '本周', '说明'],
    [
      ['管家日活率', '78%', '演示数据'],
      ['话术采纳率', '54%', '演示数据'],
      ['知识查询次数', '12', '今日'],
      ['话术生成次数', '8', '今日'],
      ['群摘要次数', '4', '今日'],
    ],
  )
  toast.success(successReceipt('导出 Copilot 周报 CSV', '5 行指标'))
}

onMounted(() => {
  void loadCatalog()
})
</script>

<template>
  <div class="cop" data-page="copilot" data-testid="page-root">
    <header class="cop__toolbar">
      <div class="cop__toolbar-info">
        <div class="cop__eyebrow">EMPLOYEE COPILOT · 员工端</div>
        <p class="cop__subtitle">零对客风险、当天见效：管家每一次纠错，都会回流成知识库的高质量语料。</p>
      </div>
      <div class="cop__toolbar-actions">
        <WTag tone="g">管家日活 78%</WTag>
        <WTag tone="b">话术采纳 54%</WTag>
        <WButton size="sm" icon="download" @click="exportWeekly">导出周报</WButton>
      </div>
    </header>

    <div class="cop__body">
      <aside class="cop__rail" aria-label="Copilot 工作区">
        <header class="cop__rail-head">
          <span class="cop__rail-title">工作区</span>
          <WTag tone="v" plain data-testid="copilot-catalog-badge">{{ catalogBadge }}</WTag>
        </header>
        <nav class="cop__rail-nav">
          <template v-for="(workspace, index) in workspaces" :key="workspace.key">
            <div v-if="index === 6" class="cop__rail-sep" role="separator" />
            <RouterLink
              :to="workspace.path"
              class="cop__rail-item"
              active-class="is-active"
              data-testid="copilot-ws-tab"
              :data-ws="workspace.key"
              :data-ws-status="catalogStatus[workspace.key]?.status ?? 'unknown'"
            >
              <WIcon :name="workspace.icon" :size="15" />
              <span class="cop__rail-label">{{ workspace.label }}</span>
              <WTag
                v-if="catalogStatus[workspace.key]?.status"
                class="cop__rail-status"
                :tone="COPILOT_STATUS_TONES[catalogStatus[workspace.key]!.status!]"
                plain
                data-testid="copilot-ws-status"
              >
                {{ COPILOT_STATUS_LABELS[catalogStatus[workspace.key]!.status!] }}
              </WTag>
            </RouterLink>
          </template>
        </nav>
        <p v-if="catalogError" class="cop__rail-error" data-testid="copilot-catalog-error">
          {{ catalogError }}：左栏入口保持可用，但状态未知（不编造）。
          <button type="button" class="cop__rail-retry" @click="retryCatalog">重试</button>
        </p>
        <p v-if="catalogDenied" class="cop__rail-error" data-testid="copilot-catalog-denied">
          工作区目录无权限（需要 {{ capabilityLabel(catalogDenied.requiredCapability) }}；当前角色
          {{ catalogDenied.role || '未知' }}）。左栏入口仍按 P1 可见性展示，状态未知。
        </p>
        <div class="cop__rail-tip">
          <b>今日已节省 46 分钟</b>
          知识查询 12 次 · 话术 8 次 · 群摘要 4 次<br />
          <span class="cop-dim">{{ DEMO_DATA_NOTE }}</span>
          <br />
          <span class="cop-dim" data-testid="copilot-rule-note">{{ COPILOT_RULE_NOTE }}</span>
        </div>
      </aside>

      <section class="cop__pane copilot__pane" data-testid="copilot-pane">
        <RouterView v-slot="{ Component }">
          <Transition name="fade" mode="out-in">
            <!-- 切换工作区不丢状态（输入/生成结果/筛选保留）；每屏仍由子路由驱动 URL -->
            <KeepAlive>
              <component :is="Component" />
            </KeepAlive>
          </Transition>
        </RouterView>
        <StateDemo screen="copilot" />
      </section>

      <aside class="cop__side" aria-label="Copilot 上下文">
        <div class="cop-side-card">
          <div class="cop-side-head">
            <b>业主画像</b>
            <WTag tone="g">在用</WTag>
          </div>
          <div class="cop-person">
            <span class="cop-avatar">王</span>
            <div class="cop-person-meta">
              <b>{{ sidebarProfile.name }}</b>
              <span>{{ sidebarProfile.room }}</span>
              <span>{{ sidebarProfile.meta }}</span>
            </div>
          </div>
          <div class="cop-side-stats">
            <div v-for="stat in sidebarProfile.stats" :key="stat.label">
              <b>{{ stat.value }}</b>
              <span>{{ stat.label }}</span>
            </div>
          </div>
          <div class="cop-side-list">
            <div v-for="item in sidebarProfile.history" :key="item.date" class="cop-side-row">
              <b>{{ item.date }}</b>
              <span>{{ item.text }}</span>
            </div>
          </div>
          <p class="cop-dim cop-side-note">右侧为 P1 演示样例；真实脱敏画像请到「业主画像」工作区按房号查询（GET /copilot/portrait）。</p>
        </div>

        <div class="cop-side-card">
          <div class="cop-side-head">
            <b>群消息摘要</b>
            <WTag tone="v">50 条 → 5 行</WTag>
          </div>
          <div class="cop-side-list">
            <div v-for="item in groupSummary" :key="item.tag" class="cop-side-row">
              <b>{{ item.tag }}</b>
              <span>{{ item.text }}</span>
            </div>
          </div>
          <WButton size="sm" icon="report" block @click="openWorkspace('/copilot/summary')">生成今日待办</WButton>
        </div>

        <div class="cop-side-card">
          <div class="cop-side-head">
            <b>快捷入口</b>
            <span class="cop-dim">常用</span>
          </div>
          <div class="cop-qgrid">
            <button v-for="key in quickLinks" :key="key" type="button" @click="openWorkspace(COPILOT_WORKSPACES[key].path)">
              <WIcon :name="COPILOT_WORKSPACES[key].icon" :size="13" />
              {{ COPILOT_WORKSPACES[key].label }}
            </button>
          </div>
        </div>

        <div class="cop-side-card">
          <div class="cop-side-head">
            <b>使用提示</b>
            <WTag tone="v">AI</WTag>
          </div>
          <div class="cop-note">
            <WIcon name="info" :size="13" />
            <span>问法越具体，命中越准：带上房号、位置或条款名，可直接定位。答案必须带出处。</span>
          </div>
        </div>

        <WCard title="边界" flat>
          <div class="cop-side-list">
            <div class="cop-side-row"><b>不存</b><span>业务数据实时来自 ERP</span></div>
            <div class="cop-side-row"><b>不碰钱</b><span>缴费只跳转，退款减免转人工</span></div>
            <div class="cop-side-row"><b>不承诺</b><span>赔偿/定责/滞纳金豁免转人工</span></div>
          </div>
        </WCard>
      </aside>
    </div>
  </div>
</template>

<style src="./copilot.css"></style>
