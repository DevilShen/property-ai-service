package com.property.ai.bff.dto;

/**
 * OpenAPI: FeeCatalogChangeRequest（P4 W15 / v1.2.0 增量）。
 * 费项口径变更提交入参（P4 v1.2.0）：`newFormula`/`reason` 必填；提交后 `pending_review`，
 * 只有 it/admin 审核通过才生效（**不直接改计费**，避免误改账单）。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.2.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record FeeCatalogChangeRequest(
        /** 费项编码（字典项） */
        String feeItemCode,
        /** 新计费公式 */
        String newFormula,
        /** 变更原因 */
        String reason) {
}
