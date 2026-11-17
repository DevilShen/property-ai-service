package com.property.ai.bff.dto;

/**
 * OpenAPI: ErpRef（ERP 侧引用（可追溯映射））。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v0.3.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record ErpRef(
        String vendor,
        String ref) {
}
