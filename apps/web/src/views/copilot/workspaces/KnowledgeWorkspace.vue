<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { WButton, WCard, WEmpty, WIcon, WLoading, WModal, WPageHeader, WTable, WTag, useToast } from '@/components'
import PermissionDenied from '@/components/feedback/PermissionDenied.vue'
import { RagAskPanel } from '@/components/rag'
import type { TableColumn } from '@/types'
import { COPILOT_WORKSPACES } from '@/app/copilot'
import {
  api,
  ApiError,
  errorNextStep,
  errorText,
  forbiddenDetailsOf,
  formatIsoMinute,
} from '@/api'
import type { ForbiddenDetails, KnowledgeDetail, KnowledgeSearchHit } from '@/api'
import { copyText } from '../shared'

/**
 * Copilot 工作区 1/8 · 知识秒查（P4-008，契约 v1.2.0 §13 `GET /knowledge/search`）
 * ===========================================================================
 * 口径（AGENTS.md「不编造知识」+ 产品 §7.2 知识秒查红线）：
 * - 检索走 `GET /api/v1/knowledge/search?q=&limit=`：服务端**只返回 published 且在生效期内**的条目，
 *   每条带 `score` 与可直接展示的 `cite`（`K-018 V1.2 · 装修管理规定 §3.1`）——`cite` 由服务端拼装，
 *   前端**不自行拼接、不补版本**；
 * - 答案区只展示**知识原文**（`GET /knowledge/{id}` 的 `content`）+ 出处四要素（ID/版本/责任人/生效期），
 *   不做自由发挥、不做「置信度」之类的编造指标；
 * - **无命中 = 拒答**：`items: []` 时显示「知识库暂无出处：已转人工 / 建议提交知识」，绝不生成答案；
 *   缺口由 `/knowledge/stats` 的 `gaps` 聚合（运营侧补知识）；
 * - 过期 / 草稿 / 待审 / 驳回**不作为命中展示**（服务端已过滤，前端不补展示）；
 * - 演示标注：检索答案是「规则组装」演示口径，模型调用由 Python 编排层承接（本页不发真实模型请求）。
 *
 * P6 W33 增量（task-81）：本页额外挂载 `RagAskPanel`（`POST /api/v1/rag/query`，与知识页「智能问答」
 * 页签**同一组件**，不重复实现）：answer（`[n]` 高亮）+ 来源卡片 + 拒答（人话 + 转人工 + 知识缺口）
 * + 模式徽标（generative/retrieval_only/rules）+ 降级提示 + 加载/空/错误/超时五态。
 * 上面的「关键词检索（/knowledge/search）」保留为规则检索对照口径（旧用例与指标继续可用）。
 */
const workspace = COPILOT_WORKSPACES.knowledge
const router = useRouter()
const toast = useToast()

const LIMIT = 5
const PRESETS = ['公摊', '装修押金', '车位费']

type DeniedInfo = ForbiddenDetails & { traceId?: string }

function deniedOf(error: unknown): DeniedInfo | null {
  const details = forbiddenDetailsOf(error)
  if (!details) return null
  return { ...details, traceId: error instanceof ApiError ? error.traceId : '' }
}

function apiMessage(error: unknown): string {
  if (error instanceof ApiError) return `${errorText(error.code)}；${errorNextStep(error.code)}`
  return '请求失败：请稍后重试或转人工'
}

const question = ref(PRESETS[0] ?? '')
const searchedQuery = ref('')
const searched = ref(false)
const loading = ref(false)
const searchError = ref('')
const searchDenied = ref<DeniedInfo | null>(null)

const hits = ref<KnowledgeSearchHit[]>([])
const activeHit = ref<KnowledgeSearchHit | null>(null)
const activeDetail = ref<KnowledgeDetail | null>(null)
const detailLoading = ref(false)
const detailError = ref('')
const detailOpen = ref(false)

/** 本次会话的检索记录（真实交互记录，不是预置演示数据） */
const history = ref<{ query: string; count: number; at: string }[]>([])

const hasHits = computed(() => hits.value.length > 0)
const bestHit = computed(() => activeHit.value ?? hits.value[0] ?? null)
const answerable = computed(() => Boolean(activeDetail.value && bestHit.value))

