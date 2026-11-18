package com.property.ai.domain.knowledge.importer;

import java.util.LinkedHashMap;
import java.util.Map;

import com.property.ai.bff.dto.DegradedInfo;
import com.property.ai.bff.dto.ErrorCode;

/**
 * 知识导入领域异常（task-86，契约 v1.5.0 imports 5 端点）。
 *
 * <p>为什么单独一个异常类：imports 需要 413 {@code E-1001}（载荷超限）、429 {@code E-4003}（超频/并发）、
 * 503 {@code E-5002}（编排层不可用）三种 HTTP 状态，而既有 {@code DomainException} 的工厂不覆盖 413/429
 * （且该文件不在本任务 write_scope 内）。本类<b>只复用 17 类错误码</b>，<b>不新造错误码字符串</b>，
 * 由 {@code bff/api/knowledge/KnowledgeImportExceptionHandler} 统一转契约错误信封。
 *
 * <p>错误码 ↔ HTTP 口径（契约 §3 / ErrorResponse 描述）：
 * <pre>
 * 400 E-1001 参数非法/类型不支持/空文本    403 E-2001 无能力/直审越权
 * 404 E-3001 任务不存在或不可见            409 E-3002 状态不允许 / E-3003 幂等冲突
 * 413 E-1001 载荷超限（reason=payload_too_large）
 * 429 E-4003 超频/并发（reason=rate_limited）503 E-5002 编排层不可用（不产生半成品）
 * </pre>
 */
public class KnowledgeImportException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode code;
    private final int httpStatus;
    private final Map<String, Object> details;
    private final DegradedInfo degraded;
    private final boolean retryable;

    private KnowledgeImportException(ErrorCode code, int httpStatus, String message, Map<String, Object> details,
            DegradedInfo degraded, boolean retryable) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
        this.details = details == null ? Map.of() : new LinkedHashMap<>(details);
        this.degraded = degraded;
        this.retryable = retryable;
    }

    public ErrorCode code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public Map<String, Object> details() {
        return Map.copyOf(details);
    }

    public DegradedInfo degraded() {
        return degraded;
    }

    public boolean retryable() {
        return retryable;
    }

    // ------------------------------------------------------------------
    // 工厂
    // ------------------------------------------------------------------

    /** 400 E-1001 参数缺失/非法（{@code details.reason} 区分 invalid_field 等） */
    public static KnowledgeImportException badRequest(String message, Map<String, Object> details) {
        return new KnowledgeImportException(ErrorCode.E_1001, 400, message, details, null, false);
    }

    /** 400 E-1001 文件类型不在白名单（{@code details.reason=unsupported_file_type}） */
    public static KnowledgeImportException unsupportedFileType(String fileName, String fileType) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", "unsupported_file_type");
        details.put("field", "file");
        details.put("fileName", fileName == null ? "" : fileName);
        details.put("fileType", fileType == null ? "" : fileType);
        details.put("allowed", java.util.List.of("txt", "md", "docx", "pdf", "zip(batch)"));
        return badRequest("不支持的文件类型（白名单 txt/md/docx/pdf，批量另加 zip）", details);
    }

    /** 413 E-1001 载荷超限（单文件 >10MB / 粘贴 >200,000 字符 / ZIP >50 文件或 >50MB） */
    public static KnowledgeImportException payloadTooLarge(String message, String reason, Map<String, Object> details) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (details != null) {
            merged.putAll(details);
        }
        merged.put("reason", reason == null ? "payload_too_large" : reason);
        return new KnowledgeImportException(ErrorCode.E_1001, 413, message, merged, null, false);
    }

    /** 429 E-4003 导入超频 / 并发任务超限（{@code details.reason=rate_limited}） */
    public static KnowledgeImportException rateLimited(String message, long retryAfterSeconds,
            Map<String, Object> details) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (details != null) {
            merged.putAll(details);
        }
        merged.put("reason", "rate_limited");
        merged.put("retryAfterSeconds", Math.max(1L, retryAfterSeconds));
        return new KnowledgeImportException(ErrorCode.E_4003, 429, message, merged, null, true);
    }

    /** 403 E-2001 能力拒绝（{@code details{requiredCapability, role, reason=capability_denied}}） */
    public static KnowledgeImportException forbiddenCapability(String requiredCapability, String role) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("requiredCapability", requiredCapability == null ? "" : requiredCapability);
        details.put("role", role == null ? "" : role);
        details.put("reason", "capability_denied");
        return new KnowledgeImportException(ErrorCode.E_2001, 403, "当前角色无知识导入权限", details, null, false);
    }

    /** 403 E-2001 直审越权（{@code details{requiredCapability=knowledge.review, role}}） */
    public static KnowledgeImportException directApproveForbidden(String role) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("requiredCapability", "knowledge.review");
        details.put("role", role == null ? "" : role);
        details.put("reason", "capability_denied");
        return new KnowledgeImportException(ErrorCode.E_2001, 403,
                "仅 it/admin 可直审导入知识（status=approved）", details, null, false);
    }

    /** 403 E-2001 直审开关关闭（演示/合规收紧，仅配置为 false 时出现） */
    public static KnowledgeImportException directApproveDisabled() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", "direct_approve_disabled");
        details.put("hint", "配置 property.knowledge.import.direct-approve-enabled=false 时只允许待审");
        return new KnowledgeImportException(ErrorCode.E_2001, 403, "直审已关闭，请以 pending_review 提交", details,
                null, false);
    }

    /** 404 E-3001 任务不存在或不可见（非本项目任务同样 404，不区分「无权限」与「不存在」） */
    public static KnowledgeImportException notFound(String jobId) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", "not_found");
        details.put("jobId", jobId == null ? "" : jobId);
        return new KnowledgeImportException(ErrorCode.E_3001, 404, "导入任务不存在或不可见：" + jobId, details, null,
                false);
    }

    /** 409 E-3002 状态机非法流转（{@code details.reason} 取 invalid_transition / already_committed） */
    public static KnowledgeImportException conflict(String message, String reason, Map<String, Object> details) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (details != null) {
            merged.putAll(details);
        }
        merged.put("reason", reason == null ? "invalid_transition" : reason);
        return new KnowledgeImportException(ErrorCode.E_3002, 409, message, merged, null, false);
    }

    /** 409 E-3003 幂等键冲突（同键不同载荷） */
    public static KnowledgeImportException idempotencyConflict(String idempotencyKey) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", "idempotency_conflict");
        details.put("idempotencyKey", idempotencyKey == null ? "" : idempotencyKey);
        return new KnowledgeImportException(ErrorCode.E_3003, 409,
                "同一幂等键不能携带不同载荷（请复用首次请求载荷或更换键）", details, null, false);
    }

    /**
     * 503 E-5002 编排层不可用（可重试；<b>不产生半成品</b>：创建不留任务、直审失败回滚已生成知识）。
     */
    public static KnowledgeImportException upstreamUnavailable(String message, Map<String, Object> details,
            boolean retryable) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (details != null) {
            merged.putAll(details);
        }
        merged.put("reason", "rag_unavailable");
        DegradedInfo degraded = new DegradedInfo(
                "rag_unavailable：" + message + "（导入未完成，可稍后重试）",
                com.property.ai.bff.dto.DegradedFallback.HUMAN, retryable, ErrorCode.E_5002, null);
        return new KnowledgeImportException(ErrorCode.E_5002, 503, message, merged, degraded, retryable);
    }
}
