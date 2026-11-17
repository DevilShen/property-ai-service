package com.property.ai.cache;

import java.util.Optional;

/**
 * 缓存抽象（P5-018）：会话 / 限流 / 只读结果缓存的统一入口。
 *
 * <p>为什么抽象成接口：调用点不感知实现，切换 Redis 只换实现类（{@code property.cache.mode}）。
 * 默认实现 {@link InMemoryCacheStore}（进程内，重启清空、非生产缓存）；Redis 为占位实现
 * {@link RedisCacheStore}（本环境无客户端依赖 → 降级内存并 WARN）。
 *
 * <p><b>口径</b>：{@link #mode()} 返回**生效模式**（降级后为 {@code memory}），{@link #requestedMode()}
 * 返回配置请求值；两者不一致即 {@link #degraded()}。任何实现都<b>不伪造</b>命中率/集群语义。
 */
public interface CacheStore {

    /**
     * 缓存计数快照。
     *
     * @param hits    命中次数
     * @param misses  未命中次数（含过期/已淘汰/不存在）
     * @param hitRate 命中率 0.0000—1.0000（hits/(hits+misses)，无请求时 0）
     */
    record Stats(long hits, long misses, double hitRate) {
    }

    /** 请求模式（配置值）：{@code memory} | {@code redis} */
    String requestedMode();

    /** 生效模式（降级后为 {@code memory}）：{@code memory} | {@code redis} */
    String mode();

    /** 是否发生降级（请求 redis 但不可用） */
    boolean degraded();

    /** 降级原因（未降级为 {@code null}） */
    String unavailableReason();

    /** 写入/覆盖（{@code null} key/value 忽略；实现须线程安全） */
    void put(String key, Object value);

    /** 读取（TTL 内命中返回值；不存在/过期返回 empty，并计入 miss） */
    Optional<Object> get(String key);

    /** 主动失效（返回是否真的删除了条目） */
    boolean evict(String key);

    /** 条目 TTL（秒，契约/观测展示口径；0 表示不缓存） */
    int ttlSeconds();

    /** 计数快照（进程内计数，重启清零） */
    Stats stats();

    /** 命中率（0.0000—1.0000；{@link #stats()} 的便捷读法） */
    default double hitRate() {
        return stats().hitRate();
    }

    /** 清空（仅测试/演示重置；生产 Redis 实现按 key 前缀/DB 隔离，不做全库清空） */
    void clear();

    /** 当前条目数（内存实现 = 本地条目数；Redis 占位实现为降级后本地条目数） */
    int size();
}
