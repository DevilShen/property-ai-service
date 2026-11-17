/** Copilot 公共工具：剪贴板、CSV 导出、演示数据说明 */
import type { CopilotWorkspaceId, CopilotWorkspaceStatus } from '@/api'
import type { CopilotWorkspaceKey } from '@/types'

export const DEMO_DATA_NOTE = '演示数据：来自原型样例（云栖苑），不接真实接口'

/**
 * P4 W16 口径标注（AGENTS.md：模型调用归 Python 编排层）
 * ---------------------------------------------------------------------------
 * Copilot 8 工作区的结果在当前阶段是**平台规则组装**（`mock:true`），Java 只做编排/落库，
 * **不在 Java/前端直接调模型**；页面必须原样标注这句，不得说成「AI 真实生成」。
 */
export const COPILOT_RULE_NOTE = '演示：规则组装，模型调用由 Python 编排层'

/**
 * P1 设计口径工作区 key → 契约 `CopilotWorkspaceId`（v1.2.0）的 UI 投影
 * ---------------------------------------------------------------------------
 * 说明：`COPILOT_WORKSPACE_ORDER`（前端路由/左栏）是 P1 设计口径的 8 个工作区，
 * 与契约 `GET /copilot/workspaces` 的 8 个 `CopilotWorkspaceId` **名称不完全一一对应**
 * （例：design `daily` 同时消费契约 `digest` + `report`；design `build` 属知识共建，
 * 复用契约 `knowledge`；design `history` 对应契约 `handoff`）。
 * shared 的 `COPILOT_WORKSPACE_ID_BY_DESIGN_KEY` 是单向 P1 枚举映射（key 集不同），
 * 因此这里只做**只读投影**（一个设计工作区可消费多个契约工作区状态），
 * 不新增契约枚举、不改 shared/OpenAPI。
 */
export const WORKSPACE_API_IDS: Readonly<Record<CopilotWorkspaceKey, readonly CopilotWorkspaceId[]>> = {
  knowledge: ['knowledge'],
  script: ['script'],
  summary: ['summary'],
  profile: ['portrait'],
  daily: ['digest', 'report'],
  group: ['group'],
  build: ['knowledge'],
  history: ['handoff'],
}

/** 契约工作区状态 → 中文人话（`available` 已接真实数据 / `degraded` 部分降级 / `planned` 未上线） */
export const COPILOT_STATUS_LABELS: Readonly<Record<CopilotWorkspaceStatus, string>> = {
  available: '已接真实数据',
  degraded: '部分降级',
  planned: '未上线',
}

/** 契约工作区状态 → WTag 语气（`available` 绿 / `degraded` 琥珀 / `planned` 中性） */
export const COPILOT_STATUS_TONES: Readonly<Record<CopilotWorkspaceStatus, 'g' | 'a' | 'n'>> = {
  available: 'g',
  degraded: 'a',
  planned: 'n',
}

/** 复制到剪贴板；失败时回退 execCommand，返回是否成功 */
export async function copyText(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text)
    return true
  } catch {
    try {
      const area = document.createElement('textarea')
      area.value = text
      area.style.position = 'fixed'
      area.style.opacity = '0'
      document.body.appendChild(area)
      area.select()
      const ok = document.execCommand('copy')
      area.remove()
      return ok
    } catch {
      return false
    }
  }
}

/** 生成并下载 UTF-8 CSV（带 BOM，Excel 直接打开不乱码） */
export function downloadCsv(filename: string, header: string[], rows: Array<Array<string | number>>): void {
  const escape = (value: string | number) => `"${String(value).replace(/"/g, '""')}"`
  const content = [header, ...rows].map((row) => row.map(escape).join(',')).join('\r\n')
  const blob = new Blob([`\ufeff${content}`], { type: 'text/csv;charset=utf-8' })
  downloadBlob(blob, filename)
}

/** 通用 Blob 下载（真实触发浏览器下载；用于证据包 JSON/CSV 与 CSV 导出） */
export function downloadBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  URL.revokeObjectURL(url)
}

/** 演示用延时（模拟检索/生成耗时，展示 loading 态） */
export function wait(ms = 700): Promise<void> {
  return new Promise((resolve) => {
    setTimeout(resolve, ms)
  })
}
