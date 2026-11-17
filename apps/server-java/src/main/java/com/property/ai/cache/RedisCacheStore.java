package com.property.ai.cache;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Redis 缓存存储（P5-018，<b>占位实现</b>：{@code property.cache.mode=redis}）。
 *
 * <h2>为什么是占位实现</h2>
 * AGENTS.md 要求本环境**不新增 Maven 依赖**（无 jedis/lettuce 客户端），因此本类不实现 RESP 命令层；
 * 它的价值是：①把「切 Redis」的开关与降级路径先打通（接口 {@link CacheStore} 已就绪）；
 * ②启动期显式 WARN 并给出根因（无客户端依赖 / 无实例 / 未配置连接串）；
 * ③所有读写委托 {@link InMemoryCacheStore}，**不假装 Redis 就绪**。
 *
 * <h2>降级语义</h2>
 * <ul>
 *   <li>{@link #requestedMode()} = {@code redis}，{@link #mode()} = {@code memory}，{@link #degraded()} = true；</li>
 *   <li>{@link #unavailableReason()} 说明根因；进程内 WARN 只打一次（构造期）；</li>
 *   <li>命中率/条目数来自降级后的内存实现（重启清零）。</li>
 * </ul>
 *
 * <p>生产接入：加 {@code redis.clients:jedis}（或 lettuce）依赖 → 在本类实现 get/put/evict/TTL
 * （或直接新增 {@code JedisCacheStore}）→ 连接串 {@code property.cache.redis-url}；
 * 坐标与命令映射见 {@code docs/ops/运维手册.md} §31。
 */
public class RedisCacheStore implements CacheStore {

    private static final Logger log = LoggerFactory.getLogger(RedisCacheStore.class);

    /** 常见 Redis 客户端类名（任一存在即认为客户端依赖已就位；本环境恒不存在） */
    private static final String[] CLIENT_CLASSES = {
            "redis.clients.jedis.Jedis",
            "io.lettuce.core.RedisClient"
    };

    private final CacheProperties properties;
    private final CacheStore fallback;
    private final String unavailableReason;

    public RedisCacheStore(CacheProperties properties, CacheStore fallback) {
        this.properties = properties == null ? new CacheProperties() : properties;
        this.fallback = fallback;
        this.unavailableReason = probe();
        log.warn("[cache] WARN 请求 cache=redis 但不可用，已降级为内存实现：{}（接入步骤见 docs/ops/运维手册.md §31；"
                + "降级期间缓存只在单实例进程内、重启清空，不得表述为 Redis 就绪）", unavailableReason);
    }

    /** 探测降级原因；本占位实现恒返回非空（不假装 Redis 就绪） */
    private String probe() {
        if (!properties.isModeValid()) {
            return "无法识别的 property.cache.mode=" + properties.getMode() + "（合法值：memory|redis）";
        }
        if (properties.getRedisUrl() == null || properties.getRedisUrl().isBlank()) {
            return "property.cache.redis-url 未配置（示例 redis://127.0.0.1:6379/0）";
        }
        if (!clientOnClasspath()) {
            return "classpath 缺少 Redis 客户端依赖（jedis/lettuce；按 AGENTS.md 本环境不新增 Maven 依赖，"
                    + "生产坐标 redis.clients:jedis:5.2.0 / io.lettuce:lettuce-core:6.5.x）";
        }
        return "RedisCacheStore 为占位实现（客户端已就位但未实现 RESP 命令层；P6 补真实实现。"
                + "连通性诊断=" + tcpDiagnostic(properties.getRedisUrl()) + "）";
    }

    private boolean clientOnClasspath() {
        for (String className : CLIENT_CLASSES) {
            try {
                Class.forName(className);
                return true;
            } catch (ClassNotFoundException | LinkageError ignored) {
                // 继续探测下一个客户端类名
            }
        }
        return false;
    }

    /** 纯 JDK TCP 连通性诊断（只用于日志/报告，不作为「Redis 就绪」依据） */
    private String tcpDiagnostic(String redisUrl) {
        HostPort hostPort = HostPort.parse(redisUrl);
        if (hostPort == null) {
            return "连接串无法解析（期望 redis://host:port/db 或 host:port）";
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(hostPort.host(), hostPort.port()),
                    Math.max(50, properties.getConnectTimeoutMs()));
            return "可达 " + hostPort.host() + ":" + hostPort.port();
        } catch (Exception e) {
            return "不可达 " + hostPort.host() + ":" + hostPort.port() + "（" + e.getMessage() + "）";
        }
    }

    @Override
    public String requestedMode() {
        return CacheMode.REDIS.value();
    }

    @Override
    public String mode() {
        return CacheMode.MEMORY.value();
    }

    @Override
    public boolean degraded() {
        return true;
    }

    @Override
    public String unavailableReason() {
        return unavailableReason;
    }

    @Override
    public void put(String key, Object value) {
        fallback.put(key, value);
    }

    @Override
    public Optional<Object> get(String key) {
        return fallback.get(key);
    }

    @Override
    public boolean evict(String key) {
        return fallback.evict(key);
    }

    @Override
    public int ttlSeconds() {
        return fallback.ttlSeconds();
    }

    @Override
    public Stats stats() {
        return fallback.stats();
    }

    @Override
    public void clear() {
        fallback.clear();
    }

    @Override
    public int size() {
        return fallback.size();
    }

    /** 连接串解析结果（host + port） */
    private record HostPort(String host, int port) {

        /** 解析 {@code redis://host:port/db} 或 {@code host:port}；失败返回 null */
        static HostPort parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            String text = raw.trim();
            int schemeIndex = text.indexOf("://");
            if (schemeIndex >= 0) {
                text = text.substring(schemeIndex + 3);
            }
            int slashIndex = text.indexOf('/');
            if (slashIndex >= 0) {
                text = text.substring(0, slashIndex);
            }
            String[] parts = text.split(":");
            if (parts.length != 2) {
                return null;
            }
            try {
                return new HostPort(parts[0].toLowerCase(Locale.ROOT), Integer.parseInt(parts[1]));
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }
}
