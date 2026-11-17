<script setup lang="ts">
import { computed, ref } from 'vue'
import {
  WButton,
  WCard,
  WDrawer,
  WEmpty,
  WLoading,
  WModal,
  WPageHeader,
  WTable,
  WTabs,
  WTag,
  WTimeline,
  useToast,
} from '@/components'
import {
  DEMO_BOUNDARY_NOTE,
  ERROR_CODE_LIST,
  SCREEN_COPY,
  SCREEN_COPY_LIST,
  SCREEN_EMPTY_COPY,
  STATE_LABELS,
  STATE_ORDER,
  TOAST_TONE_POLICY,
  demoNote,
  errorReceipt,
  failureReceipt,
  infoReceipt,
  stateCopy,
  successReceipt,
} from '@/content'
import type { ScreenKey, TableColumn, TimelineItem } from '@/types'

/**
 * 共享组件与文案预览页（/ui-kit，不进导航）。
 * 用途：组件示例（hover/active/focus/disabled）+ **文案与状态口径演示**
 * （空 / 加载 / 错误 / 降级四态、Toast 回执句式、17 类错误码人话表）。
 * 文案统一来自 `apps/web/src/content`，本页只做展示与触发，不造业务数据。
 */
const toast = useToast()

const activeTab = ref('field')
const tabs = [
  { key: 'field', label: '字段映射', badge: '12' },
  { key: 'status', label: '状态映射' },
  { key: 'dict', label: '字典映射', badge: '4' },
]

const modalOpen = ref(false)
const drawerOpen = ref(false)

/* ------------------------------ 文案与状态演示 ------------------------------ */

const activeScreen = ref<ScreenKey>('billing')
const screenButtons = SCREEN_COPY_LIST.map((copy) => ({ key: copy.key, label: copy.title }))
const activeScreenCopy = computed(() => SCREEN_COPY[activeScreen.value])
const demoStates = computed(() =>
  STATE_ORDER.map((kind) => ({ kind, label: STATE_LABELS[kind], copy: stateCopy(kind, activeScreen.value) })),
)
const toneRules = Object.entries(TOAST_TONE_POLICY).map(([tone, rule]) => ({ tone, rule }))

function fireStateReceipt(kind: 'success' | 'failure' | 'info' | 'demo') {
  if (kind === 'success') toast.success(successReceipt('导出欠费台账', '10 行'))
  if (kind === 'failure') toast.error(failureReceipt('催缴任务', '上游系统超时'))
  if (kind === 'info') toast.info(infoReceipt('入口说明', '完整回放见「全链路回溯」（10 屏）'))
  if (kind === 'demo') toast.info(demoNote('本次操作仅本地状态，未写入后端'))
}

function fireErrorCode(code: string) {
  const receipt = errorReceipt(code)
  toast[receipt.tone](receipt.message)
}

/* -------------------------------- 组件示例 -------------------------------- */

const columns: TableColumn[] = [
  { key: 'token', title: 'Token', width: '180px' },
  { key: 'value', title: '值', width: '150px' },
  { key: 'note', title: '用途' },
]

const rows: Record<string, unknown>[] = [
  { token: '--c-brand', value: '#0A6B5B', note: '主品牌深绿（P1 定稿）' },
  { token: '--c-ink', value: '#0E1D2B', note: '正文 / 深色' },
  { token: '--c-bg', value: '#F3F7F8', note: '页面浅底' },
  { token: '--rd-xl', value: '14px', note: '卡片圆角' },
]

const timelineItems: TimelineItem[] = [
  { title: '设计 token 与全局样式', time: 'P1', status: 'done', description: 'tokens.css + styles/index.css' },
  { title: 'App Shell 与 12 屏路由', time: 'P1', status: 'done', description: '侧栏 / 顶栏 / 角色可见性' },
  { title: '12 屏业务内容', time: 'P1', status: 'done', description: '12 屏 + 8 工作区已填充演示内容' },
  { title: '动作弹层与统一文案', time: 'P1', status: 'active', description: '0 死按钮；空 / 载 / 错 / 降级四态统一' },
]

