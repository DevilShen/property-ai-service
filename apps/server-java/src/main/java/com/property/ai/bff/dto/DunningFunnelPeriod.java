package com.property.ai.bff.dto;

/**
 * OpenAPI: DunningFunnelPeriod（P3 W10 / v1.1.0 增量）。
 * 漏斗统计时间窗（P3 W10 增量；from/to 均为业务日，含端点）
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.1.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record DunningFunnelPeriod(
        String from,
        String to) {
}
