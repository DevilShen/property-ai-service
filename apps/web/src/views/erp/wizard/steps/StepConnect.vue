<script setup lang="ts">
import { WButton, WIcon, WLoading, WTag } from '@/components'
import { useConnectorWizard } from '../useConnectorWizard'

/**
 * ② 授权与连通：网关地址 / 账号 / 密钥（占位，演示不保存凭据）+「测试连通」。
 * 三态：测试中 → 成功（延迟 + 返回版本）→ 失败（错误码 + 可重试）。
 * 未通过连通不能进入下一步（下一步由视图统一禁用）。
 */
const {
  baseUrl,
  account,
  secret,
  simulateFailure,
  connState,
  connResult,
  connError,
  baseUrlError,
  credentialsFilled,
  vendorOption,
  accessOption,
  fillDemoCredentials,
  testConnection,
} = useConnectorWizard()
</script>

<template>
  <section class="cwz-sec">
    <div class="cwz-sec__head">
      <span class="cwz-sec__title">授权与连通</span>
      <span class="cwz-req">* 必填</span>
      <span class="cwz-sec__hint">建议使用只读账号 + 沙箱账套；写能力在灰度页单独开通</span>
    </div>
    <div class="cwz-grid cwz-grid--3">
      <label class="cwz-field">
        <span>网关地址</span>
        <input
          v-model="baseUrl"
          class="cwz-input"
          :class="{ 'is-bad': baseUrlError !== '' }"
          type="text"
          placeholder="https://erp.example.com/openapi/v5"
          data-testid="wizard-base-url"
        />
        <em v-if="baseUrlError" class="cwz-warn">{{ baseUrlError }}</em>
      </label>
      <label class="cwz-field">
        <span>账号</span>
        <input
          v-model="account"
          class="cwz-input"
          type="text"
          placeholder="ai-service@yunqi"
          autocomplete="off"
          data-testid="wizard-account"
        />
      </label>
      <label class="cwz-field">
        <span>密钥</span>
        <input
          v-model="secret"
          class="cwz-input"
          type="password"
          placeholder="••••••••（演示不保存凭据）"
          autocomplete="new-password"
          data-testid="wizard-secret"
        />
      </label>
    </div>

    <div class="cwz-actions">
      <WButton size="sm" variant="ghost" icon="copy" data-testid="wizard-demo-connect" @click="fillDemoCredentials">
        填入演示配置
      </WButton>
      <WButton
        size="sm"
        variant="primary"
        icon="plug"
        :loading="connState === 'testing'"
        :disabled="!credentialsFilled"
        data-testid="wizard-test-connect"
        @click="testConnection"
      >
        {{ connState === 'testing' ? '正在测试连通…' : '测试连通' }}
      </WButton>
      <span class="cwz-actions__hint">
        {{ vendorOption ? vendorOption.name : '待选厂商' }} · {{ accessOption ? accessOption.label : '待选形态' }}
      </span>
      <label class="cwz-toggle">
        <input v-model="simulateFailure" type="checkbox" />
        演示：首次注入 E-4001 失败（可重试成功）
      </label>
    </div>
  </section>

  <section class="cwz-sec" data-testid="wizard-connect-state" :data-state="connState">
    <div v-if="connState === 'idle'" class="cwz-note">
      <WIcon name="lock" :size="13" />
      <span>
        <b>尚未测试连通。</b>真实凭据不落盘、不进日志；演示环境点击「填入演示配置」即可走通，
        未通过测试不能进入能力探测。
      </span>
    </div>

    <div v-else-if="connState === 'testing'" class="cwz-conn">
      <span class="cwz-conn__icon"><WLoading size="sm" /></span>
      <div class="cwz-conn__main">
        <b>正在握手：TLS → OAuth2 → 只读合成请求</b>
        <span>探测限流配额与接口版本，mock 约 900ms；不会发起真实 ERP 调用。</span>
      </div>
    </div>

    <div v-else-if="connState === 'success' && connResult" class="cwz-conn is-ok" data-testid="wizard-connect-ok">
      <span class="cwz-conn__icon"><WIcon name="check" :size="15" /></span>
      <div class="cwz-conn__main">
        <b>连通性测试通过</b>
        <span>{{ connResult.auth }} · {{ connResult.tls }} · 只读探测 3/3 通过</span>
      </div>
      <div class="cwz-conn__metric">
        <b class="num">{{ connResult.latencyMs }}ms</b>
        <span>平均响应</span>
      </div>
    </div>

    <div v-else-if="connState === 'failed' && connError" class="cwz-conn is-bad" data-testid="wizard-connect-fail">
      <span class="cwz-conn__icon"><WIcon name="close" :size="15" /></span>
      <div class="cwz-conn__main">
        <b>连通失败 · <code>{{ connError.code }}</code></b>
        <span>{{ connError.message }}。读请求可重试 1 次；若持续失败请检查白名单与账号权限。</span>
      </div>
      <WButton size="sm" icon="refresh" data-testid="wizard-retry" @click="testConnection">重试</WButton>
    </div>

    <div v-if="connState === 'success' && connResult" class="cwz-conn__kv">
      <div><span>返回版本</span><b>{{ connResult.version }}</b></div>
      <div><span>鉴权方式</span><b>{{ connResult.auth }}</b></div>
      <div><span>传输安全</span><b>{{ connResult.tls }}</b></div>
      <div><span>限流与超时</span><b>{{ connResult.limit }}</b></div>
    </div>
  </section>

  <div class="cwz-note">
    <WIcon name="shield" :size="13" />
    <span>
      <b>凭据边界：</b>密钥由连接器运行时托管在密钥管理里，前端只做占位输入；
      本次演示不保存、不上送、不打印任何凭据。
    </span>
    <WTag tone="n" plain>mock 连通</WTag>
  </div>
</template>
