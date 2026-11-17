package com.property.ai.connector;

import java.util.function.LongSupplier;

/**
 * 进程内熔断器（设计 §24.4：失败率熔断 + 半开探测）。
 *
 * <p>口径（P2 第 1 周，单实例内存实现；P5 起按项目/连接器维度进 Redis）：
 * <ul>
 *   <li>CLOSED：正常放行，连续失败达到阈值 → OPEN；</li>
 *   <li>OPEN：冷却期内直接拒绝（快速失败，BFF 走降级阶梯，不打爆上游）；</li>
 *   <li>HALF_OPEN：冷却结束后放行一次探测，成功 → CLOSED，失败 → 再次 OPEN 并重新计时。</li>
 * </ul>
 *
 * <p>线程安全：所有状态读写均 synchronized；半开探测不做并发限流（P2 单实例演示足够，
 * 多实例/高并发口径在 P5 与 Redis 限流一起落）。
 */
public class CircuitBreaker {

    /** 熔断状态 */
    public enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    private final boolean enabled;
    private final int failureThreshold;
    private final long cooldownMs;
    private final LongSupplier clock;

    private State state = State.CLOSED;
    private int consecutiveFailures;
    private long openUntil;
    private long lastTransitionAt;

    public CircuitBreaker(boolean enabled, int failureThreshold, long cooldownMs) {
        this(enabled, failureThreshold, cooldownMs, System::currentTimeMillis);
    }

    /** 可注入时钟，便于单测构造冷却/半开场景。 */
    public CircuitBreaker(boolean enabled, int failureThreshold, long cooldownMs, LongSupplier clock) {
        this.enabled = enabled;
        this.failureThreshold = Math.max(1, failureThreshold);
        this.cooldownMs = Math.max(0L, cooldownMs);
        this.clock = clock;
        this.lastTransitionAt = clock.getAsLong();
    }

    /** 是否放行本次请求（OPEN 且冷却未到 → false） */
    public synchronized boolean allowRequest() {
        if (!enabled) {
            return true;
        }
        if (state == State.CLOSED || state == State.HALF_OPEN) {
            return true;
        }
        long now = clock.getAsLong();
        if (now >= openUntil) {
            state = State.HALF_OPEN;
            lastTransitionAt = now;
            return true;
        }
        return false;
    }

    /** 上游成功：清零失败计数并闭合 */
    public synchronized void recordSuccess() {
        if (!enabled) {
            return;
        }
        consecutiveFailures = 0;
        if (state != State.CLOSED) {
            state = State.CLOSED;
            lastTransitionAt = clock.getAsLong();
        }
    }

    /** 上游失败：累计失败，达到阈值或半开探测失败 → OPEN */
    public synchronized void recordFailure() {
        if (!enabled) {
            return;
        }
        consecutiveFailures++;
        if (state == State.HALF_OPEN || consecutiveFailures >= failureThreshold) {
            state = State.OPEN;
            openUntil = clock.getAsLong() + cooldownMs;
            lastTransitionAt = clock.getAsLong();
        }
    }

    public synchronized State state() {
        if (state == State.OPEN && enabled && clock.getAsLong() >= openUntil) {
            return State.HALF_OPEN;
        }
        return state;
    }

    public synchronized int consecutiveFailures() {
        return consecutiveFailures;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int failureThreshold() {
        return failureThreshold;
    }

    public long cooldownMs() {
        return cooldownMs;
    }

    public synchronized long lastTransitionAt() {
        return lastTransitionAt;
    }
}
