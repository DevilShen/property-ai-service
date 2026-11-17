<script setup lang="ts">
import { WIcon, WTag } from '@/components'
import { PROJECTS, PROJECT_STATUS_LABELS } from '@/app/projects'
import { GRAY_RATIO_LABELS, useConnectorWizard, type GrayRatio } from '../useConnectorWizard'

/**
 * ④ 映射与灰度：映射模板（厂商标准模板 / 自定义）+ 灰度项目 + 灰度比例（10/30/50/100%）+ 确认摘要。
 * 提交只创建「连接器草稿」（mock，不落库），真实发布需沙箱试跑 200 条 + 双跑比对 + 审批。
 */
const {
  mappingTemplate,
  grayProjects,
  grayRatio,
  vendorName,
  accessName,
  connectorDisplayName,
  connResult,
  baseUrl,
  counts,
  selectMappingTemplate,
  toggleGrayProject,
  selectGrayRatio,
} = useConnectorWizard()

const RATIOS: GrayRatio[] = [10, 30, 50, 100]

function projectStatus(id: string): string {
  const project = PROJECTS.find((item) => item.id === id)
  return project ? PROJECT_STATUS_LABELS[project.status] : ''
}

function projectNames(): string {
  return grayProjects.value
    .map((id) => PROJECTS.find((item) => item.id === id)?.name ?? id)
    .join(' / ')
}
</script>

