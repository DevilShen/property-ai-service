package com.property.ai.bff.dto;

import java.util.List;

/**
 * OpenAPI: KnowledgeCommitResult（P6 W34 / 契约 v1.5.0 增量）。
 *
 * <p>提交结果（{@code POST /api/v1/knowledge/imports/{jobId}/commit} 200 载荷）：批量导入时一个任务可生成
 * 多条知识（{@code items} 非空）；<b>未审核内容不会进入 RAG 索引</b>（默认 {@code pending_review}）。
 */
public record KnowledgeCommitResult(
        /** 生成的知识列表（至少 1 条；批量任务可多条） */
        List<KnowledgeCommitItem> items) {
}
