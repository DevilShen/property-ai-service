package com.property.ai.connector;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

/**
 * 只读结果缓存（降级阶梯的 L2：{@code fallback=cache}）。
 *
 * <p>边界（设计 §24.5「不落库清单」）：本缓存**只放 ERP 只读结果的短时快照**，
 * 不落库、不持久化、不改写 ERP 主数据；建单/迁移/回访等写操作不经缓存。
 * 命中缓存时必须同时返回 {@code degraded{fallback:cache, dataAsOf}}，前端标注「已降级 + 数据时间」。
 *
 * <p><b>P5 W20 可观测性（task-65 / P5-020）</b>：本类维护进程内命中/未命中计数
 * （{@link #stats()}），供 {@code GET /api/v1/observability/metrics} 的
 * {@code cache{hits,misses,hitRate}} 使用；{@code get()} 未命中计 miss，{@code get()}/{@code getStale()}
 * 命中（含陈旧回落命中）计 hit。计数重启清零（P5 W21 迁 Redis 后由指标系统接管）。
 */
@Component
public class ReadCache {

    /** 缓存条目（不可变） */
    public record Entry(Object value, long storedAtMillis) {
    }

    /**
     * 缓存计数快照（P5 W20 可观测性）。
     *
     * @param hits    命中次数（含 TTL 内命中与陈旧窗口回落命中）
     * @param misses  未命中次数（{@link #get(String)} 未取到 TTL 内条目；陈旧回落不计 miss）
     * @param hitRate 命中率 0.0000—1.0000（hits/(hits+misses)，无请求时 0）
     */
    public record Stats(long hits, long misses, double hitRate) {
    }

    private final ConnectorProperties properties;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    public ReadCache(ConnectorProperties properties) {
        this.properties = properties;
    }

    public void put(String key, Object value) {
        if (key == null || value == null) {
            return;
        }
        cache.put(key, new Entry(value, System.currentTimeMillis()));
    }

    /** TTL 内命中返回；过期/不存在返回 empty（过期条目顺带清理） */
    public Optional<Entry> get(String key) {
        if (key == null) {
            return Optional.empty();
        }
        Entry entry = cache.get(key);
        if (entry == null) {
            misses.incrementAndGet();
            return Optional.empty();
        }
        long age = System.currentTimeMillis() - entry.storedAtMillis();
        if (age > properties.getCacheTtlMs()) {
            // 注意：不在这里删除条目 —— 降级回落（getStale）需要「TTL 过期但仍在陈旧窗口内」的快照；
            // 过期条目的清理由 getStale 的陈旧窗口与 put() 覆盖负责（P2 内存实现，键空间 = 查询维度）。
            misses.incrementAndGet();
            return Optional.empty();
        }
        hits.incrementAndGet();
        return Optional.of(entry);
    }

    /**
     * 降级回落读（P2-023）：允许取「TTL 已过期但仍在陈旧可读窗口（3×TTL）内」的快照。
     *
     * <p>只在<b>回源失败</b>时使用：返回 200 + {@code degraded{fallback=cache,dataAsOf}} +
     * {@code freshness{source=cache,ttlSeconds=0,hit=true}}，前端标注「已降级 + 数据时间」。
     * 超过陈旧窗口的条目视为不可用（不返回过期太久的房档/账单，避免误导）。
     */
    public Optional<Entry> getStale(String key) {
        if (key == null) {
            return Optional.empty();
        }
        Entry entry = cache.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        long age = System.currentTimeMillis() - entry.storedAtMillis();
        long staleWindow = Math.max(properties.getCacheTtlMs(), 1L) * 3L;
        if (age > staleWindow) {
            cache.remove(key, entry);
            return Optional.empty();
        }
        hits.incrementAndGet();
        return Optional.of(entry);
    }

    public void clear() {
        cache.clear();
    }

    public int size() {
        return cache.size();
    }

    /** 命中/未命中计数快照（P5 W20 可观测性；hitRate 四舍五入到 4 位） */
    public Stats stats() {
        long hitCount = hits.get();
        long missCount = misses.get();
        long total = hitCount + missCount;
        double rate = total == 0 ? 0d : Math.round((double) hitCount / total * 10000d) / 10000d;
        return new Stats(hitCount, missCount, rate);
    }

    /** 命中计数重置（仅测试/演示用；生产由指标系统按周期窗口统计） */
    public void resetStatsForTest() {
        hits.set(0);
        misses.set(0);
    }

    /** 展示缓存 TTL（秒，契约 {@code DataFreshness.ttlSeconds}） */
    public int ttlSeconds() {
        long ttlMs = properties.getCacheTtlMs();
        return ttlMs <= 0 ? 0 : (int) Math.max(1L, ttlMs / 1000L);
    }

    /** 缓存条目剩余 TTL（秒，向下取整、最小 0）；用于 {@code DataFreshness.ttlSeconds} */
    public int remainingTtlSeconds(Entry entry) {
        long ageMs = System.currentTimeMillis() - entry.storedAtMillis();
        long remainingMs = properties.getCacheTtlMs() - ageMs;
        return remainingMs <= 0 ? 0 : (int) (remainingMs / 1000L);
    }

    /** 缓存的 ISO 时间点（供 degraded.dataAsOf / freshness.dataAsOf） */
    public String storedAtIso(Entry entry) {
        return java.time.Instant.ofEpochMilli(entry.storedAtMillis())
                .atZone(java.time.ZoneId.of("Asia/Shanghai"))
                .toOffsetDateTime()
                .toString();
    }
}
