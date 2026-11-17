package com.property.ai.bff.api.dunning;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.property.ai.bff.api.ApiResponse;
import com.property.ai.bff.api.ticket.ApiSupport;
import com.property.ai.domain.dunning.DunningException;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 催缴域异常 → 契约统一错误信封（P3-013/014）。
 *
 * <p>为什么单独一个 {@code @RestControllerAdvice}：催缴域使用自带的 {@link DunningException}
 * （不动 W5—W9 工单域 {@code DomainException}，避免与并行任务冲突），这里按 {@code ErrorCode}
 * 推导 HTTP 状态，与 {@code ApiResponse.error(...)} 同信封输出
 * （{@code ok:false / error:E-xxxx / message / details / traceId / retryable:false}）。
 *
 * <p>{@code @Order(HIGHEST_PRECEDENCE)}：保证本 Advice 优先匹配 {@link DunningException}；
 * mock 通道失败（{@code ConnectorException}）仍由既有 {@code TicketApiExceptionHandler} 输出
 * 503 + {@code degraded{fallback=human}}（不伪造成功）。
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DunningExceptionHandler {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DunningExceptionHandler.class);

    @ExceptionHandler(DunningException.class)
    public ResponseEntity<ApiResponse<Object>> handleDunning(DunningException exception,
            HttpServletRequest request) {
        HttpStatus status = exception.httpStatus() == null
                ? ApiSupport.statusFor(exception.code())
                : HttpStatus.valueOf(exception.httpStatus());
        String traceId = ApiSupport.traceId(request);
        log.info("[dunning] 业务失败 {} {} traceId={} details={}",
                exception.code().value(), exception.getMessage(), traceId, exception.details());
        return ResponseEntity.status(status).body(ApiResponse.error(exception.code(), exception.getMessage(),
                traceId, exception.details(), null, false));
    }
}
