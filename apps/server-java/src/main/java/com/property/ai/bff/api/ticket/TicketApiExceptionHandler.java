package com.property.ai.bff.api.ticket;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.property.ai.bff.api.ApiResponse;
import com.property.ai.bff.dto.ErrorCode;
import com.property.ai.connector.ConnectorException;
import com.property.ai.connector.ConnectorRuntime;
import com.property.ai.domain.ticket.DomainException;

import jakarta.servlet.http.HttpServletRequest;

/**
 * P2 新端点统一异常 → 契约错误信封（{@code {ok:false,error:E-xxxx,message,retryable?,details?,degraded?,traceId}}）。
 *
 * <p>约束（AGENTS.md §3 / 契约 §3）：只用 17 类 {@link ErrorCode}；不新造字符串；
 * 参数/枚举解析失败按契约 400 E-1001/E-1002；未预期异常 500 E-9001；
 * 日志不记房号/手机号/请求体等隐私字段。
 */
@RestControllerAdvice
public class TicketApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(TicketApiExceptionHandler.class);

    private final ConnectorRuntime runtime;

    /**
     * 用 {@link ObjectProvider} 注入：本 Advice 会被 {@code @WebMvcTest} 切片加载（如 P1 屏 mock 测试），
     * 切片里没有连接器运行时 Bean，此时降级信息留空即可，不应导致上下文启动失败。
     */
    public TicketApiExceptionHandler(ObjectProvider<ConnectorRuntime> runtimeProvider) {
        this.runtime = runtimeProvider.getIfAvailable();
    }

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ApiResponse<Object>> handleDomain(DomainException exception, HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        log.info("[api] 业务失败 {} {} traceId={} details={}",
                exception.code().value(), exception.getMessage(), traceId, exception.details());
        return ApiSupport.errorResponse(exception, traceId);
    }

    @ExceptionHandler(ConnectorException.class)
    public ResponseEntity<ApiResponse<Object>> handleConnector(ConnectorException exception,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        log.warn("[api] 连接器失败 {} {} traceId={}", exception.code().value(), exception.getMessage(), traceId);
        return ApiSupport.errorResponse(exception, traceId, runtime);
    }

    /** 请求体不是合法 JSON / 枚举未匹配 → 400（E-1002 字典未匹配，否则 E-1001） */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Object>> handleUnreadable(HttpMessageNotReadableException exception,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        Throwable cause = exception.getMostSpecificCause();
        if (cause instanceof InvalidFormatException format && format.getTargetType() != null
                && format.getTargetType().isEnum()) {
            return ResponseEntity.badRequest().body(ApiResponse.error(ErrorCode.E_1002,
                    "枚举值未匹配：" + format.getValue() + "（请使用契约枚举小写 code）", traceId,
                    Map.of("field", fieldOf(format), "value", String.valueOf(format.getValue())),
                    null, false));
        }
        return ResponseEntity.badRequest().body(ApiResponse.error(ErrorCode.E_1001,
                "请求体不是合法 JSON 或字段类型不匹配", traceId));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Object>> handleTypeMismatch(MethodArgumentTypeMismatchException exception,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        return ResponseEntity.badRequest().body(ApiResponse.error(ErrorCode.E_1001,
                "参数类型/枚举不匹配：" + exception.getName(), traceId,
                Map.of("field", exception.getName(), "value", String.valueOf(exception.getValue())), null, false));
    }

    @ExceptionHandler({ MissingServletRequestParameterException.class, BindException.class })
    public ResponseEntity<ApiResponse<Object>> handleMissingParam(Exception exception, HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        return ResponseEntity.badRequest().body(ApiResponse.error(ErrorCode.E_1001,
                "必填参数缺失：" + exception.getMessage(), traceId));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Object>> handleMethodNotSupported(HttpRequestMethodNotSupportedException exception,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(ApiResponse.error(ErrorCode.E_1001,
                "HTTP 方法不支持：" + exception.getMethod(), traceId));
    }

    /** 未知路由（Spring 6 静态资源兜底）→ 404，避免落到 500 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Object>> handleNoResource(NoResourceFoundException exception,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(ErrorCode.E_3001,
                "接口不存在：" + request.getRequestURI(), traceId));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleUnexpected(Exception exception, HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        log.error("[api] 未预期异常 traceId={} uri={}", traceId, request.getRequestURI(), exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(ErrorCode.E_9001, "服务内部错误，已记录 traceId，请稍后重试", traceId));
    }

    private static String fieldOf(InvalidFormatException format) {
        if (format.getPath() == null || format.getPath().isEmpty()) {
            return "";
        }
        return String.valueOf(format.getPath().get(format.getPath().size() - 1).getFieldName());
    }
}
