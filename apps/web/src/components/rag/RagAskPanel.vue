<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { WButton, WCard, WEmpty, WIcon, WLoading, WModal, WTag, useToast } from '@/components'
import PermissionDenied from '@/components/feedback/PermissionDenied.vue'
import { useAuthStore } from '@/stores/auth'
import { api, ApiError, errorNextStep, errorText, forbiddenDetailsOf } from '@/api'
import { KNOWLEDGE_CATEGORY_LABELS } from '@/api'
import type {
  ForbiddenDetails,
  KnowledgeCategory,
  KnowledgeDetail,
  RagQueryResult,
  RagSource,
} from '@/api'
import {
  RAG_PRESET_QUESTIONS,
  citedIndexes,
  isKnownRefusalReason,
  ragDegradedText,
  ragModeLabel,
  ragModeNote,
  ragModeTone,
  ragRerankedText,
  refusalCopy,
  scorePercent,
  splitAnswerCitations,
} from './rag-copy'

/**
 * RAG 智能问答面板（P6 W33 / task-81；契约 v1.4.0 `POST /api/v1/rag/query`）
 * ===========================================================================
 * **单向复用组件**：知识页「智能问答」页签与 Copilot 知识秒查工作区使用同一份实现，
 * 不各写一套请求/状态机（页面只决定外壳与上下文说明）。
 *
 * 五态（缺一不可，e2e 逐条断言）：
 * 1) 加载：`rag-loading`（检索 + 生成 + 降级兜底都在这一屏等）；
 * 2) 空：未提问 → `rag-idle`（不自动发请求，避免把示例当成查询结果）；question 为空不可提交；
 * 3) 错误：400 `E-1001` / 500 `E-9001` / 401（全局跳登录）/ 网络中断 → `rag-error` 人话 + 重试；
 * 4) 超时：`E-4001` + `details.timedOut` → `rag-timeout`（契约可重试，不改错误码）；
 * 5) 降级：`degraded` 存在 → `rag-degraded` 展示**服务端原因**；模式非 `generative` 时徽标如实标注。
 *
 * 红线（AGENTS.md NEVER #11 / 设计 §6 §11）：
 * - `answer` 中的 `[n]` 只做高亮与定位（编号来自服务端，前端不改号/不补号）；
 * - **拒答不是错误**：`answerable=false` 时给人话原因 + 转人工，绝不编造答案；
 * - `mode` / `reranked` 如实展示，不把 `retrieval_only` / `rules` 说成生成答案；
 * - 来源卡片字段全部来自服务端 `sources[]`（index/knowledgeId/title/version/owner/category/score/snippet），
 *   点开原文走 `GET /knowledge/{id}`；详情 404 时只展示片段并说明（不伪造正文）。
 */
const props = withDefaults(
  defineProps<{
    /** page = 知识页整页页签；embedded = Copilot 工作区内嵌 */
    variant?: 'page' | 'embedded'
    /** 示例问题 chips */
    presets?: readonly string[]
    /** 是否展示「演示数据」标注（默认展示；不得把演示知识库说成生产数据） */
    showDemo?: boolean
    /** 结果区标题前缀（如「RAG 问答」），空则用默认文案 */
    heading?: string
  }>(),
  {
    variant: 'page',
    presets: () => RAG_PRESET_QUESTIONS,
    showDemo: true,
    heading: '',
  },
)

const router = useRouter()
const toast = useToast()
const auth = useAuthStore()

/** P1 缺口沉淀（task-87）：有 knowledge.import 能力才给「补充这条知识」入口（owner 无导入权限，隐藏） */
const canSupplement = computed(() => auth.hasCapability('knowledge.import'))

type DeniedInfo = ForbiddenDetails & { traceId?: string }

/** 请求态 */
const question = ref('')
const askedQuestion = ref('')
const loading = ref(false)
const answered = ref(false)
const error = ref('')
/** 错误分类：http（业务错误）/ timeout（E-4001 超时）/ offline（网络中断） */
const errorKind = ref<'http' | 'timeout' | 'offline'>('http')
const denied = ref<DeniedInfo | null>(null)
const result = ref<RagQueryResult | null>(null)

