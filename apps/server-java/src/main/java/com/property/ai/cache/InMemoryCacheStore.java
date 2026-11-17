package com.property.ai.cache;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.property.ai.connector.ReadCache;

/**
 * 缓存内存实现（P5-018 默认；{@code property.cache.mode=memory}）。
 *
 * <h2>与既有 {@link ReadCache} 的关系</h2>
 * 既有 {@code ReadCache} 是「ERP 只读结果的降级 L2 缓存」，语义特殊（TTL 内 {@code get} + 陈旧窗口
 * {@code getStale}），且承担 {@code GET /api/v1/observability/metrics} 的 {@code cache{hits,misses,hitRate}}
 * 计数口径。本类<b>不替换</b>它，而是：
 * <ul>
 *   <li>提供通用 KV 抽象（会话/限流/新只读缓存调用点的统一入口，支持 {@link #evict(String)}）；</li>
 *   <li>{@link #stats()} / {@link #hitRate()} 汇总「本类计数 + {@link ReadCache} 计数」，
 *       让运维看到的全局命中率包含既有的 ERP 只读缓存（两套 key 空间不相交：{@code erp:*} vs 会话/限流 key，
 *       不存在同一次请求双计数）。</li>
 * </ul>
 *
 * <p><b>边界</b>：进程内、重启清空、单实例可见；<b>不是</b>生产缓存（生产目标 Redis 7）。
 */
public class InMemoryCacheStore implements CacheStore {

    /** 缓存条目（不可变，记录写入时间用于 TTL 判定） */
    record Entry(Object value, long storedAtMillis) {
    }

    /** 既有 ERP 只读缓存（仅用于命中率汇总，可为 null：单测/无 ERP 缓存场景） */
    private final ReadCache readCache;
    private final long ttlMillis;
    private final int maxEntries;

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    public InMemoryCacheStore(ReadCache readCache, CacheProperties properties) {
        this.readCache = readCache;
        CacheProperties effective = properties == null ? new CacheProperties() : properties;
        this.ttlMillis = Math.max(1L, effective.getDefaultTtlSeconds()) * 1000L;
        this.maxEntries = Math.max(1, effective.getMaxEntries());
    }

    @Override
    public String requestedMode() {
        return CacheMode.MEMORY.value();
    }

    @Override
    public String mode() {
        return CacheMode.MEMORY.value();
    }

    @Override
    public boolean degraded() {
        return false;
    }

    @Override
    public String unavailableReason() {
        return null;
    }

    @Override
    public void put(String key, Object value) {
        if (key == null || key.isBlank() || value == null) {
            return;
        }
        if (entries.size() >= maxEntries && !entries.containsKey(key)) {
            evictOldest();
        }
        entries.put(key, new Entry(value, System.currentTimeMillis()));
    }

    @Override
    public Optional<Object> get(String key) {
        if (key == null || key.isBlank()) {
            misses.incrementAndGet();
            return Optional.empty();
        }
        Entry entry = entries.get(key);
        if (entry == null) {
            misses.incrementAndGet();
            return Optional.empty();
        }
        if (System.currentTimeMillis() - entry.storedAtMillis() > ttlMillis) {
            entries.remove(key, entry);
            misses.incrementAndGet();
            return Optional.empty();
        }
        hits.incrementAndGet();
        return Optional.of(entry.value());
    }

    @Override
    public boolean evict(String key) {
        return key != null && entries.remove(key) != null;
    }

    @Override
    public int ttlSeconds() {
        return (int) Math.max(1L, ttlMillis / 1000L);
    }

    @Override
    public Stats stats() {
        long hitCount = hits.get();
        long missCount = misses.get();
        if (readCache != null) {
            ReadCache.Stats legacy = readCache.stats();
            hitCount += legacy.hits();
            missCount += legacy.misses();
        }
        long total = hitCount + missCount;
        double rate = total == 0 ? 0d : Math.round((double) hitCount / total * 10000d) / 10000d;
        return new Stats(hitCount, missCount, rate);
    }

    @Override
    public void clear() {
        entries.clear();
        // 注意：不清空 ReadCache（它属于 ERP 降级链路，刷新/清理由连接器侧负责），仅清本类条目
    }

    @Override
    public int size() {
        return entries.size();
    }

    /** 测试/演示：仅重置本类命中计数（不影响 ReadCache 计数） */
    public void resetStatsForTest() {
        hits.set(0);
        misses.set(0);
    }

    private void evictOldest() {
        String oldestKey = null;
        long oldestAt = Long.MAX_VALUE;
        for (Map.Entry<String, Entry> item : entries.entrySet()) {
            if (item.getValue().storedAtMillis() < oldestAt) {
                oldestAt = item.getValue().storedAtMillis();
                oldestKey = item.getKey();
            }
        }
        if (oldestKey != null) {
            entries.remove(oldestKey);
        }
    }
}
