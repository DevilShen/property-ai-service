package com.property.ai.domain.knowledge.importer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.property.ai.bff.dto.KnowledgeCategory;
import com.property.ai.bff.dto.KnowledgeImportDuplicate;
import com.property.ai.bff.dto.KnowledgeImportError;
import com.property.ai.bff.dto.KnowledgeImportFileType;
import com.property.ai.bff.dto.KnowledgeImportJob;
import com.property.ai.bff.dto.KnowledgeImportJobStatus;
import com.property.ai.bff.dto.KnowledgeImportPreview;
import com.property.ai.bff.dto.KnowledgeImportPreviewChunk;
import com.property.ai.bff.dto.KnowledgeImportSourceType;
import com.property.ai.bff.dto.KnowledgeImportWarning;
import com.property.ai.bff.dto.KnowledgeCommitItem;
import com.property.ai.bff.dto.ErrorCode;

/**
 * 导入任务内部记录（task-86，内存 demo；PG 表见设计 §5.4 {@code knowledge_import_jobs}）。
 *
 * <p>与契约 DTO {@link KnowledgeImportJob} 分离的原因：内部还要保存
 * 「原文文档 ID 列表 / 提交元数据默认值（category/owner/生效期）/ 已提交知识（幂等重放）/ 分批文件明细」，
 * 这些<b>不进契约</b>（DTO 已冻结）。任务状态机在此类内集中校验：
 * <pre>
 * pending → parsing → preview_ready → committing → indexing → completed
 *                    ↘ failed（解析失败）   ↘ cancelled（pending/parsing/preview_ready 可取消）
 * 非法流转 → 409 E-3002（details.reason=invalid_transition）
 * </pre>
 */
public class KnowledgeImportJobRecord {

    /** 预览块（seq 为任务内全局 1 起连续；批量时跨文件连续） */
    public record PreviewChunk(int seq, String sectionPath, String text, int charCount) {
    }

    /** 警告（code/message；不阻断导入） */
    public record Warning(String code, String message) {
    }

    /** 疑似重复候选 */
    public record Duplicate(String knowledgeId, String title, BigDecimal similarity) {
    }

    /**
     * 批量任务的分文件预览（单文件/粘贴任务只有一个 part）。
     *
     * @param contentMasked 该文件脱敏后的全文（commit 生成知识正文 / 索引 content）
     * @param chunks        该文件的预览块（seq 为任务内全局序号）
     */
    public record FilePart(String documentId, String fileName, KnowledgeImportFileType fileType, long sizeBytes,
            String checksum, String suggestedTitle, String contentMasked, List<PreviewChunk> chunks) {
    }

    private final String jobId;
    private String projectId;
    private KnowledgeImportJobStatus status = KnowledgeImportJobStatus.PENDING;
    private final KnowledgeImportSourceType sourceType;
    private String fileName;
    private KnowledgeImportFileType fileType;
    private Integer sizeBytes;
    private String checksum;
    private String title;
    private String suggestedTitle;
    private Integer charCount;
    private Integer piiMaskedCount;
    private final List<PreviewChunk> chunks = new ArrayList<>();
    private final List<Warning> warnings = new ArrayList<>();
    private final List<Duplicate> duplicateOf = new ArrayList<>();
    private final String createdBy;
    private final String createdAt;
    private String updatedAt;
    private ErrorCode errorCode;
    private String errorMessage;
    private boolean errorRetryable;
    private final List<FilePart> files = new ArrayList<>();
    private final List<String> documentIds = new ArrayList<>();

    // ---- 提交元数据默认值（create 请求带入，commit 可覆盖；不进契约 DTO） ----
    private KnowledgeCategory category;
    private String owner;
    private String effectiveFrom;
    private String effectiveTo;

    // ---- 提交结果（幂等重放：同任务重复 commit 带同一 Idempotency-Key 返回首次结果） ----
    private final List<KnowledgeCommitItem> committedItems = new ArrayList<>();

