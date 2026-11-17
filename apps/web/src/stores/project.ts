import { computed, ref, watch } from 'vue'
import { defineStore } from 'pinia'
import { api, ApiError, errorNextStep, errorText, readProjectScope, writeProjectScope } from '@/api'
import type { Project } from '@/api/contract'
import { DEMO_PROJECTS, PROJECT_STATUSES } from '@/api/contract'
import { useAuthStore } from '@/stores/auth'

/**
 * 项目上下文 store（P5 W19 多项目隔离，契约 v1.3.0 §14 / 架构 §24.7）
 * ===========================================================================
 * 职责：
 * 1) `load()`：读 `GET /api/v1/projects`（可见项目：owner/staff 仅本项目，it/admin 全部 3 个）；
 * 2) `select(projectId)`：切换当前项目 → 写 `localStorage['property.project']`
 *    （请求层 `withProjectScope` 据此给 rooms/bills/tickets/complaints/audit 等 GET 追加 `?projectId=`），
 *    切换后由 `AppShell` 的 `RouterView :key` 强制重挂载当前业务页 → 全部重新拉取（不串数据）；
 * 3) owner / steward / agent / supervisor（`Actor.projectId` 固定）只有 1 个可见项目 → `canSwitch=false`，
 *    页面不展示切换下拉（服务端仍按 actor 项目强制数据范围，前端隐藏不是安全边界）；
 * 4) 角色切换（演示账号切换）后自动重新加载并校正当前项目：若原项目对新角色不可见，
 *    回落到新角色的本项目（`Actor.projectId`）或首个可见项目。
 *
 * 演示口径：3 个项目来自 shared `DEMO_PROJECTS`（P-YQY 云栖苑 / P-JZY 金竹苑 / P-LVY 绿苑），
 * **演示数据，不是真实组织架构/租户**（AGENTS.md NEVER #11），页面须标注「演示项目」。
 */

/** 项目状态的展示口径（与契约 `ProjectStatus` 一一对应，不新造枚举） */
export interface ProjectStatusMeta {
  label: string
  /** 顶栏/侧栏圆点色：active 绿 / gray 琥珀 / archived 灰 */
  dot: 'active' | 'gray' | 'archived'
}

export const PROJECT_STATUS_META: Record<(typeof PROJECT_STATUSES)[number], ProjectStatusMeta> = {
  active: { label: '运行中', dot: 'active' },
  gray: { label: '灰度中', dot: 'gray' },
  archived: { label: '已归档', dot: 'archived' },
}

/** 项目副标题（户数来自 API `roomCount`，不在此处编造） */
export function projectMetaText(project?: Project | null): string {
  if (!project) return '演示项目 · 数据来自 GET /api/v1/projects'
  return `${project.roomCount} 户 · 主连接器 ${project.connectorId} · 演示项目`
}

