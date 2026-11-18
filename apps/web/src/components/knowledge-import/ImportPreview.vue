<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { WButton, WCard, WTag } from '@/components'
import type {
  KnowledgeCategory,
  KnowledgeCommitResult,
  KnowledgeImportCommitRequest,
  KnowledgeImportCommitStatus,
  KnowledgeImportDuplicate,
  KnowledgeImportJob,
  KnowledgeImportPreviewChunk,
} from '@/api'
import { KNOWLEDGE_CATEGORY_LABELS } from '@/api'
import { KNOWLEDGE_STATUS_LABELS } from '@/views/knowledge/shared'
import {
  IMPORT_FILE_TYPE_LABELS,
  IMPORT_SOURCE_LABELS,
  IMPORT_STATUS_LABELS,
  IMPORT_STATUS_TONES,
  fileExtOf,
  formatBytes,
  isImportInFlight,
  warningLabel,
} from './import-copy'

/**
 * 导入预览与提交（task-87；`GET /imports/{jobId}` 预览 + `POST /imports/{jobId}/commit`）
 * ===========================================================================
 * 预览页职责（**不伪造切分/元数据**）：
 * - 切分块列表：seq / sectionPath / charCount / 脱敏正文（全部来自服务端 `preview.chunks[]`），
 *   默认全选；勾选部分块时 commit 只传 `chunks: [seq...]`（省略 = 全部）；
 * - 元数据编辑：标题/分类/责任人/生效期（留空 = 沿用创建时/服务端缺省，不编造值）；
 * - PII 提示：`piiMaskedCount` > 0 时明确「已自动脱敏 N 处」；0 时如实标注未命中（服务端检测口径）；
 * - 重复知识：`duplicateOf[]` 逐条给「打开原文」入口（跳知识运营详情），不自动合并/删除；
 * - 警告：`warnings[]` 服务端人话，**不阻断**；
 * - 提交：默认「提交待审」（status=pending_review，需 it/admin 审核后才生效/索引）；
 *   it/admin 额外可「直接生效（直审）」（status=approved，服务端强制 + 即时索引）。
 */
const props = defineProps<{
  job: KnowledgeImportJob
  /** it/admin：可直审（status=approved）；其它角色只能提交待审（服务端 403 兜底） */
  directApprover: boolean
  /** 粘贴表单里选择的审核状态（预览页「提交后状态」的初始值；非 it/admin 恒 pending_review） */
  initialStatus?: 'pending_review' | 'approved'
  submitting: boolean
  /** 提交中/索引中状态说明（如「正在索引…」） */
  submittingLabel?: string
  error: string
  commitResult: KnowledgeCommitResult | null
}>()

const emit = defineEmits<{
  submit: [payload: KnowledgeImportCommitRequest]
  cancel: []
  'open-knowledge': [knowledgeId: string]
  done: []
}>()

/** 切分块勾选（seq 来自服务端，稳定可提交） */
const selectedSeqs = ref<number[]>([])
const expandedSeqs = ref<number[]>([])
const formError = ref('')
const title = ref('')
const category = ref<KnowledgeCategory | ''>('')
const owner = ref('')
const effectiveFrom = ref('')
const effectiveTo = ref('')
/** 提交目标状态：默认待审；it/admin 可切换直审 */
const targetStatus = ref<Exclude<KnowledgeImportCommitStatus, 'draft'>>('pending_review')

const chunks = computed<KnowledgeImportPreviewChunk[]>(() => props.job.preview?.chunks ?? [])
const allSelected = computed(() => chunks.value.length > 0 && selectedSeqs.value.length === chunks.value.length)
const selectedCount = computed(() => selectedSeqs.value.length)
const duplicates = computed<KnowledgeImportDuplicate[]>(() => props.job.duplicateOf ?? [])
const warnings = computed(() => props.job.warnings ?? [])
const piiMasked = computed(() => props.job.piiMaskedCount ?? 0)
const fileTypeText = computed(() => {
  const type = props.job.fileType
  if (type) return IMPORT_FILE_TYPE_LABELS[type] ?? type
  const ext = props.job.fileName ? fileExtOf(props.job.fileName) : ''
  return ext ? ext.toUpperCase() : '—'
})
const isPreviewReady = computed(() => props.job.status === 'preview_ready')
const busy = computed(() => props.submitting || isImportInFlight(props.job.status))

