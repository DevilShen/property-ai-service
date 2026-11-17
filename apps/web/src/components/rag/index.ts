/**
 * RAG 前端组件出口（P6 W33 / task-81）
 * ===========================================================================
 * 页面统一从这里取 RAG 组件与文案映射：
 * - `RagAskPanel`：知识问答（answer + `[n]` 引用 + 来源卡片 + 拒答 + 五态 + 模式/降级徽标）；
 * - `RagHealthPanel`：RAG 链路健康（组件状态表；仅 it/admin 可见）；
 * - `rag-copy`：模式/拒答/降级/健康的人话映射（纯函数，便于单测与 e2e 对拍口径）。
 */
export { default as RagAskPanel } from './RagAskPanel.vue'
export { default as RagHealthPanel } from './RagHealthPanel.vue'
export * from './rag-copy'
