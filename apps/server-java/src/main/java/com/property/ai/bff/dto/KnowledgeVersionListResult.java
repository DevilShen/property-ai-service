package com.property.ai.bff.dto;

import java.util.List;

/**
 * OpenAPI: KnowledgeVersionListResult（P4 W15 / v1.2.0 增量）。
 * 知识版本历史结果（P4 v1.2.0）。版本数量有限（不做分页包装），`total` 便于前端判断是否已全量。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.2.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record KnowledgeVersionListResult(
        /** 知识 ID */
        String knowledgeId,
        /** 版本历史（按提交时间升序） */
        List<KnowledgeVersion> items,
        /** 版本数 */
        int total) {
}
