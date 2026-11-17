<script setup lang="ts">
import { WIcon, WTag } from '@/components'
import { ACCESS_OPTIONS, VENDOR_OPTIONS, useConnectorWizard } from '../useConnectorWizard'

/**
 * ① 接入方式：接入形态（REST / ESB / 视图+API / 文件·RPA）+ 厂商模板（用友 NC、金蝶 EAS、明源云、自研 ERP、新视窗）。
 * 两项均为必填：未选时「下一步」禁用，并在本步给出行内提示。
 */
const { accessMode, vendor, connectorName, accessOption, vendorOption, selectAccessMode, selectVendor } =
  useConnectorWizard()
</script>

<template>
  <section class="cwz-sec">
    <div class="cwz-grid cwz-grid--2">
      <label class="cwz-field">
        <span>连接器名称<em class="cwz-sec__hint">（选填，默认按厂商生成）</em></span>
        <input
          v-model="connectorName"
          class="cwz-input"
          type="text"
          :placeholder="vendorOption ? `${vendorOption.name} · 云栖苑生产` : '例如：用友 NC Cloud · 云栖苑生产'"
        />
      </label>
      <div class="cwz-field">
        <span>能力契约</span>
        <input class="cwz-input" type="text" value="Canonical API v1.4 · 18 项标准能力" readonly />
      </div>
    </div>
  </section>

  <section class="cwz-sec">
    <div class="cwz-sec__head">
      <span class="cwz-sec__title">接入形态</span>
      <span class="cwz-req">* 必选</span>
      <span class="cwz-sec__hint">决定能力上限与适配周期：接口越开放，可写能力越多</span>
    </div>
    <div class="cwz-grid cwz-grid--2">
      <button
        v-for="item in ACCESS_OPTIONS"
        :key="item.key"
        type="button"
        class="cwz-opt"
        :class="{ 'is-on': accessMode === item.key }"
        :data-testid="`wizard-access-${item.key}`"
        :aria-pressed="accessMode === item.key"
        @click="selectAccessMode(item.key)"
      >
        <span class="cwz-opt__icon"><WIcon :name="item.icon" :size="14" /></span>
        <span class="cwz-opt__main">
          <span class="cwz-opt__label">{{ item.label }}</span>
          <span class="cwz-opt__meta">{{ item.cycle }} · {{ item.realtime }}</span>
          <span class="cwz-opt__note">{{ item.note }}</span>
        </span>
      </button>
    </div>
    <p v-if="!accessMode" class="cwz-warn" data-testid="wizard-access-hint">
      <WIcon name="info" :size="11" />
      请选择一项接入形态，未选不能进入「授权与连通」
    </p>
    <p v-else class="cwz-sec__hint">
      已选：{{ accessOption?.label }} · 适配周期 {{ accessOption?.cycle }} · {{ accessOption?.realtime }}
    </p>
  </section>

  <section class="cwz-sec">
    <div class="cwz-sec__head">
      <span class="cwz-sec__title">厂商模板</span>
      <span class="cwz-req">* 必选</span>
      <span class="cwz-sec__hint">模板预置字段/状态/字典差异，探测与映射按模板初始化</span>
    </div>
    <div class="cwz-grid cwz-grid--5">
      <button
        v-for="item in VENDOR_OPTIONS"
        :key="item.key"
        type="button"
        class="cwz-vendor"
        :class="{ 'is-on': vendor === item.key }"
        :data-testid="`wizard-vendor-${item.key}`"
        :aria-pressed="vendor === item.key"
        @click="selectVendor(item.key)"
      >
        <span class="cwz-vendor__logo" :class="`is-${item.tone}`">{{ item.logo }}</span>
        <span class="cwz-vendor__name">{{ item.name }}</span>
        <span class="cwz-vendor__form">{{ item.form }}</span>
      </button>
    </div>
    <p v-if="!vendor" class="cwz-warn" data-testid="wizard-vendor-hint">
      <WIcon name="info" :size="11" />
      请选择厂商模板，未选不能进入「授权与连通」
    </p>
  </section>

  <div class="cwz-note">
    <WIcon name="shield" :size="13" />
    <span>
      <b>只读优先：</b>先接入查询类能力，人工比对 200 条无误后再逐项开放写能力；写操作需单独授权并标记「AI 创建」。
      <WTag tone="n" plain>演示：不接真实 ERP</WTag>
    </span>
  </div>
</template>
