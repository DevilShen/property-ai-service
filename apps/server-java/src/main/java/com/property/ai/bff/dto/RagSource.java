package com.property.ai.bff.dto;

/**
 * RAG 来源卡片（P6 W30 / 契约 v1.4.0 增量；{@code RagQueryResult.sources[]}）。
 *
 * <p><b>契约</b>：OpenAPI v1.4.0 {@code components.schemas.RagSource}。
 * <b>引用红线</b>：答案中的 {@code [n]} 编号必须 ⊆ {@code sources[].index}；{@code index} 为上下文编号（1 起），
 * {@code knowledgeId}/{@code version} 保证引用可追溯到知识版本（历史答案按当时版本解释）。
 * {@code snippet} 为脱敏后的入选片段（非全文）；{@code score} 为服务端给的相似度展示分（前端不参与排序）。
 */
public record RagSource(
        /** 上下文引用编号（1 起，与答案中的 [n] 一一对应） */
        int index,
        /** 知识 ID */
        String knowledgeId,
        /** 知识标题 */
        String title,
        /** 知识版本（引用必须带版本） */
        String version,
        /** 知识责任人（岗位/掩码名，用于过期追溯） */
        String owner,
        /** 知识分类 */
        KnowledgeCategory category,
        /** 相关度/相似度展示分（0—1，服务端给） */
        double score,
        /** 脱敏后的入选片段（约 ≤200 字，不返回索引正文全文） */
        String snippet) {
}