const hitRows = computed<Record<string, unknown>[]>(
  () =>
    hits.value.map((hit) => ({
      ...hit,
      scoreText: `${Math.round(hit.score * 100)}%`,
      periodText: `${hit.effectiveFrom || '—'} 起 · ${hit.effectiveTo || '长期有效'}`,
      ownRate: activeHit.value?.knowledgeId === hit.knowledgeId,
    })) as unknown as Record<string, unknown>[],
)

const hitColumns: TableColumn[] = [
  { key: 'knowledgeId', title: '知识 ID', width: '74px' },
  { key: 'title', title: '标题' },
  { key: 'version', title: '版本', width: '56px' },
  { key: 'owner', title: '责任人', width: '76px' },
  { key: 'scoreText', title: '相关度', width: '60px' },
  { key: 'action', title: '操作', width: '92px' },
]

const versionColumns: TableColumn[] = [
  { key: 'version', title: '版本', width: '62px' },
  { key: 'status', title: '状态', width: '76px' },
  { key: 'effectiveFrom', title: '生效日', width: '92px' },
  { key: 'submittedAtText', title: '提交时间', width: '92px' },
  { key: 'submittedBy', title: '提交人', width: '86px' },
]

const versionRows = computed<Record<string, unknown>[]>(() =>
  (activeDetail.value?.versions ?? []).map((version) => ({
    version: version.version,
    status: version.status,
    effectiveFrom: version.effectiveFrom || '—',
    submittedAtText: formatIsoMinute(version.submittedAt) || '—',
    submittedBy: version.submittedBy || '—',
    changeId: version.changeId || '基线版本',
  })),
)

function sourceTextOf(detail: KnowledgeDetail | null): string {
  if (!detail?.source) return '—'
  return [detail.source.doc, detail.source.clause, detail.source.page].filter(Boolean).join(' · ')
}

/** 命中表格行 → 类型化对象（模板里不做 TS 断言） */
function hitOf(row: unknown): KnowledgeSearchHit {
  return row as KnowledgeSearchHit
}

/** 载入某条命中的知识原文（详情接口；不补写、不摘要改写） */
async function loadHitDetail(hit: KnowledgeSearchHit): Promise<void> {
  activeHit.value = hit
  detailLoading.value = true
  detailError.value = ''
  try {
    const { data } = await api.knowledge.get(hit.knowledgeId)
    activeDetail.value = data
    // 引用以服务端 `cite` 为准；若详情版本与命中版本不一致，提示「版本已更新」而不是覆盖 cite
    if (data.version && hit.version && data.version !== hit.version) {
      toast.warning(`该知识已更新到 ${data.version}（命中时为 ${hit.version}），请以最新版本为准`)
    }
  } catch (error) {
    activeDetail.value = null
    detailError.value = apiMessage(error)
  } finally {
    detailLoading.value = false
  }
}

async function search(): Promise<void> {
  const keyword = question.value.trim()
  if (!keyword) {
    toast.warning('请先输入要查询的问题（q 必填，≤200 字符）')
    return
  }
  loading.value = true
  searchError.value = ''
  searchDenied.value = null
  try {
    const { data } = await api.knowledge.search({ q: keyword, limit: LIMIT })
    hits.value = data.items
    searchedQuery.value = data.query || keyword
    searched.value = true
    activeDetail.value = null
    activeHit.value = null
    history.value = [
      { query: data.query || keyword, count: data.items.length, at: new Date().toISOString() },
      ...history.value.filter((item) => item.query !== (data.query || keyword)),
    ].slice(0, 6)
    if (data.items[0]) {
      await loadHitDetail(data.items[0])
      toast.success(`命中 ${data.items.length} 条知识 · 首条相关度 ${Math.round(data.items[0].score * 100)}%（引用已带版本）`)
    } else {
      toast.info('知识库暂无出处：本次不生成答案，已提示转人工 / 建议提交知识')
    }
  } catch (error) {
    hits.value = []
    searched.value = true
    searchedQuery.value = keyword
    const denied = deniedOf(error)
    if (denied) searchDenied.value = denied
    else searchError.value = apiMessage(error)
  } finally {
    loading.value = false
  }
}

function usePreset(text: string): void {
  question.value = text
  void search()
}

async function copyAnswer(): Promise<void> {
  const hit = bestHit.value
  const detail = activeDetail.value
  if (!hit || !detail) return
  const text = `${detail.title}\n${detail.content}\n出处：${hit.cite}`
  const ok = await copyText(text)
  if (ok) toast.success('答案已复制（含出处与版本）')
  else toast.error('复制失败，请检查浏览器剪贴板权限')
}

