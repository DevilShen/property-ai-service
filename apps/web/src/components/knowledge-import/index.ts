/**
 * 知识导入组件统一出口（P6 W34 / task-87；契约 v1.5.0 §12）
 * ---------------------------------------------------------------------------
 * 只导出页面需要的组件与纯函数；
 * - `KnowledgeImportPanel`：知识页「导入」页签（方式卡片/粘贴/上传/预览/任务详情/任务列表）；
 * - `ImportPreview`：预览与提交（切分块勾选 + 元数据 + PII/重复/警告 + 待审/直审）；
 * - `ImportJobTable`：导入任务列表（服务端分页 + 状态筛选）；
 * - `import-copy.ts`：状态/来源/限额/警告的人话映射（数字取自 shared `KNOWLEDGE_IMPORT_LIMITS`）。
 */
export { default as KnowledgeImportPanel } from './KnowledgeImportPanel.vue'
export { default as ImportPreview } from './ImportPreview.vue'
export { default as ImportJobTable } from './ImportJobTable.vue'

export {
  IMPORT_COMMIT_STATUS_LABELS,
  IMPORT_FILE_ACCEPT,
  IMPORT_FILE_TYPE_LABELS,
  IMPORT_LIMIT_TEXT,
  IMPORT_SOURCE_LABELS,
  IMPORT_STATUS_LABELS,
  IMPORT_STATUS_OPTIONS,
  IMPORT_STATUS_SHORT_LABELS,
  IMPORT_STATUS_TONES,
  IMPORT_WARNING_LABELS,
  canCancelImport,
  canCommitImport,
  fileExtOf,
  formatBytes,
  importApiMessage,
  importErrorText,
  isImportInFlight,
  isImportTerminal,
  jobSizeText,
  validateImportFile,
  warningLabel,
} from './import-copy'
