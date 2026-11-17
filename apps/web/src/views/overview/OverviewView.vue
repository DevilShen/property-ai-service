<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { WButton, WCard, WIcon, WModal, WPageHeader, WTabs, WTag, useToast } from '@/components'
import { SCREENS } from '@/app/screens'
import StateDemo from '@/content/StateDemo.vue'
import { successReceipt } from '@/content'
import {
  ARCH_LAYERS,
  CAPABILITIES,
  CAP_GROUPS,
  ERP_CONNECTORS,
  HERO_STATS,
  LOOPS,
  METRICS,
  METHODS,
  PRINCIPLES,
  REPORT_MARKDOWN,
  ROADMAP,
  downloadText,
} from './data'
import type { ErpConnector } from './data'

/**
 * 01 方案总览
 * 六层架构 / 18 项标准能力 / 5 套 ERP / 三条闭环 / 90 天路线 / 验收指标。
 * 演示数据来源：原型 + _design/00-项目基线.md，页内标注「演示数据」；文案统一走 `@/content`。
 */
const screen = SCREENS.overview
const toast = useToast()
const router = useRouter()

const capGroup = ref('all')
const activeLayer = ref('adapter')
const metricsOpen = ref(false)
const reportOpen = ref(false)
const erpOpen = ref(false)
const activeErp = ref<ErpConnector | null>(null)

const filteredCaps = computed(() =>
  capGroup.value === 'all' ? CAPABILITIES : CAPABILITIES.filter((cap) => cap.group === capGroup.value),
)

const LOGO_COLORS = ['#3B82C4', '#D9A441', '#8D6FE0', '#3FA88A', '#7C8B96']
const LOGO_LABELS = ['用友', '金蝶', '明源', '自研', '+1']

function toggleLayer(key: string) {
  activeLayer.value = activeLayer.value === key ? '' : key
}

function openErp(erp: ErpConnector) {
  activeErp.value = erp
  erpOpen.value = true
}

function exportReport() {
  downloadText('物业AI客服-汇报要点-演示.md', REPORT_MARKDOWN)
  toast.success(successReceipt('下载汇报要点 Markdown', '纯文本可直接粘贴进汇报材料'))
}

function openLoop(no: number) {
  if (no === 1) {
    void router.push('/progress')
    return
  }
  toast.info(no === 2 ? '投诉闭环在坐席工作台（05 屏）完整呈现，可从左侧导航进入' : '催缴闭环在费用与催缴（06 屏）完整呈现，可从左侧导航进入')
}

function coveredPct(erp: ErpConnector): string {
  return `${erp.coveragePct}%`
}
</script>

