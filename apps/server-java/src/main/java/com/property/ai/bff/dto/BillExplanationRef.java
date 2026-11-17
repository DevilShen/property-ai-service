package com.property.ai.bff.dto;

/**
 * OpenAPI: BillExplanationRef（P3 W10 / v1.1.0 增量）。
 * 解释引用的知识条目（可追溯：条目 + 标题 + 版本；无出处不得编造）
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.1.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record BillExplanationRef(
        /** 知识条目 ID，如 K-002 */
        String knowledgeId,
        String title,
        /** 知识版本，如 V1.1 */
        String version) {
}
