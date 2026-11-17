package com.property.ai.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 缓存配置（前缀 {@code property.cache}，P5-018）。
 *
 * <p>开关语义：
 * <ul>
 *   <li>{@code mode=memory}（默认）：{@link InMemoryCacheStore}（可 evict 的进程内 KV，含命中率计数）；</li>
 *   <li>{@code mode=redis}：{@link RedisCacheStore}（<b>占位实现</b>）；无客户端依赖/无实例时
 *       启动期 WARN + 降级 {@link InMemoryCacheStore}，服务照常起。</li>
 * </ul>
 *
 * <p><b>依赖边界（AGENTS.md）</b>：不引入 jedis/lettuce；生产依赖坐标与命令映射只在
 * {@code docs/ops/运维手册.md} §31 文档给出。
 */
@ConfigurationProperties(prefix = "property.cache")
public class CacheProperties {

    /** 缓存模式：{@code memory}（默认）| {@code redis} */
    private String mode = "memory";

    /**
     * Redis 连接串（{@code mode=redis} 时必填）。
     *
     * <p>示例：{@code redis://127.0.0.1:6379/0}；默认空串 = 未配置 → 降级 memory。
     */
    private String redisUrl = "";

    /** Redis 连通性探测超时（毫秒，仅日志诊断；不引客户端） */
    private int connectTimeoutMs = 500;

    /** 默认条目存活时间（秒）；会话/限流等调用方可显式传更短 TTL（本 demo 统一使用默认值） */
    private long defaultTtlSeconds = 300L;

    /** 内存实现条目上限（超出按最早写入淘汰） */
    private int maxEntries = 10000;

    /** 请求的缓存模式（非法配置值 → memory） */
    public CacheMode requestedMode() {
        CacheMode parsed = CacheMode.fromValue(mode);
        return parsed == null ? CacheMode.MEMORY : parsed;
    }

    /** 是否请求了 Redis */
    public boolean isRedisRequested() {
        return requestedMode() == CacheMode.REDIS;
    }

    /** 配置值是否为合法模式（供装配期 WARN 用） */
    public boolean isModeValid() {
        return CacheMode.fromValue(mode) != null;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getRedisUrl() {
        return redisUrl;
    }

    public void setRedisUrl(String redisUrl) {
        this.redisUrl = redisUrl;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public long getDefaultTtlSeconds() {
        return defaultTtlSeconds;
    }

    public void setDefaultTtlSeconds(long defaultTtlSeconds) {
        this.defaultTtlSeconds = defaultTtlSeconds;
    }

    public int getMaxEntries() {
        return maxEntries;
    }

    public void setMaxEntries(int maxEntries) {
        this.maxEntries = maxEntries;
    }
}
