package com.property.ai.bff.dto;

/**
 * OpenAPI: KnowledgeOwnerStat（P4 W15 / v1.2.0 增量）。
 * 知识责任人分布项（P4 v1.2.0）：用于「过期/待审知识追到人」。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.2.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record KnowledgeOwnerStat(
        /** 责任人（岗位/掩码名） */
        String owner,
        /** 名下知识总数 */
        int total,
        /** 其中生效中 */
        int published,
        /** 其中已过期（需责任人复核） */
        int expired) {
}
