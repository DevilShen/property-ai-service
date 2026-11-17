<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { WButton, WIcon } from '@/components'
import ActionModal from './ActionModal.vue'
import type { BindConfirm, BindMethodKey, BindMethodOption } from './shared'

/**
 * 手机号绑定弹层（手机号 B 策略）
 * ------------------------------------------------------------------
 * 一般咨询免绑定；查账单 / 报修 / 查进度必须绑定。
 * 三种方式任选其一：微信授权 / 手动 + 短信 / 线下核验。
 * 连续 3 次失败按规则直接转人工且不提示失败原因（emit('handoff')）。
 * 演示环境不发起真实微信授权、不发送真实短信，只使用示例数据。
 */
const BUILTIN_METHODS: BindMethodOption[] = [
  { key: 'wechat', title: '微信授权绑定', desc: '一次授权，自动关联房号与手机号；仅用于账户功能', icon: 'user' },
  { key: 'sms', title: '手动 + 短信验证', desc: '输入手机号，收验证码后绑定（演示不发真实短信）', icon: 'chat' },
  { key: 'offline', title: '线下核验', desc: '管家现场核验身份证与房本后开通，适合老年业主', icon: 'shield' },
]
const MAX_FAILS = 3
const PHONE_RE = /^1[3-9]\d{9}$/
const CODE_RE = /^\d{6}$/

const props = withDefaults(
  defineProps<{
    open: boolean
    methods?: BindMethodOption[]
    /** 演示手机号尾号（用于输入框占位，不展示完整号码） */
    demoTail?: string
    /** 底部合规说明；不传则用默认文案 */
    note?: string
  }>(),
  { demoTail: '8821', note: '' },
)

const emit = defineEmits<{
  'update:open': [value: boolean]
  confirm: [payload: BindConfirm]
  /** 连续失败转人工（父级用 Toast / 状态提示承接） */
  handoff: [reason: string]
}>()

/** 绑定方式：宿主不传时用内置三选一 */
const methodList = computed<BindMethodOption[]>(() => props.methods ?? BUILTIN_METHODS)

const method = ref<string>('wechat')
const phone = ref('')
const code = ref('')
const codeSent = ref(false)
const info = ref('')
const fails = ref(0)
const fallback = ref(false)
const error = ref('')

/** 演示数据/宿主传入的 key 归一成契约里的三选一 */
function asMethodKey(value: string): BindMethodKey {
  return value === 'sms' || value === 'offline' ? value : 'wechat'
}

watch(
  () => props.open,
  (open) => {
    if (!open) return
    method.value = asMethodKey(methodList.value[0]?.key ?? 'wechat')
    phone.value = ''
    code.value = ''
    codeSent.value = false
    info.value = ''
    fails.value = 0
    fallback.value = false
    error.value = ''
  },
)

function setOpen(value: boolean): void {
  emit('update:open', value)
}

function chooseMethod(key: string): void {
  if (fallback.value) return
  method.value = asMethodKey(key)
  error.value = ''
  info.value = ''
  code.value = ''
  codeSent.value = false
}

/** 记录一次失败；达到 3 次即转人工，且不再提示失败原因 */
function fail(message: string): void {
  fails.value += 1
  if (fails.value >= MAX_FAILS) {
    fallback.value = true
    error.value = ''
    info.value = ''
    emit('handoff', '手机号绑定连续 3 次失败')
    return
  }
  error.value = `${message}（连续失败 ${fails.value}/${MAX_FAILS}）`
}

function sendCode(): void {
  error.value = ''
  if (!PHONE_RE.test(phone.value.trim())) {
    fail('手机号格式不正确，请输入 11 位手机号')
    return
  }
  codeSent.value = true
  info.value = '验证码已生成（演示码 123456），演示环境不发送真实短信'
}

function onConfirm(): void {
  if (fallback.value) {
    setOpen(false)
    return
  }
  error.value = ''
  if (method.value === 'sms') {
    const value = phone.value.trim()
    if (!PHONE_RE.test(value)) {
      fail('手机号格式不正确，请输入 11 位手机号')
      return
    }
    if (!codeSent.value) {
      fail('请先获取短信验证码')
      return
    }
    if (!CODE_RE.test(code.value.trim())) {
      fail('验证码不正确，请输入 6 位验证码')
      return
    }
    emit('confirm', { method: 'sms', phone: value })
    return
  }
  emit('confirm', { method: asMethodKey(method.value), phone: '' })
}
</script>

<template>
  <ActionModal
    :open="open"
    name="bindphone"
    title="手机号绑定（账户功能前置）"
    width="540px"
    lead="一般咨询免绑定；查账单 / 报修 / 查进度必须绑定。三种方式任选其一，连续 3 次失败转人工且不提示错因。"
    :hint="note || '演示环境不发起真实微信授权、不发送真实短信；仅使用 20 户示例数据。'"
    :confirm-text="fallback ? '关闭' : '确认绑定'"
    :show-cancel="!fallback"
    :error="error"
    @update:open="setOpen"
    @confirm="onConfirm"
  >
    <div class="act-list">
      <button
        v-for="item in methodList"
        :key="item.key"
        type="button"
        class="act-opt"
        :class="{ 'is-on': method === item.key }"
        :aria-pressed="method === item.key"
        :disabled="fallback"
        :data-testid="`bind-method-${item.key}`"
        @click="chooseMethod(item.key)"
      >
        <span class="act-opt__icon"><WIcon :name="item.icon" :size="14" /></span>
        <span class="act-opt__body">
          <b>{{ item.title }}</b>
          <span>{{ item.desc }}</span>
        </span>
      </button>
    </div>

    <template v-if="method === 'sms' && !fallback">
      <div class="act-field">
        <label for="act-bind-phone">手机号</label>
        <input
          id="act-bind-phone"
          v-model="phone"
          class="act-input"
          type="tel"
          inputmode="numeric"
          maxlength="11"
          :placeholder="`例如 138****${demoTail}`"
          data-testid="bind-phone"
          @input="error = ''"
        />
      </div>
      <div class="act-row">
        <WButton size="sm" :disabled="codeSent" data-testid="bind-send-code" @click="sendCode">
          {{ codeSent ? '验证码已生成' : '获取验证码（演示）' }}
        </WButton>
        <input
          v-if="codeSent"
          v-model="code"
          class="act-input"
          type="text"
          inputmode="numeric"
          maxlength="6"
          placeholder="6 位验证码（演示码 123456）"
          data-testid="bind-code"
          @input="error = ''"
        />
      </div>
      <p v-if="info" class="act-sub" data-testid="bind-info">{{ info }}</p>
    </template>

    <p v-if="method === 'wechat' && !fallback" class="act-sub">
      演示环境不发起真实微信授权；确认后按「微信已绑定」处理，不读取微信资料与真实手机号。
    </p>
    <p v-if="method === 'offline' && !fallback" class="act-sub">
      提交后由管家联系您现场核验身份证与房本（演示不预约真实上门），核验通过后开通账户功能。
    </p>

    <div v-if="fallback" class="act-fallback" data-testid="bind-fallback">
      <WIcon name="headset" :size="14" />
      <div>
        <b>已转人工核验</b>
        <span>连续 3 次失败，按规则直接转人工（不提示失败原因）。请等待管家联系，或携带身份证与房本到物业服务中心线下核验。</span>
      </div>
    </div>
  </ActionModal>
</template>
