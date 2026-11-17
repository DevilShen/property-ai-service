package com.property.ai.bff.dto;

/**
 * OpenAPI: HouseholdRelation（本房脱敏关系人）。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v0.3.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record HouseholdRelation(
        RelationType relationType,
        String nameMasked,
        String phoneMasked,
        Boolean isPrimary) {
}
