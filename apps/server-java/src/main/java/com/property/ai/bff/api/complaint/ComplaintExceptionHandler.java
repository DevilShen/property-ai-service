package com.property.ai.bff.api.complaint;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.property.ai.bff.api.ApiResponse;
import com.property.ai.bff.api.ticket.ApiSupport;
import com.property.ai.domain.complaint.ComplaintException;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 投诉域异常 → 契约统一错误信封（P3-023）。
 *
 * <p>为什么单独一个 {@code @RestControllerAdvice}：投诉域使用自带的
 * {@link ComplaintException}（不动 W5—W9 工单域 {@code DomainException} 文件，避免与并行任务冲突），
 * 这里按 {@link com.property.ai.bff.dto.ErrorCode} 推导 HTTP 状态并与
 * {@code ApiResponse.error(...)} 同信封输出（{@code ok:false / error:E-xxxx / message / details / traceId / retryable:false}）。
 *
 * <p>{@code @Order(HIGHEST_PRECEDENCE)}：保证本 Advice 优先匹配 {@link ComplaintException}，
 * 其它异常继续落到 {@code TicketApiExceptionHandler}（其 {@code Exception} 兜底不受影响）。
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ComplaintExceptionHandler {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ComplaintExceptionHandler.class);

    @ExceptionHandler(ComplaintException.class)
    public ResponseEntity<ApiResponse<Object>> handleComplaint(ComplaintException exception,
            HttpServletRequest request) {
        HttpStatus status = exception.httpStatus() == null
                ? ApiSupport.statusFor(exception.code())
                : HttpStatus.valueOf(exception.httpStatus());
        String traceId = ApiSupport.traceId(request);
        log.info("[complaint] 业务失败 {} {} traceId={} details={}",
                exception.code().value(), exception.getMessage(), traceId, exception.details());
        return ResponseEntity.status(status).body(ApiResponse.error(exception.code(), exception.getMessage(),
                traceId, exception.details(), null, false));
    }
}
