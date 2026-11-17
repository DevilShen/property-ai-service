package com.property.ai.cache;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 限流器（P5-018，内存固定窗口计数实现）。
 *
 * <h2>语义</h2>
 * 每个 key（如 {@code login:ip:127.0.0.1}、{@code write:actorId:owner-3-2-1802}）在 {@code windowSeconds}
 * 窗口内最多放行 {@code limit} 次；窗口从首次请求开始计时，到期后自动开新窗口（固定窗口，非滑动窗口）。
 * 返回 {@link Decision} 供调用方决定是否返回 429 并给出 {@code retryAfterMillis}。
 *
 * <h2>接入点（演示口径）</h2>
 * 登录接口与写接口（建单/迁移/回访/映射发布等）是目标调用点；<b>本任务不改既有端点形状/契约</b>，
 * 因此仅提供实现 + 单测 + 文档接入指引（{@code docs/ops/运维手册.md} §31.6），
 * 由 P5 W22/W23 端点 owner 接入。生产实现替换为 Redis 计数（{@link CacheStore#put(String, Object)}
 * + 过期语义）即可保证多实例一致。
 *
 * <p>线程安全：{@link ConcurrentHashMap} + {@link AtomicInteger}；窗口切换用 {@code compute} 原子完成。
 */
public class RateLimiter {

    /**
     * 限流决策。
     *
     * @param allowed          是否放行
     * @param limit            窗口上限
     * @param remaining        剩余可放行次数（0 表示已打满）
     * @param retryAfterMillis 距窗口重置的毫秒数（放行时也可返回，供响应头 {@code Retry-After} 用）
     */
    public record Decision(boolean allowed, int limit, int remaining, long retryAfterMillis) {
    }

    /** 固定窗口计数（startMillis + 已用次数） */
    private static final class Window {
        final long startMillis;
        final AtomicInteger used = new AtomicInteger();

        Window(long startMillis) {
            this.startMillis = startMillis;
        }
    }

    private final RateLimitProperties properties;
    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimiter(RateLimitProperties properties) {
        this(properties, Clock.systemUTC());
    }

    /** 测试可注入固定/可推进时钟，用于验证窗口滚动 */
    public RateLimiter(RateLimitProperties properties, Clock clock) {
        this.properties = properties == null ? new RateLimitProperties() : properties;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /** 按默认配额（{@code property.ratelimit.default-limit/window-seconds}）尝试获取 1 次配额 */
    public Decision tryAcquire(String key) {
        return tryAcquire(key, properties.getDefaultLimit(), properties.getWindowSeconds());
    }

    /**
     * 尝试获取 1 次配额（显式 limit/window，供登录/写接口按场景覆盖）。
     *
     * @param key           限流维度键（非空；建议 {@code 场景:维度:值}，不含房号/手机号明文）
     * @param limit         窗口上限（≤0 视为不限）
     * @param windowSeconds 窗口长度（秒，≤0 视为 1 秒）
     */
    public Decision tryAcquire(String key, int limit, long windowSeconds) {
        long effectiveWindowMillis = Math.max(1L, windowSeconds) * 1000L;
        if (!properties.isEnabled() || limit <= 0) {
            return new Decision(true, Math.max(0, limit), Math.max(0, limit), 0L);
        }
        if (key == null || key.isBlank()) {
            // 无维度键不参与限流（调用方应保证 key 可归因；不因缺 key 误伤业务）
            return new Decision(true, limit, limit, 0L);
        }
        long now = clock.millis();
        Window window = windows.compute(key, (ignored, current) -> current == null
                || now - current.startMillis >= effectiveWindowMillis ? new Window(now) : current);
        int used = window.used.incrementAndGet();
        long resetAt = window.startMillis + effectiveWindowMillis;
        long retryAfter = Math.max(0L, resetAt - now);
        if (used > limit) {
            return new Decision(false, limit, 0, retryAfter);
        }
        return new Decision(true, limit, Math.max(0, limit - used), retryAfter);
    }

    /** 当前窗口已用次数（诊断/测试用；无窗口返回 0） */
    public int used(String key) {
        Window window = key == null ? null : windows.get(key);
        return window == null ? 0 : window.used.get();
    }

    /** 清理某 key 的窗口（测试/演示重置用） */
    public void reset(String key) {
        if (key != null) {
            windows.remove(key);
        }
    }

    /** 清理全部窗口（测试/演示重置用；生产多实例由 Redis TTL 自动过期） */
    public void reset() {
        windows.clear();
    }

    /** 当前受控 key 数量（诊断用；避免无界增长需在 P6 用 Redis TTL 替代） */
    public int trackedKeys() {
        return windows.size();
    }
}
