<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { WButton, WTag, useToast } from '@/components'
import AdminModal from './AdminModal.vue'
import type { KnowledgeDraft } from './types'

/**
 * 新增 / 变更知识弹层（P1-033）
 * ----------------------------------------------------------------
 * 六个必填项：标题 / 分类 / 版本 / 生效期 / 责任人 / 内容（缺一项就不给提交，前端逐项报错）；
 * 提交后 emit('saved', draft) 由调用方把新知识插入列表首行（或按变更覆盖原条目），并 Toast 回执。
 */
const props = withDefaults(
  defineProps<{
    open: boolean
    name?: string
    title?: string
    mode?: 'create' | 'update'
    categories?: string[]
    initial?: Partial<KnowledgeDraft>
    /** 变更模式下的知识编号，用于回执文案 */
    code?: string
  }>(),
  {
    name: 'knowledge-upsert',
    title: '',
    mode: 'create',
    categories: () => ['收费类', '规约制度', '办事流程', '项目事实', '动态通知'],
    initial: () => ({}),
    code: '',
  },
)

const emit = defineEmits<{
  'update:open': [value: boolean]
  saved: [draft: KnowledgeDraft]
  close: []
}>()

const toast = useToast()

const EMPTY: KnowledgeDraft = { title: '', category: '', version: 'v1', period: '', owner: '', content: '' }

const form = ref<KnowledgeDraft>({ ...EMPTY })
const errors = ref<Partial<Record<keyof KnowledgeDraft, string>>>({})

const heading = computed(() => props.title || (props.mode === 'update' ? '变更知识（版本升级）' : '新增知识'))
const submitText = computed(() => (props.mode === 'update' ? '提交变更' : '提交审核'))

function buildInitial(): KnowledgeDraft {
  const initial = props.initial
  return {
    title: initial.title ?? '',
    category: initial.category ?? props.categories[0] ?? '',
    version: initial.version ?? 'v1',
    period: initial.period ?? '',
    owner: initial.owner ?? '',
    content: initial.content ?? '',
  }
}

watch(
  () => props.open,
  (open) => {
    if (open) {
      form.value = buildInitial()
      errors.value = {}
    }
  },
  { immediate: true },
)

function validate(): boolean {
  const next: Partial<Record<keyof KnowledgeDraft, string>> = {}
  if (!form.value.title.trim()) next.title = '请填写知识标题'
  else if (form.value.title.trim().length < 4) next.title = '标题至少 4 个字，便于检索命中'
  if (!form.value.category.trim()) next.category = '请选择知识分类'
  if (!form.value.version.trim()) next.version = '请填写版本号（例如 v1 / v2.1）'
  if (!form.value.period.trim()) next.period = '请填写生效期（例如 2026-12-01 起）'
  if (!form.value.owner.trim()) next.owner = '必须指定责任人，否则过期无人复核'
  if (!form.value.content.trim()) next.content = '请填写知识正文，AI 只按正文作答'
  else if (form.value.content.trim().length < 10) next.content = '正文至少 10 个字，否则无法支撑答案出处'
  errors.value = next
  return Object.keys(next).length === 0
}

function submit() {
  if (!validate()) {
    toast.warning('必填项未完成：标题 / 分类 / 版本 / 生效期 / 责任人 / 内容')
    return
  }
  const draft: KnowledgeDraft = {
    title: form.value.title.trim(),
    category: form.value.category,
    version: form.value.version.trim(),
    period: form.value.period.trim(),
    owner: form.value.owner.trim(),
    content: form.value.content.trim(),
  }
  emit('saved', draft)
  if (props.mode === 'update') {
    toast.success(`知识变更已提交：${props.code || draft.title} → ${draft.version}（生效 ${draft.period}，进入复核队列）`)
  } else {
    toast.success(`知识已提交审核：${draft.title} · ${draft.version}（责任人与生效期已登记，mock）`)
  }
  emit('update:open', false)
}
</script>

