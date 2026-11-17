package com.property.ai.bff.dto;

import java.util.List;

/**
 * OpenAPI: TicketFeedbackRequest（回访评价请求（写操作，必须带 Idempotency-Key））。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v0.3.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record TicketFeedbackRequest(
        /** 满意度 1—5 分 */
        int score,
        List<String> tags,
        String note) {
}
