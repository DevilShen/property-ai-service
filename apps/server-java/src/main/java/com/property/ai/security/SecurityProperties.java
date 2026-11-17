package com.property.ai.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 演示鉴权配置（前缀 {@code property.security}，P2 W8 / 契约 v0.3.3 §8）。
 *
 * <p><b>demo 口径（不得说成真实鉴权）</b>：P2 是演示身份 + 本地演示 JWT（HS256），
 * 不接真实 SSO/微信/短信；{@code jwt-secret} 是仓库内公开的演示密钥，<b>不得用于生产</b>。
 * 生产接入真实身份体系时：密钥走 KMS/环境变量 + 轮换，并新增独立未认证/无权限错误码（待 lead 裁决）。
 *
 * <p>取值来源与契约/README 对齐：
 * <ul>
 *   <li>{@code property.security.enabled}：默认 {@code true}（W8 起业务端点需要 Bearer token）；
 *       测试资源 {@code src/test/resources/application.properties} 置 {@code false}，
 *       保证 W5—W7 既有 249 条测试不回归（鉴权由专门的 auth 测试类开启验证）。</li>
 *   <li>{@code property.security.jwt-secret}：HS256 演示密钥（默认值仅本地演示/联调用）。</li>
 *   <li>{@code property.security.token-ttl-seconds}：token 有效期，默认
 *       {@code AUTH_TOKEN_TTL_SECONDS = 7200} 秒（shared/OpenAPI 同值）。</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "property.security")
public class SecurityProperties {

    /** 演示鉴权总开关（默认 true；生产必须为 true，仅测试/离线演示置 false） */
    private boolean enabled = true;

    /** HS256 演示密钥（公开演示值，禁止用于生产；生产改由 KMS/环境变量注入并轮换） */
    private String jwtSecret = "property-ai-demo-only-jwt-secret-2026-11";

    /** token 有效期（秒），契约 {@code AUTH_TOKEN_TTL_SECONDS = 7200} */
    private long tokenTtlSeconds = 7200L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getJwtSecret() {
        return jwtSecret;
    }

    public void setJwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    public long getTokenTtlSeconds() {
        return tokenTtlSeconds;
    }

    public void setTokenTtlSeconds(long tokenTtlSeconds) {
        this.tokenTtlSeconds = tokenTtlSeconds;
    }
}
