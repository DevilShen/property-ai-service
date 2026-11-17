package com.property.ai.bff.dto;

/**
 * OpenAPI: ProbeSummary（探测汇总（三态计数 + drift），v0.3.2）。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v0.3.2 严格对齐；仅 DTO，不含业务逻辑。
 */
public record ProbeSummary(
        int supported,
        int degraded,
        int unsupported,
        /** 与上一次探测相比状态变化的能力数 */
        int drift) {
}
