package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * OpenAPI: FeeCatalogChangeReviewRequest（P4 W15 / v1.2.0 增量）。
 * 费项口径变更审核入参（P4 v1.2.0）：仅 it/admin；驳回需 `note`（否则 400 `E-1001`）。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.2.0 严格对齐；仅 DTO，不含业务逻辑。
 * 可选字段以 class 级 {@code @JsonInclude(NON_NULL)} 保证未赋值时不序列化（契约默认不返回该字段）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FeeCatalogChangeReviewRequest(
        /** 审核决定 */
        ReviewDecision decision,
        /** 审核意见（驳回时必填） */
        String note) {
}