<template>
  <section class="cwz-sec">
    <div class="cwz-sec__head">
      <span class="cwz-sec__title">映射模板</span>
      <span class="cwz-req">* 必选</span>
      <span class="cwz-sec__hint">字段 / 状态码 / 字典三类映射由模板生成，差异项进变更单</span>
    </div>
    <div class="cwz-grid cwz-grid--2">
      <button
        type="button"
        class="cwz-opt"
        :class="{ 'is-on': mappingTemplate === 'standard' }"
        data-testid="wizard-mapping-standard"
        :aria-pressed="mappingTemplate === 'standard'"
        @click="selectMappingTemplate('standard')"
      >
        <span class="cwz-opt__icon"><WIcon name="mapping" :size="14" /></span>
        <span class="cwz-opt__main">
          <span class="cwz-opt__label">{{ vendorName }} 标准模板</span>
          <span class="cwz-opt__meta">14 字段 · 6 状态码 · 4 字典</span>
          <span class="cwz-opt__note">复用标准物业模板，字段/状态/字典自动带入，差异项标「待确认」</span>
        </span>
      </button>
      <button
        type="button"
        class="cwz-opt"
        :class="{ 'is-on': mappingTemplate === 'custom' }"
        data-testid="wizard-mapping-custom"
        :aria-pressed="mappingTemplate === 'custom'"
        @click="selectMappingTemplate('custom')"
      >
        <span class="cwz-opt__icon"><WIcon name="edit" :size="14" /></span>
        <span class="cwz-opt__main">
          <span class="cwz-opt__label">自定义映射</span>
          <span class="cwz-opt__meta">继承标准模板后逐项调整</span>
          <span class="cwz-opt__note">适合强定制老系统；发布前必须 200 条试跑 + 业务确认</span>
        </span>
      </button>
    </div>
    <p v-if="!mappingTemplate" class="cwz-warn">
      <WIcon name="info" :size="11" />
      请选择映射模板
    </p>
  </section>

  <section class="cwz-sec">
    <div class="cwz-sec__head">
      <span class="cwz-sec__title">灰度项目</span>
      <span class="cwz-req">* 至少 1 个</span>
      <span class="cwz-sec__hint">按 projectId 路由到连接器，灰度期新旧连接器并行、差异率超标自动回滚</span>
    </div>
    <div class="cwz-chips">
      <button
        v-for="project in PROJECTS"
        :key="project.id"
        type="button"
        class="cwz-chip"
        :class="{ 'is-on': grayProjects.includes(project.id) }"
        :data-testid="`wizard-project-${project.id}`"
        :aria-pressed="grayProjects.includes(project.id)"
        @click="toggleGrayProject(project.id)"
      >
        {{ project.name }}
        <em class="cwz-sec__hint">{{ projectStatus(project.id) }}</em>
      </button>
    </div>
    <p v-if="grayProjects.length === 0" class="cwz-warn">
      <WIcon name="info" :size="11" />
      至少选择一个灰度项目
    </p>
  </section>

  <section class="cwz-sec">
    <div class="cwz-sec__head">
      <span class="cwz-sec__title">灰度比例</span>
      <span class="cwz-req">* 必选</span>
      <span class="cwz-sec__hint">按业主标识哈希分桶，固定分桶避免同一业主来回切</span>
    </div>
    <div class="cwz-actions">
      <div class="cwz-seg" role="group" aria-label="灰度比例">
        <button
          v-for="ratio in RATIOS"
          :key="ratio"
          type="button"
          class="cwz-seg__item"
          :class="{ 'is-on': grayRatio === ratio }"
          :data-testid="`wizard-ratio-${ratio}`"
          :aria-pressed="grayRatio === ratio"
          @click="selectGrayRatio(ratio)"
        >
          {{ ratio }}%
        </button>
      </div>
      <span class="cwz-actions__hint">{{ grayRatio ? GRAY_RATIO_LABELS[grayRatio] : '请选择灰度比例' }}</span>
    </div>
    <p v-if="!grayRatio" class="cwz-warn">
      <WIcon name="info" :size="11" />
      请选择灰度比例（只读能力可 10% → 30% → 100%；写能力必须沙箱 + 双跑）
    </p>
  </section>

  <section class="cwz-sec">
    <div class="cwz-sec__head">
      <span class="cwz-sec__title">确认摘要</span>
      <WTag tone="a" plain>提交后为草稿</WTag>
    </div>
    <div class="cwz-kv">
      <div><span>连接器名称</span><b>{{ connectorDisplayName }}</b></div>
      <div><span>接入形态 / 厂商</span><b>{{ accessName }} · {{ vendorName }}</b></div>
      <div><span>网关地址</span><b>{{ baseUrl || '未填写' }}</b></div>
      <div>
        <span>连通结果</span>
        <b v-if="connResult">{{ connResult.latencyMs }}ms · {{ connResult.version }} · {{ connResult.auth }}</b>
        <b v-else>未测试</b>
      </div>
      <div>
        <span>能力覆盖</span>
        <b>{{ counts.supported }} 支持 / {{ counts.degraded }} 降级 / {{ counts.unsupported }} 不支持（18 项）</b>
      </div>
      <div>
        <span>映射模板</span>
        <b>{{ mappingTemplate === 'standard' ? `${vendorName} 标准模板` : mappingTemplate === 'custom' ? '自定义映射' : '未选择' }}</b>
      </div>
      <div><span>灰度项目</span><b>{{ grayProjects.length ? projectNames() : '未选择' }}</b></div>
      <div><span>灰度比例</span><b>{{ grayRatio ? `${grayRatio}%` : '未选择' }}</b></div>
      <div><span>回滚策略</span><b>路由切回上一连接器版本 ≤1 分钟，AI 侧无感</b></div>
    </div>
  </section>

  <div class="cwz-note">
    <WIcon name="clock" :size="13" />
    <span>
      <b>草稿不是发布：</b>提交后仍需沙箱试跑 200 条（通过率 ≥98%）、灰度双跑差异率 &lt;0.5% 与业务签字，
      才会进入正式路由；本次演示只生成草稿编号。
    </span>
    <WTag tone="n" plain>不落库</WTag>
  </div>

  <p class="cwz-sec__hint">
    映射明细（14 字段 / 6 状态码 / 4 字典）在「字段与状态映射」页维护，本向导只选择模板与灰度策略。
  </p>
</template>