/** 来源原文弹层 */
const sourceOpen = ref(false)
const activeSource = ref<RagSource | null>(null)
const sourceDetail = ref<KnowledgeDetail | null>(null)
const sourceDetailLoading = ref(false)
const sourceDetailError = ref('')
const sourceDetailMissing = ref(false)

/** 引用高亮（点 `[n]` 定位来源卡片） */
const highlightedIndex = ref(0)
const sourceEls = new Map<number, HTMLElement>()

const isEmbedded = computed(() => props.variant === 'embedded')
const canSubmit = computed(() => !loading.value && question.value.trim().length > 0)

/** 答案 `[n]` 切分（只切分不改写） */
const answerSegments = computed(() => splitAnswerCitations(result.value?.answer ?? ''))
const answerCitations = computed(() => citedIndexes(answerSegments.value))
const sources = computed<RagSource[]>(() => result.value?.sources ?? [])

const modeLabel = computed(() => ragModeLabel(result.value?.mode))
const modeTone = computed(() => ragModeTone(result.value?.mode))
const modeNote = computed(() => ragModeNote(result.value?.mode))
const degradedText = computed(() => ragDegradedText(result.value))
const rerankedText = computed(() => ragRerankedText(Boolean(result.value?.reranked)))
const isRefusal = computed(() => Boolean(result.value) && result.value?.answerable === false)
const refusal = computed(() => refusalCopy(result.value?.refusalReason ?? null))
const refusalReasonCode = computed(() => result.value?.refusalReason ?? '')
const refusalReasonKnown = computed(() => isKnownRefusalReason(refusalReasonCode.value))

function apiMessage(caught: unknown): string {
  if (caught instanceof ApiError) return `${errorText(caught.code)}；${errorNextStep(caught.code)}`
  return '请求失败：请稍后重试或转人工'
}

function deniedOf(caught: unknown): DeniedInfo | null {
  const details = forbiddenDetailsOf(caught)
  if (!details) return null
  return { ...details, traceId: caught instanceof ApiError ? caught.traceId : '' }
}

function categoryText(category?: KnowledgeCategory | null): string {
  if (!category) return '—'
  return KNOWLEDGE_CATEGORY_LABELS[category] ?? String(category)
}

/** 提交提问（唯一请求入口；重试复用同一函数） */
async function ask(): Promise<void> {
  const text = question.value.trim()
  if (!text) {
    toast.warning('请先输入问题（question 必填，1—500 字符）')
    return
  }
  if (text.length > 500) {
    error.value = `问题过长（${text.length} 字符）：契约上限 500 字符，请精简后再问`
    errorKind.value = 'http'
    answered.value = true
    result.value = null
    return
  }
  loading.value = true
  error.value = ''
  denied.value = null
  answered.value = true
  askedQuestion.value = text
  highlightedIndex.value = 0
  try {
    const { data } = await api.rag.query({ question: text })
    result.value = data
    if (!data.answerable) {
      toast.info('服务端判定本次不可答：已按拒答口径展示原因与转人工入口（不编造答案）')
    } else if (data.mode !== 'generative') {
      toast.info(`答案来自「${ragModeLabel(data.mode)}」链路（非生成答案，引用仍按服务端编号展示）`)
    }
  } catch (caught) {
    result.value = null
    const forbidden = deniedOf(caught)
    if (forbidden) {
      denied.value = forbidden
      return
    }
    if (caught instanceof ApiError && caught.code === 'E-4001') {
      const timedOut = caught.details?.timedOut === true
      errorKind.value = timedOut ? 'timeout' : 'offline'
      error.value = timedOut
        ? '问答请求超时（契约 E-4001 可重试）：编排层响应较慢或链路繁忙，请稍后重试，或直接转人工'
        : '网络不可用或请求被中断：请检查网络后重试，或转人工'
      return
    }
    errorKind.value = 'http'
    error.value = apiMessage(caught)
  } finally {
    loading.value = false
  }
}

/** 示例 chips：填入并立即提问 */
function usePreset(text: string): void {
  question.value = text
  void ask()
}

