/**
 * 管理端复制工具（P1-033）
 * ----------------------------------------------------------------
 * 统一走 navigator.clipboard.writeText；权限被拒时退回 execCommand 兜底，
 * 两者都失败则 Toast 明确提示「未授权剪贴板权限」，不留静默失败。
 */
import { useToast } from '@/composables/useToast'

/** 复制文本；返回是否成功（不抛异常） */
export async function copyText(text: string): Promise<boolean> {
  try {
    if (typeof navigator !== 'undefined' && navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
      return true
    }
  } catch {
    // 权限拒绝 / 非安全上下文：继续走兜底
  }

  try {
    if (typeof document === 'undefined') return false
    const area = document.createElement('textarea')
    area.value = text
    area.setAttribute('readonly', '')
    area.style.position = 'fixed'
    area.style.top = '-1000px'
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

/** 带 Toast 回执的复制：成功「已复制 xx」，失败提示权限 */
export function useClipboard() {
  const toast = useToast()

  async function copy(text: string, label = '内容'): Promise<boolean> {
    const ok = await copyText(text)
    if (ok) toast.success(`已复制${label}`)
    else toast.error('复制失败：浏览器未授权剪贴板权限，请手动选中后按 Ctrl+C')
    return ok
  }

  return { copy, copyText }
}
