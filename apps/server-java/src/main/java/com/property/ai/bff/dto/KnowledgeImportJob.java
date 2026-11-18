package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * OpenAPI: KnowledgeImportJob（P6 W34 / 契约 v1.5.0 增量）。
 *
 * <p>知识导入任务：{@code POST /api/v1/knowledge/imports} 的 201 载荷与
 * {@code GET /api/v1/knowledge/imports/{jobId}} 详情载荷（统一信封 {@code KnowledgeImportJobResponse.data}）。
 * <ul>
 *   <li><b>状态机</b>：pending → parsing → preview_ready → committing → indexing → completed；
 *       失败 {@code failed}（带 {@code error}）；取消 {@code cancelled}（终态），非法流转 409 {@code E-3002}；</li>
 *   <li><b>来源</b>：{@code sourceType} = paste/file/batch/badcase；文件类任务带
 *       fileName/fileType/sizeBytes/checksum（归一化文本 SHA-256，去重与幂等用）；</li>
 *   <li><b>不可信输入</b>：{@code preview.chunks[].text} 已脱敏；warnings 提示不阻断；duplicateOf 提示重复候选；</li>
 *   <li><b>原文不清理（用户裁决）</b>：本 DTO <b>不设</b> retention/过期删除字段（保留期策略不在契约内）；</li>
 *   <li><b>可见范围</b>：非本项目任务对外表现为 404 {@code E-3001}（不区分无权限与不存在）。</li>
 * </ul>
 *
 * <p>仅 DTO，不含业务逻辑；可选字段以 class 级 {@code @JsonInclude(NON_NULL)} 保证未赋值时不序列化。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KnowledgeImportJob(
        /** 导入任务 ID（IMP-YYYYMMDD-NNNN，稳定主键） */
        String jobId,
        /** 项目 ID（服务端按 actor 项目强制） */
        String projectId,
        /** 任务状态（见 {@link KnowledgeImportJobStatus}） */
        KnowledgeImportJobStatus status,
        /** 来源类型（见 {@link KnowledgeImportSourceType}） */
        KnowledgeImportSourceType sourceType,
        /** 原始文件名（仅 file/batch） */
        String fileName,
        /** 文件类型（见 {@link KnowledgeImportFileType}；仅 file/batch） */
        KnowledgeImportFileType fileType,
        /** 原始文件字节数（单文件 ≤10MB；ZIP 总量 ≤50MB） */
        Integer sizeBytes,
        /** 原文归一化文本 SHA-256（64 位小写十六进制；去重与幂等用） */
        String checksum,
        /** 任务标题 */
        String title,
        /** 解析给出的标题建议（可被 commit 覆盖） */
        String suggestedTitle,
        /** 归一化后正文字符数（粘贴 ≤200,000） */
        Integer charCount,
        /** PII 自动脱敏处数（0 = 未命中） */
        Integer piiMaskedCount,
        /** 预览（未就绪时 chunks 为空数组 + totalChunks=0） */
        KnowledgeImportPreview preview,
        /** 警告列表（不阻断导入） */
        List<KnowledgeImportWarning> warnings,
        /** 疑似重复知识候选（省略 = 无重复） */
        List<KnowledgeImportDuplicate> duplicateOf,
        /** 创建人（演示账号 ID） */
        String createdBy,
        /** 创建时间（ISO 8601） */
        String createdAt,
        /** 最后更新时间（ISO 8601） */
        String updatedAt,
        /** 失败原因（仅 status=failed） */
        KnowledgeImportError error) {
}