/** 重置为未提问态（清空结果，保留输入便于改问） */
function reset(): void {
  result.value = null
  answered.value = false
  error.value = ''
  denied.value = null
  highlightedIndex.value = 0
}

/** 点 `[n]`：定位到对应来源卡片（编号必须在 sources 内，服务端已校验；越界只提示不补造） */
function focusSource(index: number): void {
  if (!sources.value.some((source) => source.index === index)) {
    toast.warning(`引用 [${index}] 不在来源列表中（服务端引用校验应已拦截，页面不补造来源）`)
    return
  }
  highlightedIndex.value = index
  sourceEls.get(index)?.scrollIntoView({ behavior: 'smooth', block: 'center' })
}

function setSourceEl(index: number, el: unknown): void {
  if (el instanceof HTMLElement) sourceEls.set(index, el)
  else sourceEls.delete(index)
}

/** 打开来源原文（`GET /knowledge/{id}`；404 = 该条目未在知识库详情开放 → 只展示片段） */
async function openSource(source: RagSource): Promise<void> {
  activeSource.value = source
  sourceOpen.value = true
  sourceDetail.value = null
  sourceDetailError.value = ''
  sourceDetailMissing.value = false
  sourceDetailLoading.value = true
  try {
    const { data } = await api.knowledge.get(source.knowledgeId)
    sourceDetail.value = data
  } catch (caught) {
    if (caught instanceof ApiError && caught.httpStatus === 404) {
      sourceDetailMissing.value = true
    } else {
      sourceDetailError.value = apiMessage(caught)
    }
  } finally {
    sourceDetailLoading.value = false
  }
}

function closeSource(): void {
  sourceOpen.value = false
}

/** 转人工：复用 Copilot 话术/转人工入口（不新造页面；演示环境不真实派单） */
function handoff(): void {
  toast.info('已记录转人工意图：正在打开 Copilot 话术工作区（演示环境，不真实派单）')
  void router.push('/copilot/script')
}

/** 知识缺口：到 Copilot 知识共建提交（真实缺口池由运营侧受理） */
function submitGap(): void {
  toast.info('正在打开 Copilot「知识共建」：把 AI 答不上的问题提交到缺口池')
  void router.push('/copilot/build')
}

/**
 * P1 缺口沉淀（task-87 / task-88）：把拒答问题带到**知识导入页** `/knowledge/import?gap=` 并预填
 * （标题 = 问题、正文模板、sourceType=badcase），由责任人补充制度依据后提交待审。
 * 为什么用非导航路由 `/knowledge/import` 而不是知识页页签 `/knowledge?tab=import`：
 * `knowledge.import` 能力含 agent，但 `/knowledge` 的 12 屏可见性矩阵不含 agent → 页签对 agent 不可达，
 * 跳独立页才能让「有导入能力的角色」都点得到入口（同一 `KnowledgeImportPanel`，仍不伪造知识）。
 * 本组件只做跳转 + 预填，提交仍走 `POST /api/v1/knowledge/imports`。
 */
function supplementKnowledge(): void {
  const target = (askedQuestion.value || question.value).trim()
  if (!target) {
    toast.warning('缺少问题文本：请先提问，再从拒答结果点「补充这条知识」')
    return
  }
  toast.info('正在打开知识导入页：已预填问题标题与来源 badcase，请补充标准答案后提交待审')
  void router.push({ path: '/knowledge/import', query: { gap: target } })
}

function goKnowledgeCenter(): void {
  void router.push('/knowledge')
}
</script>

