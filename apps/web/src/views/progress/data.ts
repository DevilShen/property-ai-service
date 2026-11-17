import type { IconName } from '@/types'

/**
 * 03 工单进度 · 演示与文案（P2 起数据来自真实 API）
 *
 * 数据边界（契约 v0.3.0）：
 * - 工单号 / 7 节点时间轴 / 派单班组 / ETA / 版本号 → `GET /api/v1/tickets/{ticketNo}`；
 * - 评价 / 改约 / 催办 → `POST /tickets/{ticketNo}/feedback` 与 `/transition`（写操作幂等）；
 * - 本文件只保留「回访话术、可预约时段、知识沉淀建议、AI/人分工讲解」等**演示文案**，
 *   不再保存任何假工单号、假时间轴或假金额。
 */

/** 无 `?ticket=` 深链时的默认演示单（mock-erp 种子单，DISPATCHED） */
export const DEMO_TICKET_NO = 'WO-20261114-0832'

/** 改约可选时段（演示选项；提交后由 ERP 回写 `slot`） */
export const RESCHEDULE_SLOTS = ['今天 18:00—19:00', '明天上午 09:00—11:00', '明天下午 14:00—16:00']

export const REVIEW = {
  script:
    '“您好，我是云栖苑智能管家。本次报修已完工，想请您用 10 秒评价本次服务，也可以直接说还有哪里没处理好。”',
  note: '回访只收集评价与遗留问题，不涉及赔偿与责任认定。',
}

export const KNOWLEDGE_SUGGESTION =
  '近 30 天“卫生间渗漏”同类工单中多数为角阀/软管老化。建议新增《卫生间渗漏初判与楼上协调话术》，减少二次上门。（演示建议，待知识运营复核）'

export const AI_DID: Array<{ text: string; icon: IconName }> = [
  { text: '5 问结构化受理，自动带出房号与业主关系', icon: 'chat' },
  { text: '按位置 + 现象匹配班组，生成工单字段（由 ERP 回执为准）', icon: 'workflow' },
  { text: '接单 / 出发 / 完工节点以 ERP 回写为准，不做本地推测', icon: 'bell' },
  { text: '完工后发起回访，评价结论写回平台（不改写工单结论）', icon: 'clock' },
]

export const HUMAN_DID = [
  '上门维修、更换角阀、处理验收',
  '填写现场处理结论（AI 不代写定责）',
  '疑难工单改派与业主沟通',
]

export interface ProgressQualityItem {
  label: string
  value: string
}

/** 时间轴左侧的质量/元数据口径说明（数值由 API 响应在页面填充） */
export const QUALITY_NOTES: string[] = [
  '节点时间来自 ERP 审计回写（`nodes`），前端不猜测缺失状态',
  '状态文案使用契约 7 节点 `TICKET_STATUS_LABELS`',
  '评价仅 `COMPLETED` 可提交，成功后工单进入 `REVIEWED`',
]
