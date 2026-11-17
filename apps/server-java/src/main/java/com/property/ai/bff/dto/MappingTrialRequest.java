package com.property.ai.bff.dto;

/**
 * OpenAPI: MappingTrialRequest（映射试跑请求（默认 200 样本），v0.3.2）。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v0.3.2 严格对齐；仅 DTO，不含业务逻辑。
 */
public record MappingTrialRequest(
        /** 试跑样本数（默认 200，上限 1000） */
        Integer sampleSize) {
}
