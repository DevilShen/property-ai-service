import type { ProjectMeta, ProjectStatus } from '@/types'

/**
 * 项目切换占位数据。云栖苑户数来自原型演示口径（2,000 户），
 * 其余项目仅演示切换，不编造指标。
 */
export const PROJECTS: ProjectMeta[] = [
  { id: 'yunqi', name: '云栖苑', status: 'online', meta: '2,000 户 · 演示项目' },
  { id: 'yunqi-2', name: '云栖苑二期', status: 'gray', meta: '灰度中 · 待能力探测' },
  { id: 'yunqi-biz', name: '云栖苑商办', status: 'planned', meta: '规划中 · 未接入 ERP' },
]

export const PROJECT_STATUS_LABELS: Record<ProjectStatus, string> = {
  online: '运行中',
  gray: '灰度中',
  planned: '规划中',
}

export const PROJECT_STATUS_TONES: Record<ProjectStatus, 'g' | 'a' | 'n'> = {
  online: 'g',
  gray: 'a',
  planned: 'n',
}

export const DEFAULT_PROJECT_ID = PROJECTS[0]?.id ?? 'yunqi'
