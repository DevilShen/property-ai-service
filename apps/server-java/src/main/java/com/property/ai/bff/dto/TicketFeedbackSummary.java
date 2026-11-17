package com.property.ai.bff.dto;

import java.util.List;

/**
 * OpenAPI: TicketFeedbackSummary（回访评价摘要，v0.3.1）。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v0.3.1 严格对齐；仅 DTO，不含业务逻辑。
 *
 * <p>同时用于 {@code Ticket.feedback}（落库后随详情返回，刷新不丢评分）与
 * {@code TicketFeedbackResult.feedback}（回访成功即时回显）。</p>
 */
public record TicketFeedbackSummary(
        /** 满意度 1—5 分 */
        int score,
        /** 评价标签，如「响应快」「处理干净」 */
        List<String> tags,
        /** 评价备注（不含敏感信息） */
        String note,
        /** 回访时间（ISO 8601） */
        String reviewedAt) {
}
