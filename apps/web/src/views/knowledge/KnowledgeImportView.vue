<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { WButton, WPageHeader, WTag, useToast } from '@/components'
import { KnowledgeImportPanel } from '@/components/knowledge-import'
import { canAccess, firstAllowedPath } from '@/app/permissions'
import { ROLE_LABELS } from '@/app/roles'
import { capabilityLabel, roleHasCapability } from '@/auth/capabilities'
import { useAuthStore } from '@/stores/auth'
import { useAppStore } from '@/stores/app'

/**
 * 知识导入独立页 `/knowledge/import`（P6 W34 / task-88，**非 12 屏导航页**）
 * ===========================================================================
 * 为什么需要这条非导航路由（沿用 `/complaints`、`/events`、`/rag` 的先例）：
 * - 能力 `knowledge.import` 的允许角色是 steward/agent/supervisor/it/admin（`auth/capabilities.ts`），
 *   但 12 屏可见性矩阵里 `/knowledge` 只对 steward/supervisor/it/admin 开放 →
 *   **agent 有导入能力，却在界面上点不到入口**（改矩阵会动 12 屏基线，本次不动）；
 * - 因此把「导入」本体放到按能力码守卫的 `/knowledge/import`：与知识页「导入」页签
 *   渲染**同一个** `KnowledgeImportPanel`（一处实现、同一套 API，不复制实现、不新增数据口径）；
 * - 知识页「导入」页签保留（staff/it/admin 现状不变），页头额外提供跳本页的入口；RAG 拒答卡
 *   「补充这条知识」直接跳本页并预填 gap（`?gap=<问题>`，来源 badcase）。
 *
 * 口径（AGENTS.md NEVER #9/#11）：
 * - 路由 `meta.requiresCapability='knowledge.import'` 只做**前端入口兜底**，不是安全边界：
 *   真实 403 由 Java BFF 角色 × 能力矩阵判定（`E-2001` + `details.requiredCapability`）；
 * - 页面不伪造知识：任务/预览块/警告/重复/失败原因全部来自服务端 imports 端点；
 * - 不新增侧栏菜单（导航仍由 `app/screens.ts` 的 12 屏派生），本页只作为「可直达的入口」。
 */
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const app = useAppStore()
const toast = useToast()

const roleLabel = computed(() => (auth.role ? ROLE_LABELS[auth.role] : '未登录'))
/** 知识运营屏（12 屏里的 `/knowledge`）是否对当前角色可见——决定「返回」与「打开运营视图」的落点 */
const canKnowledgeOps = computed(() => canAccess(app.currentRole, '/knowledge'))

/**
 * 返回口径（不把 agent 送回 `/forbidden`）：
 * 1) 能看到知识运营屏 → 回知识页「导入」页签（staff/it/admin 的原有路径）；
 * 2) 看不到但能问知识（agent）→ 回知识秒查 `/rag`（它进入本页的常态来源）；
 * 3) 兜底 → 该角色第一个可见页。
 */
const backTarget = computed<{ path: string; label: string }>(() => {
  const role = app.currentRole
  if (canKnowledgeOps.value) return { path: '/knowledge?tab=import', label: '知识页「导入」页签' }
  if (roleHasCapability(role, 'rag.query')) return { path: '/rag', label: '知识秒查' }
  return { path: firstAllowedPath(role), label: '可访问首页' }
})

/** RAG 拒答缺口预填（`/knowledge/import?gap=<问题>`）：标题=问题、来源 badcase，由面板统一消费 */
const gapPrefill = ref<{ question: string; sourceType: 'badcase' } | null>(null)

function queryText(value: unknown): string {
  if (typeof value === 'string') return value
  if (Array.isArray(value) && typeof value[0] === 'string') return value[0]
  return ''
}

/** gap 只作为预填文本（≤200 字符，与面板/服务端上限一致），不作为查询/写入依据 */
function applyGapQuery(): void {
  const gap = queryText(route.query.gap).trim()
  gapPrefill.value = gap ? { question: gap.slice(0, 200), sourceType: 'badcase' } : null
}

watch(() => route.query, applyGapQuery, { immediate: true })

/**
 * 面板内的「打开知识运营视图 / 打开知识详情」：知识运营屏对 agent 不可见，
 * 此时不跳 `/forbidden`（死路），改为如实说明「去哪个角色看、本页能看什么」。
 */
function openKnowledge(knowledgeId?: string): void {
  const id = (knowledgeId ?? '').trim()
  if (!canKnowledgeOps.value) {
    toast.info(
      `导入已生成${id ? `知识 ${id}` : '知识'}：知识运营视图（/knowledge）仅对 steward/supervisor/it/admin 开放；` +
        '当前角色可在本页任务列表查看任务状态与提交回执（待审知识需 it/admin 审核后才发布/可检索）',
    )
    return
  }
  toast.info(
    id
      ? `知识 ${id} 已生成：正在打开知识运营视图，可在知识清单按编号检索`
      : '正在打开知识运营视图：可在知识清单按标题检索新导入的知识（待审需 it/admin 审核）',
  )
  void router.push({ path: '/knowledge', query: { tab: 'ops' } })
}

function goBack(): void {
  void router.push(backTarget.value.path)
}
</script>

<template>
  <div class="w-page kimp-view" data-page="knowledge-import" data-testid="page-root">
    <WPageHeader
      eyebrow="P6 · 知识导入"
      title="知识导入"
      description="粘贴文本 / 上传文件 / 批量 ZIP → 服务端解析切分预览 → 确认提交（默认待审；it/admin 可直审）。本页为非导航路由（/knowledge/import），按能力 knowledge.import 准入，不新增侧栏菜单。"
    >
      <template #actions>
        <WTag tone="g" plain :data-role="auth.role ?? ''">当前演示角色：{{ roleLabel }}</WTag>
        <WTag tone="n" plain>{{ capabilityLabel('knowledge.import') }}</WTag>
        <WButton size="sm" variant="ghost" data-testid="kimp-page-back" @click="goBack">
          返回{{ backTarget.label }}
        </WButton>
      </template>
    </WPageHeader>

    <!-- 与知识页「导入」页签同一组件、同一 API（不复制实现） -->
    <KnowledgeImportPanel :prefill="gapPrefill" @open-knowledge="openKnowledge" />

    <p class="kimp-view__note" data-testid="kimp-page-note">
      导入来源：粘贴文本（sourceType=paste/badcase）· 单文件 txt/md/docx/pdf（sourceType=file）·
      批量 ZIP（sourceType=batch，需 knowledge.import.batch）。提交默认进入
      <code>pending_review</code>，需 it/admin 审核通过后变成 <code>published</code> 才会被检索/作答引用；
      直审（approved）仅 it/admin。接口：<code>POST /api/v1/knowledge/imports</code> 系列（契约 v1.5.0 §12）；
      任务/原文为演示进程内存储（重启回滚），不接真实知识库。
    </p>
  </div>
</template>

<style scoped>
.kimp-view {
  min-width: 0;
}

.kimp-view__note {
  margin-top: 2px;
  font-size: var(--fs-2xs);
  color: var(--c-faint);
  line-height: var(--lh-base);
}

.kimp-view__note code {
  padding: 0 3px;
  border-radius: var(--rd-xs);
  background: var(--c-bg-2);
  font-family: var(--font-mono);
  font-size: 9.5px;
  color: var(--c-ink-2);
}
</style>
