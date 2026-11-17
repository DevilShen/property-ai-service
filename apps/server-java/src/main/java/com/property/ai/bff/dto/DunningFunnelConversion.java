package com.property.ai.bff.dto;

/**
 * OpenAPI: DunningFunnelConversion（P3 W10 / v1.1.0 增量）。
 * 漏斗转化率（P3 W10 增量；均为 分子/分母 小数，保留 4 位；分母为 0 时记 0）
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.1.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record DunningFunnelConversion(
        double reachedToResponded,
        double respondedToPromised,
        double promisedToPaid,
        double reachedToPaid) {
}
