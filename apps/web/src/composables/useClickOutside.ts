import { onBeforeUnmount, type Ref } from 'vue'

/**
 * 点击外部关闭下拉/浮层。
 * 监听 document click 与 Escape，卸载时移除监听。
 */
export function useClickOutside(target: Ref<HTMLElement | null>, handler: () => void) {
  function onDocumentClick(event: MouseEvent) {
    const el = target.value
    if (el && !el.contains(event.target as Node)) handler()
  }

  function onKeydown(event: KeyboardEvent) {
    if (event.key === 'Escape') handler()
  }

  if (typeof document !== 'undefined') {
    document.addEventListener('click', onDocumentClick)
    document.addEventListener('keydown', onKeydown)
  }

  onBeforeUnmount(() => {
    if (typeof document === 'undefined') return
    document.removeEventListener('click', onDocumentClick)
    document.removeEventListener('keydown', onKeydown)
  })
}
