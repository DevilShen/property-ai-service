package com.property.ai.bff.dto;

/**
 * 知识重建索引结果（P6 W30 / 契约 v1.4.0 增量；
 * {@code POST /api/v1/knowledge/{knowledgeId}/reindex}）。
 *
 * <p><b>契约</b>：OpenAPI v1.4.0 {@code components.schemas.RagReindexResult}。字段：
 * <ul>
 *   <li>{@code knowledgeId}：知识 ID（回显）；</li>
 *   <li>{@code chunks}：重建后的片段数（幂等：重复重建不产生重复片段；0 = 正文为空或全部被脱敏剔除）；</li>
 *   <li>{@code indexedAt}：重建完成时间（ISO 8601）。</li>
 * </ul>
 *
 * <p>语义边界：<b>不修改</b>知识正文 / 版本 / 生效期 / 状态，只重建检索索引；写操作落审计
 * {@code knowledge.reindex}（资源类型 {@code knowledge}）。权限 {@code knowledge.reindex} 仅 it/admin。
 */
public record RagReindexResult(
        /** 知识 ID（回显） */
        String knowledgeId,
        /** 重建后的片段数（幂等） */
        int chunks,
        /** 重建完成时间（ISO 8601） */
        String indexedAt) {
}
