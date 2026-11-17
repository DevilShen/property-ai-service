import { reactive, readonly } from 'vue'
import type { ToastItem } from '@/types'

/**
 * 轻量 Toast 状态（模块级单例，无需 Pinia）。
 * 组件内调用 useToast().success('...')，由 AppShell 里的 <WToastHost /> 统一渲染。
 */
const state = reactive<{ items: ToastItem[] }>({ items: [] })
let seed = 0

function dismiss(id: number) {
  const index = state.items.findIndex((item) => item.id === id)
  if (index >= 0) state.items.splice(index, 1)
}

function push(message: string, tone: ToastItem['tone'] = 'info', duration = 2600): number {
  const id = ++seed
  state.items.push({ id, message, tone, duration })
  if (duration > 0) {
    setTimeout(() => dismiss(id), duration)
  }
  return id
}

const api = {
  items: readonly(state.items),
  push,
  dismiss,
  info: (message: string, duration?: number) => push(message, 'info', duration),
  success: (message: string, duration?: number) => push(message, 'success', duration),
  warning: (message: string, duration?: number) => push(message, 'warning', duration),
  error: (message: string, duration?: number) => push(message, 'error', duration),
}

export function useToast() {
  return api
}
