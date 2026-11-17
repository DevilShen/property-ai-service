package com.property.ai.bff.dto;

/**
 * OpenAPI: Citation（知识引用（差额解释可追溯））。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v0.3.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record Citation(
        String kbId,
        String version,
        String title,
        String effectiveDate,
        String owner) {
}