    KnowledgeImportJobRecord(String jobId, String projectId, KnowledgeImportSourceType sourceType, String createdBy,
            String createdAt) {
        this.jobId = jobId;
        this.projectId = projectId;
        this.sourceType = sourceType;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    // ------------------------------------------------------------------
    // 状态机
    // ------------------------------------------------------------------

    /** 允许的流转（唯一事实源；未列出的流转一律 409 invalid_transition）。 */
    private static Map<KnowledgeImportJobStatus, List<KnowledgeImportJobStatus>> allowedTransitions() {
        Map<KnowledgeImportJobStatus, List<KnowledgeImportJobStatus>> map = new LinkedHashMap<>();
        map.put(KnowledgeImportJobStatus.PENDING, List.of(KnowledgeImportJobStatus.PARSING,
                KnowledgeImportJobStatus.FAILED, KnowledgeImportJobStatus.CANCELLED));
        map.put(KnowledgeImportJobStatus.PARSING, List.of(KnowledgeImportJobStatus.PREVIEW_READY,
                KnowledgeImportJobStatus.FAILED, KnowledgeImportJobStatus.CANCELLED));
        map.put(KnowledgeImportJobStatus.PREVIEW_READY, List.of(KnowledgeImportJobStatus.COMMITTING,
                KnowledgeImportJobStatus.CANCELLED));
        map.put(KnowledgeImportJobStatus.COMMITTING, List.of(KnowledgeImportJobStatus.INDEXING,
                KnowledgeImportJobStatus.COMPLETED, KnowledgeImportJobStatus.PREVIEW_READY,
                KnowledgeImportJobStatus.FAILED));
        map.put(KnowledgeImportJobStatus.INDEXING, List.of(KnowledgeImportJobStatus.COMPLETED,
                KnowledgeImportJobStatus.PREVIEW_READY, KnowledgeImportJobStatus.FAILED));
        map.put(KnowledgeImportJobStatus.COMPLETED, List.of());
        map.put(KnowledgeImportJobStatus.FAILED, List.of());
        map.put(KnowledgeImportJobStatus.CANCELLED, List.of());
        return map;
    }

    /**
     * 状态流转（非法 → 409 {@code E-3002} {@code invalid_transition}）。
     *
     * @param target  目标状态
     * @param updated 目标状态下的 updatedAt（业务时区 ISO）
     */
    public void transitionTo(KnowledgeImportJobStatus target, String updated) {
        List<KnowledgeImportJobStatus> allowed = allowedTransitions().getOrDefault(status, List.of());
        if (target == null || !allowed.contains(target)) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("jobId", jobId);
            details.put("status", status.value());
            details.put("requested", target == null ? "" : target.value());
            details.put("allowed", allowed.stream().map(KnowledgeImportJobStatus::value).toList());
            throw KnowledgeImportException.conflict(
                    "导入任务状态不允许该操作（当前 " + status.value() + " → "
                            + (target == null ? "" : target.value()) + "）",
                    "invalid_transition", details);
        }
        this.status = target;
        this.updatedAt = updated;
    }

    /** 是否在途（并发任务口径）：pending/parsing/committing/indexing。 */
    public boolean inFlight() {
        return status == KnowledgeImportJobStatus.PENDING || status == KnowledgeImportJobStatus.PARSING
                || status == KnowledgeImportJobStatus.COMMITTING || status == KnowledgeImportJobStatus.INDEXING;
    }

    /** 是否终态（不可取消、不可再提交）。 */
    public boolean terminal() {
        return status == KnowledgeImportJobStatus.COMPLETED || status == KnowledgeImportJobStatus.FAILED
                || status == KnowledgeImportJobStatus.CANCELLED;
    }

    // ------------------------------------------------------------------
    // 对外 DTO
    // ------------------------------------------------------------------

