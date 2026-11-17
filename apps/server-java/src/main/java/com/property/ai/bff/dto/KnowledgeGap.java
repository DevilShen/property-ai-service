package com.property.ai.bff.dto;

/**
 * OpenAPI: KnowledgeGap（P4 W15 / v1.2.0 增量）。
 * 知识缺口项（P4 v1.2.0）：某主题被问到但未命中知识（`unanswered`）的次数，用于缺口 TopN 运营。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.2.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record KnowledgeGap(
        /** 缺口主题（意图/问题聚类） */
        String topic,
        /** 被问次数 */
        int asked,
        /** 未命中（答不上/拒答）次数 */
        int unanswered) {
}
