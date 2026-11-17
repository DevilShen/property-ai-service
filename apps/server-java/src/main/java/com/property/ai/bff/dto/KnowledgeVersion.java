package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * OpenAPI: KnowledgeVersion（P4 W15 / v1.2.0 增量）。
 * 知识版本历史项（P4 v1.2.0）。每次 `change` 提交/审核产生一条版本；`submittedAt` 升序由服务端保证，
 * 客户端按 `version` 展示「当前 / 历史」。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.2.0 严格对齐；仅 DTO，不含业务逻辑。
 * 可选字段以 class 级 {@code @JsonInclude(NON_NULL)} 保证未赋值时不序列化（契约默认不返回该字段）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KnowledgeVersion(
        /** 知识 ID */
        String knowledgeId,
        /** 版本号 */
        String version,
        /** 该版本状态 */
        KnowledgeItemStatus status,
        /** 生效日 */
        String effectiveFrom,
        /** 失效日 */
        String effectiveTo,
        /** 提交人（账号/岗位，掩码） */
        String submittedBy,
        /** 提交时间 */
        String submittedAt,
        /** 关联变更单 ID（P4 变更审核链路） */
        String changeId,
        /** 审核意见（驳回原因/生效说明） */
        String reviewNote) {
}
