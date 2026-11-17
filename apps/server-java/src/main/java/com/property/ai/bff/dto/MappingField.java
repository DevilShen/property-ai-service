package com.property.ai.bff.dto;

/**
 * OpenAPI: MappingField（单条字段映射（标准字段 ↔ ERP 字段），v0.3.2）。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v0.3.2 严格对齐；仅 DTO，不含业务逻辑。
 */
public record MappingField(
        /** ERP 侧源字段 */
        String sourceField,
        /** 标准字段 */
        String targetField,
        /** 字段类型 */
        String type,
        /** 是否必填 */
        boolean required,
        /** 转换规则说明（可选） */
        String transform) {
}
