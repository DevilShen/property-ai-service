package com.property.ai.bff.api.ticket;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * traceId 过滤器：调用方传 {@code X-Trace-Id}（或 {@code X-Request-Id}）则透传，否则生成 UUID；
 * 请求内通过 {@link ApiSupport#traceId(HttpServletRequest)} 复用，响应回写 {@code X-Trace-Id} 头。
 *
 * <p>用途：契约统一信封 {@code traceId} + 连接器出站 {@code X-Trace-Id} + 后续审计（P2-031）
 * 三者同源，便于端到端回溯（设计 §24.8 Trace 维度）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TraceIdFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = request.getHeader(ApiSupport.TRACE_HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = request.getHeader("X-Request-Id");
        }
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }
        traceId = traceId.trim();
        request.setAttribute(ApiSupport.TRACE_ATTRIBUTE, traceId);
        response.setHeader(ApiSupport.TRACE_HEADER, traceId);
        long startedAt = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
            // 日志只记方法与耗时，不记房号/手机号/请求体（AGENTS.md 隐私红线）
            log.info("[api] {} {} -> {} ({}ms) traceId={}",
                    request.getMethod(), request.getRequestURI(), response.getStatus(), elapsedMs, traceId);
        }
    }
}