<template>
  <div
    class="rag-ask"
    :class="{ 'is-embedded': isEmbedded }"
    data-testid="rag-panel"
    :data-variant="isEmbedded ? 'embedded' : 'page'"
  >
    <!-- ===================== ① 提问区（POST /api/v1/rag/query） ===================== -->
    <WCard
      :title="heading || '知识问答（RAG）'"
      subtitle="混合检索 + 引用校验 + 拒答：答案必须带 [n] 出处，无依据不编造"
      data-testid="rag-ask-card"
    >
      <template #extra>
        <WTag
          v-if="result"
          :tone="modeTone"
          data-testid="rag-mode-badge"
          :data-mode="result.mode"
          :data-reranked="String(result.reranked)"
        >
          {{ modeLabel }}
        </WTag>
        <WTag v-if="showDemo" tone="v" plain data-testid="rag-demo-badge">演示数据（mock 知识库）</WTag>
      </template>

      <div class="rag-ask__row">
        <WIcon name="search" :size="15" />
        <input
          v-model="question"
          class="rag-ask__input"
          type="text"
          maxlength="500"
          placeholder="问知识，例如：物业费单价 / 缴费期限 / 公摊电费 / 报修流程"
          data-testid="rag-question-input"
          @keyup.enter="ask"
        />
        <WButton
          variant="primary"
          size="sm"
          :loading="loading"
          :disabled="!canSubmit"
          data-testid="rag-ask-submit"
          @click="ask"
        >
          提问
        </WButton>
      </div>

      <div class="rag-ask__presets" data-testid="rag-presets">
        <span class="rag-ask__presets-lab">示例问题</span>
        <button
          v-for="(preset, index) in presets"
          :key="preset"
          type="button"
          class="rag-chip"
          :data-testid="`rag-preset-${index}`"
          :data-question="preset"
          @click="usePreset(preset)"
        >
          {{ preset }}
        </button>
        <WButton
          v-if="result || error || denied"
          size="sm"
          variant="ghost"
          data-testid="rag-reset"
          @click="reset"
        >
          清空结果
        </WButton>
      </div>

      <div class="rag-ask__meta">
        <WTag tone="n" plain>端点 POST /api/v1/rag/query（能力 rag.query）</WTag>
        <WTag tone="n" plain>question 1—500 字符 · topK 默认 5</WTag>
        <WTag tone="a" plain>无依据即拒答 + 转人工，不编造</WTag>
      </div>
    </WCard>

    <!-- ===================== ② 加载态 ===================== -->
    <WCard v-if="loading" title="正在检索并求解" data-testid="rag-loading">
      <WLoading center label="正在调用 RAG 编排层（混合检索 → 引用校验 → 生成/降级兜底）…" />
    </WCard>

    <!-- ===================== ③ 403 无权限态 ===================== -->
    <WCard v-else-if="denied" title="无权限（403）" data-testid="rag-denied">
      <PermissionDenied
        compact
        :required-capability="denied.requiredCapability"
        :required-role="denied.role"
        :trace-id="denied.traceId"
        detail="知识问答需要 rag.query 能力；当前演示角色不具备，页面不会返回任何答案或来源。"
      >
        <template #actions>
          <WButton size="sm" variant="ghost" data-testid="rag-denied-retry" @click="ask">重试</WButton>
        </template>
      </PermissionDenied>
    </WCard>

    <!-- ===================== ④ 错误 / 超时态 ===================== -->
    <WCard
      v-else-if="error"
      :title="errorKind === 'timeout' ? '超时（E-4001 可重试）' : errorKind === 'offline' ? '网络不可用' : '问答未完成'"
      :data-testid="errorKind === 'timeout' ? 'rag-timeout' : 'rag-error'"
    >
      <p class="rag-note rag-note--warn">
        <WTag :tone="errorKind === 'timeout' ? 'a' : 'r'" plain>
          {{ errorKind === 'timeout' ? '请求超时' : errorKind === 'offline' ? '网络不可用' : '不可用' }}
        </WTag>
        {{ error }}
      </p>
      <div class="rag-actions">
        <WButton size="sm" data-testid="rag-error-retry" @click="ask">重试</WButton>
        <WButton size="sm" variant="ghost" data-testid="rag-error-handoff" @click="handoff">转人工</WButton>
      </div>
    </WCard>

    <!-- ===================== ⑤ 空态（未提问） ===================== -->
    <WCard v-else-if="!answered" title="未开始提问" data-testid="rag-idle">
      <WEmpty
        icon="search"
        title="输入问题后点「提问」"
        description="没有输入时不发起请求（避免空问题触发检索）。也可以点上面的示例问题；答案只来自知识库，且必须带 [n] 出处。"
      />
    </WCard>

    <!-- ===================== ⑥ 拒答态（answerable=false，仍 200） ===================== -->
    <WCard
      v-else-if="isRefusal && result"
      title="拒答：不给答案，转人工"
      :subtitle="`问题：${askedQuestion}`"
      data-testid="rag-refusal"
      :data-reason="refusalReasonCode"
    >
      <template #extra>
        <WTag tone="r" plain>answerable=false</WTag>
        <WTag v-if="refusalReasonKnown" tone="a" plain data-testid="rag-refusal-reason-code">
          {{ refusalReasonCode }}
        </WTag>
      </template>

      <div class="rag-refusal">
        <b class="rag-refusal__title" data-testid="rag-refusal-title">{{ refusal.title }}</b>
        <p class="rag-refusal__text" data-testid="rag-refusal-reason">{{ refusal.detail }}</p>
        <p v-if="result.answer" class="rag-refusal__server" data-testid="rag-refusal-answer">
          <span class="rag-refusal__lab">服务端回执</span>{{ result.answer }}
        </p>
        <p class="rag-refusal__next" data-testid="rag-refusal-next">{{ refusal.next }}</p>
        <p class="rag-refusal__server">
          <span class="rag-refusal__lab">链路</span>
          模式 {{ modeLabel }} · 置信度 {{ scorePercent(result.confidence) }} ·
          {{ rerankedText }} · traceId <code>{{ result.traceId }}</code>
        </p>
        <div class="rag-actions">
          <WButton size="sm" variant="primary" icon="arrow-right" data-testid="rag-handoff" @click="handoff">
            转人工处理
          </WButton>
          <WButton
            v-if="canSupplement"
            size="sm"
            variant="secondary"
            data-testid="rag-refusal-supplement"
            @click="supplementKnowledge"
          >
            补充这条知识
          </WButton>
          <WButton size="sm" data-testid="rag-refusal-gap" @click="submitGap">提交知识缺口</WButton>
          <WButton size="sm" variant="ghost" data-testid="rag-refusal-retry" @click="ask">重试</WButton>
        </div>
        <p class="rag-refusal__hint">
          口径：拒答不是错误（HTTP 200 + <code>answerable=false</code>）；页面不生成猜测答案，
          缺口会进入知识运营的缺口池，由责任人补知识后再纳入检索。
          <template v-if="canSupplement">
            「补充这条知识」会把当前问题预填到知识导入页（`/knowledge/import?gap=`，来源标记 badcase），
            由责任人补充标准答案后进入待审流程。
          </template>
        </p>
      </div>
    </WCard>

    <!-- ===================== ⑦ 可答态（answer + [n] + 来源卡片） ===================== -->
    <template v-else-if="result && result.answerable">
      <WCard
        title="答案"
        :subtitle="`问题：${askedQuestion} · 来源 ${sources.length} 条（按服务端 index 升序）`"
        data-testid="rag-answer"
        :data-mode="result.mode"
        :data-answerable="String(result.answerable)"
      >
        <template #extra>
          <WTag :tone="modeTone" data-testid="rag-answer-mode">{{ modeLabel }}</WTag>
          <WTag tone="n" plain :data-confidence="scorePercent(result.confidence)">
            置信度 {{ scorePercent(result.confidence) }}
          </WTag>
          <WTag
            :tone="result.reranked ? 'g' : 'n'"
            plain
            data-testid="rag-reranked"
            :data-reranked="String(result.reranked)"
          >
            {{ rerankedText }}
          </WTag>
        </template>

        <p class="rag-answer__mode-note" data-testid="rag-mode-note">
          {{ modeNote }}
        </p>

        <div v-if="degradedText" class="rag-degraded" data-testid="rag-degraded">
          <WIcon name="info" :size="13" />
          <span><b>已降级</b>：{{ degradedText }}</span>
        </div>

        <p class="rag-answer__text" data-testid="rag-answer-text">
          <template v-for="(segment, index) in answerSegments" :key="`${segment.kind}-${index}`">
            <button
              v-if="segment.kind === 'cite'"
              type="button"
              class="rag-cite"
              :data-cite="segment.index"
              :class="{ 'is-on': highlightedIndex === segment.index }"
              :title="`定位来源 [${segment.index}]`"
              @click="focusSource(segment.index)"
            >
              {{ segment.text }}
            </button>
            <span v-else>{{ segment.text }}</span>
          </template>
        </p>

        <p class="rag-answer__trace">
          <span>引用编号 {{ answerCitations.length ? answerCitations.map((n) => `[${n}]`).join(' ') : '无' }}</span>
          <span>traceId <code data-testid="rag-trace-id">{{ result.traceId }}</code></span>
        </p>

        <div class="rag-actions">
          <WButton size="sm" variant="ghost" data-testid="rag-answer-reset" @click="reset">重新提问</WButton>
          <WButton size="sm" variant="ghost" data-testid="rag-answer-knowledge" @click="goKnowledgeCenter">
            去知识运营中心
          </WButton>
        </div>
      </WCard>

      <!-- 来源卡片：字段全部来自服务端 sources[]，可点开原文/详情 -->
      <WCard
        title="来源卡片"
        subtitle="index / knowledgeId / title / version / owner / category / score / snippet（服务端给的引用四要素）"
        data-testid="rag-sources"
      >
        <template #extra>
          <WTag tone="n" plain>{{ sources.length }} 条</WTag>
        </template>

        <p v-if="sources.length === 0" class="rag-note" data-testid="rag-sources-empty">
          <WTag tone="a" plain>无来源</WTag>
          服务端未返回来源卡片：页面不补造引用，请转人工核实。
        </p>
        <ul v-else class="rag-src-list">
          <li
            v-for="source in sources"
            :key="source.index"
            :ref="(el) => setSourceEl(source.index, el)"
            class="rag-src"
            :class="{ 'is-on': highlightedIndex === source.index }"
            :data-testid="`rag-source-${source.index}`"
            :data-knowledge-id="source.knowledgeId"
          >
            <div class="rag-src__head">
              <b class="rag-src__idx num">[{{ source.index }}]</b>
              <b class="rag-src__title" :data-testid="`rag-source-title-${source.index}`">{{ source.title }}</b>
              <WTag tone="n" plain>
                <span :data-testid="`rag-source-id-${source.index}`">{{ source.knowledgeId }}</span>
              </WTag>
              <WTag tone="g" plain>{{ source.version }}</WTag>
              <WTag tone="n" plain>{{ categoryText(source.category) }}</WTag>
              <WTag tone="b" plain>相关度 {{ scorePercent(source.score) }}</WTag>
              <span class="rag-src__owner">责任人 {{ source.owner }}</span>
              <WButton
                size="sm"
                variant="ghost"
                :data-testid="`rag-source-open-${source.index}`"
                @click="openSource(source)"
              >
                展开原文
              </WButton>
            </div>
            <p class="rag-src__snippet" :data-testid="`rag-source-snippet-${source.index}`">{{ source.snippet }}</p>
          </li>
        </ul>

        <p class="rag-src__hint">
          片段为服务端脱敏后的入选原文（非全文）；点「展开原文」走
          <code>GET /api/v1/knowledge/&#123;id&#125;</code> 读知识详情（含版本历史）。
        </p>
      </WCard>
    </template>

    <!-- ===================== 来源原文弹层 ===================== -->
    <WModal v-model:open="sourceOpen" title="来源原文（知识详情）" width="660px">
      <div v-if="activeSource" class="rag-srcdetail" data-testid="rag-source-detail">
        <div class="rag-srcdetail__head">
          <WTag tone="n" plain>[{{ activeSource.index }}] {{ activeSource.knowledgeId }}</WTag>
          <WTag tone="g" plain>{{ activeSource.version }}</WTag>
          <WTag tone="n" plain>{{ categoryText(activeSource.category) }}</WTag>
          <WTag tone="b" plain>相关度 {{ scorePercent(activeSource.score) }}</WTag>
          <span class="dim">责任人 {{ activeSource.owner }}</span>
        </div>

        <p v-if="sourceDetailLoading" class="rag-note"><WLoading label="正在读取知识详情…" /></p>
        <template v-else-if="sourceDetail">
          <h4 class="rag-srcdetail__title">{{ sourceDetail.title }}</h4>
          <p class="rag-srcdetail__content" data-testid="rag-source-detail-content">{{ sourceDetail.content }}</p>
          <p class="rag-note">
            版本 {{ sourceDetail.version }} · 状态 {{ sourceDetail.status }} · 生效期 {{ sourceDetail.effectiveFrom || '—' }} 起
          </p>
          <p class="rag-note">
            出处 {{ [sourceDetail.source?.doc, sourceDetail.source?.clause, sourceDetail.source?.page].filter(Boolean).join(' · ') || '—' }}
          </p>
        </template>
        <p v-else-if="sourceDetailMissing" class="rag-note rag-note--warn" data-testid="rag-source-detail-missing">
          <WTag tone="a" plain>详情不可用 404</WTag>
          该来源条目未在知识库详情端点开放（可能来自编排层索引的同名片段）：页面只展示服务端返回的检索片段，不伪造正文。
        </p>
        <p v-else class="rag-note rag-note--warn" data-testid="rag-source-detail-error">
          <WTag tone="r" plain>详情读取失败</WTag>{{ sourceDetailError }}
        </p>

        <div class="rag-srcdetail__block">
          <b>检索片段（服务端脱敏）</b>
          <p class="rag-src__snippet">{{ activeSource.snippet }}</p>
        </div>
      </div>
      <template #footer>
        <WButton size="sm" data-testid="rag-source-detail-close" @click="closeSource">关闭</WButton>
      </template>
    </WModal>
  </div>
