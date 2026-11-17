package com.property.ai.security;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import com.property.ai.audit.AuditFilter;
import com.property.ai.audit.AuditService;

/**
 * 演示鉴权/审计的 Web 装配（P2-029/P2-030/P2-031）。
 *
 * <p>为什么用 {@code FilterRegistrationBean} 而不是给 Filter 加 {@code @Component}：
 * {@code @WebMvcTest} 切片会把 {@code @Component} 的 {@code Filter} 一起加载，但切片里没有
 * JwtService/AuditService 等 Bean，会导致既有 P1 屏 mock 切片测试（249 条基线内）启动失败；
 * 通过 {@code @Configuration} 注册只在完整上下文（{@code @SpringBootTest}/正式运行）生效，
 * 既有测试零改动。
 *
 * <p>顺序：{@code TraceIdFilter}（{@code @Order(HIGHEST_PRECEDENCE)}，注入 traceId）
 * → {@link SecurityFilter}（鉴权 → 401/403）→ {@link AuditFilter}（写操作审计，可读响应体）
 * → Controller。
 */
@Configuration
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig {

    /** 鉴权过滤器顺序：必须在 TraceIdFilter 之后（复用 traceId 写 401/403 信封与审计） */
    static final int SECURITY_FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    /** 写审计过滤器顺序：必须在 SecurityFilter 之后（需 ActorContext + 已放行） */
    static final int AUDIT_FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 20;

    /** 演示 JWT 服务（HS256 + 本地演示密钥；TTL 默认 7200s，可从配置覆盖） */
    @Bean
    public JwtService jwtService(SecurityProperties properties) {
        return new JwtService(properties.getJwtSecret(), properties.getTokenTtlSeconds(), Clock.systemUTC());
    }

    /** Bearer 鉴权 + 角色 × 能力矩阵过滤器 */
    @Bean
    public FilterRegistrationBean<SecurityFilter> securityFilter(SecurityProperties properties,
            JwtService jwtService, AuditService auditService) {
        FilterRegistrationBean<SecurityFilter> registration =
                new FilterRegistrationBean<>(new SecurityFilter(properties, jwtService, auditService));
        registration.addUrlPatterns("/*");
        registration.setOrder(SECURITY_FILTER_ORDER);
        registration.setName("securityFilter");
        return registration;
    }

    /** 写操作审计过滤器（记录写操作结果 + traceId，供 GET /api/v1/audit/events 查询） */
    @Bean
    public FilterRegistrationBean<AuditFilter> auditFilter(SecurityProperties properties,
            AuditService auditService) {
        FilterRegistrationBean<AuditFilter> registration =
                new FilterRegistrationBean<>(new AuditFilter(properties, auditService));
        registration.addUrlPatterns("/*");
        registration.setOrder(AUDIT_FILTER_ORDER);
        registration.setName("auditFilter");
        return registration;
    }
}
