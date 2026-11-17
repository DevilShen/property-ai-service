/**
 * 文案与状态统一出口（P1-036）
 * ---------------------------------------------------------------------------
 * 页面只从这里取文案，不再各写一份：
 * - `state-copy`：空 / 加载 / 错误 / 降级 四态统一句式 + 每屏空态 / 降级映射；
 * - `screen-copy`：12 屏面向用户说明 + 四态组合（标题仍来自 `@/app/screens`）；
 * - `action-copy`：操作回执句式（成功 / 失败 / 演示）+ Toast 语气规则 + 17 类错误码人话。
 *
 * 用法示例：
 *   import { emptyCopy, successReceipt, errorReceipt, DEMO_NOTE } from '@/content'
 */
export * from './state-copy'
export * from './screen-copy'
export * from './action-copy'
