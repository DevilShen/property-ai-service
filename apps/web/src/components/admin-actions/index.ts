/**
 * 管理端动作弹层统一出口（P1-033）
 * ----------------------------------------------------------------
 * 六视图（费用/映射/编排/回溯/知识/看板）只从这里导入，避免深层相对路径；
 * 新增管理端动作时先在这里登记，杜绝各页再写一套内联弹层。
 */
export { default as AdminModal } from './AdminModal.vue'
export { default as FilterModal } from './FilterModal.vue'
export { default as ExportModal } from './ExportModal.vue'
export { default as TrialRunModal } from './TrialRunModal.vue'
export { default as DegradeModal } from './DegradeModal.vue'
export { default as EvidenceModal } from './EvidenceModal.vue'
export { default as KnowledgeUpsertModal } from './KnowledgeUpsertModal.vue'
export { default as CollectionTaskModal } from './CollectionTaskModal.vue'

export { buildCsvFilename, csvCell, csvWithBom, downloadCsv, serializeCsv } from './csv'
export { copyText, useClipboard } from './clipboard'
export type * from './types'