const errorColumns: TableColumn[] = [
  { key: 'code', title: '错误码', width: '88px' },
  { key: 'label', title: '契约语义', width: '190px' },
  { key: 'userMessage', title: '面向用户的人话' },
  { key: 'retryable', title: '可重试', width: '72px', align: 'center' },
  { key: 'action', title: '触发', width: '84px', align: 'right' },
]

const errorRows: Record<string, unknown>[] = ERROR_CODE_LIST.map((copy) => ({
  code: copy.code,
  label: copy.label,
  userMessage: copy.userMessage,
  retryable: copy.retryable ? '是' : '否',
}))

function demo(message: string) {
  toast.info(message)
}

function confirmDemo() {
  modalOpen.value = false
  toast.success(successReceipt('提交示例确认', '弹层已关闭'))
}
</script>

<template>
  <div class="w-page uikit" data-page="ui-kit">
    <WPageHeader
      eyebrow="共享组件 · 开发预览"
      title="组件与文案预览"
      description="Button / Card / Tag / Modal / Drawer / Tabs / Timeline / Table / Empty / Loading / Toast 的最小可用示例；另附「文案与状态」口径（四态句式、Toast 回执、17 类错误码人话）。本页不进导航，仅内部预览。"
    >
      <template #actions>
        <WTag tone="g">P1-005 / P1-036</WTag>
        <WButton size="sm" variant="ghost" icon="refresh" @click="demo('示例状态已重置：页面交互不做持久化')">重置示例</WButton>
      </template>
    </WPageHeader>

    <WCard title="Button" subtitle="hover / active / focus-visible / disabled 四态；primary 用于主操作">
      <div class="uikit__rows">
        <div class="uikit__row">
          <WButton variant="primary" @click="demo('主操作已触发：仅演示交互，不写入数据')">主操作</WButton>
          <WButton @click="demo('次操作已触发：仅演示交互，不写入数据')">次操作</WButton>
          <WButton variant="ghost" @click="demo('幽灵按钮已触发：用于低优先级入口')">幽灵按钮</WButton>
          <WButton variant="ai" icon="sparkles" @click="demo('AI 按钮已触发：生成类动作需人工确认后落库')">AI 生成</WButton>
          <WButton variant="danger" icon="close" @click="demo('危险操作需二次确认；演示环境不执行真实取消')">取消任务</WButton>
        </div>
        <div class="uikit__row">
          <WButton size="sm" @click="demo('小号按钮：工具栏与卡片内操作')">小号</WButton>
          <WButton @click="demo('中号按钮：页面主操作')">中号</WButton>
          <WButton size="lg" @click="demo('大号按钮：空态与引导页')">大号</WButton>
          <WButton loading>加载中</WButton>
          <WButton disabled>禁用</WButton>
          <WButton icon="download" @click="demo('导出：演示环境真实生成 CSV，数据为示例数据')">带图标</WButton>
        </div>
      </div>
    </WCard>

    <WCard title="Tag / Tabs" subtitle="标签色调与页内分段">
      <div class="uikit__rows">
        <div class="uikit__row">
          <WTag tone="g">品牌绿</WTag>
          <WTag tone="b">信息蓝</WTag>
          <WTag tone="a">警告琥珀</WTag>
          <WTag tone="r">风险红</WTag>
          <WTag tone="v">AI 紫</WTag>
          <WTag tone="n">中性</WTag>
          <WTag tone="g" plain>无色点</WTag>
        </div>
        <div class="uikit__row">
          <WTabs v-model="activeTab" :tabs="tabs" />
          <span class="uikit__hint">当前：{{ activeTab }}</span>
        </div>
      </div>
    </WCard>

    <!-- 文案与状态：四态口径 -->
    <WCard
      title="文案与状态 · 四态示例"
      subtitle="空 / 加载 / 错误 / 降级；文案统一来自 src/content，真实状态由数据与响应里的 degraded 标记决定"
    >
      <template #extra>
        <WTag tone="n" plain>{{ activeScreenCopy.title }}</WTag>
      </template>
      <div class="uikit__screen-picker">
        <button
          v-for="item in screenButtons"
          :key="item.key"
          type="button"
          class="uikit__screen-btn"
          :class="{ 'is-active': item.key === activeScreen }"
          @click="activeScreen = item.key"
        >
          {{ item.label }}
        </button>
      </div>
      <div class="uikit__states">
        <div v-for="state in demoStates" :key="state.kind" class="uikit__state" :data-state="state.kind">
          <WTag :tone="state.copy.tone" plain>{{ state.label }}</WTag>
          <WEmpty
            v-if="state.kind !== 'loading'"
            compact
            :icon="state.copy.icon"
            :title="state.copy.title"
            :description="state.copy.description"
          />
          <WLoading v-else center :label="state.copy.description" />
        </div>
      </div>
      <p class="uikit__hint">
        口径：空态给「下一步」；加载只说「正在读什么」；错误不出现内部码与业务数字；降级必须说明「哪些写操作转人工」。
        本屏空态示例：{{ SCREEN_EMPTY_COPY[activeScreen].description }}
      </p>
    </WCard>

    <!-- 文案与状态：Toast 回执 -->
    <WCard title="文案与状态 · Toast 回执与语气" subtitle="成功 / 失败 / 中性说明 / 演示补充四类句式，页面不再自拼后缀">
      <div class="uikit__row">
        <WButton variant="primary" size="sm" @click="fireStateReceipt('success')">成功回执</WButton>
        <WButton variant="danger" size="sm" @click="fireStateReceipt('failure')">失败回执</WButton>
        <WButton size="sm" @click="fireStateReceipt('info')">中性说明</WButton>
        <WButton size="sm" variant="ghost" @click="fireStateReceipt('demo')">演示补充</WButton>
      </div>
      <div class="uikit__tone-table">
        <div v-for="item in toneRules" :key="item.tone" class="uikit__tone-row">
          <code class="uikit__code">{{ item.tone }}</code>
          <span>{{ item.rule }}</span>
        </div>
      </div>
      <p class="uikit__hint">{{ DEMO_BOUNDARY_NOTE }}；不做群内自动回复、不碰钱、不认定责任。</p>
    </WCard>

    <!-- 文案与状态：17 类错误码人话表 -->
    <WCard
      title="文案与状态 · 错误码人话表（17 类）"
      subtitle="错误码来自契约 packages/shared §9（与 OpenAPI 同源），只做人话映射、不新造码；点击「Toast」可看实际回执"
    >
      <WTable :columns="errorColumns" :rows="errorRows" row-key="code" dense>
        <template #code="{ value }">
          <code class="uikit__code">{{ value }}</code>
        </template>
        <template #retryable="{ value }">
          <WTag :tone="value === '是' ? 'g' : 'n'" plain>{{ value }}</WTag>
        </template>
        <template #action="{ row }">
          <WButton size="sm" variant="ghost" @click="fireErrorCode(String(row.code))">Toast</WButton>
        </template>
      </WTable>
    </WCard>

    <div class="uikit__grid">
      <WCard title="Timeline" subtitle="工单 7 节点 / 追溯链路复用">
        <WTimeline :items="timelineItems" />
      </WCard>

      <WCard title="Table" subtitle="列配置 + 具名插槽；无数据自动空态">
        <WTable :columns="columns" :rows="rows" row-key="token">
          <template #token="{ value }">
            <code class="uikit__code">{{ value }}</code>
          </template>
          <template #value="{ value }">
            <span class="num">{{ value }}</span>
          </template>
        </WTable>
      </WCard>
    </div>

    <div class="uikit__grid">
      <WCard title="Empty / Loading" subtitle="页面与卡片内均可复用；文案见上方四态口径">
        <div class="uikit__states">
          <WEmpty compact :icon="stateCopy('empty').icon" :title="stateCopy('empty').title" :description="stateCopy('empty').description" />
          <WEmpty compact :icon="stateCopy('error').icon" :title="stateCopy('error').title" :description="stateCopy('error').description" />
          <WLoading center :label="stateCopy('loading').description" />
        </div>
      </WCard>

      <WCard title="Modal / Drawer / Toast" subtitle="浮层与全局反馈">
        <div class="uikit__row">
          <WButton @click="modalOpen = true">打开 Modal</WButton>
          <WButton @click="drawerOpen = true">打开 Drawer</WButton>
          <WButton variant="primary" @click="toast.success(successReceipt('执行示例动作'))">成功 Toast</WButton>
          <WButton variant="danger" @click="toast.error(failureReceipt('示例动作', '缺少必填项'))">错误 Toast</WButton>
        </div>
        <p class="uikit__hint">
          说明：共享组件只承载 UI 与交互；真实业务动作（导出 / 复制 / 提交）已接到各屏管理端动作弹层，回执统一走 Toast 句式。
        </p>
      </WCard>
    </div>

    <WModal v-model:open="modalOpen" title="示例弹窗">
      <p class="uikit__hint">这是共享 WModal 的最小示例：支持 ESC、点击遮罩关闭，页脚放主 / 次操作。</p>
      <template #footer>
        <WButton size="sm" variant="ghost" @click="modalOpen = false">取消</WButton>
        <WButton size="sm" variant="primary" @click="confirmDemo">确认</WButton>
      </template>
    </WModal>

    <WDrawer v-model:open="drawerOpen" title="示例抽屉" width="380px">
      <p class="uikit__hint">共享 WDrawer 从右侧滑出，用于通知中心、筛选面板等场景。</p>
      <WTimeline :items="timelineItems.slice(0, 2)" />
      <template #footer>
        <WButton size="sm" variant="primary" @click="drawerOpen = false">关闭</WButton>
      </template>
    </WDrawer>
  </div>
