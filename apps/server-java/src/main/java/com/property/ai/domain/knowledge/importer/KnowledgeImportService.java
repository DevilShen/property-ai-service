package com.property.ai.domain.knowledge.importer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.property.ai.audit.AuditService;
import com.property.ai.bff.api.ticket.ApiSupport;
import com.property.ai.bff.dto.Actor;
import com.property.ai.bff.dto.ActorRole;
import com.property.ai.bff.dto.AuditAction;
import com.property.ai.bff.dto.AuditResourceType;
import com.property.ai.bff.dto.AuditResult;
import com.property.ai.bff.dto.ErrorCode;
import com.property.ai.bff.dto.KnowledgeCategory;
import com.property.ai.bff.dto.KnowledgeCommitItem;
import com.property.ai.bff.dto.KnowledgeCommitResult;
import com.property.ai.bff.dto.KnowledgeImportCommitRequest;
import com.property.ai.bff.dto.KnowledgeImportCommitStatus;
import com.property.ai.bff.dto.KnowledgeImportCreateRequest;
import com.property.ai.bff.dto.KnowledgeImportFileType;
import com.property.ai.bff.dto.KnowledgeImportJob;
import com.property.ai.bff.dto.KnowledgeImportJobListResult;
import com.property.ai.bff.dto.KnowledgeImportJobStatus;
import com.property.ai.bff.dto.KnowledgeImportSourceType;
import com.property.ai.bff.dto.KnowledgeItemStatus;
import com.property.ai.bff.dto.KnowledgeSource;
import com.property.ai.cache.RateLimiter;
import com.property.ai.domain.knowledge.KnowledgeChangeService;
import com.property.ai.domain.knowledge.KnowledgeIndexHook;
import com.property.ai.domain.knowledge.KnowledgeProjectScope;
import com.property.ai.domain.knowledge.KnowledgeRepository;
import com.property.ai.domain.project.ProjectService;
import com.property.ai.domain.ticket.TicketTimes;
import com.property.ai.security.ActorContext;
import com.property.ai.security.PermissionMatrix;

/**
 * 知识导入服务（task-86；契约 v1.5.0 {@code /api/v1/knowledge/imports} 5 端点；设计 §4—§6）。
 *
 * <h2>职责（Java 只做 BFF/领域编排）</h2>
 * 鉴权/项目隔离/配额/幂等/任务状态机/原文留存/审核联动/审计全部在本服务；
 * <b>解析/归一化/脱敏/切分/去重/向量入库在 Python 编排层</b>（AGENTS.md NEVER #2）。
 *
 * <h2>状态机（唯一事实源 {@link KnowledgeImportJobRecord}）</h2>
 * <pre>
 * pending → parsing → preview_ready → committing → indexing → completed
 *             ↘ failed                  ↘ completed（待审/草稿提交）
 * pending / parsing / preview_ready → cancelled（终态）
 * 非法流转 → 409 E-3002（invalid_transition / already_committed）
 * </pre>
 *
 * <h2>限额（服务端强制，前端只提示）</h2>
 * 单文件 ≤10MB、粘贴 ≤200,000 字符、ZIP ≤50 文件 / ≤50MB（超限 413 {@code E-1001}）、
 * ≤10 次/分钟/账号、在途任务 ≤2（超限 429 {@code E-4003}）。
 *
 * <h2>不产生半成品</h2>
 * ① 编排层不可用 → 503 {@code E-5002} 且<b>不登记任务</b>（同 Idempotency-Key 可重试）；
 * ② 解析失败（加密 PDF/损坏 docx/空文本）→ 201 + 任务 {@code failed} + 人话 error（契约示例口径）；
 * ③ 直审（approved）索引失败 → <b>回滚已登记知识</b>并回到 {@code preview_ready}，503 {@code E-5002} 可重试。
 *
 * <h2>审核联动索引</h2>
 * {@code pending_review} 提交后走既有 {@code POST /knowledge/{knowledgeId}/review}（changeId=knowledgeId）：
 * 审核通过 → {@link #onReviewed} 触发 {@code /internal/rag/index} + {@code knowledge.index} 审计（失败仅告警）；
 * 审核驳回 → 导入知识置 {@code rejected}，不索引。
 */
