package com.property.ai.bff.dto;

/**
 * OpenAPI: KnowledgeSearchHit（P4 W15 / v1.2.0 增量）。
 * 知识检索命中项（P4 v1.2.0）。**只返回 `published` 且在生效期内的条目**；`cite` 是可直接展示的
 * 引用串（`K-018 V1.2 · 装修管理规定 §3.1`），前端不得自行拼接。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.2.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record KnowledgeSearchHit(
        /** 知识 ID */
        String knowledgeId,
        /** 条目标题 */
        String title,
        /** 版本号 */
        String version,
        /** 责任人（岗位/掩码名） */
        String owner,
        /** 生效日 */
        String effectiveFrom,
        /** 失效日 */
        String effectiveTo,
        /** 相关度（0—1，服务端给出，前端不参与排序） */
        double score,
        /** 引用串（可直接展示，含 ID/版本/出处） */
        String cite) {
}
