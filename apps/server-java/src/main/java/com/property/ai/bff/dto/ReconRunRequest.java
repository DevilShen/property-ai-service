package com.property.ai.bff.dto;

/**
 * OpenAPI: ReconRunRequest（对账触发请求）。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v0.3.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record ReconRunRequest(
        ReconScope scope,
        /** 默认 200，上限 1000 */
        Integer sampleSize) {
}