const categoryOptions = computed(() =>
  (Object.keys(KNOWLEDGE_CATEGORY_LABELS) as KnowledgeCategory[]).map((value) => ({
    value,
    label: KNOWLEDGE_CATEGORY_LABELS[value],
  })),
)

function resetFromJob(): void {
  selectedSeqs.value = chunks.value.map((chunk) => chunk.seq)
  expandedSeqs.value = []
  title.value = props.job.suggestedTitle || props.job.title || ''
  category.value = ''
  owner.value = ''
  effectiveFrom.value = ''
  effectiveTo.value = ''
  targetStatus.value =
    props.directApprover && props.initialStatus === 'approved' ? 'approved' : 'pending_review'
  formError.value = ''
}

watch(
  () => props.job.jobId,
  () => resetFromJob(),
  { immediate: true },
)
// 预览块可能在轮询中补全（in-flight → preview_ready）：块集合变化时同步默认全选
watch(
  () => chunks.value.map((chunk) => chunk.seq).join(','),
  () => {
    if (selectedSeqs.value.length === 0) selectedSeqs.value = chunks.value.map((chunk) => chunk.seq)
  },
)

function isSelected(seq: number): boolean {
  return selectedSeqs.value.includes(seq)
}

function toggleChunk(seq: number): void {
  selectedSeqs.value = isSelected(seq)
    ? selectedSeqs.value.filter((item) => item !== seq)
    : [...selectedSeqs.value, seq].sort((a, b) => a - b)
}

function selectAll(): void {
  selectedSeqs.value = chunks.value.map((chunk) => chunk.seq)
}

function clearSelection(): void {
  selectedSeqs.value = []
}

function isExpanded(seq: number): boolean {
  return expandedSeqs.value.includes(seq)
}

function toggleExpand(seq: number): void {
  expandedSeqs.value = isExpanded(seq)
    ? expandedSeqs.value.filter((item) => item !== seq)
    : [...expandedSeqs.value, seq]
}

/** 提交（默认待审；it/admin 可直审）。前端只做必填/长度预校验，真实边界在服务端。 */
function submit(): void {
  formError.value = ''
  if (!isPreviewReady.value) {
    formError.value = `任务当前状态为「${IMPORT_STATUS_LABELS[props.job.status] ?? props.job.status}」，仅 preview_ready 可提交`
    return
  }
  if (selectedSeqs.value.length === 0) {
    formError.value = '请至少勾选 1 个切分块（不勾选无法生成知识；服务端会 400 E-1001）'
    return
  }
  const trimmedTitle = title.value.trim()
  if (trimmedTitle.length > 200) {
    formError.value = `标题过长（${trimmedTitle.length} 字符）：上限 200 字符`
    return
  }
  if (effectiveFrom.value && effectiveTo.value && effectiveFrom.value > effectiveTo.value) {
    formError.value = '生效日不能晚于失效日（服务端会返回 400 E-1001）'
    return
  }
  if (targetStatus.value === 'approved' && !props.directApprover) {
    formError.value = '直审需 it/admin（knowledge.review）；当前角色只能提交「待审核」'
    return
  }
  const payload: KnowledgeImportCommitRequest = {
    status: targetStatus.value,
    chunks: allSelected.value ? undefined : [...selectedSeqs.value].sort((a, b) => a - b),
  }
  if (trimmedTitle) payload.title = trimmedTitle
  if (category.value) payload.category = category.value
  if (owner.value.trim()) payload.owner = owner.value.trim()
  if (effectiveFrom.value) payload.effectiveFrom = effectiveFrom.value
  if (effectiveTo.value) payload.effectiveTo = effectiveTo.value
  emit('submit', payload)
}

function openDuplicate(duplicate: KnowledgeImportDuplicate): void {
  emit('open-knowledge', duplicate.knowledgeId)
}

function formatPercent(value: number): string {
  if (!Number.isFinite(value)) return '—'
  return `${Math.round(value * 1000) / 10}%`
}
</script>