</template>

<style scoped>
.uikit__rows {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
}

.uikit__row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--sp-2);
}

.uikit__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--sp-3);
}

.uikit__states {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--sp-2);
  align-items: stretch;
}

.uikit__state {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
  padding: 8px 10px 10px;
  border: 1px solid var(--c-line-2);
  border-radius: var(--rd-md);
  background: var(--c-bg-2);
}

.uikit__state :deep(.w-empty),
.uikit__state :deep(.w-loading) {
  align-self: center;
}

.uikit__screen-picker {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-bottom: var(--sp-3);
}

.uikit__screen-btn {
  height: 24px;
  padding: 0 9px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-sm);
  background: var(--c-card);
  color: var(--c-muted);
  font-size: var(--fs-xs);
  cursor: pointer;
  transition: background-color var(--tr-fast), color var(--tr-fast), border-color var(--tr-fast);
}

.uikit__screen-btn:hover {
  border-color: var(--c-brand);
  color: var(--c-brand);
}

.uikit__screen-btn.is-active {
  border-color: var(--c-brand);
  background: var(--c-brand-soft);
  color: var(--c-brand-deep);
  font-weight: var(--fw-semibold);
}

.uikit__screen-btn:focus-visible {
  outline: none;
  box-shadow: var(--sh-ring);
}

.uikit__tone-table {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-top: var(--sp-3);
}

.uikit__tone-row {
  display: flex;
  align-items: baseline;
  gap: 8px;
  font-size: var(--fs-xs);
  color: var(--c-muted);
  line-height: 1.7;
}

.uikit__hint {
  font-size: var(--fs-xs);
  color: var(--c-muted);
  line-height: 1.7;
}

.uikit__code {
  padding: 1px 5px;
  border-radius: var(--rd-xs);
  background: var(--c-bg-2);
  border: 1px solid var(--c-line-2);
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--c-ink-2);
}

@media (max-width: 1279px) {
  .uikit__grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .uikit__states {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
