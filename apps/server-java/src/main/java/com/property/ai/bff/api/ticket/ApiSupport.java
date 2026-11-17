package com.property.ai.bff.api.ticket;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.property.ai.bff.api.ApiResponse;
import com.property.ai.bff.dto.DegradedFallback;
import com.property.ai.bff.dto.DegradedInfo;
import com.property.ai.bff.dto.ErrorCode;
import com.property.ai.connector.ConnectorException;
import com.property.ai.connector.ConnectorRuntime;
import com.property.ai.domain.ticket.DomainException;

import jakarta.servlet.http.HttpServletRequest;

/**
 * REST 层公共口径（P2 新端点共用）：traceId、幂等键、房号归一、错误码 → HTTP 状态、统一错误信封。
 *
 * <p>契约依据（{@code docs/openapi/README.md} §2 / {@code openapi.yaml} ErrorResponse 描述）：
 * <pre>
 * HTTP ↔ 错误码：400=E-1xxx；403=E-2xxx；404=E-3001；409=E-3002/E-3003；
 *               429=E-4003；503=E-4001/E-4002/E-5001/E-5002；500=E-9001
 * </pre>
 */
public final class ApiSupport {

    /** traceId 透传头（与连接器出站 X-Trace-Id 同名，便于全链路回溯） */
    public static final String TRACE_HEADER = "X-Trace-Id";
    /** 请求属性名：traceId 在本次请求内复用 */
    public static final String TRACE_ATTRIBUTE = "com.property.ai.traceId";

    private static final int IDEMPOTENCY_KEY_MIN = 8;
    private static final int IDEMPOTENCY_KEY_MAX = 64;
    private static final java.util.regex.Pattern ROOM_CODE_PATTERN =
            java.util.regex.Pattern.compile("^\\d{1,2}-\\d{1,2}-\\d{3,4}$");

    private ApiSupport() {
    }

    /** 取 traceId：优先请求内已解析（Filter 注入）→ X-Trace-Id/X-Request-Id 头 → 生成 UUID */
    public static String traceId(HttpServletRequest request) {
        if (request == null) {
            return UUID.randomUUID().toString();
        }
        Object attribute = request.getAttribute(TRACE_ATTRIBUTE);
        if (attribute instanceof String text && !text.isBlank()) {
            return text;
        }
        String header = request.getHeader(TRACE_HEADER);
        if (header == null || header.isBlank()) {
            header = request.getHeader("X-Request-Id");
        }
        return header == null || header.isBlank() ? UUID.randomUUID().toString() : header.trim();
    }

    /** 幂等键必填校验（契约：写操作 8—64 字符，缺 → 400 E-1001） */
    public static String requireIdempotencyKey(String raw) {
        String key = raw == null ? "" : raw.trim();
        if (key.isEmpty()) {
            throw DomainException.badRequest("写操作必须携带 Idempotency-Key 头（8—64 字符）", Map.of(
                    "field", "Idempotency-Key",
                    "required", true));
        }
        if (key.length() < IDEMPOTENCY_KEY_MIN || key.length() > IDEMPOTENCY_KEY_MAX) {
            throw DomainException.badRequest("Idempotency-Key 长度必须为 8—64 字符", Map.of(
                    "field", "Idempotency-Key",
                    "length", key.length()));
        }
        return key;
    }

    /**
     * 房号归一（契约 §2：房号统一 {@code roomCode}，如 {@code 1-1-101}）。
     * 兼容全角连字符/空格；无法归一 → 400 E-1003。
     */
    public static String requireRoomCode(String raw) {
        String code = raw == null ? "" : raw.trim().replace('－', '-').replace('—', '-').replace(" ", "");
        if (code.isEmpty() || !ROOM_CODE_PATTERN.matcher(code).matches()) {
            throw DomainException.badRoomCode(raw);
        }
        return code;
    }

    /** 分页参数校验（契约 §5：page ≥1，pageSize 1—100） */
    public static void requirePageParams(int page, int pageSize) {
        if (page < 1) {
            throw DomainException.badRequest("page 必须 ≥ 1", Map.of("field", "page", "page", page));
        }
        if (pageSize < 1 || pageSize > 100) {
            throw DomainException.badRequest("pageSize 必须在 1—100 之间",
                    Map.of("field", "pageSize", "pageSize", pageSize, "maximum", 100));
        }
    }

    /** 错误码 → HTTP 状态（契约 ErrorResponse 描述） */
    public static HttpStatus statusFor(ErrorCode code) {
        if (code == null) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return switch (code) {
            case E_1001, E_1002, E_1003 -> HttpStatus.BAD_REQUEST;
            case E_2001, E_2002, E_2003 -> HttpStatus.FORBIDDEN;
            case E_3001 -> HttpStatus.NOT_FOUND;
            case E_3002, E_3003 -> HttpStatus.CONFLICT;
            case E_4003 -> HttpStatus.TOO_MANY_REQUESTS;
            case E_4001, E_4002, E_5001, E_5002, E_6001, E_6002 -> HttpStatus.SERVICE_UNAVAILABLE;
            case E_9001 -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    /** 领域异常 → 统一错误信封（自动带 details/degraded/retryable） */
    public static <T> ResponseEntity<ApiResponse<T>> errorResponse(DomainException exception, String traceId) {
        HttpStatus status = exception.httpStatus() == null
                ? statusFor(exception.code())
                : HttpStatus.valueOf(exception.httpStatus());
        return ResponseEntity.status(status)
                .body(ApiResponse.error(exception.code(), exception.getMessage(), traceId,
                        exception.details(), exception.degraded(), exception.retryable()));
    }

    /** 连接器异常 → 统一错误信封（读失败转人工/降级；写失败由领域层包装为 DomainException） */
    public static <T> ResponseEntity<ApiResponse<T>> errorResponse(ConnectorException exception, String traceId,
            ConnectorRuntime runtime) {
        DegradedInfo degraded = runtime == null
                ? null
                : runtime.degradedFromFailure(exception, DegradedFallback.HUMAN);
        Map<String, Object> details = exception.details();
        return ResponseEntity.status(statusFor(exception.code()))
                .body(ApiResponse.error(exception.code(), exception.getMessage(), traceId, details, degraded,
                        exception.retryable()));
    }
}