<template>
  <WCard
    title="预览与提交"
    :subtitle="`${job.jobId} · ${IMPORT_SOURCE_LABELS[job.sourceType] ?? job.sourceType} · ${fileTypeText}`"
    data-testid="kimp-preview"
    :data-job-id="job.jobId"
    :data-status="job.status"
  >
    <template #extra>
      <WTag :tone="IMPORT_STATUS_TONES[job.status]" data-testid="kimp-preview-status">
        {{ IMPORT_STATUS_LABELS[job.status] ?? job.status }}
      </WTag>
      <WTag tone="n" plain>切分 {{ chunks.length }} 块 · 已选 {{ selectedCount }}</WTag>
      <WTag v-if="piiMasked > 0" tone="a" plain data-testid="kimp-preview-pii">已脱敏 {{ piiMasked }} 处</WTag>
    </template>

    <p v-if="isImportInFlight(job.status)" class="kimp-note" data-testid="kimp-preview-inflight">
      <WTag tone="b" plain>解析/索引中</WTag>任务仍在处理（每 2s 轮询 <code>GET /api/v1/knowledge/imports/{jobId}</code>）：
      完成后自动展示切分块与提交入口。
    </p>

    <!-- ============ 预览元信息（全部来自服务端） ============ -->
    <div class="kimp-preview__meta" data-testid="kimp-preview-meta">
      <span><em>任务</em><b class="num">{{ job.jobId }}</b></span>
      <span><em>项目</em><b class="num">{{ job.projectId }}</b></span>
      <span><em>文件类型</em><b>{{ fileTypeText }}</b></span>
      <span><em>大小</em><b class="num">{{ formatBytes(job.sizeBytes) }}</b></span>
      <span><em>字符数</em><b class="num">{{ job.charCount ?? '—' }}</b></span>
      <span><em>切分块</em><b class="num">{{ job.preview?.totalChunks ?? 0 }}</b></span>
      <span><em>创建人</em><b>{{ job.createdBy }}</b></span>
      <span><em>校验和</em><b class="num">{{ (job.checksum ?? '').slice(0, 12) || '—' }}</b></span>
    </div>

    <!-- ============ PII 提示（服务端脱敏计数） ============ -->
    <p class="kimp-note" :class="{ 'kimp-note--warn': piiMasked > 0 }" data-testid="kimp-preview-pii-note">
      <WTag :tone="piiMasked > 0 ? 'a' : 'g'" plain>{{ piiMasked > 0 ? 'PII 已脱敏' : 'PII 未命中' }}</WTag>
      <template v-if="piiMasked > 0">
        服务端识别并脱敏 {{ piiMasked }} 处隐私信息（手机号/身份证等）：预览与入库文本均为脱敏后内容，原文不做自动清理。
      </template>
      <template v-else>
        服务端未命中手机号/身份证等隐私模式（0 处）；入库前仍以服务端脱敏结果为准。
      </template>
    </p>

    <!-- ============ 重复知识提示 ============ -->
    <div v-if="duplicates.length" class="kimp-preview__dups" data-testid="kimp-preview-duplicates">
      <b class="kimp-preview__block-title">疑似重复知识（SHA-256 归一化 + 相似度提示 · 不自动合并）</b>
      <div
        v-for="duplicate in duplicates"
        :key="duplicate.knowledgeId"
        class="kimp-preview__dup"
        :data-testid="`kimp-preview-duplicate-${duplicate.knowledgeId}`"
      >
        <WTag tone="a" plain>相似 {{ formatPercent(duplicate.similarity) }}</WTag>
        <span><b>{{ duplicate.title }}</b> · <code>{{ duplicate.knowledgeId }}</code></span>
        <WButton size="sm" variant="ghost" :data-testid="`kimp-preview-duplicate-open-${duplicate.knowledgeId}`" @click="openDuplicate(duplicate)">
          打开原文
        </WButton>
      </div>
    </div>

    <!-- ============ 警告（不阻断） ============ -->
    <div v-if="warnings.length" class="kimp-preview__warns" data-testid="kimp-preview-warnings">
      <b class="kimp-preview__block-title">解析警告（提示不阻断，可继续提交）</b>
      <ul>
        <li v-for="(warning, index) in warnings" :key="`${warning.code}-${index}`" :data-testid="`kimp-preview-warning-${index}`">
          <WTag tone="a" plain>{{ warningLabel(warning) }}</WTag>
          <span>{{ warning.message }}</span>
        </li>
      </ul>
    </div>

    <!-- ============ 切分块 ============ -->
    <div class="kimp-preview__chunks-head">
      <b class="kimp-preview__block-title">切分块（勾选要入库的块；seq 由服务端稳定给出）</b>
      <span class="kimp-preview__chunk-acts">
        <WButton size="sm" variant="ghost" data-testid="kimp-preview-select-all" @click="selectAll">全选</WButton>
        <WButton size="sm" variant="ghost" data-testid="kimp-preview-clear-all" @click="clearSelection">清空</WButton>
      </span>
    </div>

    <p v-if="chunks.length === 0" class="kimp-note" data-testid="kimp-preview-empty">
      <WTag tone="a" plain>没有切分块</WTag>
      服务端未返回预览块（可能仍在解析或解析失败）：请刷新任务查看状态；解析失败原因在任务详情里。
    </p>
    <ul v-else class="kimp-preview__chunks" data-testid="kimp-preview-chunks">
      <li
        v-for="chunk in chunks"
        :key="chunk.seq"
        class="kimp-chunk"
        :class="{ 'is-off': !isSelected(chunk.seq) }"
        :data-testid="`kimp-preview-chunk-${chunk.seq}`"
      >
        <label class="kimp-chunk__check">
          <input
            type="checkbox"
            :checked="isSelected(chunk.seq)"
            :data-testid="`kimp-preview-chunk-check-${chunk.seq}`"
            @change="toggleChunk(chunk.seq)"
          />
          <span class="num">#{{ chunk.seq }}</span>
        </label>
        <div class="kimp-chunk__body">
          <div class="kimp-chunk__head">
            <b class="kimp-chunk__path" :data-testid="`kimp-preview-chunk-path-${chunk.seq}`">
              {{ chunk.sectionPath || '（无标题路径）' }}
            </b>
            <WTag tone="n" plain>{{ chunk.charCount }} 字符</WTag>
            <WButton size="sm" variant="ghost" :data-testid="`kimp-preview-chunk-expand-${chunk.seq}`" @click="toggleExpand(chunk.seq)">
              {{ isExpanded(chunk.seq) ? '收起' : '全文' }}
            </WButton>
          </div>
          <p class="kimp-chunk__text" :class="{ 'is-clamped': !isExpanded(chunk.seq) }">
            {{ chunk.text }}
          </p>
        </div>
      </li>
    </ul>

    <!-- ============ 元数据编辑 ============ -->
    <div class="kimp-preview__form" data-testid="kimp-preview-form">
      <b class="kimp-preview__block-title">元数据（留空 = 沿用创建时/服务端缺省；不编造值）</b>
      <div class="kimp-form-grid">
        <label class="kimp-field is-block">
          <span>标题（≤200 字符）</span>
          <input v-model="title" data-testid="kimp-preview-title" placeholder="入库知识标题" />
        </label>
        <label class="kimp-field">
          <span>分类</span>
          <select v-model="category" data-testid="kimp-preview-category">
            <option value="">沿用创建时分类</option>
            <option v-for="option in categoryOptions" :key="option.value" :value="option.value">
              {{ option.label }}
            </option>
          </select>
        </label>
        <label class="kimp-field">
          <span>责任人</span>
          <input v-model="owner" data-testid="kimp-preview-owner" placeholder="沿用创建时责任人" />
        </label>
        <label class="kimp-field">
          <span>生效日</span>
          <input v-model="effectiveFrom" type="date" data-testid="kimp-preview-from" />
        </label>
        <label class="kimp-field">
          <span>失效日（可空 = 长期有效）</span>
          <input v-model="effectiveTo" type="date" data-testid="kimp-preview-to" />
        </label>
        <label v-if="directApprover" class="kimp-field">
          <span>提交后状态</span>
          <select v-model="targetStatus" data-testid="kimp-preview-status-select">
            <option value="pending_review">待审核（默认，需 it/admin 审核）</option>
            <option value="approved">直接生效（直审，立即索引）</option>
          </select>
        </label>
      </div>
      <p class="kimp-hint" data-testid="kimp-preview-status-note">
        <template v-if="directApprover">
          当前角色可直审：<code>approved</code> 会立即发布并触发索引（写审计 <code>knowledge.import.commit</code> +
          <code>knowledge.index</code>）；<code>pending_review</code> 需走既有审核接口。
        </template>
        <template v-else>
          默认提交为 <code>pending_review</code>（待审，不直接生效）：需 it/admin 在知识运营中心审核通过后才发布/索引。
          直审能力仅 it/admin（服务端 403 <code>E-2001</code> 兜底）。
        </template>
      </p>
    </div>

    <p v-if="formError" class="kimp-form-error" data-testid="kimp-preview-form-error">{{ formError }}</p>
    <p v-if="error" class="kimp-form-error" data-testid="kimp-preview-error">{{ error }}</p>

    <!-- ============ 提交结果（服务端 items[]） ============ -->
    <div v-if="commitResult" class="kimp-preview__result" data-testid="kimp-commit-result">
      <b class="kimp-preview__block-title">提交结果（服务端 KnowledgeCommitResult.items[]）</b>
      <ul>
        <li v-for="item in commitResult.items" :key="item.knowledgeId" :data-testid="`kimp-commit-item-${item.knowledgeId}`">
          <WTag tone="g" plain>{{ item.knowledgeId }}</WTag>
          <span class="num">{{ item.version }}</span>
          <WTag :tone="item.status === 'published' ? 'g' : item.status === 'pending_review' ? 'a' : 'n'">
            {{ KNOWLEDGE_STATUS_LABELS[item.status] ?? item.status }}
          </WTag>
        </li>
      </ul>
      <p class="kimp-hint">
        <template v-if="job.status === 'completed' && targetStatus === 'pending_review'">
          已生成待审知识：请 it/admin 在知识运营中心用 <code>POST /knowledge/&#123;id&#125;/review</code>
          （changeId = knowledgeId）审核；通过后才发布并纳入检索。
        </template>
        <template v-else>
          直审已完成：知识状态 published，服务端已调用编排层索引（<code>knowledge.index</code> 审计），可立即被
          <code>GET /knowledge/search</code> 检索到。
        </template>
      </p>
    </div>

    <template #footer>
      <div class="kimp-preview__actions">
        <span class="dim">
          提交 = 写操作（幂等键按「任务 + 选择块 + 状态」复用）；服务端仅 <code>preview_ready</code> 可提交。
        </span>
        <span class="kimp-preview__btns">
          <WButton size="sm" variant="ghost" data-testid="kimp-preview-back" :disabled="busy" @click="emit('done')">
            返回任务列表
          </WButton>
          <WButton
            size="sm"
            variant="danger"
            data-testid="kimp-preview-cancel"
            :disabled="busy || !isPreviewReady"
            @click="emit('cancel')"
          >
            取消任务
          </WButton>
          <WButton
            v-if="!directApprover"
            size="sm"
            variant="primary"
            :loading="submitting && targetStatus === 'pending_review'"
            :disabled="busy || !isPreviewReady || commitResult !== null"
            data-testid="kimp-preview-submit-pending"
            @click="
              () => {
                targetStatus = 'pending_review'
                submit()
              }
            "
          >
            提交待审（{{ selectedCount }} 块）
          </WButton>
          <template v-else>
            <WButton
              size="sm"
              :loading="submitting && targetStatus === 'pending_review'"
              :disabled="busy || !isPreviewReady || commitResult !== null"
              data-testid="kimp-preview-submit-pending"
              @click="
                () => {
                  targetStatus = 'pending_review'
                  submit()
                }
              "
            >
              提交待审
            </WButton>
            <WButton
              size="sm"
              variant="primary"
              :loading="submitting && targetStatus === 'approved'"
              :disabled="busy || !isPreviewReady || commitResult !== null"
              data-testid="kimp-preview-submit-approved"
              @click="
                () => {
                  targetStatus = 'approved'
                  submit()
                }
              "
            >
              直接生效（直审，it/admin）
            </WButton>
          </template>
        </span>
      </div>
      <p v-if="submittingLabel" class="kimp-hint" data-testid="kimp-preview-submitting">{{ submittingLabel }}</p>
    </template>
  </WCard>