function goScript(): void {
  void router.push(COPILOT_WORKSPACES.script.path)
}

function goBuild(): void {
  void router.push(COPILOT_WORKSPACES.build.path)
}

function goKnowledgeCenter(): void {
  void router.push('/knowledge')
}

onMounted(() => {
  // 首屏不自动检索：避免把「示例问题」的命中当成用户查询结果
  searched.value = false
})
</script>

<template>
  <div class="cop-page" data-page="copilot" data-testid="copilot-knowledge">
    <WPageHeader :eyebrow="`04 · Copilot · ${workspace.label}`" :title="workspace.label" :description="workspace.description">
      <template #actions>
        <WTag tone="n">项目级隔离 · 云栖苑知识域</WTag>
        <WTag tone="g">引用强制溯源</WTag>
        <WButton size="sm" variant="ghost" icon="refresh" :loading="loading" data-testid="kb-search-again" @click="search">
          重新检索
        </WButton>
      </template>
    </WPageHeader>

    <!-- ====================== 提问（GET /knowledge/search） ====================== -->
    <WCard title="提问" subtitle="知识秒查：问题 → 命中知识原文 + 出处（ID/版本/责任人/生效期）">
      <div class="kb-ask">
        <WIcon name="search" :size="15" />
        <input
          v-model="question"
          class="kb-ask__input"
          type="text"
          placeholder="问知识，例如：公摊 / 装修押金 / 车位费"
          data-testid="kb-ask-input"
          @keyup.enter="search"
        />
        <WButton variant="primary" size="sm" :loading="loading" data-testid="kb-search-submit" @click="search">检索</WButton>
      </div>
      <div class="cop-chips kb-presets">
        <button
          v-for="preset in PRESETS"
          :key="preset"
          type="button"
          class="cop-chip"
          :data-testid="`kb-preset-${preset}`"
          @click="usePreset(preset)"
        >
          {{ preset }}
        </button>
      </div>
      <div class="kb-ask-meta">
        <WTag tone="n" plain>检索端点 GET /api/v1/knowledge/search</WTag>
        <WTag tone="n" plain>只回 published 且在生效期内</WTag>
        <WTag tone="a" plain>无命中即拒答，不编造</WTag>
        <WTag tone="v" plain>演示数据（mock 知识库）</WTag>
      </div>
      <p v-if="history.length" class="kb-history" data-testid="kb-search-history">
        本次会话检索：
        <span v-for="item in history" :key="item.query" class="kb-history__item">
          「{{ item.query }}」命中 {{ item.count }} 条 · {{ formatIsoMinute(item.at) }}
        </span>
      </p>
    </WCard>

    <!-- ============ RAG 问答（POST /api/v1/rag/query，与知识页「智能问答」同一组件） ============ -->
    <RagAskPanel variant="embedded" heading="RAG 知识问答" />

    <!-- 加载 / 错误 / 403 / 未检索 -->
    <WCard v-if="loading" title="检索中" data-testid="kb-search-loading">
      <WLoading center label="正在检索项目知识库并校验版本与生效期…" />
    </WCard>

    <WCard v-else-if="searchDenied" title="无权限（403）" data-testid="kb-search-denied">
      <PermissionDenied
        compact
        :required-capability="searchDenied.requiredCapability"
        :required-role="searchDenied.role"
        :trace-id="searchDenied.traceId"
        detail="知识秒查需要 knowledge.read 能力；当前演示角色不具备，页面不会返回任何知识内容。"
      />
    </WCard>

    <WCard v-else-if="searchError" title="检索未完成" data-testid="kb-search-error">
      <p class="kb-note kb-note--warn">
        <WTag tone="r" plain>检索不可用</WTag>
        {{ searchError }}
      </p>
      <WButton size="sm" data-testid="kb-search-retry" @click="search">重试</WButton>
    </WCard>

    <WCard v-else-if="!searched" title="未开始检索" data-testid="kb-search-idle">
      <WEmpty
        icon="search"
        title="输入问题后点「检索」"
        description="没有输入内容时不发起检索，避免空结果误导；也可点上面的示例问题。答案只来自知识库原文并强制带出处。"
      />
    </WCard>

    <!-- ====================== 无命中：拒答 + 转人工 ====================== -->
    <WCard
      v-else-if="!hasHits"
      title="知识库暂无出处"
      :subtitle="`问题：${searchedQuery}`"
      data-testid="kb-search-refusal"
    >
      <template #extra>
        <WTag tone="r" plain>拒答 · 不编造</WTag>
      </template>
      <div class="kb-refusal">
        <b class="kb-refusal__title">知识库暂无出处：已转人工 / 建议提交知识</b>
        <p class="kb-refusal__text">
          「{{ searchedQuery }}」在知识库中没有已发布且在生效期内的条目。按知识秒查红线：
          <b>无出处不得作答</b>，本页不生成任何答案（过期/草稿/待审条目服务端已过滤，不作为可答依据）。
        </p>
        <div class="kb-refusal__actions">
          <WButton size="sm" variant="primary" icon="arrow-right" data-testid="kb-refusal-handoff" @click="goScript">
            转人工处理
          </WButton>
          <WButton size="sm" data-testid="kb-refusal-submit" @click="goBuild">建议提交知识（知识共建）</WButton>
          <WButton size="sm" variant="ghost" data-testid="kb-refusal-retry" @click="search">重试检索</WButton>
        </div>
        <p class="kb-refusal__hint">
          缺口会进入知识运营中心的「缺口 TopN」（服务端未命中 telemetry），由责任人补知识后再纳入检索。
        </p>
      </div>
    </WCard>

    <!-- ====================== 命中：知识原文 + 出处 ====================== -->
    <template v-else>
      <WCard title="答案" :subtitle="`问题：${searchedQuery} · 命中 ${hits.length} 条（按相关度降序，服务端排序）`" data-testid="kb-answer">
        <template #extra>
          <WTag tone="g">引用已绑定</WTag>
          <WTag tone="n" plain>演示数据（mock 知识库）</WTag>
        </template>

        <p v-if="detailLoading" class="kb-note"><WLoading label="正在读取命中知识的原文与版本…" /></p>
        <p v-else-if="searchDenied === null && detailError" class="kb-note kb-note--warn" data-testid="kb-answer-error">
          <WTag tone="r" plain>原文不可用</WTag>{{ detailError }}
          <WButton v-if="bestHit" size="sm" variant="ghost" @click="loadHitDetail(bestHit)">重试</WButton>
        </p>
        <template v-else-if="answerable && activeDetail && bestHit">
          <div class="kb-tags">
            <WTag :tone="'n'">{{ activeDetail.knowledgeId }}</WTag>
            <WTag tone="n">{{ bestHit.version }} 生效中</WTag>
            <WTag tone="v" plain>演示：规则组装，模型调用由 Python 编排层</WTag>
          </div>

          <h2 class="kb-answer-title" data-testid="kb-answer-title">{{ activeDetail.title }}</h2>
          <p class="kb-answer-text" data-testid="kb-answer-content">{{ activeDetail.content }}</p>

          <!-- 引用串由服务端拼装（cite），前端不自行拼接 -->
          <div class="kb-cite" data-testid="kb-answer-cite">
            <WIcon name="book" :size="13" />
            <span>出处：<b>{{ bestHit.cite }}</b></span>
          </div>

          <div class="kb-meta" data-testid="kb-answer-meta">
            <div>
              <span>知识 ID</span>
              <b data-testid="kb-answer-id">{{ activeDetail.knowledgeId }}</b>
            </div>
            <div>
              <span>版本</span>
              <b data-testid="kb-answer-version">{{ bestHit.version }}</b>
            </div>
            <div>
              <span>责任人</span>
              <b data-testid="kb-answer-owner">{{ bestHit.owner }}</b>
            </div>
            <div>
              <span>生效期</span>
              <b data-testid="kb-answer-period">
                {{ bestHit.effectiveFrom || '—' }} 起 · {{ bestHit.effectiveTo || '长期有效' }}
              </b>
            </div>
            <div>
              <span>出处文件</span>
              <b data-testid="kb-answer-source">{{ sourceTextOf(activeDetail) }}</b>
            </div>
            <div>
              <span>相关度（服务端）</span>
              <b data-testid="kb-answer-score">{{ Math.round(bestHit.score * 100) }}%</b>
            </div>
          </div>

          <p class="kb-demo-note" data-testid="kb-answer-demo-note">
            演示口径：本页答案是知识库条款原文（非模型生成）；生产环境的模型调用由 Python 编排层承接，答案仍必须带出处。
          </p>

          <div class="kb-actions">
            <div class="cop-actions-right">
              <WButton size="sm" icon="copy" data-testid="kb-answer-copy" @click="copyAnswer">复制（含出处）</WButton>
              <WButton size="sm" icon="book" data-testid="kb-answer-open" @click="detailOpen = true">查看知识详情</WButton>
              <WButton size="sm" variant="ai" icon="sparkles" data-testid="kb-answer-script" @click="goScript">生成话术</WButton>
              <WButton size="sm" variant="ghost" data-testid="kb-answer-center" @click="goKnowledgeCenter">
                去知识运营中心
              </WButton>
            </div>
          </div>
        </template>
        <p v-else class="kb-note">该命中缺少原文，未生成答案（请重试或转人工）。</p>
      </WCard>

      <!-- 其他候选命中（全部来自服务端；点击切换答案原文） -->
      <WCard title="候选命中" subtitle="全部命中均来自 GET /knowledge/search；点击可切换答案原文" data-testid="kb-hits">
        <WTable :columns="hitColumns" :rows="hitRows" row-key="knowledgeId" dense>
          <template #knowledgeId="{ row }">
            <span class="cop-code" :data-testid="`kb-hit-id-${hitOf(row).knowledgeId}`">
              {{ hitOf(row).knowledgeId }}
            </span>
          </template>
          <template #title="{ row }"><b>{{ hitOf(row).title }}</b></template>
          <template #version="{ value }"><span class="cop-code">{{ value }}</span></template>
          <template #owner="{ value }"><span class="dim">{{ value }}</span></template>
          <template #scoreText="{ value }"><span class="num">{{ value }}</span></template>
          <template #action="{ row }">
            <WButton
              size="sm"
              variant="ghost"
              :data-testid="`kb-hit-open-${hitOf(row).knowledgeId}`"
              @click="loadHitDetail(hitOf(row))"
            >
              看原文
            </WButton>
          </template>
        </WTable>
        <div class="kb-hits__cites" data-testid="kb-hit-cites">
          <span v-for="hit in hits" :key="hit.knowledgeId" class="kb-hits__cite" :data-testid="`kb-hit-cite-${hit.knowledgeId}`">
            {{ hit.cite }}
          </span>
        </div>
      </WCard>
    </template>

    <!-- 知识共建入口（真实缺口由运营侧受理） -->
    <div class="cop-grid-2">
      <WCard title="没查到怎么办" subtitle="不编造：拒答 + 转人工 + 提缺口">
        <ul class="kb-mini">
          <li>① 换个问法：带条款名/位置（如「公摊电费怎么算」）；</li>
          <li>② 仍无命中 → 拒答并转人工，不生成猜测答案；</li>
          <li>③ 把问题提交到知识共建，进入运营缺口池。</li>
        </ul>
        <WButton size="sm" variant="ghost" icon="edit" data-testid="kb-build-entry" @click="goBuild">去知识共建提交</WButton>
      </WCard>
      <WCard title="知识运营中心" subtitle="版本 / 生效期 / 责任人 / 采纳与缺口">
        <ul class="kb-mini">
          <li>知识清单与版本历史、过期/草稿标注（仅运营视图可见）；</li>
          <li>变更提交 → it/admin 审核通过后生效，检索立即读新版本；</li>
          <li>采纳率 / 缺口 TopN / 责任人分布来自服务端统计。</li>
        </ul>
        <WButton size="sm" icon="arrow-right" data-testid="kb-center-entry" @click="goKnowledgeCenter">
          打开知识运营中心
        </WButton>
      </WCard>
    </div>

    <!-- 知识详情弹层（原文 + 出处 + 版本历史） -->
    <WModal v-model:open="detailOpen" title="知识详情（原文 / 出处 / 版本历史）" width="640px">
      <div v-if="activeDetail" class="kb-detail" data-testid="kb-detail-modal">
        <h3 class="kb-answer-title">{{ activeDetail.title }}</h3>
        <div class="kb-tags">
          <WTag tone="n">{{ activeDetail.knowledgeId }}</WTag>
          <WTag tone="n">{{ activeDetail.version }}</WTag>
          <WTag tone="g">{{ activeDetail.status }}</WTag>
          <WTag tone="n" plain>责任人 {{ activeDetail.owner }}</WTag>
        </div>
        <p class="kb-answer-text">{{ activeDetail.content }}</p>
        <p class="kb-note">出处：{{ sourceTextOf(activeDetail) }} · 生效期 {{ activeDetail.effectiveFrom || '—' }} 起</p>
        <p class="kb-note">关联引用：{{ (activeDetail.refs ?? []).join('、') || '—' }}</p>
        <WTable :columns="versionColumns" :rows="versionRows" row-key="version" dense data-testid="kb-detail-versions">
          <template #version="{ value }"><span class="cop-code">{{ value }}</span></template>
          <template #status="{ value }"><WTag tone="n" plain>{{ value }}</WTag></template>
        </WTable>
        <p class="kb-demo-note">引用必须带版本：历史答案按当时版本解释；过期版本不得作为答案依据。</p>
      </div>
      <p v-else class="kb-note"><WLoading label="正在读取知识详情…" /></p>
      <template #footer>
        <WButton size="sm" @click="detailOpen = false">关闭</WButton>
      </template>
    </WModal>
  </div>
