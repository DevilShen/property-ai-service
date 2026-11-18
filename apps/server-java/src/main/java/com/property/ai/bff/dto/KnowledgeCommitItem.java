package com.property.ai.bff.dto;

/**
 * OpenAPI: KnowledgeCommitItem（P6 W34 / 契约 v1.5.0 增量）。
 *
 * <p>提交生成的知识项（{@code KnowledgeCommitResult.items[]}）。{@code version} 从 {@code V1.0} 起
 * （引用必须带版本）；{@code status} 复用既有 {@link KnowledgeItemStatus}
 * （待审 pending_review / 草稿 draft / 直审 published / 审核拒绝 rejected）。
 */
public record KnowledgeCommitItem(
        /** 生成的知识 ID（稳定主键，可回溯到导入任务） */
        String knowledgeId,
        /** 知识版本（新建从 V1.0 起） */
        String version,
        /** 知识状态（复用既有 KnowledgeItemStatus） */
        KnowledgeItemStatus status) {
}
