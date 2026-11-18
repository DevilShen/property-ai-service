package com.property.ai.bff.dto;

import java.util.List;

/**
 * OpenAPI: KnowledgeImportJobListResult（P6 W34 / 契约 v1.5.0 增量）。
 *
 * <p>导入任务列表结果（{@code GET /api/v1/knowledge/imports} 载荷）：分页口径沿用契约通则
 * （page 1 起 / pageSize ≤100 默认 20）；<b>只含 actor 项目可见任务</b>（服务端强制 projectId 作用域）。
 */
public record KnowledgeImportJobListResult(
        /** 任务列表（按 createdAt 倒序；无任务时为空数组） */
        List<KnowledgeImportJob> items,
        /** 当前页码（1 起） */
        Integer page,
        /** 每页条数（默认 20，上限 100） */
        Integer pageSize,
        /** 可见任务总数 */
        Integer total) {
}