</template>

<style scoped>
.kimp-preview__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 16px;
  margin-bottom: 9px;
  padding: 8px 10px;
  border: 1px solid var(--c-line-2);
  border-radius: var(--rd-md);
  background: var(--c-bg-2);
}

.kimp-preview__meta span {
  display: inline-flex;
  align-items: baseline;
  gap: 5px;
  font-size: var(--fs-2xs);
  color: var(--c-ink-2);
}

.kimp-preview__meta em {
  font-style: normal;
  font-size: 9.5px;
  color: var(--c-faint);
}

.kimp-preview__block-title {
  display: block;
  margin: 10px 0 6px;
  font-size: var(--fs-2xs);
  font-weight: var(--fw-semibold);
  color: var(--c-ink-2);
}

.kimp-preview__dups,
.kimp-preview__warns,
.kimp-preview__result {
  margin-bottom: 8px;
  padding: 8px 10px;
  border: 1px solid var(--c-line-2);
  border-radius: var(--rd-md);
  background: var(--c-card);
  font-size: var(--fs-2xs);
  color: var(--c-muted);
}

.kimp-preview__dup {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  padding: 4px 0;
}

.kimp-preview__dup code,
.kimp-preview__result code {
  font-family: var(--font-mono);
  font-size: 9.5px;
}