    /** 契约 {@link KnowledgeImportJob}（可选字段为 null → class 级 NON_NULL 不序列化）。 */
    public KnowledgeImportJob toDto() {
        List<KnowledgeImportPreviewChunk> chunkDtos = chunks.stream()
                .map(chunk -> new KnowledgeImportPreviewChunk(chunk.seq(), chunk.sectionPath(), chunk.text(),
                        chunk.charCount()))
                .toList();
        KnowledgeImportPreview preview = new KnowledgeImportPreview(chunkDtos, chunks.size());
        List<KnowledgeImportWarning> warningDtos = warnings.stream()
                .map(warning -> new KnowledgeImportWarning(warning.code(), warning.message()))
                .toList();
        List<KnowledgeImportDuplicate> duplicateDtos = duplicateOf.isEmpty() ? List.of()
                : duplicateOf.stream()
                        .map(item -> new KnowledgeImportDuplicate(item.knowledgeId(), item.title(), item.similarity()))
                        .toList();
        KnowledgeImportError error = errorCode == null ? null
                : new KnowledgeImportError(errorCode, errorMessage, errorRetryable);
        return new KnowledgeImportJob(jobId, projectId, status, sourceType, fileName, fileType, sizeBytes, checksum,
                title, suggestedTitle, charCount, piiMaskedCount, preview, warningDtos,
                duplicateDtos.isEmpty() ? null : duplicateDtos, createdBy, createdAt, updatedAt, error);
    }

    // ------------------------------------------------------------------
    // getters / setters（包内可见写，读对外）
    // ------------------------------------------------------------------

    public String jobId() {
        return jobId;
    }

    public String projectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public KnowledgeImportJobStatus status() {
        return status;
    }

    public void setStatusDirect(KnowledgeImportJobStatus status, String updatedAt) {
        this.status = status;
        this.updatedAt = updatedAt;
    }

    public KnowledgeImportSourceType sourceType() {
        return sourceType;
    }

    public String fileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public KnowledgeImportFileType fileType() {
        return fileType;
    }

    public void setFileType(KnowledgeImportFileType fileType) {
        this.fileType = fileType;
    }

    public Integer sizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(Integer sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public String checksum() {
        return checksum;
    }

    public void setChecksum(String checksum) {
        this.checksum = checksum;
    }

    public String title() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String suggestedTitle() {
        return suggestedTitle;
    }

    public void setSuggestedTitle(String suggestedTitle) {
        this.suggestedTitle = suggestedTitle;
    }

    public Integer charCount() {
        return charCount;
    }

    public void setCharCount(Integer charCount) {
        this.charCount = charCount;
    }

    public Integer piiMaskedCount() {
        return piiMaskedCount;
    }

    public void setPiiMaskedCount(Integer piiMaskedCount) {
        this.piiMaskedCount = piiMaskedCount;
    }

    public List<PreviewChunk> chunks() {
        return chunks;
    }

    public List<Warning> warnings() {
        return warnings;
    }

    public List<Duplicate> duplicateOf() {
        return duplicateOf;
    }

    public String createdBy() {
        return createdBy;
    }

    public String createdAt() {
        return createdAt;
    }

    public String updatedAt() {
        return updatedAt;
    }

    public void touch(String updatedAt) {
        this.updatedAt = updatedAt;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public String errorMessage() {
        return errorMessage;
    }

    public boolean errorRetryable() {
        return errorRetryable;
    }

    /** 置失败终态（failed + error）。 */
    public void fail(ErrorCode code, String message, boolean retryable, String updatedAt) {
        this.status = KnowledgeImportJobStatus.FAILED;
        this.errorCode = code == null ? ErrorCode.E_5001 : code;
        this.errorMessage = message;
        this.errorRetryable = retryable;
        this.updatedAt = updatedAt;
    }

    public List<FilePart> files() {
        return files;
    }

    public List<String> documentIds() {
        return documentIds;
    }

    public KnowledgeCategory categoryForCommit() {
        return category;
    }

    public void setCategoryForCommit(KnowledgeCategory category) {
        this.category = category;
    }

    public String ownerForCommit() {
        return owner;
    }

    public void setOwnerForCommit(String owner) {
        this.owner = owner;
    }

    public String effectiveFromDefault() {
        return effectiveFrom;
    }

    public void setEffectiveFromDefault(String effectiveFrom) {
        this.effectiveFrom = effectiveFrom;
    }

    public String effectiveToDefault() {
        return effectiveTo;
    }

    public void setEffectiveToDefault(String effectiveTo) {
        this.effectiveTo = effectiveTo;
    }

    public List<KnowledgeCommitItem> committedItems() {
        return committedItems;
    }
}