</template>

<style scoped>
.rag-ask {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
  min-width: 0;
}

.rag-ask__row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 10px;
  height: 38px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-md);
  background: var(--c-bg-2);
  color: var(--c-faint);
}

.rag-ask__row:focus-within {
  border-color: var(--c-brand);
  background: var(--c-card);
  box-shadow: var(--sh-ring);
}

.rag-ask__input {
  flex: 1;
  min-width: 0;
  border: 0;
  outline: none;
  background: transparent;
  font-size: var(--fs-sm);
  color: var(--c-ink-2);
}

.rag-ask__presets {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 9px;
}

.rag-ask__presets-lab {
  font-size: 9.5px;
  color: var(--c-faint);
}

.rag-chip {
  height: 23px;
  padding: 0 9px;
  border: 1px solid var(--c-line);
  border-radius: var(--rd-pill);
  background: var(--c-card);
  font-size: var(--fs-2xs);
  color: var(--c-ink-2);
  transition: border-color var(--tr-fast), color var(--tr-fast), background-color var(--tr-fast);
}

.rag-chip:hover {
  border-color: var(--c-brand);
  color: var(--c-brand);
  background: var(--c-brand-soft);
}

.rag-ask__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  margin-top: 9px;
}

/* ---------- 答案 ---------- */
.rag-answer__mode-note {
  margin-bottom: 8px;
  font-size: var(--fs-2xs);
  color: var(--c-muted);
  line-height: var(--lh-base);
}

