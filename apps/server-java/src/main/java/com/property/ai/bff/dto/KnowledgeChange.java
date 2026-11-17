package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * OpenAPI: KnowledgeChange（P4 W15 / v1.2.0 增量）。
 * 知识变更单（P4 v1.2.0）。提交后为 `pending_review`，由 it/admin 审核；通过后生成新版本并生效，
 * 驳回需带 `note`。审计口径 `knowledge.change` / `knowledge.review`（资源类型 `knowledge`）。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.2.0 严格对齐；仅 DTO，不含业务逻辑。
 * 可选字段以 class 级 {@code @JsonInclude(NON_NULL)} 保证未赋值时不序列化（契约默认不返回该字段）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KnowledgeChange(
        /** 变更单 ID */
        String changeId,
        /** 知识 ID */
        String knowledgeId,
        /** 变更单状态 */
        KnowledgeChangeStatus status,
        /** 提交人（账号/岗位，掩码） */
        String submittedBy,
        /** 提交时间 */
        String submittedAt,
        /** 变更原因（审核依据，必读） */
        String reason,
        /** 审核人（仅审核后返回） */
        String reviewedBy,
        /** 审核时间（仅审核后返回） */
        String reviewedAt) {
}
