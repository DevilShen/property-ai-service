package com.property.ai.bff.dto;

import java.util.List;

/**
 * OpenAPI: MappingVersion（映射整体版本（字段/状态码/字典映射），v0.3.2）。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v0.3.2 严格对齐；仅 DTO，不含业务逻辑。
 */
public record MappingVersion(
        String id,
        String name,
        String connectorId,
        String version,
        MappingVersionStatus status,
        int fieldCount,
        /** 字段映射明细（仅详情返回，可选） */
        List<MappingField> fields,
        String updatedAt,
        /** 发布/生效时间（可选） */
        String publishedAt,
        /** 变更人/发布人（可选） */
        String operator) {
}