.kimp-preview__warns ul,
.kimp-preview__result ul {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.kimp-preview__warns li,
.kimp-preview__result li {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  line-height: 1.6;
}

.kimp-preview__chunks-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.kimp-preview__chunk-acts {
  display: inline-flex;
  gap: 4px;
}

.kimp-preview__chunks {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.kimp-chunk {
  display: flex;
  gap: 8px;
  padding: 7px 9px;
  border: 1px solid var(--c-line-2);
  border-radius: var(--rd-md);
  background: var(--c-bg-2);
}

.kimp-chunk.is-off {
  opacity: 0.55;
}

.kimp-chunk__check {
  display: inline-flex;
  align-items: flex-start;
  gap: 4px;
  font-size: var(--fs-2xs);
  color: var(--c-muted);
}

.kimp-chunk__body {
  flex: 1;
  min-width: 0;
}

.kimp-chunk__head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
}

.kimp-chunk__path {
  font-size: var(--fs-2xs);
  font-weight: var(--fw-semibold);
  color: var(--c-ink-2);
}

.kimp-chunk__text {
  margin-top: 4px;
  font-size: var(--fs-2xs);
  line-height: 1.75;
  color: var(--c-muted);
  white-space: pre-wrap;
  word-break: break-word;
}

.kimp-chunk__text.is-clamped {
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.kimp-preview__form {
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed var(--c-line-2);
}

.kimp-form-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 8px 12px;
}

.kimp-field {
  display: flex;
  flex-direction: column;
  gap: 3px;
  font-size: 9.5px;
  color: var(--c-faint);
}

.kimp-field input,
.kimp-field select {
  height: 29px;
  padding: 0 8px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-sm);
  background: var(--c-card);
  font-size: var(--fs-xs);
  color: var(--c-ink-2);
}

.kimp-field.is-block {
  grid-column: 1 / -1;
}

.kimp-preview__actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px;
}

.kimp-preview__btns {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 6px;
}

.kimp-hint {
  margin-top: 6px;
  font-size: 9.5px;
  color: var(--c-faint);
  line-height: 1.6;
}

.kimp-hint code {
  padding: 0 3px;
  border-radius: var(--rd-xs);
  background: var(--c-bg-2);
  font-family: var(--font-mono);
  font-size: 9.5px;
}

.kimp-note {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 8px;
  padding: 7px 10px;
  border-radius: var(--rd-sm);
  background: var(--c-bg-2);
  font-size: var(--fs-2xs);
  color: var(--c-muted);
  line-height: var(--lh-base);
}

.kimp-note--warn {
  background: var(--c-amber-soft);
  color: #9a4b05;
}

.kimp-form-error {
  margin-top: 8px;
  padding: 7px 10px;
  border-left: 2px solid #c0392b;
  border-radius: 0 var(--rd-sm) var(--rd-sm) 0;
  background: var(--c-amber-soft);
  color: #9a4b05;
  font-size: var(--fs-2xs);
  line-height: var(--lh-base);
}
</style>
