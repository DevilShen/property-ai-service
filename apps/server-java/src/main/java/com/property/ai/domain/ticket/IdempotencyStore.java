package com.property.ai.domain.ticket;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Component;

import com.property.ai.connector.ConnectorProperties;

/**
 * 写操作幂等登记（契约 §4：{@code POST /tickets}、{@code /transition}、{@code /feedback} 必须带
 * {@code Idempotency-Key}，服务端保存 ≥24h；同键同载荷返回首次结果，同键不同载荷 409 E-3003）。
 *
 * <p>P2 第 1 周为内存 Map（单实例）；P5 落 PG/Redis：{@code idempotency(key PRIMARY KEY, fingerprint, result jsonb,
 * expires_at timestamptz)} + 定期清理。写操作不自动重试，由客户端复用同一键重试。
 */
@Component
public class IdempotencyStore {

    /** 幂等条目：载荷指纹 + 首次结果快照 + 过期时间 */
    public record Entry(String fingerprint, Object result, Instant expiresAt) {
    }

    private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final Duration window;

    public IdempotencyStore(ConnectorProperties properties) {
        this.window = Duration.ofHours(Math.max(1, properties.getIdempotencyWindowHours()));
    }

    /** 命中且未过期返回条目；过期条目顺带清理 */
    public Optional<Entry> find(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        Entry entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (Instant.now().isAfter(entry.expiresAt())) {
            entries.remove(key, entry);
            return Optional.empty();
        }
        return Optional.of(entry);
    }

    public void save(String key, String fingerprint, Object result) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("幂等键不能为空");
        }
        entries.put(key, new Entry(fingerprint, result, Instant.now().plus(window)));
    }

    public Duration window() {
        return window;
    }

    public int size() {
        return entries.size();
    }

    public void clear() {
        entries.clear();
    }
}
