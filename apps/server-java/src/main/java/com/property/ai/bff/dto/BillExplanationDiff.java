package com.property.ai.bff.dto;

import java.math.BigDecimal;

/**
 * OpenAPI: BillExplanationDiff（P3 W10 / v1.1.0 增量）。
 * 与上期账单的差异（amount 由 ERP 给出，AI 不重算；reason 必须可追溯到 knowledgeRefs）
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.1.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record BillExplanationDiff(
        /** 与上期差额（= 本期 - 上期；负数为下降） */
        BigDecimal amount,
        /** 差异原因文案（引用知识条目与版本） */
        String reason) {
}
