package com.property.ai.bff.api;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.property.ai.bff.dto.DegradedInfo;
import com.property.ai.bff.dto.ErrorCode;

/**
 * 统一 API 响应外壳。字段与 docs/openapi/openapi.yaml v0.3.0 的统一信封对齐：
 *
 * <pre>
 * 成功：{ "ok": true,  "data": {...}, "traceId": "..." , "degraded": {...}? }
 * 失败：{ "ok": false, "error": "E-xxxx", "message": "...", "retryable": false,
 *         "details": {...}, "degraded": {...}?, "traceId": "..." }
 * </pre>
 *
 * P2 约定：{@code error} 是机器可读错误码，新端点只允许 17 类 {@link ErrorCode}；
 * P1 屏 mock 遗留业务码（如 {@code SCREEN_NOT_FOUND}）仅为兼容保留。
 * P1 既有 4 参数构造顺序保持不变（ok/data/error/traceId），message/degraded/details/retryable 追加在末尾，
 * 因此 P1 Controller 与单元测试无需改动；空字段不序列化（{@code @JsonInclude(NON_NULL)}）。
 *
 * <p>P2-009 追加：{@code retryable} 与 {@code details} 对应契约 {@code ErrorResponse.retryable/details}
 * （如 409 E-3002 的 {@code details.allowed}、E-3003 的 {@code details.idempotencyKey}），
 * 由 lead 批准的范围扩展（P2-036 契约复核时同步 OpenAPI 描述）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        boolean ok,
        T data,
        String error,
        String traceId,
        String message,
        DegradedInfo degraded,
        Boolean retryable,
        Map<String, Object> details) {

    /** 兼容构造：4 参数（P1）。 */
    public ApiResponse(boolean ok, T data, String error, String traceId) {
        this(ok, data, error, traceId, null, null, null, null);
    }

    /** 兼容构造：6 参数（P2 第 1 周）。 */
    public ApiResponse(boolean ok, T data, String error, String traceId, String message, DegradedInfo degraded) {
        this(ok, data, error, traceId, message, degraded, null, null);
    }

    /** 成功（P1 兼容）。 */
    public static <T> ApiResponse<T> ok(T data, String traceId) {
        return new ApiResponse<>(true, data, null, traceId);
    }

    /** 成功 + 降级标记（降级不是错误：仍 2xx）。 */
    public static <T> ApiResponse<T> okDegraded(T data, DegradedInfo degraded, String traceId) {
        return new ApiResponse<>(true, data, null, traceId, null, degraded);
    }

    /** 失败（P1 兼容：error 为业务码或 17 类错误码字符串）。 */
    public static <T> ApiResponse<T> error(String error, String traceId) {
        return new ApiResponse<>(false, null, error, traceId);
    }

    /** 失败 + 人话文案（P1/P2 兼容）。 */
    public static <T> ApiResponse<T> error(String error, String message, String traceId) {
        return new ApiResponse<>(false, null, error, traceId, message, null);
    }

    /** 失败 + 17 类错误码（P2 推荐）。 */
    public static <T> ApiResponse<T> error(ErrorCode error, String message, String traceId) {
        return new ApiResponse<>(false, null, error.value(), traceId, message, null);
    }

    /** 失败 + 17 类错误码 + 结构化 details（P2 推荐，用于 409 allowed/version_conflict 等）。 */
    public static <T> ApiResponse<T> error(ErrorCode error, String message, String traceId,
            Map<String, Object> details) {
        return new ApiResponse<>(false, null, error.value(), traceId, message, null, null,
                details == null || details.isEmpty() ? null : details);
    }

    /** 失败 + 错误码 + details + degraded + retryable（完整形态，上游失败/降级转人工时使用）。 */
    public static <T> ApiResponse<T> error(ErrorCode error, String message, String traceId,
            Map<String, Object> details, DegradedInfo degraded, Boolean retryable) {
        return new ApiResponse<>(false, null, error.value(), traceId, message, degraded, retryable,
                details == null || details.isEmpty() ? null : details);
    }
}
