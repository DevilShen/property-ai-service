package com.property.ai.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 限流配置（前缀 {@code property.ratelimit}，P5-018）。
 *
 * <p>口径：<b>进程内固定窗口计数</b>限流（demo），用于登录/写接口的接入演示与单测；
 * 生产目标 Redis + 滑动窗口/令牌桶（本环境无 Redis 实例，按「接口 + 可切换 demo 实现」交付）。
 * 默认每个 key（如 {@code login:ip} / {@code write:actorId}）60 秒 60 次；
 * 关闭（{@code enabled=false}）时 {@link RateLimiter} 全部放行。
 */
@ConfigurationProperties(prefix = "property.ratelimit")
public class RateLimitProperties {

    /** 限流总开关（默认开启；关闭 = 全部放行，仅联调/压测用） */
    private boolean enabled = true;

    /** 默认窗口内允许次数 */
    private int defaultLimit = 60;

    /** 默认窗口长度（秒） */
    private long windowSeconds = 60L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getDefaultLimit() {
        return defaultLimit;
    }

    public void setDefaultLimit(int defaultLimit) {
        this.defaultLimit = defaultLimit;
    }

    public long getWindowSeconds() {
        return windowSeconds;
    }

    public void setWindowSeconds(long windowSeconds) {
        this.windowSeconds = windowSeconds;
    }
}
