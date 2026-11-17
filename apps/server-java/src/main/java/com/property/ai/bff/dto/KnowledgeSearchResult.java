package com.property.ai.bff.dto;

import java.util.List;

/**
 * OpenAPI: KnowledgeSearchResult（P4 W15 / v1.2.0 增量）。
 * 知识检索结果（P4 v1.2.0）。无命中返回 `items: []` + 200（不是错误）：AI 必须**拒答并转人工**，
 * 不得编造答案（产品 §7.2 知识秒查红线）；缺口由 `/knowledge/stats` 的 `gaps` 聚合。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.2.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record KnowledgeSearchResult(
        /** 命中项（按 score 降序） */
        List<KnowledgeSearchHit> items,
        /** 原始查询词（回带回显，便于「无命中」反馈） */
        String query) {
}
