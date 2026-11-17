package com.property.ai.bff.dto;

import java.math.BigDecimal;

/**
 * OpenAPI: BillExplanationLine（P3 W10 / v1.1.0 增量）。
 * 账单解释的单费项明细（分摊基数 + 计费规则可追溯；金额 ERP 口径）
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.1.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record BillExplanationLine(
        /** 标准费项编码（PROPERTY_FEE / PUBLIC_ENERGY / PARKING_FEE / LATE_FEE） */
        String itemCode,
        /** 费项展示名 */
        String name,
        BigDecimal amount,
        /** 分摊基数 / 计价依据（如建筑面积、车位号、公摊电量） */
        String base,
        /** 计费规则 / 算式（展示口径，供业主核对） */
        String rule) {
}
