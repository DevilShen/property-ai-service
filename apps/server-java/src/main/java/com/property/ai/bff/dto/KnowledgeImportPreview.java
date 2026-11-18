package com.property.ai.bff.dto;

import java.util.List;

/**
 * OpenAPI: KnowledgeImportPreview（P6 W34 / 契约 v1.5.0 增量）。
 *
 * <p>导入预览（{@code KnowledgeImportJob.preview}）。任务未到 {@code preview_ready} 时
 * {@code chunks} 为空数组 + {@code totalChunks=0}（<b>不是错误</b>，前端继续轮询）。
 */
public record KnowledgeImportPreview(
        /** 切分块列表（按 seq 升序；未就绪时为空数组） */
        List<KnowledgeImportPreviewChunk> chunks,
        /** 预览块总数（= 全部切分块数，不随勾选变化） */
        Integer totalChunks) {
}
