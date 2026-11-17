package com.property.ai.bff.dto;

/**
 * OpenAPI: DeskReleaseResult（P4 W15 / v1.2.0 增量）。
 * 释放结果（P4 v1.2.0）：释放后会话回队列或结束，`status` 回带新状态。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.2.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record DeskReleaseResult(
        /** 会话 ID */
        String conversationId,
        /** 释放时间 */
        String releasedAt,
        /** 释放后状态（waiting=回队列 / closed=结束） */
        DeskConversationStatus status) {
}
