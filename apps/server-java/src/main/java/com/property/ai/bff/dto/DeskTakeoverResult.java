package com.property.ai.bff.dto;

/**
 * OpenAPI: DeskTakeoverResult（P4 W15 / v1.2.0 增量）。
 * 接管结果（P4 v1.2.0）：返回接管坐席与状态，前端据此切到会话面板。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.2.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record DeskTakeoverResult(
        /** 会话 ID */
        String conversationId,
        /** 接管坐席（账号/工号，掩码） */
        String agentId,
        /** 接管时间 */
        String takenAt,
        /** 接管后状态 */
        DeskConversationStatus status) {
}
