package com.property.ai.domain.knowledge.importer;

import com.property.ai.bff.dto.ErrorCode;

/**
 * 知识导入编排层调用失败（task-86，契约 v1.5.0 imports）。
 *
 * <p>与 {@code bff/api/rag/RagClientException} 同构（只做错误分类，不携带请求/响应原文与 token），
 * 但导入语义不同：
 * <ul>
 *   <li><b>不可用</b>（传输/超时/5xx/端点未就绪/token 缺失）→ 调用方转 503 {@code E-5002}，
 *       <b>创建不留半成品任务</b>、直审失败回滚已生成知识；</li>
 *   <li><b>被拒绝</b>（编排层 400/413/422：二进制/加密/空文本/不支持类型）→ 创建任务置 {@code failed}
 *       + {@code error{code=E-5001, retryable=false}}（按契约示例：加密 PDF → E-5001 不可重试）；</li>
 *   <li><b>响应不可解析</b>（非 JSON / 缺必需字段）→ 按上游不可用处理（503 {@code E-5002}）。</li>
 * </ul>
 */
public class RagImportException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 失败分类（决定失败路径） */
    public enum Kind {
        /** 传输层不可达（连接被拒/DNS/断链） */
        UNAVAILABLE,
        /** 调用超时 */
        TIMEOUT,
        /** 内网 token 未配置 / 编排层 401、403（配置缺陷，同样不可用） */
        UNAUTHORIZED,
        /** 编排层 5xx */
        UPSTREAM_ERROR,
        /** 编排层端点未就绪（404/405） */
        ENDPOINT_MISSING,
        /** 出站开关关闭（property.rag.enabled=false） */
        DISABLED,
        /** 编排层明确拒绝输入（400/413/422，含 reason） */
        REJECTED,
        /** 响应不是合法 JSON / 缺必需字段 */
        INVALID_RESPONSE
    }

    private final Kind kind;
    private final ErrorCode code;
    private final Integer upstreamStatus;
    private final boolean retryable;
    /** 编排层机器可读原因（如 pdf_encrypted / empty_text / unsupported_file_type） */
    private final String reason;

    private RagImportException(Kind kind, String message, ErrorCode code, Integer upstreamStatus, boolean retryable,
            String reason, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.code = code == null ? ErrorCode.E_5002 : code;
        this.upstreamStatus = upstreamStatus;
        this.retryable = retryable;
        this.reason = reason == null ? "" : reason;
    }

    public Kind kind() {
        return kind;
    }

    public ErrorCode code() {
        return code;
    }

    public Integer upstreamStatus() {
        return upstreamStatus;
    }

    public boolean retryable() {
        return retryable;
    }

    public String reason() {
        return reason;
    }

    /** 是否属于「编排层不可用」（→ 503，不产生半成品） */
    public boolean unavailable() {
        return switch (kind) {
            case UNAVAILABLE, TIMEOUT, UNAUTHORIZED, UPSTREAM_ERROR, ENDPOINT_MISSING, DISABLED, INVALID_RESPONSE -> true;
            case REJECTED -> false;
        };
    }

    // ------------------------------------------------------------------
    // 工厂
    // ------------------------------------------------------------------

    static RagImportException disabled() {
        return new RagImportException(Kind.DISABLED,
                "RAG 编排层调用已关闭（property.rag.enabled=false）", ErrorCode.E_5002, null, true, "", null);
    }

    static RagImportException tokenMissing() {
        return new RagImportException(Kind.UNAUTHORIZED,
                "RAG 编排层内网 token 未配置（property.rag.internal-token / RAG_INTERNAL_TOKEN）",
                ErrorCode.E_5002, null, false, "", null);
    }

    static RagImportException timeout(long timeoutMs, String path) {
        return new RagImportException(Kind.TIMEOUT,
                "编排层 " + path + " 超过 " + timeoutMs + "ms 未返回", ErrorCode.E_5002, null, true, "", null);
    }

    static RagImportException transport(String path, Throwable cause) {
        return new RagImportException(Kind.UNAVAILABLE,
                "编排层 " + path + " 不可达（"
                        + (cause == null ? "unknown" : cause.getClass().getSimpleName()) + "）",
                ErrorCode.E_5002, null, true, "", cause);
    }

    static RagImportException unauthorized(String path, int status) {
        return new RagImportException(Kind.UNAUTHORIZED,
                "编排层 " + path + " 鉴权失败（HTTP " + status + "；检查 X-Internal-Token 配置）",
                ErrorCode.E_5002, status, false, "", null);
    }

    static RagImportException upstream(String path, int status) {
        if (status == 404 || status == 405) {
            return new RagImportException(Kind.ENDPOINT_MISSING,
                    "编排层 " + path + " 端点未就绪（HTTP " + status + "）", ErrorCode.E_5002, status, true, "", null);
        }
        return new RagImportException(Kind.UPSTREAM_ERROR,
                "编排层 " + path + " 上游错误（HTTP " + status + "）", ErrorCode.E_5002, status, true, "", null);
    }

    static RagImportException rejected(String path, int status, String message, String reason) {
        return new RagImportException(Kind.REJECTED,
                message == null || message.isBlank()
                        ? "编排层 " + path + " 拒绝输入（HTTP " + status + "）" : message,
                ErrorCode.E_5001, status, false, reason, null);
    }

    static RagImportException invalidResponse(String path, String detail) {
        return new RagImportException(Kind.INVALID_RESPONSE,
                "编排层 " + path + " 响应不可解析：" + detail, ErrorCode.E_5002, null, false, "", null);
    }

    /** 失败分类标签（日志/降级原因用，不含 token/原文/内网地址） */
    public String label() {
        return kind.name().toLowerCase(java.util.Locale.ROOT);
    }
}
