package com.property.ai.bff.api.rag;

import com.property.ai.bff.dto.ErrorCode;

/**
 * RAG 编排层调用失败（P6 W30 / task-80）：只做<b>错误分类</b>，不携带请求/响应原文与 token。
 *
 * <h2>为什么需要独立异常</h2>
 * 编排层是 Java 的外部依赖（Python 内网服务）。契约口径：
 * <ul>
 *   <li>问答：不可用/超时/5xx → <b>降级规则检索</b>（{@code mode=rules} + 2xx，降级不是错误）；</li>
 *   <li>健康：不可用 → {@code status=degraded}（仍 200）；</li>
 *   <li>重建索引：不可用 → 503 {@code E-5001} + {@code degraded}（写操作不假成功）。</li>
 * </ul>
 * 分类结果直接决定上述三条路径，故异常只暴露 {@link Kind}（可读原因）+ 契约错误码，
 * <b>不记录</b>问题原文/答案正文/token（AGENTS.md NEVER #8）。
 */
public class RagClientException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 失败分类（决定降级路径与日志文案） */
    public enum Kind {
        /** {@code property.rag.enabled=false}：显式关闭出站 */
        DISABLED("编排层调用已关闭"),
        /** 未配置 {@code internal-token}：编排层 fail-closed 401，本站不发请求 */
        TOKEN_MISSING("内网 token 未配置"),
        /** 建连/读超时（JDK {@code HttpTimeoutException}） */
        TIMEOUT("编排层响应超时"),
        /** 连接被拒/DNS/断链等传输层错误 */
        TRANSPORT("编排层不可达"),
        /** 编排层 401/403（token 不匹配等配置缺陷） */
        UNAUTHORIZED("编排层鉴权失败"),
        /** 编排层 5xx（含 502/503/504） */
        UPSTREAM_ERROR("编排层上游错误"),
        /** 编排层 404 等：端点未提供（W33 未就绪时的正常状态） */
        ENDPOINT_MISSING("编排层端点未就绪"),
        /** 编排层 4xx（请求不合法，属集成缺陷） */
        BAD_REQUEST("编排层拒绝请求"),
        /** 响应不是合法 JSON / 缺必需字段（映射失败） */
        INVALID_RESPONSE("编排层响应不可解析");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        /** 人话标签（日志/降级原因用，不含隐私） */
        public String label() {
            return label;
        }
    }

    private final Kind kind;
    private final ErrorCode code;
    private final Integer upstreamStatus;
    private final boolean retryable;

    public RagClientException(Kind kind, String message, ErrorCode code, Integer upstreamStatus, boolean retryable,
            Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.code = code == null ? ErrorCode.E_5001 : code;
        this.upstreamStatus = upstreamStatus;
        this.retryable = retryable;
    }

    public Kind kind() {
        return kind;
    }

    /** 契约 17 类错误码口径：不可用/超时 = E-5001；响应不可解析 = E-5002 */
    public ErrorCode code() {
        return code;
    }

    /** 编排层 HTTP 状态（传输层/超时为 null） */
    public Integer upstreamStatus() {
        return upstreamStatus;
    }

    /** 是否值得稍后重试（降级信息 retryable 字段用） */
    public boolean retryable() {
        return retryable;
    }

    // ------------------------------------------------------------------
    // 工厂
    // ------------------------------------------------------------------

    public static RagClientException disabled() {
        return new RagClientException(Kind.DISABLED,
                "RAG 编排层调用已关闭（property.rag.enabled=false）", ErrorCode.E_5001, null, true, null);
    }

    public static RagClientException tokenMissing() {
        return new RagClientException(Kind.TOKEN_MISSING,
                "RAG 编排层内网 token 未配置（property.rag.internal-token / RAG_INTERNAL_TOKEN）",
                ErrorCode.E_5001, null, true, null);
    }

    public static RagClientException timeout(long timeoutMs, String path) {
        return new RagClientException(Kind.TIMEOUT,
                "编排层 " + path + " 超过 " + timeoutMs + "ms 未返回", ErrorCode.E_5001, null, true, null);
    }

    public static RagClientException transport(String path, Throwable cause) {
        return new RagClientException(Kind.TRANSPORT,
                "编排层 " + path + " 不可达（" + (cause == null ? "unknown" : cause.getClass().getSimpleName()) + "）",
                ErrorCode.E_5001, null, true, cause);
    }

    public static RagClientException upstream(String path, int status) {
        if (status == 401 || status == 403) {
            return new RagClientException(Kind.UNAUTHORIZED,
                    "编排层 " + path + " 鉴权失败（HTTP " + status + "；检查 X-Internal-Token 配置）",
                    ErrorCode.E_5001, status, false, null);
        }
        if (status == 404 || status == 405) {
            return new RagClientException(Kind.ENDPOINT_MISSING,
                    "编排层 " + path + " 端点未就绪（HTTP " + status + "）", ErrorCode.E_5001, status, true, null);
        }
        if (status >= 500) {
            return new RagClientException(Kind.UPSTREAM_ERROR,
                    "编排层 " + path + " 上游错误（HTTP " + status + "）", ErrorCode.E_5001, status, true, null);
        }
        return new RagClientException(Kind.BAD_REQUEST,
                "编排层 " + path + " 拒绝请求（HTTP " + status + "）", ErrorCode.E_5001, status, false, null);
    }

    public static RagClientException invalidResponse(String path, String detail) {
        return new RagClientException(Kind.INVALID_RESPONSE,
                "编排层 " + path + " 响应不可解析：" + detail, ErrorCode.E_5002, null, false, null);
    }

    /** 降级原因（人话，带分类标记；不含 token/原文/内网地址） */
    public String degradedReason() {
        return kind.name().toLowerCase(java.util.Locale.ROOT) + "：" + getMessage();
    }
}