.rag-answer__text {
  font-size: var(--fs-sm);
  line-height: 1.85;
  color: var(--c-ink-2);
  white-space: pre-wrap;
  word-break: break-word;
}

.rag-cite {
  display: inline-flex;
  align-items: center;
  height: 17px;
  padding: 0 4px;
  margin: 0 1px;
  border: 1px solid #b9e3d8;
  border-radius: var(--rd-xs);
  background: var(--c-brand-soft);
  color: #0a725f;
  font-family: var(--font-mono);
  font-size: 10px;
  font-weight: var(--fw-semibold);
  vertical-align: baseline;
}

.rag-cite:hover,
.rag-cite.is-on {
  border-color: var(--c-brand);
  background: #d6f2ea;
}

.rag-answer__trace {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 9px;
  padding-top: 8px;
  border-top: 1px dashed var(--c-line-2);
  font-size: 9.5px;
  color: var(--c-faint);
}

.rag-answer__trace code {
  font-family: var(--font-mono);
  font-size: 9.5px;
}

/* ---------- 降级 ---------- */
.rag-degraded {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  margin-bottom: 9px;
  padding: 7px 10px;
  border-left: 2px solid var(--c-amber);
  border-radius: 0 var(--rd-sm) var(--rd-sm) 0;
  background: var(--c-amber-soft);
  color: #9a4b05;
  font-size: var(--fs-2xs);
  line-height: var(--lh-base);
}

