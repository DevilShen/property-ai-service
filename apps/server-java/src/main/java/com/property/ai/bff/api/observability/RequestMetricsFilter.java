package com.property.ai.bff.api.observability;

import java.io.IOException;

import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * HTTP 请求计数过滤器（P5-020 / task-65）。
 *
 * <p>在 {@code doFilter} 返回后读响应状态码并累计到 {@link RequestMetrics}（因此 401/403 由
 * {@code SecurityFilter} 写出、5xx 由异常处理器写出，都会被计入）。<b>不读请求体、不记录路径/角色</b>，
 * 只记状态码（隐私与开销红线）。
 *
 * <p>注册方式：{@link ObservabilityConfig} 里的 {@code FilterRegistrationBean}（<b>不加 {@code @Component}</b>，
 * 避免 {@code @WebMvcTest} 切片把过滤器一并加载而缺少依赖，沿用 {@code SecurityConfig} 的既有口径）。
 */
public class RequestMetricsFilter extends OncePerRequestFilter {

    private final RequestMetrics metrics;

    public RequestMetricsFilter(RequestMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } finally {
            metrics.record(response.getStatus());
        }
    }
}
