<script setup lang="ts">
import { WButton, WIcon, WLoading, WTag } from '@/components'
import { PROBE_STATE_META, useConnectorWizard } from '../useConnectorWizard'

/**
 * ③ 能力探测：18 项标准能力 mock 探测，三态计数（支持 / 降级 / 不支持）+ 重新探测。
 * 缺能力必须给出可执行降级说明（跳转 ERP / 转企微 / 落人工池），不做"假支持"。
 */
const {
  probeState,
  probeResults,
  probeAt,
  counts,
  fallbackGroups,
  runProbe,
  vendorName,
  accessName,
} = useConnectorWizard()
</script>

<template>
  <section class="cwz-sec">
    <div class="cwz-sec__head">
      <span class="cwz-sec__title">能力探测结果</span>
      <span class="cwz-sec__hint">{{ vendorName }} · {{ accessName }} · 共 18 项标准能力</span>
      <WTag tone="n" plain>{{ probeAt || '未探测' }}</WTag>
    </div>

    <div class="cwz-stats">
      <div class="cwz-stat is-ok">
        <b class="cwz-stat__val num">{{ counts.supported }}</b>
        <span class="cwz-stat__lab">项支持 · 入口正常开放</span>
      </div>
      <div class="cwz-stat is-warn">
        <b class="cwz-stat__val num">{{ counts.degraded }}</b>
        <span class="cwz-stat__lab">项降级 · 入口保留 + 能力受限提示</span>
      </div>
      <div class="cwz-stat is-bad">
        <b class="cwz-stat__val num">{{ counts.unsupported }}</b>
        <span class="cwz-stat__lab">项不支持 · 隐藏入口 + 走替代通道</span>
      </div>
    </div>

    <div class="cwz-actions">
      <WButton size="sm" icon="refresh" :loading="probeState === 'probing'" data-testid="wizard-probe" @click="runProbe">
        重新探测
      </WButton>
      <span class="cwz-actions__hint">探测方式：元数据 → 只读合成请求 → 字典比对（mock 1.2s，探测有效期 30 天）</span>
    </div>

    <div v-if="probeState === 'probing'" class="cwz-loading">
      <WLoading center label="正在逐能力探测 18 项标准能力（mock 1.2s）…" />
    </div>

    <div v-else-if="probeState === 'done'" class="cwz-rows" data-testid="wizard-probe-table">
      <div class="cwz-row cwz-row--head">
        <span>标准能力</span>
        <span>能力编码</span>
        <span>探测结果</span>
        <span>证据 / 降级通道</span>
      </div>
      <div
        v-for="item in probeResults"
        :key="item.code"
        class="cwz-row"
        data-testid="wizard-probe-row"
        :data-state="item.state"
        :data-capability="item.code"
      >
        <span class="cwz-row__cap">
          <b>{{ item.name }}</b>
          <WTag v-if="item.p0" tone="b" plain>P0</WTag>
          <span class="cwz-row__code">{{ item.level }}</span>
        </span>
        <code class="cwz-row__code">{{ item.code }}</code>
        <span class="cwz-row__state">
          <WTag :tone="PROBE_STATE_META[item.state].tone">{{ PROBE_STATE_META[item.state].text }}</WTag>
        </span>
        <span class="cwz-row__note" :class="{ 'is-fallback': item.state !== 'supported' }">{{ item.evidence }}</span>
      </div>
    </div>

    <div v-else class="cwz-note">
      <WIcon name="info" :size="13" />
      <span>进入本步会自动执行一次探测（mock）；也可点击「重新探测」复跑，结果驱动入口显隐与降级话术。</span>
    </div>
  </section>

  <section v-if="probeState === 'done'" class="cwz-sec">
    <div class="cwz-sec__head">
      <span class="cwz-sec__title">缺能力降级说明</span>
      <span class="cwz-sec__hint">降级必须是可执行动作，不允许“界面有、实际不能用”；矩阵是能力事实唯一来源（演示 mock 探测）</span>
    </div>
    <div v-if="fallbackGroups.length" class="cwz-fb">
      <div v-for="group in fallbackGroups" :key="group.key" class="cwz-fb__item">
        <div class="cwz-fb__head">
          <b>{{ group.label }}</b>
          <WTag tone="a" plain>{{ group.items.length }} 项</WTag>
        </div>
        <span class="cwz-fb__note">{{ group.note }}</span>
        <div class="cwz-fb__caps">
          <code v-for="item in group.items" :key="item.code">{{ item.code }}</code>
        </div>
      </div>
    </div>
    <p v-else class="cwz-fb__empty">
      本次探测 18 项能力全部支持，无需降级；上线后仍按每日 06:00 全量复核，与声明不符以探测为准。
    </p>
  </section>
</template>
