package com.property.ai.bff.dto;

import java.math.BigDecimal;

/**
 * OpenAPI: KnowledgeImportDuplicate（P6 W34 / 契约 v1.5.0 增量）。
 *
 * <p>疑似重复知识：SHA-256 归一化文本命中 + 相似度提示，<b>默认跳过、可强制新建</b>；
 * 只给 knowledgeId/title/similarity，不返回既有知识全文。
 */
public record KnowledgeImportDuplicate(
        /** 重复候选知识 ID */
        String knowledgeId,
        /** 重复候选知识标题 */
        String title,
        /** 相似度展示分（0—1；1.0 = 归一化文本 SHA-256 完全一致） */
        BigDecimal similarity) {
}