export const useProjectStore = defineStore('project', () => {
  const auth = useAuthStore()

  /** 可见项目（`GET /projects` 响应；加载前为空数组） */
  const projects = ref<Project[]>([])
  const loading = ref(false)
  const loaded = ref(false)
  const error = ref<{ code: string; message: string; next: string } | null>(null)
  /** 当前项目 ID（初值取持久化值；加载后按可见列表校正） */
  const currentProjectId = ref<string>(readProjectScope())

  /** 当前项目对象（未加载/不在可见列表 → null，页面用 `currentProjectOrDemo` 兜底展示） */
  const currentProject = computed<Project | null>(() => {
    const hit = projects.value.find((item) => item.projectId === currentProjectId.value)
    return hit ?? projects.value[0] ?? null
  })

  /**
   * 展示兜底：列表未返回时用 shared `DEMO_PROJECTS` 中同 ID 的演示项目（仅为展示，
   * 不代表已从 API 读到；`mockFallback=true` 让页面可标注「本地演示兜底」）。
   */
  const currentProjectOrDemo = computed<Project | null>(() => {
    if (currentProject.value) return currentProject.value
    const fallbackId = currentProjectId.value || auth.actor?.projectId || ''
    return DEMO_PROJECTS.find((item) => item.projectId === fallbackId) ?? DEMO_PROJECTS[0] ?? null
  })

  /** 展示用是否走了本地演示兜底（API 未返回任何项目时） */
  const usingDemoFallback = computed(() => !currentProject.value && projects.value.length === 0)

  /** 是否允许切换（仅 it/admin 多个项目；owner/staff 只有本项目 → 不显示下拉） */
  const canSwitch = computed(() => projects.value.length > 1)

  const currentStatusMeta = computed<ProjectStatusMeta>(() => {
    const status = currentProjectOrDemo.value?.status
    return (status && PROJECT_STATUS_META[status]) || PROJECT_STATUS_META.active
  })

  const currentLabel = computed(() => currentProjectOrDemo.value?.name ?? '未选择项目')
  const currentMetaText = computed(() => projectMetaText(currentProjectOrDemo.value))

  /** 当前项目 ID（渲染用：不含空值；空 → 空串表示「未确定」） */
  const activeProjectId = computed(() => currentProjectOrDemo.value?.projectId ?? '')

  function persist(projectId: string): void {
    writeProjectScope(projectId)
  }

  /**
   * 校正当前项目：优先保留原选择（仍在可见列表内），否则用 actor 本项目，最后回落首个可见项目。
   * `actorProjectId` 来自登录态 `Actor.projectId`（owner/staff 有值；it/admin 为 undefined）。
   */
  function reconcile(): void {
    const visible = projects.value
    if (visible.length === 0) return
    const stillVisible = visible.some((item) => item.projectId === currentProjectId.value)
    if (stillVisible) {
      persist(currentProjectId.value)
      return
    }
    const actorProjectId = auth.actor?.projectId ?? ''
    const preferred =
      visible.find((item) => item.projectId === actorProjectId) ?? visible[0]
    currentProjectId.value = preferred?.projectId ?? ''
    persist(currentProjectId.value)
  }

  /** 读可见项目列表（幂等；`force` 强制重新拉取）；失败保留旧数据并暴露人话错误 */
  async function load(force = false): Promise<void> {
    if (loading.value) return
    if (loaded.value && !force) return
    loading.value = true
    error.value = null
    try {
      const { data } = await api.project.list()
      projects.value = data
      loaded.value = true
      reconcile()
    } catch (caught) {
      const apiError = caught instanceof ApiError ? caught : null
      const code = apiError?.code ?? 'E-9001'
      error.value = { code, message: errorText(code), next: errorNextStep(code) }
    } finally {
      loading.value = false
    }
  }

  /**
   * 切换项目（仅当目标在可见列表内，避免越权入口）：
   * 返回是否切换成功；调用方负责 Toast 反馈与页面重挂载（AppShell 自动跟随）。
   */
  function select(projectId: string): boolean {
    if (!projectId || projectId === currentProjectId.value) return false
    if (!projects.value.some((item) => item.projectId === projectId)) return false
    currentProjectId.value = projectId
    persist(projectId)
    return true
  }

  /** 清空（登出/切换演示账号前调用；避免把上一个角色的项目带进新会话） */
  function reset(): void {
    projects.value = []
    loaded.value = false
    error.value = null
    currentProjectId.value = auth.actor?.projectId ?? ''
    persist(currentProjectId.value)
  }

  /**
   * 角色变化（演示账号切换/登录）→ 重新拉取可见项目并校正当前项目。
   * 注意：不在此处 watch token 过期（401 出口由 client 负责），只关心角色身份变化。
   */
  watch(
    () => auth.role,
    (next, prev) => {
      if (next === prev) return
      // 角色切换：清掉旧角色的项目缓存，重新按新角色可见范围加载
      projects.value = []
      loaded.value = false
      currentProjectId.value = auth.actor?.projectId ?? ''
      persist(currentProjectId.value)
      if (next) void load(true)
    },
  )

  return {
    projects,
    loading,
    loaded,
    error,
    currentProjectId,
    activeProjectId,
    currentProject,
    currentProjectOrDemo,
    currentMetaText,
    currentLabel,
    currentStatusMeta,
    usingDemoFallback,
    canSwitch,
    load,
    select,
    reset,
    reconcile,
  }
})