</template>

<style scoped>
.kb-ask {
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

.kb-ask:focus-within {
  border-color: var(--c-brand);
  background: var(--c-card);
  box-shadow: var(--sh-ring);
}

.kb-ask__input {
  flex: 1;
  min-width: 0;
  border: 0;
  outline: none;
  background: transparent;
  font-size: var(--fs-sm);
  color: var(--c-ink-2);
}

.kb-presets {
  margin-top: 8px;
}

.kb-ask-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  margin-top: 8px;
}

.kb-history {
  margin-top: 8px;
  font-size: var(--fs-2xs);
  color: var(--c-faint);
  line-height: var(--lh-base);
}

.kb-history__item {
  margin-right: 8px;
}

.kb-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  margin-bottom: 6px;
}

.kb-answer-title {
  font-size: var(--fs-lg);
  font-weight: 600;
  line-height: 1.45;
  color: var(--c-ink);
}

.kb-answer-text {
  margin-top: 6px;
  font-size: var(--fs-sm);
  line-height: 1.75;
  color: var(--c-ink-2);
}

.kb-cite {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 9px;
  padding: 7px 10px;
  border-left: 2px solid var(--c-brand);
  border-radius: 0 var(--rd-sm) var(--rd-sm) 0;
  background: var(--c-brand-soft);
  font-size: var(--fs-2xs);
  color: #0a725f;
}