<template>
  <div class="w-page" data-page="overview" data-testid="page-root">
    <WPageHeader :eyebrow="screen.eyebrow" :title="screen.title" :description="screen.description">
      <template #actions>
        <WTag tone="a" plain>演示数据</WTag>
        <WTag tone="v">演示内容已就绪</WTag>
        <WButton size="sm" icon="chart" @click="metricsOpen = true">验收指标</WButton>
        <WButton size="sm" variant="primary" icon="download" @click="reportOpen = true">导出汇报要点</WButton>
      </template>
    </WPageHeader>

    <!-- 一句话 + 关键数字 -->
    <section class="ovw-hero" aria-label="方案一句话">
      <div class="ovw-hero__main">
        <div class="ovw-kicker">PROPERTY AI · CANONICAL ADAPTER</div>
        <h2 class="ovw-hero__title">AI 能力层与物业 ERP <em>解耦</em>，一次建设、多系统复用</h2>
        <p class="ovw-hero__desc">
          AI 不替换 ERP，也不直接绑定某一家厂商。所有业务动作先收敛为 18 项「标准能力契约」，再由各 ERP
          的连接器完成翻译——换 ERP 只换连接器，AI 侧零改动。
        </p>
        <div class="ovw-hstats">
          <div v-for="stat in HERO_STATS" :key="stat.label">
            <b>{{ stat.value }}</b>
            <span>{{ stat.label }}</span>
          </div>
        </div>
      </div>
      <div class="ovw-mock" aria-hidden="true">
        <div class="ovw-mock__row"><span>AI 侧</span><b>1 套能力层</b></div>
        <div class="ovw-mock__line" />
        <div class="ovw-mock__cap"><span>标准能力契约</span><b>CANONICAL v1.4</b></div>
        <div class="ovw-mock__line" />
        <div class="ovw-mock__row"><span>ERP 连接器</span><b>5 套</b></div>
        <div class="ovw-mock__logos">
          <i v-for="(label, index) in LOGO_LABELS" :key="label" :style="{ background: LOGO_COLORS[index] }">{{ label }}</i>
        </div>
      </div>
    </section>

    <!-- 六层架构 -->
    <WCard title="六层架构" subtitle="触点 → 交互 → AI 能力 → 适配解耦 → 数据与知识 → ERP 业务系统（点击任一层看职责）">
      <template #extra>
        <WTag tone="g" plain>解耦核心：第 4 层</WTag>
      </template>
      <div class="ovw-arch">
        <template v-for="(layer, index) in ARCH_LAYERS" :key="layer.key">
          <button type="button" class="ovw-layer" :class="[{ 'is-on': activeLayer === layer.key }, `is-${layer.tone}`]" @click="toggleLayer(layer.key)">
            <span class="ovw-layer__label">
              <b>{{ layer.label }}</b>
              <span>{{ layer.caption }}</span>
            </span>
            <span class="ovw-cells">
              <span v-for="cell in layer.cells" :key="cell.label" class="ovw-cell" :class="`is-${layer.tone}`">
                <WIcon :name="cell.icon" :size="12" />
                {{ cell.label }}
              </span>
            </span>
          </button>
          <div v-if="activeLayer === layer.key" class="ovw-layer-detail">{{ layer.detail }}</div>
          <div v-if="index < ARCH_LAYERS.length - 1" class="ovw-arrow"><WIcon name="chevron-down" :size="13" /></div>
        </template>
      </div>
      <div class="cop-note" style="margin-top: 4px">
        <WIcon name="shield" :size="13" />
        <span>
          ERP 是唯一数据源：房档、账单、工单、投诉、通知的全部业务逻辑仍在 ERP；AI 侧不存业务主数据（业务数据落库 0）。
        </span>
      </div>
    </WCard>

    <!-- 18 项能力 -->
    <WCard title="18 项标准能力（Canonical API v1.4）" subtitle="AI 侧只依赖能力编码，不认厂商接口；权限级别 L1 只能说 / L2 可以做 / L3 绝不承诺">
      <WTabs v-model="capGroup" :tabs="CAP_GROUPS" size="sm" />
      <div class="ovw-caps" style="margin-top: 10px">
        <div v-for="cap in filteredCaps" :key="cap.code" class="ovw-cap">
          <div class="ovw-cap__head">
            <b>{{ cap.name }}</b>
            <WTag :tone="cap.level === 'L1' ? 'b' : 'g'" plain>{{ cap.level }}</WTag>
          </div>
          <code class="ovw-cap__code">{{ cap.code }}</code>
        </div>
      </div>
      <div class="cop-note" style="margin-top: 8px">
        <WIcon name="info" :size="13" />
        <span>L1 只读能力 6 项、L2 可办理 12 项；能力矩阵由探测生成，缺能力自动降级并隐藏入口，不做“假支持”。</span>
      </div>
    </WCard>

    <!-- 5 套 ERP -->
    <WCard title="5 套 ERP 连接器" subtitle="覆盖数来自能力探测（演示）；点击卡片查看降级项">
      <template #extra>
        <WTag tone="b" plain>P0 八项能力全部可用</WTag>
      </template>
      <div class="ovw-erps">
        <button v-for="erp in ERP_CONNECTORS" :key="erp.key" type="button" class="ovw-erp" @click="openErp(erp)">
          <div class="ovw-erp__head">
            <b>{{ erp.name }}</b>
            <WTag :tone="erp.tone" plain>{{ erp.state }}</WTag>
          </div>
          <div class="ovw-erp__access">{{ erp.access }}</div>
          <div class="ovw-erp__coverage">
            <span>能力覆盖</span>
            <b>{{ erp.coverage }}</b>
          </div>
          <div class="ovw-erp__bar">
            <i :class="{ 'is-warn': erp.coveragePct < 95, 'is-off': erp.coveragePct < 60 }" :style="{ width: coveredPct(erp) }" />
          </div>
        </button>
      </div>
    </WCard>

    <!-- 三条闭环 -->
    <WCard title="三条闭环" subtitle="报修 / 投诉 / 催缴：AI 做结构化与触达，人做上门、谈判与认定">
      <div class="ovw-loops">
        <article v-for="loop in LOOPS" :key="loop.no" class="ovw-loop">
          <div class="ovw-loop__head">
            <span class="ovw-loop__no">{{ loop.no }}</span>
            {{ loop.title }}
          </div>
          <p class="ovw-loop__desc">{{ loop.desc }}</p>
          <div class="ovw-chain">
            <template v-for="(node, index) in loop.chain" :key="node">
              <span>{{ node }}</span>
              <i v-if="index < loop.chain.length - 1">→</i>
            </template>
          </div>
          <div class="ovw-loop__split">
            <div><b>AI 做</b>{{ loop.ai }}</div>
            <div><b>人做</b>{{ loop.human }}</div>
          </div>
          <div class="cop-actions">
            <WButton size="sm" variant="ghost" icon="arrow-right" @click="openLoop(loop.no)">查看演示</WButton>
          </div>
        </article>
      </div>
    </WCard>

    <!-- 三种接入方式 -->
    <WCard title="三种接入方式" subtitle="不是每家 ERP 都有好用的开放接口：先能接、再逐步放开能力">
      <div class="ovw-methods">
        <div v-for="method in METHODS" :key="method.title" class="ovw-method">
          <span class="ovw-method__icon"><WIcon :name="method.icon" :size="16" /></span>
          <div>
            <h4>
              {{ method.title }}
              <WTag :tone="method.tone">{{ method.tag }}</WTag>
            </h4>
            <p>{{ method.desc }}</p>
            <div class="ovw-method__meta">{{ method.meta }}</div>
          </div>
        </div>
      </div>
    </WCard>

    <!-- 90 天路线 -->
    <WCard title="90 天路线（P0—P3）+ 复制期" subtitle="先内部 Copilot、再对客灰度、最后扩面；每一步都有可验收的出口">
      <div class="ovw-roadmap">
        <div v-for="step in ROADMAP" :key="step.phase" class="ovw-step">
          <span>{{ step.phase }}</span>
          <b>{{ step.title }}</b>
          <p>{{ step.desc }}</p>
        </div>
      </div>
    </WCard>

    <!-- 关键指标 -->
    <WCard title="关键指标（验收口径）" subtitle="口径参照 _design/00-项目基线 §7；演示项目云栖苑 2,000 户">
      <template #extra>
        <WTag tone="a" plain>目标值 · 演示数据</WTag>
      </template>
      <div class="ovw-metrics">
        <div v-for="metric in METRICS" :key="metric.name" class="ovw-metric">
          <div>
            <span>{{ metric.name }}</span>
            <small>{{ metric.note }}</small>
          </div>
          <b>{{ metric.target }}</b>
        </div>
      </div>
    </WCard>

    <!-- 原则 -->
    <WCard title="设计原则" subtitle="评审时先讲边界，再讲能力">
      <div class="ovw-principles">
        <span v-for="item in PRINCIPLES" :key="item.label" class="ovw-principle">
          <b>{{ item.label }}</b>
          {{ item.text }}
        </span>
      </div>
    </WCard>

    <WModal v-model:open="metricsOpen" title="验收指标与口径" width="640px">
      <p class="cop-dim">验收口径来自 _design/00-项目基线 §7（演示数据，不编造真实业务值）。</p>
      <div class="ovw-metrics" style="margin-top: 8px">
        <div v-for="metric in METRICS" :key="metric.name" class="ovw-metric">
          <div>
            <span>{{ metric.name }}</span>
            <small>{{ metric.note }}</small>
          </div>
          <b>{{ metric.target }}</b>
        </div>
      </div>
      <template #footer>
        <WButton size="sm" @click="metricsOpen = false">关闭</WButton>
      </template>
    </WModal>

    <WModal v-model:open="reportOpen" title="导出汇报要点" width="700px">
      <p class="cop-dim">内容为纯文本 Markdown，可直接粘贴进汇报材料（演示数据）。</p>
      <pre class="ovw-report">{{ REPORT_MARKDOWN }}</pre>
      <template #footer>
        <WButton size="sm" variant="ghost" @click="reportOpen = false">关闭</WButton>
        <WButton size="sm" variant="primary" icon="download" @click="exportReport">下载 Markdown</WButton>
      </template>
    </WModal>

    <WModal v-model:open="erpOpen" title="连接器能力与降级项" width="560px">
      <div v-if="activeErp" class="ovw-erp-detail">
        <div class="cop-kv"><span>ERP</span><b>{{ activeErp.name }}</b></div>
        <div class="cop-kv"><span>接入方式</span><b>{{ activeErp.access }}</b></div>
        <div class="cop-kv"><span>能力覆盖</span><b>{{ activeErp.coverage }}</b></div>
        <div class="cop-kv"><span>状态</span><b>{{ activeErp.state }}</b></div>
        <div class="cop-divider" />
        <div v-for="item in activeErp.degraded" :key="item" class="ovw-erp-detail__row">
          <WIcon name="shield" :size="13" />
          <span>{{ item }}</span>
        </div>
        <p class="cop-dim">降级由能力探测自动生成；缺能力的入口会被隐藏，不做“界面有、实际不能用”的假支持。</p>
      </div>
      <template #footer>
        <WButton size="sm" @click="erpOpen = false">关闭</WButton>
      </template>
    </WModal>

    <StateDemo screen="overview" />
  </div>
</template>

<style src="./overview.css"></style>
