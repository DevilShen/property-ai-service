<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { onBeforeRouteLeave, useRouter } from 'vue-router'
import { WButton, WCard, WIcon, WModal, WPageHeader, WTag, useToast } from '@/components'
import StepAccess from './steps/StepAccess.vue'
import StepConnect from './steps/StepConnect.vue'
import StepProbe from './steps/StepProbe.vue'
import StepPublish from './steps/StepPublish.vue'
import { WIZARD_STEPS, useConnectorWizard, type WizardStepKey } from './useConnectorWizard'
import './wizard.css'

/**
 * P1-025 新增连接器 4 步向导（独立路由页 /erp/wizard）
 * ① 接入方式 → ② 授权与连通 → ③ 能力探测 → ④ 映射与灰度。
 * 校验：未选接入方式 / 未通过连通 / 未完成探测 / 缺映射灰度信息时「下一步」禁用；
 * 已到达过的步骤可点回；离开 dirty 状态二次确认。
 * 全部为 mock：不接真实 ERP、不保存凭据、草稿不落库。
 */
const router = useRouter()
const toast = useToast()

const {
  step,
  stepValid,
  validityOf,
  canGo,
  goStep,
  next,
  prev,
  footerHint,
  connectorDisplayName,
  dirty,
  submitted,
  submitDraft,
  reset,
  clearTimers,
} = useConnectorWizard()

// 单例状态：每次进入向导都从头开始
reset()

const NEXT_LABELS = ['', '下一步：授权与连通', '下一步：能力探测', '下一步：映射与灰度']

const currentStep = computed(() => WIZARD_STEPS[step.value - 1])

const leaveOpen = ref(false)
const blockedPath = ref('')
let allowLeave = false

function isDone(key: WizardStepKey): boolean {
  return key < step.value && validityOf(key)
}

onBeforeRouteLeave((to) => {
  if (allowLeave || submitted.value || !dirty.value) return true
  blockedPath.value = to.fullPath
  leaveOpen.value = true
  return false
})

function stayHere() {
  leaveOpen.value = false
  blockedPath.value = ''
}

function discardAndLeave() {
  leaveOpen.value = false
  allowLeave = true
  const target = blockedPath.value || '/erp'
  blockedPath.value = ''
  clearTimers()
  void router.push(target)
}

function backToErp() {
  void router.push('/erp')
}

function cancelWizard() {
  void router.push('/erp')
}

function submit() {
  const draftId = submitDraft()
  if (!draftId) return
  toast.success(`连接器草稿已创建：${draftId}（演示）`)
  allowLeave = true
  void router.push('/erp')
}

// 卸载时清掉 mock 延时，避免离开后仍写状态
onBeforeUnmount(() => clearTimers())
</script>

<template>
  <div class="w-page cwz" data-testid="connector-wizard" data-page="erp-wizard">
    <button type="button" class="cwz-back" @click="backToErp">
      <WIcon name="chevron-right" :size="12" />
      返回适配中心
    </button>

    <WPageHeader
      eyebrow="07 · 适配层 · NEW CONNECTOR"
      title="新增连接器 · 4 步向导"
      description="接入方式 → 授权与连通 → 能力探测 → 映射与灰度：每步校验通过才可继续，已到达的步骤可随时点回修改。"
    >
      <template #actions>
        <WTag tone="n" plain>演示环境 · 不接真实 ERP</WTag>
        <WButton size="sm" variant="ghost" icon="close" @click="cancelWizard">取消</WButton>
      </template>
    </WPageHeader>

    <ol class="cwz-steps">
      <li
        v-for="item in WIZARD_STEPS"
        :key="item.key"
        class="cwz-step"
        :class="{ 'is-active': step === item.key, 'is-done': isDone(item.key), 'is-locked': !canGo(item.key) }"
      >
        <button
          type="button"
          class="cwz-step__btn"
          :data-testid="`wizard-step-${item.key}`"
          :data-step-state="step === item.key ? 'active' : isDone(item.key) ? 'done' : canGo(item.key) ? 'todo' : 'locked'"
          :disabled="!canGo(item.key)"
          :aria-current="step === item.key ? 'step' : undefined"
          @click="goStep(item.key)"
        >
          <span class="cwz-step__no">
            <WIcon v-if="isDone(item.key)" name="check" :size="12" />
            <template v-else>{{ item.key }}</template>
          </span>
          <span class="cwz-step__body">
            <span class="cwz-step__label">{{ item.label }}</span>
            <span class="cwz-step__note">{{ item.note }}</span>
          </span>
        </button>
      </li>
    </ol>

    <div class="cwz-progress" aria-hidden="true"><i :style="{ width: `${step * 25}%` }" /></div>

    <WCard :padded="false">
      <template #head>
        <span class="cwz-card__title">{{ currentStep.title }}</span>
        <span class="cwz-card__sub">{{ currentStep.note }}</span>
      </template>
      <template #extra>
        <WTag :tone="stepValid ? 'g' : 'a'" plain>{{ stepValid ? '校验通过' : '待完善' }}</WTag>
      </template>

      <div class="cwz-body">
        <StepAccess v-if="step === 1" />
        <StepConnect v-else-if="step === 2" />
        <StepProbe v-else-if="step === 3" />
        <StepPublish v-else />
      </div>

      <template #footer>
        <div class="cwz-foot">
          <span class="cwz-foot__hint">{{ footerHint }}</span>
          <div class="cwz-foot__actions">
            <WButton size="sm" variant="ghost" data-testid="wizard-prev" :disabled="step === 1" @click="prev">
              上一步
            </WButton>
            <WButton
              v-if="step < 4"
              size="sm"
              variant="primary"
              data-testid="wizard-next"
              :disabled="!stepValid"
              @click="next"
            >
              {{ NEXT_LABELS[step] }}
            </WButton>
            <WButton
              v-else
              size="sm"
              variant="primary"
              icon="check"
              data-testid="wizard-submit"
              :disabled="!stepValid"
              @click="submit"
            >
              创建连接器草稿
            </WButton>
          </div>
        </div>
      </template>
    </WCard>

    <WModal v-model:open="leaveOpen" title="离开向导？" width="440px">
      <div class="cwz-modal">
        <div class="cwz-modal__row">
          <span>当前连接器</span>
          <b>{{ connectorDisplayName }}</b>
        </div>
        <div class="cwz-modal__row">
          <span>已填写进度</span>
          <b>第 {{ step }} 步 · {{ currentStep.label }}</b>
        </div>
        <p class="cwz-modal__note">
          离开后，本次填写的接入方式、连通结果与能力探测会丢失（演示环境不落库、不保存凭据）。
        </p>
      </div>
      <template #footer>
        <WButton size="sm" variant="ghost" @click="stayHere">继续编辑</WButton>
        <WButton size="sm" variant="danger" icon="close" @click="discardAndLeave">放弃并离开</WButton>
      </template>
    </WModal>
  </div>
</template>