.kb-meta {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  margin-top: 10px;
  padding: 9px 10px;
  border: 1px solid var(--c-line-2);
  border-radius: var(--rd-md);
  background: var(--c-bg-2);
}

.kb-meta span {
  display: block;
  font-size: 9.5px;
  color: var(--c-faint);
}

.kb-meta b {
  display: block;
  margin-top: 1px;
  font-size: 10.5px;
  font-weight: 500;
  color: var(--c-ink-2);
  word-break: break-all;
}

.kb-demo-note {
  margin-top: 8px;
  padding: 6px 9px;
  border-radius: var(--rd-sm);
  background: var(--c-ai-soft);
  color: #4338ca;
  font-size: var(--fs-2xs);
  line-height: var(--lh-base);
}

.kb-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 10px;
}

.kb-note {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  font-size: var(--fs-2xs);
  color: var(--c-muted);
  line-height: var(--lh-base);
}

.kb-note--warn {
  padding: 7px 10px;
  border-radius: var(--rd-sm);
  background: var(--c-amber-soft);
  color: #9a4b05;
  margin-bottom: 8px;
}

/* ---------- 无命中（拒答） ---------- */
.kb-refusal {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.kb-refusal__title {
  font-size: var(--fs-md);
  font-weight: var(--fw-semibold);
  color: var(--c-ink);
}

.kb-refusal__text {
  font-size: var(--fs-sm);
  line-height: 1.75;
  color: var(--c-ink-2);
}

.kb-refusal__actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.kb-refusal__hint {
  padding: 6px 9px;
  border-radius: var(--rd-sm);
  background: var(--c-amber-soft);
  color: #9a4b05;
  font-size: var(--fs-2xs);
  line-height: var(--lh-base);
}

.kb-hits__cites {
  display: flex;
  flex-direction: column;
  gap: 3px;
  margin-top: 8px;
  font-size: 9.5px;
  color: var(--c-faint);
  font-family: var(--font-mono);
}

.kb-detail {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.kb-mini {
  display: flex;
  flex-direction: column;
  gap: 5px;
  font-size: var(--fs-xs);
  color: var(--c-muted);
  line-height: 1.6;
}

@media (max-width: 1279px) {
  .kb-meta {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
