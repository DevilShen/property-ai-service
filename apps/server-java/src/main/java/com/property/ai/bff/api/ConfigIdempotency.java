package com.property.ai.bff.api;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import com.property.ai.domain.ticket.DomainException;

/**
 * 配置类动作的可选幂等键支持（契约 v0.3.2 {@code IdempotencyKeyOptional}）。
 *
 * <p>适用范围：映射试跑/发布/回滚、能力探测等<b>配置类动作</b>（可重放、不写业务主数据）。
 * 口径与写操作一致：
 * <ul>
 *   <li>不传 {@code Idempotency-Key} → 直接执行（配置动作可重放）；</li>
 *   <li>传了 → 同键<b>同载荷</b>返回首次结果（24h 窗口）；同键<b>不同载荷</b> → 409 {@code E-3003}；</li>
 *   <li>键长 8—64 字符（契约 {@code IdempotencyKeyOptional}），不合规 → 400 {@code E-1001}。</li>
 * </ul>
 *
 * <p>P2 为进程内存储（单实例）；P5 起迁 Redis 并保留本类接口。
 */
@Component
public class ConfigIdempotency {

    private static final Duration WINDOW = Duration.ofHours(24);
    private static final int KEY_MIN = 8;
    private static final int KEY_MAX = 64;

    private record Entry(String fingerprint, Object result, long createdAtMs) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    /**
     * 执行一次配置动作（带可选幂等键）。
     *
     * @param idempotencyKey 可选键（null/blank = 不启用幂等）
     * @param fingerprint    载荷指纹（动作 + 业务参数，用于区分「同键不同载荷」）
     * @param action         实际执行逻辑
     */
    public <T> T execute(String idempotencyKey, String fingerprint, Supplier<T> action) {
        String key = normalize(idempotencyKey);
        if (key == null) {
            return action.get();
        }
        long now = System.currentTimeMillis();
        Entry existing = entries.get(key);
        if (existing != null) {
            if (now - existing.createdAtMs() > WINDOW.toMillis()) {
                entries.remove(key, existing);
            } else {
                if (!existing.fingerprint().equals(fingerprint)) {
                    throw DomainException.idempotencyConflict(key);
                }
                @SuppressWarnings("unchecked")
                T replay = (T) existing.result();
                return replay;
            }
        }
        synchronized (this) {
            Entry raced = entries.get(key);
            if (raced != null && now - raced.createdAtMs() <= WINDOW.toMillis()) {
                if (!raced.fingerprint().equals(fingerprint)) {
                    throw DomainException.idempotencyConflict(key);
                }
                @SuppressWarnings("unchecked")
                T replay = (T) raced.result();
                return replay;
            }
            T result = action.get();
            entries.put(key, new Entry(fingerprint, result, now));
            return result;
        }
    }

    private static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String key = raw.trim();
        if (key.length() < KEY_MIN || key.length() > KEY_MAX) {
            throw DomainException.badRequest("Idempotency-Key 长度必须为 8—64 字符",
                    Map.of("field", "Idempotency-Key", "length", key.length()));
        }
        return key;
    }
}
