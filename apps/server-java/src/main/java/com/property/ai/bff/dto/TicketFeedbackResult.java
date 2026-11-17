package com.property.ai.bff.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * OpenAPI: TicketFeedbackResult（回访评价结果（成功后 REVIEWED））。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v0.3.1 严格对齐；仅 DTO，不含业务逻辑。
 *
 * <p>v0.3.1 增量：末尾追加可选 {@code feedback}（与 {@code Ticket.feedback} 同构，便于前端统一刷新）。
 * 保留 8 参数旧构造重载；{@code feedback} 为 null 时不序列化，W5 响应形状不变。</p>
 */
public record TicketFeedbackResult(
        String ticketNo,
        TicketStatus status,
        String statusText,
        int score,
        List<String> tags,
        boolean supervisorAlert,
        String reviewedAt,
        boolean mock,
        /** v0.3.1 增量：回访结果回显 */
        TicketFeedbackSummary feedback) {

    /** P2 W5 兼容构造（无 feedback）。 */
    public TicketFeedbackResult(
            String ticketNo,
            TicketStatus status,
            String statusText,
            int score,
            List<String> tags,
            boolean supervisorAlert,
            String reviewedAt,
            boolean mock) {
        this(ticketNo, status, statusText, score, tags, supervisorAlert, reviewedAt, mock, null);
    }

    /** v0.3.1 增量字段：未回访时省略，保持 W5 响应形状。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Override
    public TicketFeedbackSummary feedback() {
        return feedback;
    }
}
