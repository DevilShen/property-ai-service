package com.property.ai.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import com.property.ai.connector.ReadCache;

/**
 * 缓存/限流装配（P5-018）。
 *
 * <p>默认 {@code property.cache.mode=memory}：注册可 evict 的内存 {@link CacheStore}，
 * 其命中率汇总既有 ERP 只读缓存 {@link ReadCache} 的计数（观测指标口径不变）。
 * {@code mode=redis}：注册 {@link RedisCacheStore} 占位实现，构造期探测不可用 → WARN + 委托内存实现。
 *
 * <p>{@link RateLimiter} 独立注册（内存固定窗口）；未挂到既有端点（不改端点形状/契约），
 * 接入指引见 {@code docs/ops/运维手册.md} §31.6。
 */
@Configuration
@EnableConfigurationProperties({CacheProperties.class, RateLimitProperties.class})
public class CacheConfig {

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    @Bean
    public CacheStore cacheStore(CacheProperties properties, ReadCache readCache, Environment environment) {
        applyShortFormMode(properties, environment);
        if (!properties.isModeValid()) {
            log.warn("[cache] WARN 无法识别的 property.cache.mode={}，按 memory 处理（合法值：memory|redis）",
                    properties.getMode());
        }
        InMemoryCacheStore memoryStore = new InMemoryCacheStore(readCache, properties);
        if (properties.isRedisRequested()) {
            return new RedisCacheStore(properties, memoryStore);
        }
        log.info("[cache] cacheMode=memory（进程内 KV；重启清空、非生产缓存；Redis 开关见 property.cache.mode）");
        return memoryStore;
    }

    /** 短开关兼容：{@code property.cache} → {@code property.cache.mode}（显式长开关优先） */
    static void applyShortFormMode(CacheProperties properties, Environment environment) {
        if (environment == null) {
            return;
        }
        String explicitMode = environment.getProperty("property.cache.mode");
        if (explicitMode != null && !explicitMode.isBlank()) {
            return;
        }
        String shortForm = environment.getProperty("property.cache");
        if (shortForm != null && !shortForm.isBlank()) {
            properties.setMode(shortForm.trim());
            log.info("[cache] 使用短开关 property.cache={}（等价 property.cache.mode={}）", shortForm.trim(),
                    shortForm.trim());
        }
    }

    @Bean
    public RateLimiter rateLimiter(RateLimitProperties properties) {
        if (!properties.isEnabled()) {
            log.warn("[cache] WARN 限流已关闭（property.ratelimit.enabled=false，仅联调/压测可用）");
        }
        return new RateLimiter(properties);
    }
}
