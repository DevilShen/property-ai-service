package com.property.ai.bff.dto;

import java.math.BigDecimal;

/**
 * OpenAPI: BillItem（账单费项（展示口径））。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v0.3.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record BillItem(
        String label,
        BigDecimal amount,
        String note,
        String itemCode,
        String knowledgeId) {
}
