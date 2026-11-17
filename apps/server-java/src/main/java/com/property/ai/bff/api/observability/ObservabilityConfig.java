package com.property.ai.bff.api.observability;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * 可观测性装配（P5-020 / task-65）。
 *
 * <p><b>内部演示端点：契约 v1.3.0 未声明 {@code /api/v1/observability/*}，P5-024 契约补口</b>。
 *
 * <p>只注册一个轻量 HTTP 计数过滤器（{@link RequestMetricsFilter}），顺序在 {@code TraceIdFilter} 之后、
 * {@code SecurityFilter} 之前（{@code HIGHEST_PRECEDENCE + 5}）：这样它包住鉴权/审计链路，
 * 401/403/5xx 都能被计数，且不改变既有过滤器的相对顺序与行为。
 */
@Configuration
public class ObservabilityConfig {

    /** 计数过滤器顺序：TraceIdFilter（+0）之后、SecurityFilter（+10）之前 */
    static final int REQUEST_METRICS_FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 5;

    @Bean
    public FilterRegistrationBean<RequestMetricsFilter> requestMetricsFilter(RequestMetrics requestMetrics) {
        FilterRegistrationBean<RequestMetricsFilter> registration =
                new FilterRegistrationBean<>(new RequestMetricsFilter(requestMetrics));
        registration.addUrlPatterns("/*");
        registration.setOrder(REQUEST_METRICS_FILTER_ORDER);
        registration.setName("requestMetricsFilter");
        return registration;
    }
}