/* ---------- 来源卡片 ---------- */
.rag-src-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.rag-src {
  padding: 9px 11px;
  border: 1px solid var(--c-line-2);
  border-radius: var(--rd-md);
  background: var(--c-bg-2);
  transition: border-color var(--tr-fast), background-color var(--tr-fast);
}

.rag-src.is-on {
  border-color: var(--c-brand);
  background: var(--c-brand-soft);
}

.rag-src__head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
}

.rag-src__idx {
  font-size: var(--fs-2xs);
  color: var(--c-brand);
  font-weight: var(--fw-semibold);
}

.rag-src__title {
  font-size: var(--fs-sm);
  font-weight: var(--fw-semibold);
  color: var(--c-ink);
}

.rag-src__owner {
  font-size: 9.5px;
  color: var(--c-faint);
}

.rag-src__snippet {
  margin-top: 5px;
  font-size: var(--fs-2xs);
  line-height: 1.75;
  color: var(--c-muted);
  word-break: break-word;
}

.rag-src__hint {
  margin-top: 9px;
  font-size: 9.5px;
  color: var(--c-faint);
  line-height: 1.6;
}

.rag-src__hint code,
.rag-refusal__hint code,
.rag-refusal__server code {
  padding: 0 3px;
  border-radius: var(--rd-xs);
  background: var(--c-bg-2);
  font-family: var(--font-mono);
  font-size: 9.5px;
}

