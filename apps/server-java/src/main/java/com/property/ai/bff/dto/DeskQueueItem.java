package com.property.ai.bff.dto;

/**
 * OpenAPI: DeskQueueItem（P4 W15 / v1.2.0 增量）。
 * 坐席队列项（P4 v1.2.0）。`waitingSeconds`/`slaDueAt` 由服务端按 SLA 规则计算，前端只做倒计时展示；
 * `preview` 为脱敏后的最后一句（不含手机号/地址）。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.2.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record DeskQueueItem(
        /** 会话 ID（可回溯 traceId 关联） */
        String conversationId,
        /** 归一房号 */
        String roomCode,
        /** 业主姓名（掩码） */
        String ownerNameMasked,
        /** 来源通道 */
        TicketPushChannel channel,
        /** 已等待秒数 */
        int waitingSeconds,
        /** 首响 SLA 截止时间 */
        String slaDueAt,
        /** 会话状态 */
        DeskConversationStatus status,
        /** 最后一句脱敏预览 */
        String preview) {
}