<template>
  <AdminModal :open="open" :name="name" :title="heading" width="560px" @update:open="emit('update:open', $event)" @close="emit('close')">
    <div class="adm-form">
      <label class="adm-form__field">
        <span>知识标题 <em>*</em></span>
        <input v-model="form.title" type="text" placeholder="例如：装修押金退还流程与扣款口径" data-testid="admin-upsert-title" />
        <i v-if="errors.title" data-testid="admin-upsert-error-title">{{ errors.title }}</i>
      </label>

      <div class="adm-form__row">
        <label class="adm-form__field">
          <span>分类 <em>*</em></span>
          <select v-model="form.category" data-testid="admin-upsert-category">
            <option v-for="name in categories" :key="name" :value="name">{{ name }}</option>
          </select>
          <i v-if="errors.category" data-testid="admin-upsert-error-category">{{ errors.category }}</i>
        </label>
        <label class="adm-form__field">
          <span>版本 <em>*</em></span>
          <input v-model="form.version" type="text" placeholder="v1" data-testid="admin-upsert-version" />
          <i v-if="errors.version" data-testid="admin-upsert-error-version">{{ errors.version }}</i>
        </label>
      </div>

      <div class="adm-form__row">
        <label class="adm-form__field">
          <span>生效期 <em>*</em></span>
          <input v-model="form.period" type="text" placeholder="2026-12-01 起" data-testid="admin-upsert-period" />
          <i v-if="errors.period" data-testid="admin-upsert-error-period">{{ errors.period }}</i>
        </label>
        <label class="adm-form__field">
          <span>责任人 <em>*</em></span>
          <input v-model="form.owner" type="text" placeholder="张敏" data-testid="admin-upsert-owner" />
          <i v-if="errors.owner" data-testid="admin-upsert-error-owner">{{ errors.owner }}</i>
        </label>
      </div>

      <label class="adm-form__field">
        <span>正文 <em>*</em></span>
        <textarea
          v-model="form.content"
          rows="4"
          placeholder="写清口径、金额、依据与生效范围；AI 只按正文作答并强制带出处"
          data-testid="admin-upsert-content"
        />
        <i v-if="errors.content" data-testid="admin-upsert-error-content">{{ errors.content }}</i>
      </label>
    </div>

    <p class="adm-note">
      <WTag tone="r" plain>卡点</WTag>
      收费标准变更必须先更新知识再发布生效；过期条目自动降级为「以最新公示为准」，不允许「新价已执行、AI 还报旧价」。
    </p>

    <template #footer>
      <WButton size="sm" variant="ghost" @click="emit('update:open', false)">取消</WButton>
      <WButton size="sm" variant="primary" icon="check" data-testid="admin-upsert-submit" @click="submit">{{ submitText }}</WButton>
    </template>
  </AdminModal>
</template>

<style scoped>
.adm-form {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.adm-form__row {
  display: flex;
  gap: 8px;
}

.adm-form__field {
  display: flex;
  flex-direction: column;
  gap: 3px;
  flex: 1;
  min-width: 0;
  font-size: var(--fs-2xs);
  color: var(--c-faint);
}

.adm-form__field em {
  font-style: normal;
  color: var(--c-red);
}

.adm-form__field input,
.adm-form__field select,
.adm-form__field textarea {
  padding: 0 9px;
  height: 30px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-md);
  background: var(--c-card);
  font-family: inherit;
  font-size: var(--fs-sm);
  color: var(--c-ink);
}

.adm-form__field textarea {
  height: auto;
  padding: 7px 9px;
  line-height: var(--lh-base);
  resize: vertical;
}

.adm-form__field input:focus,
.adm-form__field select:focus,
.adm-form__field textarea:focus {
  outline: none;
  border-color: var(--c-brand);
  box-shadow: var(--sh-ring);
}

.adm-form__field i {
  font-style: normal;
  font-size: 9.5px;
  color: var(--c-red);
}

.adm-note {
  display: flex;
  align-items: flex-start;
  gap: 4px;
  flex-wrap: wrap;
  padding: 7px 9px;
  border-radius: var(--rd-sm);
  background: var(--c-amber-soft);
  color: #9a4b05;
  font-size: var(--fs-2xs);
  line-height: var(--lh-base);
}
</style>
