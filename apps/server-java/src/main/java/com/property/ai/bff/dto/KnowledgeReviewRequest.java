package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * OpenAPI: KnowledgeReviewRequest（P4 W15 / v1.2.0 增量）。
 * 知识变更审核入参（P4 v1.2.0）。`changeId` 必须属于该 `knowledgeId` 且为 `pending_review`，
 * 否则 409 `E-3002`（`details.reason=invalid_transition`）；驳回时 `note` 必填（否则 400 `E-1001`）。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.2.0 严格对齐；仅 DTO，不含业务逻辑。
 * 可选字段以 class 级 {@code @JsonInclude(NON_NULL)} 保证未赋值时不序列化（契约默认不返回该字段）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KnowledgeReviewRequest(
        /** 待审核变更单 ID */
        String changeId,
        /** 审核决定 */
        ReviewDecision decision,
        /** 审核意见（驳回时必填） */
        String note) {
}
