package com.property.ai.cache;

import java.util.Locale;

/**
 * 缓存模式（P5-018，开关 {@code property.cache.mode}）。
 *
 * <p>生产目标 Redis 7；本环境无 Redis 实例、且按约定不新增 Maven 依赖（无 jedis/lettuce 客户端），
 * 故 {@link #REDIS} 是「接口就绪 + 可切换开关」，启动期探测失败降级 {@link #MEMORY} 并 WARN。
 */
public enum CacheMode {

    /** 进程内内存实现（默认；重启清空，非生产缓存） */
    MEMORY("memory"),

    /** Redis 7（生产目标；本环境无客户端/实例时降级 memory） */
    REDIS("redis");

    private final String value;

    CacheMode(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    /**
     * 解析配置值（大小写不敏感）。
     *
     * @return 匹配模式；不认识的值返回 {@code null}（调用方 WARN 后按 memory 处理）
     */
    public static CacheMode fromValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return MEMORY;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (CacheMode mode : values()) {
            if (mode.value.equals(normalized)) {
                return mode;
            }
        }
        return null;
    }
}
