package com.property.ai.bff.dto;

/**
 * OpenAPI: ReconRunResult（对账受理结果（异步））。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v0.3.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record ReconRunResult(
        String runId,
        ReconScope scope,
        int sampleSize,
        ReconRunStatus status,
        String startedAt,
        boolean mock) {
}
