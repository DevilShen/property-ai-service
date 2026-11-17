package com.property.ai.bff.dto;

import java.math.BigDecimal;

/**
 * OpenAPI: GrayCompareItem（单能力双跑比对项，P5 W19 / v1.3.0，架构 §24.4）。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.3.0 严格对齐；仅 DTO，不含业务逻辑。
 * checked 比对样本数 / matched 完全一致数 / diffCount 差异数 / diffRate = diffCount / checked。
 * 只读比对，不含真实业主敏感信息。
 */
public record GrayCompareItem(
        CapabilityCode capability,
        String oldConnector,
        String newConnector,
        int checked,
        int matched,
        int diffCount,
        BigDecimal diffRate) {
}
