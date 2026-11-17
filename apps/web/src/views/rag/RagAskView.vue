<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { WButton, WPageHeader, WTag } from '@/components'
import { RagAskPanel } from '@/components/rag'
import { capabilityLabel } from '@/auth/capabilities'
import { useAuthStore } from '@/stores/auth'
import { ROLE_LABELS } from '@/app/roles'

/**
 * 知识秒查页 `/rag`（P6 W33 / task-81，**非 12 屏导航页**）
 * ===========================================================================
 * 为什么单独开一条非导航路由（lead 授权，**不改 `app/screens.ts` 12 屏基线**）：
 * - `POST /api/v1/rag/query` 的能力 `rag.query` 全角色都有（owner/steward/agent/supervisor/it/admin），
 *   但 `/knowledge` 知识运营屏在 P1 可见性矩阵里只对 staff/it/admin 开放；
 * - 业主/坐席也需要能直接问知识（引用 + 拒答 + 转人工），因此把「问答」本体放到 `/rag`，
 *   与「知识运营（列表/版本/变更/审核）」解耦；知识页「智能问答」页签与 Copilot 知识工作区
 *   继续用同一 `RagAskPanel` 组件（一处实现，多处复用）。
 *
 * 口径：
 * - 路由守卫按 `rag.query` 能力码兜底（`meta.requiresCapability`）；**前端不是安全边界**，
 *   真实 401/403 由 Java BFF 判定，403 时面板渲染 PermissionDenied；
 * - 页面不新增任何数据口径：answer / sources / mode / reranked / degraded / refusalReason
 *   全部来自服务端 `RagQueryResult`，`[n]` 只做高亮与定位；
 * - 演示环境为 `retrieval_only` / `rules` 链路（LLM 生成不可用），页面如实标注模式与降级，
 *   不把仅检索/规则兜底说成生成答案（AGENTS.md NEVER #11）。
 */
const router = useRouter()
const auth = useAuthStore()

const roleLabel = computed(() => (auth.role ? ROLE_LABELS[auth.role] : '未登录'))
const canKnowledgeOps = computed(() => auth.hasCapability('knowledge.read'))
const canRagHealth = computed(() => auth.hasCapability('rag.health'))
</script>

<template>
  <div class="w-page rag-view" data-page="rag" data-testid="page-root">
    <WPageHeader
      eyebrow="P6 · RAG"
      title="知识秒查"
      description="问一句 → 混合检索 + 引用校验 → 答案必须带 [n] 出处；无依据即拒答并转人工，不编造答案。"
    >
      <template #actions>
        <WTag tone="g" plain :data-role="auth.role ?? ''">当前演示角色：{{ roleLabel }}</WTag>
        <WTag tone="n" plain>{{ capabilityLabel('rag.query') }}</WTag>
        <WButton
          v-if="canRagHealth"
          size="sm"
          icon="activity"
          data-testid="rag-view-health-entry"
          @click="router.push('/knowledge')"
        >
          RAG 健康（知识页）
        </WButton>
        <WButton
          v-if="canKnowledgeOps"
          size="sm"
          variant="ghost"
          icon="book"
          data-testid="rag-view-knowledge-entry"
          @click="router.push('/knowledge')"
        >
          知识运营中心
        </WButton>
      </template>
    </WPageHeader>

    <RagAskPanel variant="page" heading="知识秒查" />

    <p class="rag-view__note" data-testid="rag-view-note">
      说明：本页为非导航路由（`/rag`），只承载「问答」；知识清单 / 版本 / 变更审核 / 采纳看板仍在
      `/knowledge`（仅 staff、it/admin 可见）。能力门槛 `rag.query` 由服务端强制，页面不据此展示越权数据；
      当前链路为演示态（编排层仅检索/规则兜底 + mock 知识库），不得当作生产生成能力。
    </p>
  </div>
</template>

<style scoped>
.rag-view {
  min-width: 0;
}

.rag-view__note {
  margin-top: 2px;
  font-size: var(--fs-2xs);
  color: var(--c-faint);
  line-height: var(--lh-base);
}
</style>
