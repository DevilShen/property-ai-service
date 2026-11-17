package com.property.ai.bff.dto;

/**
 * OpenAPI: MappingDiffItem（单条试跑差异，v0.3.2）。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v0.3.2 严格对齐；仅 DTO，不含业务逻辑。
 */
public record MappingDiffItem(
        /** 样本主键 */
        String key,
        MappingDiffKind kind,
        String sourceField,
        String targetField,
        String expected,
        String actual,
        String roomCode) {
}
