package com.property.ai.bff.dto;

import java.math.BigDecimal;

/**
 * OpenAPI: ArrearsBucketStat（P3 W10 / v1.1.0 增量）。
 * 单个欠费分层的统计（户数 + 金额合计，ERP 口径，不由前端求和）
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.1.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record ArrearsBucketStat(
        ArrearsBucket bucket,
        /** 该分层欠费户数 */
        int rooms,
        /** 该分层欠费金额合计（元） */
        BigDecimal amount) {
}
