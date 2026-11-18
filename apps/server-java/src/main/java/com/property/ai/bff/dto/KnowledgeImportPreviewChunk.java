package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * OpenAPI: KnowledgeImportPreviewChunk（P6 W34 / 契约 v1.5.0 增量）。
 *
 * <p>导入预览切分块：{@code seq} 从 1 连续递增且<b>稳定</b>（同一任务重复预览 seq 不变，供
 * {@code commit.chunks} 按 seq 勾选）；{@code sectionPath} 为标题/条款路径（无结构时省略）；
 * {@code text} 为<b>脱敏后</b>片段（不含明文手机号/身份证）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KnowledgeImportPreviewChunk(
        /** 块序号（1 起连续） */
        Integer seq,
        /** 章节/条款路径（可选，如「装修管理规定 §3.1」） */
        String sectionPath,
        /** 脱敏后的片段文本 */
        String text,
        /** 本块字符数 */
        Integer charCount) {
}