/* ---------- 拒答 ---------- */
.rag-refusal {
  display: flex;
  flex-direction: column;
  gap: 7px;
}

.rag-refusal__title {
  font-size: var(--fs-md);
  font-weight: var(--fw-semibold);
  color: var(--c-ink);
}

.rag-refusal__text,
.rag-refusal__next {
  font-size: var(--fs-sm);
  line-height: 1.75;
  color: var(--c-ink-2);
}

.rag-refusal__next {
  padding: 6px 9px;
  border-radius: var(--rd-sm);
  background: var(--c-bg-2);
  font-size: var(--fs-2xs);
  color: var(--c-muted);
}

.rag-refusal__server {
  font-size: var(--fs-2xs);
  color: var(--c-muted);
  line-height: var(--lh-base);
}

.rag-refusal__lab {
  display: inline-block;
  margin-right: 6px;
  padding: 0 5px;
  border-radius: var(--rd-xs);
  background: var(--c-bg-2);
  font-size: 9px;
  color: var(--c-faint);
}

.rag-refusal__hint {
  padding: 6px 9px;
  border-radius: var(--rd-sm);
  background: var(--c-amber-soft);
  color: #9a4b05;
  font-size: var(--fs-2xs);
  line-height: var(--lh-base);
}

/* ---------- 通用 ---------- */
.rag-note {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  font-size: var(--fs-2xs);
  color: var(--c-muted);
  line-height: var(--lh-base);
}

.rag-note--warn {
  margin-bottom: 8px;
  padding: 7px 10px;
  border-radius: var(--rd-sm);
  background: var(--c-amber-soft);
  color: #9a4b05;
}

.rag-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
}

.rag-srcdetail {
  display: flex;
  flex-direction: column;
  gap: 7px;
}

.rag-srcdetail__head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
}

.rag-srcdetail__title {
  font-size: var(--fs-md);
  font-weight: var(--fw-semibold);
  color: var(--c-ink);
}

.rag-srcdetail__content {
  font-size: var(--fs-sm);
  line-height: 1.8;
  color: var(--c-ink-2);
  white-space: pre-wrap;
  word-break: break-word;
}

.rag-srcdetail__block {
  padding: 8px 10px;
  border: 1px solid var(--c-line-2);
  border-radius: var(--rd-md);
  background: var(--c-bg-2);
  font-size: var(--fs-2xs);
  color: var(--c-muted);
}

.rag-srcdetail__block b {
  color: var(--c-ink-2);
  font-weight: var(--fw-semibold);
}
</style>