@Service
public class KnowledgeImportService implements KnowledgeIndexHook {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeImportService.class);

    /** 审计资源类型（契约：auditAction knowledge.import.commit / knowledge.index，resourceType=knowledge） */
    private static final String RESOURCE_TYPE = "knowledge";
    /** 导入频率窗口（秒） */
    private static final long RATE_WINDOW_SECONDS = 60L;
    private static final int MAX_TITLE_LENGTH = 200;
    private static final int MAX_REVIEW_NOTE_LENGTH = 500;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private final KnowledgeImportRepository repository;
    private final KnowledgeDocumentRepository documentRepository;
    private final RagImportClient ragClient;
    private final KnowledgeRepository knowledgeRepository;
    private final KnowledgeChangeService changeService;
    private final AuditService auditService;
    private final RateLimiter rateLimiter;
    private final KnowledgeImportProperties properties;
    private final ProjectService projectService;

    private Clock clock = Clock.system(TicketTimes.BUSINESS_ZONE);

    public KnowledgeImportService(KnowledgeImportRepository repository,
            KnowledgeDocumentRepository documentRepository, RagImportClient ragClient,
            KnowledgeRepository knowledgeRepository, KnowledgeChangeService changeService, AuditService auditService,
            RateLimiter rateLimiter, KnowledgeImportProperties properties, ProjectService projectService) {
        this.repository = repository;
        this.documentRepository = documentRepository;
        this.ragClient = ragClient;
        this.knowledgeRepository = knowledgeRepository;
        this.changeService = changeService;
        this.auditService = auditService;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.projectService = projectService;
    }

    /** 测试注入固定时钟（任务时间/生效期断言用；生产不调用） */
    void setClockForTest(Clock clock) {
        this.clock = clock == null ? Clock.system(TicketTimes.BUSINESS_ZONE) : clock;
    }

    /** 限流维度键（诊断/测试预消费同一键用；不含敏感信息） */
    public static String rateLimitKey(String actorId) {
        return "knowledge.import:" + (actorId == null || actorId.isBlank() ? "anonymous" : actorId);
    }

    // ==================================================================
    // 创建（粘贴 / 单文件 / 批量 ZIP）
    // ==================================================================

    /** 粘贴文本 / RAG 缺口沉淀（{@code application/json} 分支）。 */
    public KnowledgeImportJob createPaste(KnowledgeImportCreateRequest request, String scopeProjectId, Actor actor,
            String idempotencyKey, String traceId) {
        return create(CreateSpec.paste(request), scopeProjectId, actor, idempotencyKey, traceId);
    }

    /** 单文件 / 批量 ZIP（{@code multipart/form-data} 分支）。 */
    public KnowledgeImportJob createFile(String fileName, byte[] content, String title, String categoryRaw,
            String sourceTypeRaw, String scopeProjectId, Actor actor, String idempotencyKey, String traceId) {
        return create(CreateSpec.file(fileName, content, title, categoryRaw, sourceTypeRaw), scopeProjectId, actor,
                idempotencyKey, traceId);
    }

    /** 创建入参（两条入口归一，避免两套校验分支漂移）。 */
    private record CreateSpec(boolean fileMode, String fileName, byte[] content, String title, String categoryRaw,
            String sourceTypeRaw, KnowledgeImportCreateRequest paste) {

        static CreateSpec paste(KnowledgeImportCreateRequest request) {
            return new CreateSpec(false, null, null, request == null ? null : request.title(), null, null, request);
        }

        static CreateSpec file(String fileName, byte[] content, String title, String categoryRaw, String sourceTypeRaw) {
            return new CreateSpec(true, fileName, content, title, categoryRaw, sourceTypeRaw, null);
        }
    }

    private KnowledgeImportJob create(CreateSpec spec, String scopeProjectId, Actor actor, String idempotencyKey,
            String traceId) {
        String actorId = actorIdOf(actor);
        String projectId = resolveProjectId(scopeProjectId, actor);
        if (spec.fileMode()) {
            return createFileJob(spec, projectId, actor, actorId, idempotencyKey, traceId);
        }
        return createPasteJob(spec, projectId, actor, actorId, idempotencyKey, traceId);
    }

    private KnowledgeImportJob createPasteJob(CreateSpec spec, String projectId, Actor actor, String actorId,
            String idempotencyKey, String traceId) {
        assertCapability(actor, PermissionMatrix.KNOWLEDGE_IMPORT);
        KnowledgeImportCreateRequest request = spec.paste();
        if (request == null) {
            throw KnowledgeImportException.badRequest("请求体必填：{title, content}", Map.of("body", "required"));
        }
        String title = requireText("title", request.title(), MAX_TITLE_LENGTH);
        String content = request.content() == null ? "" : request.content();
        if (content.isBlank()) {
            throw KnowledgeImportException.badRequest("content 必填且非空（粘贴文本/Markdown）",
                    Map.of("field", "content", "required", true, "reason", "empty_text"));
        }
        if (content.length() > properties.getMaxPasteChars()) {
            throw KnowledgeImportException.payloadTooLarge(
                    "粘贴文本超限：" + content.length() + " 字符 > " + properties.getMaxPasteChars() + " 字符上限",
                    "payload_too_large",
                    Map.of("field", "content", "limit", properties.getMaxPasteChars(), "length", content.length()));
        }
        KnowledgeImportSourceType sourceType = parsePasteSourceType(request.sourceType());
        KnowledgeCategory category = parseCategory(request.category() == null ? null : request.category().value());
        String effectiveFrom = optionalDateText("effectiveFrom", request.effectiveFrom());
        String effectiveTo = optionalDateText("effectiveTo", request.effectiveTo());
        requireDateRange(effectiveFrom, effectiveTo);

        checkQuota(actor, actorId);
        String fingerprint = fingerprint("paste", projectId, title, null, category, sourceType,
                sha256Hex(content));
        KnowledgeImportJob replayed = replayCreate(idempotencyKey, fingerprint);
        if (replayed != null) {
            return replayed;
        }

        String nowIso = nowIso();
        KnowledgeImportJobRecord job = new KnowledgeImportJobRecord(repository.nextJobId(), projectId, sourceType,
                actorId, nowIso);
        job.setTitle(title);
        job.setCategoryForCommit(category);
        job.setOwnerForCommit(request.owner());
        job.setEffectiveFromDefault(effectiveFrom);
        job.setEffectiveToDefault(effectiveTo);
        job.transitionTo(KnowledgeImportJobStatus.PARSING, nowIso);
        RagImportClient.InternalPreview preview = null;
        try {
            preview = ragClient.preview(null, KnowledgeImportFileType.TXT, content, null, projectId, title, traceId);
        } catch (RagImportException ex) {
            handlePreviewFailure(job, ex, traceId);
        }
        if (preview == null) {
            // 解析失败：201 + 任务 failed + 人话 error（契约示例口径：加密/空文本 → E-5001 不可重试）
            return finishCreate(job, idempotencyKey, fingerprint, traceId);
        }
        KnowledgeImportJobRecord.FilePart part = registerPreviewPart(job, preview, null,
                KnowledgeImportFileType.TXT, content.getBytes(StandardCharsets.UTF_8), content, null,
                false, actorId, nowIso);
        job.files().add(part);
        job.setTitle(title);
        job.setSuggestedTitle(preview.suggestedTitle());
        job.setChecksum(part.checksum());
        job.setCharCount(preview.charCount());
        job.setPiiMaskedCount(preview.piiMaskedCount());
        job.transitionTo(KnowledgeImportJobStatus.PREVIEW_READY, nowIso);
        return finishCreate(job, idempotencyKey, fingerprint, traceId);
    }

    private KnowledgeImportJob createFileJob(CreateSpec spec, String projectId, Actor actor, String actorId,
            String idempotencyKey, String traceId) {
        assertCapability(actor, PermissionMatrix.KNOWLEDGE_IMPORT);
        String fileName = requireText("fileName", spec.fileName(), 260);
        byte[] content = spec.content() == null ? new byte[0] : spec.content();
        if (content.length == 0) {
            throw KnowledgeImportException.badRequest("上传文件为空", Map.of("field", "file", "reason", "empty_file"));
        }
        String title = spec.title() == null || spec.title().isBlank() ? null
                : requireText("title", spec.title(), MAX_TITLE_LENGTH);
        KnowledgeCategory category = parseCategory(spec.categoryRaw());
        KnowledgeImportSourceType sourceType = parseFileSourceType(spec.sourceTypeRaw());
        KnowledgeImportFileType fileType = requireFileType(fileName, content);

        if (fileType == KnowledgeImportFileType.ZIP) {
            if (sourceType != KnowledgeImportSourceType.BATCH) {
                throw KnowledgeImportException.unsupportedFileType(fileName, "zip");
            }
            assertCapability(actor, PermissionMatrix.KNOWLEDGE_IMPORT_BATCH);
            if (content.length > properties.getMaxZipBytes()) {
                throw KnowledgeImportException.payloadTooLarge(
                        "ZIP 超限：" + content.length + " 字节 > " + properties.getMaxZipBytes() + " 字节（50MB）",
                        "payload_too_large",
                        Map.of("field", "file", "limit", properties.getMaxZipBytes(), "length", content.length));
            }
        } else {
            if (sourceType == KnowledgeImportSourceType.BATCH) {
                throw KnowledgeImportException.badRequest("sourceType=batch 仅支持 ZIP 批量上传",
                        Map.of("field", "sourceType", "reason", "invalid_field"));
            }
            if (content.length > properties.getMaxFileBytes()) {
                throw KnowledgeImportException.payloadTooLarge(
                        "文件超限：" + content.length + " 字节 > " + properties.getMaxFileBytes() + " 字节（10MB）",
                        "payload_too_large",
                        Map.of("field", "file", "limit", properties.getMaxFileBytes(), "length", content.length));
            }
            requireSniffedType(fileName, fileType, content);
        }

        checkQuota(actor, actorId);
        String fingerprint = fingerprint("file", projectId, title, fileName, category, sourceType,
                sha256Hex(content));
        KnowledgeImportJob replayed = replayCreate(idempotencyKey, fingerprint);
        if (replayed != null) {
            return replayed;
        }

        String nowIso = nowIso();
        KnowledgeImportJobRecord job = new KnowledgeImportJobRecord(repository.nextJobId(), projectId, sourceType,
                actorId, nowIso);
        job.setFileName(fileName);
        job.setFileType(fileType);
        job.setSizeBytes(content.length);
        job.setCategoryForCommit(category);
        job.transitionTo(KnowledgeImportJobStatus.PARSING, nowIso);

        if (fileType == KnowledgeImportFileType.ZIP) {
            return createBatchJob(job, fileName, content, title, actorId, nowIso, idempotencyKey, fingerprint,
                    traceId);
        }

        RagImportClient.InternalPreview preview = null;
        try {
            preview = ragClient.preview(fileName, fileType, null, content, projectId, title, traceId);
        } catch (RagImportException ex) {
            handlePreviewFailure(job, ex, traceId);
        }
        if (preview == null) {
            return finishCreate(job, idempotencyKey, fingerprint, traceId);
        }
        String rawText = fileType == KnowledgeImportFileType.TXT || fileType == KnowledgeImportFileType.MD
                ? decodeText(content) : null;
        KnowledgeImportJobRecord.FilePart part = registerPreviewPart(job, preview, fileName, fileType, content,
                rawText, null, false, actorId, nowIso);
        job.files().add(part);
        job.setTitle(firstNonBlank(title, preview.suggestedTitle(), baseName(fileName)));
        job.setSuggestedTitle(preview.suggestedTitle());
        job.setChecksum(part.checksum());
        job.setCharCount(preview.charCount());
        job.setPiiMaskedCount(preview.piiMaskedCount());
        job.transitionTo(KnowledgeImportJobStatus.PREVIEW_READY, nowIso);
        return finishCreate(job, idempotencyKey, fingerprint, traceId);
    }

    /** ZIP 批量：安全解包 → 逐文件 preview → 合并为 batch job（seq 全局连续、sectionPath 前缀文件名）。 */
    private KnowledgeImportJob createBatchJob(KnowledgeImportJobRecord job, String zipFileName, byte[] zipBytes,
            String title, String actorId, String nowIso, String idempotencyKey, String fingerprint, String traceId) {
        KnowledgeImportZipExtractor.Extraction extraction = KnowledgeImportZipExtractor.extract(zipBytes,
                new KnowledgeImportZipExtractor.Limits(properties.getMaxZipFiles(), properties.getMaxZipBytes(),
                        properties.getMaxFileBytes()));
        for (String skipped : extraction.skipped()) {
            job.warnings().add(new KnowledgeImportJobRecord.Warning("file_skipped", skipped));
        }
        int parsed = 0;
        List<String> failures = new ArrayList<>();
        int charCount = 0;
        int piiMaskedCount = 0;
        for (KnowledgeImportZipExtractor.Entry entry : extraction.entries()) {
            RagImportClient.InternalPreview preview;
            try {
                preview = ragClient.preview(entry.fileName(), entry.fileType(), null, entry.bytes(),
                        job.projectId(), null, traceId);
            } catch (RagImportException ex) {
                handlePreviewFailure(job, ex, traceId);
                // 走到这里说明是「输入被拒绝」而非编排层不可用：单文件失败只跳过，不整包失败
                failures.add(entry.fileName() + "：" + ex.getMessage());
                job.warnings().add(new KnowledgeImportJobRecord.Warning("file_skipped",
                        entry.fileName() + " 解析失败（" + safeReason(ex) + "），已跳过"));
                continue;
            }
            String rawText = entry.fileType() == KnowledgeImportFileType.TXT
                    || entry.fileType() == KnowledgeImportFileType.MD ? decodeText(entry.bytes()) : null;
            KnowledgeImportJobRecord.FilePart part = registerPreviewPart(job, preview, entry.fileName(),
                    entry.fileType(), entry.bytes(), rawText, null, true, actorId, nowIso);
            job.files().add(part);
            parsed++;
            charCount += preview.charCount();
            piiMaskedCount += preview.piiMaskedCount();
        }
        if (parsed == 0) {
            job.fail(ErrorCode.E_5001, "ZIP 内文件均解析失败"
                    + (failures.isEmpty() ? "" : "（" + String.join("；", failures) + "）"), false, nowIso);
            return finishCreate(job, idempotencyKey, fingerprint, traceId);
        }
        job.setTitle(firstNonBlank(title, baseName(zipFileName)));
        job.setSuggestedTitle(baseName(zipFileName));
        job.setChecksum(sha256Hex(concatChecksums(job.files())));
        job.setCharCount(charCount);
        job.setPiiMaskedCount(piiMaskedCount);
        job.transitionTo(KnowledgeImportJobStatus.PREVIEW_READY, nowIso);
        log.info("[knowledge-import] batch preview ok jobId={} files={} chunks={} skipped={} traceId={}",
                job.jobId(), job.files().size(), job.chunks().size(), job.warnings().size(), traceId);
        return finishCreate(job, idempotencyKey, fingerprint, traceId);
    }

    // ==================================================================
    // 列表 / 详情 / 取消
    // ==================================================================

    /** 任务列表（分页 + 状态过滤；只返回 actor 项目可见任务）。 */
    public KnowledgeImportJobListResult list(String scopeProjectId, String statusParam, int page, int pageSize,
            Actor actor) {
        assertCapability(actor, PermissionMatrix.KNOWLEDGE_IMPORT);
        ApiSupport.requirePageParams(page, pageSize);
        KnowledgeImportJobStatus status = parseStatus(statusParam);
        List<KnowledgeImportJobRecord> matched = new ArrayList<>();
        for (KnowledgeImportJobRecord job : repository.all()) {
            if (!visible(job, scopeProjectId)) {
                continue;
            }
            if (status != null && job.status() != status) {
                continue;
            }
            matched.add(job);
        }
        int total = matched.size();
        int offset = (page - 1) * pageSize;
        List<KnowledgeImportJob> items;
        if (offset >= total) {
            items = List.of();
        } else {
            int end = Math.min(total, offset + pageSize);
            items = matched.subList(offset, end).stream().map(KnowledgeImportJobRecord::toDto).toList();
        }
        return new KnowledgeImportJobListResult(items, page, pageSize, total);
    }

    /** 任务详情（非本项目 → 404 E-3001，不区分无权限与不存在）。 */
    public KnowledgeImportJob get(String jobId, String scopeProjectId, Actor actor) {
        assertCapability(actor, PermissionMatrix.KNOWLEDGE_IMPORT);
        return requireVisibleJob(jobId, scopeProjectId).toDto();
    }

    /** 取消任务（pending/parsing/preview_ready 可取消；终态 409）。 */
    public KnowledgeImportJob cancel(String jobId, String scopeProjectId, Actor actor, String traceId) {
        assertCapability(actor, PermissionMatrix.KNOWLEDGE_IMPORT);
        KnowledgeImportJobRecord job = requireVisibleJob(jobId, scopeProjectId);
        String nowIso = nowIso();
        job.transitionTo(KnowledgeImportJobStatus.CANCELLED, nowIso);
        // 语义边界：取消不删除已归档原文（原文不清理，用户裁决），也不逆向已生成知识（committing 之后不可取消）
        log.info("[knowledge-import] cancel ok jobId={} by={} traceId={}", job.jobId(), actorIdOf(actor), traceId);
        return job.toDto();
    }

    // ==================================================================
    // 提交（生成知识；直审即时索引；待审走既有 review）
    // ==================================================================

    /** 确认预览并提交（契约 {@code knowledgeImportCommit}）。 */
    public KnowledgeCommitResult commit(String jobId, KnowledgeImportCommitRequest request, String scopeProjectId,
            Actor actor, String idempotencyKey, String traceId) {
        assertCapability(actor, PermissionMatrix.KNOWLEDGE_IMPORT);
        KnowledgeImportJobRecord job = requireVisibleJob(jobId, scopeProjectId);
        KnowledgeImportCommitStatus target = request == null || request.status() == null
                ? KnowledgeImportCommitStatus.PENDING_REVIEW : request.status();
        String fingerprint = commitFingerprint(job, request, target);
        KnowledgeCommitResult replayed = replayCommit(idempotencyKey, fingerprint);
        if (replayed != null) {
            return replayed;
        }
        requireDirectApprovePermission(target, actor);
        if (job.status() != KnowledgeImportJobStatus.PREVIEW_READY) {
            String reason = job.status() == KnowledgeImportJobStatus.COMPLETED ? "already_committed"
                    : "invalid_transition";
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("jobId", job.jobId());
            details.put("status", job.status().value());
            details.put("hint", "仅 preview_ready 可提交；completed 任务不可重复生成知识");
            throw KnowledgeImportException.conflict("导入任务当前状态不允许提交（" + job.status().value() + "）", reason,
                    details);
        }
        CommitPlan plan = buildCommitPlan(job, request, target);
        String nowIso = nowIso();
        job.transitionTo(KnowledgeImportJobStatus.COMMITTING, nowIso);
        List<KnowledgeCommitItem> items = new ArrayList<>();
        List<String> createdKnowledgeIds = new ArrayList<>();
        try {
            for (CommitItemDraft draft : plan.drafts()) {
                String knowledgeId = repository.nextKnowledgeId();
                knowledgeRepository.registerImported(new KnowledgeRepository.Entry(
                        knowledgeId, draft.title(), draft.content(), "V1.0", draft.status(), draft.owner(),
                        draft.effectiveFrom(), draft.effectiveTo(), draft.source(), draft.category(),
                        job.sourceType() == KnowledgeImportSourceType.BADCASE, 0, List.of(job.jobId()), null,
                        nowIso));
                KnowledgeProjectScope.registerImported(knowledgeId, job.projectId());
                createdKnowledgeIds.add(knowledgeId);
                if (target == KnowledgeImportCommitStatus.PENDING_REVIEW) {
                    // 待审：登记 changeId=knowledgeId 的待审变更单，走既有 /knowledge/{id}/review 审核
                    changeService.registerImportedPending(knowledgeId, job.createdBy(), draft.title(),
                            draft.content(), draft.effectiveFrom(), draft.effectiveTo(),
                            request == null ? null : request.reviewNote());
                }
                items.add(new KnowledgeCommitItem(knowledgeId, "V1.0", draft.status()));
            }
            if (target == KnowledgeImportCommitStatus.APPROVED) {
                job.transitionTo(KnowledgeImportJobStatus.INDEXING, nowIso);
                indexApprovedItems(job, plan, items, createdKnowledgeIds, actor, traceId, nowIso);
            }
            job.committedItems().addAll(items);
            job.transitionTo(KnowledgeImportJobStatus.COMPLETED, nowIso);
        } catch (KnowledgeImportException ex) {
            rollbackKnowledge(createdKnowledgeIds);
            job.transitionTo(KnowledgeImportJobStatus.PREVIEW_READY, nowIso());
            throw ex;
        }
        KnowledgeCommitResult result = new KnowledgeCommitResult(List.copyOf(items));
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            repository.saveIdempotency(idempotencyKey.trim(), new KnowledgeImportRepository.IdempotencyEntry(
                    "commit", fingerprint, job.jobId(), List.copyOf(items)));
        }
        log.info("[knowledge-import] commit ok jobId={} status={} items={} by={} traceId={}", job.jobId(),
                target.value(), items.size(), actorIdOf(actor), traceId);
        return result;
    }

    /** 直审知识逐条索引；失败 → 回滚已生成知识 + 任务回 preview_ready + 503 E-5002（不产生半成品）。 */
    private void indexApprovedItems(KnowledgeImportJobRecord job, CommitPlan plan, List<KnowledgeCommitItem> items,
            List<String> createdKnowledgeIds, Actor actor, String traceId, String nowIso) {
        for (int index = 0; index < plan.drafts().size(); index++) {
            CommitItemDraft draft = plan.drafts().get(index);
            String knowledgeId = items.get(index).knowledgeId();
            try {
                ragClient.index(new RagImportClient.IndexRequest(knowledgeId, draft.title(), job.projectId(),
                        draft.category() == null ? "" : draft.category().value(), draft.owner(), "V1.0",
                        draft.effectiveFrom(), draft.effectiveTo(), "published", draft.content(),
                        draft.sourceDocId(), sha256Hex(draft.content())), traceId);
                auditKnowledgeIndex(knowledgeId, AuditResult.SUCCESS, traceId, actor);
            } catch (RagImportException ex) {
                log.warn("[knowledge-import] 直审索引失败，回滚已生成知识 jobId={} knowledgeId={} kind={} traceId={}",
                        job.jobId(), knowledgeId, ex.label(), traceId);
                auditKnowledgeIndex(knowledgeId, AuditResult.FAILED, traceId, actor);
                throw KnowledgeImportException.upstreamUnavailable(
                        "直审索引失败（" + ex.label() + "），已回滚本次生成的知识，任务保持 preview_ready 可重试",
                        Map.of("kind", ex.label(), "jobId", job.jobId()), ex.retryable());
            }
        }
    }

    private void rollbackKnowledge(List<String> createdKnowledgeIds) {
        for (String knowledgeId : createdKnowledgeIds) {
            knowledgeRepository.unregisterImported(knowledgeId);
            KnowledgeProjectScope.unregisterImported(knowledgeId);
        }
    }

    // ==================================================================
    // 审核联动（KnowledgeIndexHook）：pending_review 审核通过 → 索引
    // ==================================================================

    @Override
    public void onReviewed(String knowledgeId, boolean approved, String traceId) {
        String kid = knowledgeId == null ? "" : knowledgeId.trim();
        if (kid.isEmpty()) {
            return;
        }
        String nowIso = nowIso();
        if (!approved) {
            knowledgeRepository.updateImportedStatus(kid, KnowledgeItemStatus.REJECTED, nowIso);
            log.info("[knowledge-import] 导入知识审核驳回 → rejected knowledgeId={} traceId={}", kid, traceId);
            return;
        }
        Optional<KnowledgeRepository.Entry> entryOptional = knowledgeRepository.find(kid);
        if (entryOptional.isEmpty()) {
            log.warn("[knowledge-import] 审核联动跳过：导入知识不存在 knowledgeId={} traceId={}", kid, traceId);
            return;
        }
        KnowledgeRepository.Entry entry = entryOptional.get();
        Actor actor = ActorContext.actorOrNull();
        String projectId = firstNonBlank(KnowledgeProjectScope.importedProjectOf(kid),
                ProjectService.DEFAULT_PROJECT_ID);
        String sourceDocId = sourceDocIdOf(entry);
        try {
            ragClient.index(new RagImportClient.IndexRequest(kid, entry.title(), projectId,
                    entry.category() == null ? "" : entry.category().value(), entry.owner(), entry.version(),
                    entry.effectiveFrom(), entry.effectiveTo(), "published", entry.content(), sourceDocId,
                    sha256Hex(entry.content())), traceId);
            auditKnowledgeIndex(kid, AuditResult.SUCCESS, traceId, actor);
            log.info("[knowledge-import] 审核通过索引 ok knowledgeId={} projectId={} traceId={}", kid, projectId,
                    traceId);
        } catch (RagImportException ex) {
            // 失败只告警 + 审计留痕：知识已 published，可手动重建索引（不阻断审核结果）
            log.warn("[knowledge-import] 审核通过索引失败（仅告警）knowledgeId={} kind={} traceId={}", kid,
                    ex.label(), traceId);
            auditKnowledgeIndex(kid, AuditResult.FAILED, traceId, actor);
        }
    }

    private String sourceDocIdOf(KnowledgeRepository.Entry entry) {
        if (entry.refs() == null || entry.refs().isEmpty()) {
            return null;
        }
        String jobId = entry.refs().get(0);
        return documentRepository.byJob(jobId).stream().findFirst()
                .map(KnowledgeDocumentRepository.DocumentRecord::documentId).orElse(null);
    }

    // ==================================================================
    // 内部：提交计划
    // ==================================================================

    private record CommitItemDraft(String title, String content, KnowledgeItemStatus status, String owner,
            String effectiveFrom, String effectiveTo, KnowledgeCategory category, KnowledgeSource source,
            String sourceDocId) {
    }

    private record CommitPlan(List<CommitItemDraft> drafts) {
    }

    private CommitPlan buildCommitPlan(KnowledgeImportJobRecord job, KnowledgeImportCommitRequest request,
            KnowledgeImportCommitStatus target) {
        Set<Integer> selected = parseChunkSelection(request == null ? null : request.chunks(), job.chunks().size());
        String requestTitle = request == null ? null : request.title();
        if (requestTitle != null && requestTitle.length() > MAX_TITLE_LENGTH) {
            throw KnowledgeImportException.badRequest("title 长度不能超过 " + MAX_TITLE_LENGTH + " 字符",
                    Map.of("field", "title", "length", requestTitle.length(), "maximum", MAX_TITLE_LENGTH));
        }
        if (request != null && request.reviewNote() != null
                && request.reviewNote().length() > MAX_REVIEW_NOTE_LENGTH) {
            throw KnowledgeImportException.badRequest("reviewNote 长度不能超过 " + MAX_REVIEW_NOTE_LENGTH + " 字符",
                    Map.of("field", "reviewNote", "length", request.reviewNote().length(), "maximum",
                            MAX_REVIEW_NOTE_LENGTH));
        }
        KnowledgeCategory category = firstNonNull(request == null ? null : request.category(),
                job.categoryForCommit());
        String owner = firstNonBlank(request == null ? null : request.owner(), job.ownerForCommit(),
                job.createdBy());
        String effectiveFrom = firstNonBlank(request == null ? null : request.effectiveFrom(),
                job.effectiveFromDefault(), todayIso());
        String effectiveTo = firstNonBlank(request == null ? null : request.effectiveTo(),
                job.effectiveToDefault());
        requireDateRange(optionalDateText("effectiveFrom", effectiveFrom),
                effectiveTo == null ? null : optionalDateText("effectiveTo", effectiveTo));
        KnowledgeItemStatus itemStatus = switch (target) {
            case DRAFT -> KnowledgeItemStatus.DRAFT;
            case PENDING_REVIEW -> KnowledgeItemStatus.PENDING_REVIEW;
            case APPROVED -> KnowledgeItemStatus.PUBLISHED;
        };
        List<CommitItemDraft> drafts = new ArrayList<>();
        for (KnowledgeImportJobRecord.FilePart part : job.files()) {
            List<KnowledgeImportJobRecord.PreviewChunk> chosen = chosenChunks(part, selected);
            if (chosen.isEmpty()) {
                continue;
            }
            String content = joinChunkTexts(chosen);
            if (content.isBlank()) {
                continue;
            }
            boolean partial = selected != null;
            if (!partial || chosen.size() == part.chunks().size()) {
                content = part.contentMasked() == null || part.contentMasked().isBlank()
                        ? content : part.contentMasked();
            }
            String title = resolveCommitTitle(job, part, requestTitle);
            String doc = part.fileName() == null ? "导入文本" : part.fileName();
            drafts.add(new CommitItemDraft(title, content, itemStatus, owner, effectiveFrom, effectiveTo, category,
                    new KnowledgeSource(doc, null, null), part.documentId()));
        }
        if (drafts.isEmpty()) {
            throw KnowledgeImportException.badRequest("没有可提交的预览块（chunks 选择为空或内容为空）",
                    Map.of("field", "chunks", "reason", "invalid_field"));
        }
        if (drafts.size() > 1 && requestTitle != null) {
            // 批量任务：request.title 只作为首个知识的标题前缀，其余按文件名/解析标题（避免同名覆盖语义）
            CommitItemDraft first = drafts.get(0);
            drafts.set(0, new CommitItemDraft(requestTitle + "（" + first.source().doc() + "）", first.content(),
                    first.status(), first.owner(), first.effectiveFrom(), first.effectiveTo(), first.category(),
                    first.source(), first.sourceDocId()));
        }
        return new CommitPlan(List.copyOf(drafts));
    }

    private String resolveCommitTitle(KnowledgeImportJobRecord job, KnowledgeImportJobRecord.FilePart part,
            String requestTitle) {
        if (requestTitle != null && !requestTitle.isBlank()
                && (job.files().size() == 1)) {
            return requestTitle.trim();
        }
        return firstNonBlank(part.suggestedTitle(), job.title(), job.suggestedTitle(), "未命名导入知识");
    }

    /** 解析 chunks 选择（null/空 = 全部；越界/非正 → 400）。 */
    private Set<Integer> parseChunkSelection(List<Integer> chunks, int totalChunks) {
        if (chunks == null || chunks.isEmpty()) {
            return null;
        }
        Set<Integer> selected = new LinkedHashSet<>();
        for (Integer seq : chunks) {
            if (seq == null || seq < 1 || seq > totalChunks) {
                throw KnowledgeImportException.badRequest(
                        "chunks 含越界 seq（有效范围 1—" + totalChunks + "）",
                        Map.of("field", "chunks", "value", seq, "totalChunks", totalChunks,
                                "reason", "invalid_field"));
            }
            selected.add(seq);
        }
        return selected;
    }

    private List<KnowledgeImportJobRecord.PreviewChunk> chosenChunks(KnowledgeImportJobRecord.FilePart part,
            Set<Integer> selected) {
        if (selected == null) {
            return part.chunks();
        }
        List<KnowledgeImportJobRecord.PreviewChunk> chosen = new ArrayList<>();
        for (KnowledgeImportJobRecord.PreviewChunk chunk : part.chunks()) {
            if (selected.contains(chunk.seq())) {
                chosen.add(chunk);
            }
        }
        return chosen;
    }

    private static String joinChunkTexts(List<KnowledgeImportJobRecord.PreviewChunk> chunks) {
        StringBuilder builder = new StringBuilder();
        for (KnowledgeImportJobRecord.PreviewChunk chunk : chunks) {
            if (chunk.text() == null || chunk.text().isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append("\n\n");
            }
            builder.append(chunk.text());
        }
        return builder.toString();
    }

    // ==================================================================
    // 内部：创建辅助
    // ==================================================================

    /** 预览 → 文档留存 + 预览块（全局 seq、批量 sectionPath 前缀文件名）+ 警告/重复合并。 */
    private KnowledgeImportJobRecord.FilePart registerPreviewPart(KnowledgeImportJobRecord job,
            RagImportClient.InternalPreview preview, String fileName, KnowledgeImportFileType fileType,
            byte[] bytes, String rawText, String rawBase64, boolean batch, String actorId, String nowIso) {
        String masked = preview.contentMasked() == null || preview.contentMasked().isBlank()
                ? joinInternalChunks(preview.chunks()) : preview.contentMasked();
        String checksum = sha256Hex(masked);
        String documentId = documentRepository.nextDocumentId();
        documentRepository.save(new KnowledgeDocumentRepository.DocumentRecord(documentId, job.projectId(),
                job.jobId(), job.sourceType(), fileName, fileType,
                bytes == null ? 0L : bytes.length, checksum, rawText,
                rawBase64 != null ? rawBase64 : rawBase64Of(bytes, fileType), masked, actorId, nowIso));
        job.documentIds().add(documentId);

        List<KnowledgeImportJobRecord.PreviewChunk> partChunks = new ArrayList<>();
        int seqOffset = job.chunks().size();
        for (int index = 0; index < preview.chunks().size(); index++) {
            RagImportClient.InternalChunk chunk = preview.chunks().get(index);
            int seq = seqOffset + index + 1;
            String sectionPath = batch && fileName != null
                    ? (chunk.sectionPath() == null || chunk.sectionPath().isBlank() ? fileName
                            : fileName + " · " + chunk.sectionPath())
                    : chunk.sectionPath();
            KnowledgeImportJobRecord.PreviewChunk record = new KnowledgeImportJobRecord.PreviewChunk(seq, sectionPath,
                    chunk.text(), chunk.charCount());
            partChunks.add(record);
            job.chunks().add(record);
        }
        for (RagImportClient.InternalWarning warning : preview.warnings()) {
            String message = batch && fileName != null ? fileName + "：" + warning.message() : warning.message();
            job.warnings().add(new KnowledgeImportJobRecord.Warning(warning.code(), message));
        }
        for (RagImportClient.InternalDuplicate duplicate : preview.duplicates()) {
            boolean exists = job.duplicateOf().stream()
                    .anyMatch(item -> item.knowledgeId().equals(duplicate.knowledgeId()));
            if (!exists) {
                job.duplicateOf().add(new KnowledgeImportJobRecord.Duplicate(duplicate.knowledgeId(),
                        duplicate.title(), duplicate.similarity()));
            }
        }
        return new KnowledgeImportJobRecord.FilePart(documentId, fileName, fileType,
                bytes == null ? 0L : bytes.length, checksum, preview.suggestedTitle(), masked,
                List.copyOf(partChunks));
    }

    /**
     * 预览失败处理（唯一口径）：
     * <ul>
     *   <li>编排层不可用 → <b>不登记任务</b>（回滚）+ 503 {@code E-5002} 可重试（不产生半成品）；</li>
     *   <li>输入被拒绝（400/413/422：加密 PDF/损坏 docx/空文本）→ 任务置 {@code failed}
     *       （{@code error.code=E-5001, retryable=false}），由调用方 201 返回 failed 任务。</li>
     * </ul>
     */
    private void handlePreviewFailure(KnowledgeImportJobRecord job, RagImportException ex, String traceId) {
        if (ex.unavailable()) {
            repository.remove(job.jobId());
            log.warn("[knowledge-import] 编排层不可用，任务未登记 kind={} traceId={}", ex.label(), traceId);
            throw KnowledgeImportException.upstreamUnavailable(
                    "知识解析服务不可用（" + ex.label() + "），任务未创建，可稍后重试",
                    Map.of("kind", ex.label()), ex.retryable());
        }
        String message = ex.getMessage() == null ? "解析失败" : ex.getMessage();
        job.fail(ErrorCode.E_5001, message, false, nowIso());
        log.info("[knowledge-import] 解析失败，任务置 failed jobId={} reason={} traceId={}", job.jobId(),
                safeReason(ex), traceId);
    }

    private KnowledgeImportJob finishCreate(KnowledgeImportJobRecord job, String idempotencyKey, String fingerprint,
            String traceId) {
        repository.save(job);
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            repository.saveIdempotency(idempotencyKey.trim(), new KnowledgeImportRepository.IdempotencyEntry(
                    "create", fingerprint, job.jobId(), null));
        }
        log.info("[knowledge-import] create ok jobId={} status={} sourceType={} chars={} chunks={} traceId={}",
                job.jobId(), job.status().value(), job.sourceType().value(), job.charCount(), job.chunks().size(),
                traceId);
        return job.toDto();
    }

    /** 创建幂等重放（同键同载荷返回首次任务；不同载荷 409 E-3003）。 */
    private KnowledgeImportJob replayCreate(String idempotencyKey, String fingerprint) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        Optional<KnowledgeImportRepository.IdempotencyEntry> entry = repository.findIdempotency(idempotencyKey);
        if (entry.isEmpty()) {
            return null;
        }
        if (!"create".equals(entry.get().kind()) || !fingerprint.equals(entry.get().fingerprint())) {
            throw KnowledgeImportException.idempotencyConflict(idempotencyKey);
        }
        return repository.find(entry.get().jobId()).map(KnowledgeImportJobRecord::toDto).orElse(null);
    }

    /** 提交幂等重放（同键同载荷返回首次提交结果）。 */
    @SuppressWarnings("unchecked")
    private KnowledgeCommitResult replayCommit(String idempotencyKey, String fingerprint) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        Optional<KnowledgeImportRepository.IdempotencyEntry> entry = repository.findIdempotency(idempotencyKey);
        if (entry.isEmpty()) {
            return null;
        }
        if (!"commit".equals(entry.get().kind()) || !fingerprint.equals(entry.get().fingerprint())) {
            throw KnowledgeImportException.idempotencyConflict(idempotencyKey);
        }
        Object result = entry.get().result();
        if (result instanceof List<?> list) {
            return new KnowledgeCommitResult((List<KnowledgeCommitItem>) list);
        }
        return new KnowledgeCommitResult(List.of());
    }

    /** 限流（≤10 次/分钟/账号）+ 并发（在途任务 ≤2）。 */
    private void checkQuota(Actor actor, String actorId) {
        RateLimiter.Decision decision = rateLimiter.tryAcquire(rateLimitKey(actorId),
                properties.getMaxPerMinute(), RATE_WINDOW_SECONDS);
        if (!decision.allowed()) {
            long retryAfter = Math.max(1L, (decision.retryAfterMillis() + 999L) / 1000L);
            throw KnowledgeImportException.rateLimited(
                    "导入过于频繁（上限 " + properties.getMaxPerMinute() + " 次/分钟），请稍后重试",
                    retryAfter, Map.of("limit", properties.getMaxPerMinute(), "windowSeconds", RATE_WINDOW_SECONDS));
        }
        int inFlight = repository.inFlightCount(actorId);
        if (inFlight >= properties.getMaxConcurrentJobs()) {
            throw KnowledgeImportException.rateLimited(
                    "账号并发导入任务已达上限（" + properties.getMaxConcurrentJobs() + "），请等待当前任务完成",
                    30L, Map.of("limit", properties.getMaxConcurrentJobs(), "inFlight", inFlight));
        }
    }

    // ==================================================================
    // 内部：可见性 / 鉴权 / 解析
    // ==================================================================

    private KnowledgeImportJobRecord requireVisibleJob(String jobId, String scopeProjectId) {
        KnowledgeImportJobRecord job = repository.find(jobId)
                .orElseThrow(() -> KnowledgeImportException.notFound(jobId));
        if (!visible(job, scopeProjectId)) {
            // 非本项目任务对外表现为 404（不区分无权限与不存在，避免探测他人任务）
            throw KnowledgeImportException.notFound(jobId);
        }
        return job;
    }

    private static boolean visible(KnowledgeImportJobRecord job, String scopeProjectId) {
        return scopeProjectId == null || scopeProjectId.isBlank() || scopeProjectId.equals(job.projectId());
    }

    private String resolveProjectId(String scopeProjectId, Actor actor) {
        if (scopeProjectId != null && !scopeProjectId.isBlank()) {
            return scopeProjectId;
        }
        if (actor != null && actor.projectId() != null && !actor.projectId().isBlank()) {
            return actor.projectId();
        }
        // 平台级主体未指定项目：按演示默认项目归属（ProjectService.projectOf 同口径，不产生无项目任务）
        return ProjectService.DEFAULT_PROJECT_ID;
    }

    private void assertCapability(Actor actor, String capability) {
        if (actor == null) {
            // security 关闭的离线演示/单测：与既有领域服务口径一致，不做能力拦截
            log.warn("[knowledge-import] 无演示主体（security 关闭），跳过能力校验 capability={}", capability);
            return;
        }
        if (!PermissionMatrix.allows(actor.role(), capability)) {
            throw KnowledgeImportException.forbiddenCapability(capability,
                    actor.role() == null ? "" : actor.role().value());
        }
    }

    /** 直审权限（契约：{@code status=approved} 仅 it/admin；其它角色 403 E-2001 requiredCapability=knowledge.review）。 */
    private void requireDirectApprovePermission(KnowledgeImportCommitStatus target, Actor actor) {
        if (target != KnowledgeImportCommitStatus.APPROVED) {
            return;
        }
        if (!properties.isDirectApproveEnabled()) {
            throw KnowledgeImportException.directApproveDisabled();
        }
        if (actor == null) {
            return;
        }
        boolean platformRole = actor.role() == ActorRole.IT || actor.role() == ActorRole.ADMIN;
        if (!platformRole || !PermissionMatrix.allows(actor.role(), PermissionMatrix.KNOWLEDGE_REVIEW)) {
            throw KnowledgeImportException.directApproveForbidden(
                    actor.role() == null ? "" : actor.role().value());
        }
    }

    private KnowledgeImportSourceType parsePasteSourceType(String raw) {
        if (raw == null || raw.isBlank()) {
            return KnowledgeImportSourceType.PASTE;
        }
        String text = raw.trim().toLowerCase(Locale.ROOT);
        if ("paste".equals(text)) {
            return KnowledgeImportSourceType.PASTE;
        }
        if ("badcase".equals(text)) {
            return KnowledgeImportSourceType.BADCASE;
        }
        throw KnowledgeImportException.badRequest(
                "sourceType 只允许 paste/badcase（JSON 粘贴入口；文件请走 multipart）",
                Map.of("field", "sourceType", "value", raw, "allowed", List.of("paste", "badcase")));
    }

    private KnowledgeImportSourceType parseFileSourceType(String raw) {
        if (raw == null || raw.isBlank()) {
            return KnowledgeImportSourceType.FILE;
        }
        String text = raw.trim().toLowerCase(Locale.ROOT);
        if ("file".equals(text)) {
            return KnowledgeImportSourceType.FILE;
        }
        if ("batch".equals(text)) {
            return KnowledgeImportSourceType.BATCH;
        }
        throw KnowledgeImportException.badRequest(
                "sourceType 只允许 file/batch（文件上传入口）",
                Map.of("field", "sourceType", "value", raw, "allowed", List.of("file", "batch")));
    }

    private KnowledgeCategory parseCategory(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim().toLowerCase(Locale.ROOT);
        for (KnowledgeCategory category : KnowledgeCategory.values()) {
            if (category.value().equals(text)) {
                return category;
            }
        }
        throw KnowledgeImportException.badRequest("category 不在契约枚举内：" + raw,
                Map.of("field", "category", "value", raw));
    }

    private KnowledgeImportJobStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim().toLowerCase(Locale.ROOT);
        for (KnowledgeImportJobStatus status : KnowledgeImportJobStatus.values()) {
            if (status.value().equals(text)) {
                return status;
            }
        }
        throw KnowledgeImportException.badRequest("status 参数不在任务状态机内：" + raw,
                Map.of("field", "status", "value", raw));
    }

    /** 后缀 + 内容嗅探双重校验（不信任客户端 MIME）；白名单外 400 unsupported_file_type。 */
    private KnowledgeImportFileType requireFileType(String fileName, byte[] content) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        String ext = dot < 0 ? "" : lower.substring(dot + 1);
        KnowledgeImportFileType fileType = switch (ext) {
            case "txt" -> KnowledgeImportFileType.TXT;
            case "md", "markdown" -> KnowledgeImportFileType.MD;
            case "docx" -> KnowledgeImportFileType.DOCX;
            case "pdf" -> KnowledgeImportFileType.PDF;
            case "zip" -> KnowledgeImportFileType.ZIP;
            default -> throw KnowledgeImportException.unsupportedFileType(fileName, ext);
        };
        return fileType;
    }

    /** 内容嗅探（魔数）：docx/pdf/zip 必须匹配真实格式，txt/md 拒绝二进制（NUL）。 */
    private void requireSniffedType(String fileName, KnowledgeImportFileType fileType, byte[] content) {
        boolean zipMagic = content.length >= 2 && content[0] == 'P' && content[1] == 'K';
        boolean pdfMagic = startsWith(content, "%PDF-".getBytes(StandardCharsets.US_ASCII));
        switch (fileType) {
            case DOCX -> {
                if (!zipMagic) {
                    throw KnowledgeImportException.unsupportedFileType(fileName,
                            "docx（文件不是有效 Office Open XML，可能为 .doc 旧格式或已损坏）");
                }
            }
            case PDF -> {
                if (!pdfMagic) {
                    throw KnowledgeImportException.unsupportedFileType(fileName,
                            "pdf（文件缺少 %PDF 头，可能不是 PDF 或已损坏）");
                }
            }
            case ZIP -> {
                if (!zipMagic) {
                    throw KnowledgeImportException.unsupportedFileType(fileName, "zip（文件不是有效 ZIP）");
                }
            }
            case TXT, MD -> {
                for (byte value : content) {
                    if (value == 0) {
                        throw KnowledgeImportException.badRequest(
                                "疑似二进制文件（含 NUL 字节），拒绝按文本解析：" + fileName,
                                Map.of("field", "file", "reason", "encoding_invalid", "fileName", fileName));
                    }
                }
            }
        }
    }

    private static boolean startsWith(byte[] content, byte[] prefix) {
        if (content.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (content[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    // ==================================================================
    // 内部：杂项
    // ==================================================================

    private void auditKnowledgeIndex(String knowledgeId, AuditResult result, String traceId, Actor actor) {
        try {
            auditService.record(new AuditService.AuditRecord(actorIdOf(actor),
                    actor == null || actor.role() == null ? ActorRole.OWNER : actor.role(),
                    AuditAction.KNOWLEDGE_INDEX, AuditResourceType.KNOWLEDGE, knowledgeId, traceId, result, null,
                    List.of()));
        } catch (RuntimeException ex) {
            log.warn("[knowledge-import] knowledge.index 审计写入失败 knowledgeId={} err={}", knowledgeId,
                    ex.getClass().getSimpleName());
        }
    }

    private String commitFingerprint(KnowledgeImportJobRecord job, KnowledgeImportCommitRequest request,
            KnowledgeImportCommitStatus target) {
        StringBuilder builder = new StringBuilder("commit|").append(job.jobId()).append('|')
                .append(target.value());
        if (request != null) {
            builder.append('|').append(nullToEmpty(request.title()))
                    .append('|').append(request.category() == null ? "" : request.category().value())
                    .append('|').append(nullToEmpty(request.owner()))
                    .append('|').append(nullToEmpty(request.effectiveFrom()))
                    .append('|').append(nullToEmpty(request.effectiveTo()))
                    .append('|').append(request.chunks() == null ? "" : request.chunks().toString())
                    .append('|').append(nullToEmpty(request.reviewNote()));
        }
        return sha256Hex(builder.toString());
    }

    private String fingerprint(String kind, String projectId, String title, String fileName,
            KnowledgeCategory category, KnowledgeImportSourceType sourceType, String payloadHash) {
        return sha256Hex(kind + "|" + projectId + "|" + nullToEmpty(title) + "|" + nullToEmpty(fileName) + "|"
                + (category == null ? "" : category.value()) + "|" + sourceType.value() + "|" + payloadHash);
    }

    private String requireText(String field, String raw, int maxLength) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            throw KnowledgeImportException.badRequest(field + " 必填", Map.of("field", field, "required", true));
        }
        if (text.length() > maxLength) {
            throw KnowledgeImportException.badRequest(field + " 长度不能超过 " + maxLength + " 字符",
                    Map.of("field", field, "length", text.length(), "maximum", maxLength));
        }
        return text;
    }

    private String optionalDateText(String field, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim(), DATE).toString();
        } catch (DateTimeParseException ex) {
            throw KnowledgeImportException.badRequest("日期格式非法（" + field + "，应为 YYYY-MM-DD）",
                    Map.of("field", field, "value", raw, "format", "YYYY-MM-DD"));
        }
    }

    private void requireDateRange(String effectiveFrom, String effectiveTo) {
        if (effectiveFrom == null || effectiveTo == null) {
            return;
        }
        if (LocalDate.parse(effectiveFrom).isAfter(LocalDate.parse(effectiveTo))) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("field", "effectiveFrom");
            details.put("reason", "invalid_range");
            details.put("effectiveFrom", effectiveFrom);
            details.put("effectiveTo", effectiveTo);
            throw KnowledgeImportException.badRequest("effectiveFrom 不能晚于 effectiveTo", details);
        }
    }

    private String todayIso() {
        return LocalDate.now(clock.withZone(TicketTimes.BUSINESS_ZONE)).toString();
    }

    private String nowIso() {
        return TicketTimes.iso(clock.instant());
    }

    private static String joinInternalChunks(List<RagImportClient.InternalChunk> chunks) {
        StringBuilder builder = new StringBuilder();
        for (RagImportClient.InternalChunk chunk : chunks) {
            if (chunk.text() == null || chunk.text().isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append("\n\n");
            }
            builder.append(chunk.text());
        }
        return builder.toString();
    }

    private static String concatChecksums(List<KnowledgeImportJobRecord.FilePart> files) {
        StringBuilder builder = new StringBuilder();
        for (KnowledgeImportJobRecord.FilePart part : files) {
            builder.append(part.checksum() == null ? "" : part.checksum()).append('|');
        }
        return builder.toString();
    }

    private static String rawBase64Of(byte[] bytes, KnowledgeImportFileType fileType) {
        if (bytes == null || fileType == null) {
            return null;
        }
        if (fileType == KnowledgeImportFileType.DOCX || fileType == KnowledgeImportFileType.PDF) {
            return Base64.getEncoder().encodeToString(bytes);
        }
        return null;
    }

    /** txt/md 字节 → 文本（utf-8 严格 → gb18030 严格 → utf-8 replace；与编排层解码口径一致，仅用于原文留存）。 */
    static String decodeText(byte[] bytes) {
        for (java.nio.charset.Charset charset : new java.nio.charset.Charset[] {
                java.nio.charset.StandardCharsets.UTF_8, java.nio.charset.Charset.forName("GB18030") }) {
            try {
                return charset.newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            } catch (java.nio.charset.CharacterCodingException ex) {
                // 继续尝试下一个编码
            }
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(64);
            for (byte item : hash) {
                builder.append(String.format("%02x", item));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private static String sha256Hex(byte[] value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value == null ? new byte[0] : value);
            StringBuilder builder = new StringBuilder(64);
            for (byte item : hash) {
                builder.append(String.format("%02x", item));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private static String baseName(String fileName) {
        if (fileName == null) {
            return null;
        }
        String normalized = fileName.replace('\\', '/');
        int index = normalized.lastIndexOf('/');
        String name = index >= 0 ? normalized.substring(index + 1) : normalized;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String safeReason(RagImportException ex) {
        String reason = ex.reason();
        return reason == null || reason.isBlank() ? ex.label() : reason;
    }

    private static String actorIdOf(Actor actor) {
        return actor == null || actor.actorId() == null || actor.actorId().isBlank() ? "anonymous"
                : actor.actorId();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static <T> T firstNonNull(T first, T second) {
        return first != null ? first : second;
    }

    /** 文档仓储大小（诊断/测试用：验证「原文不清理」）。 */
    public int documentCount() {
        return documentRepository.size();
    }

    /** 任务仓储大小（诊断/测试用）。 */
    public int jobCount() {
        return repository.all().size();
    }

    /** 审计资源类型（诊断用；与 {@code knowledge.import.commit} / {@code knowledge.index} 同源）。 */
    public static String resourceType() {
        return RESOURCE_TYPE;
    }
}
