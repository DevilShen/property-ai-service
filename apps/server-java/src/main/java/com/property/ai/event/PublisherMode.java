package com.property.ai.event;

import java.util.Locale;

/**
 * 事件投递通道模式（P5-019，开关 {@code property.event.publisher}）。
 *
 * <p>生产目标 Kafka（至少一次投递 + 消费幂等 + DLQ）；本环境无 broker、且按约定不新增 Maven 依赖
 * （无 kafka-clients），故 {@link #KAFKA} 是「接口就绪 + 可切换开关」，
 * 启动期探测失败降级 {@link #MEMORY}（进程内 EventStore 投递）并 WARN。
 */
public enum PublisherMode {

    /** 进程内投递（复用 {@code EventStore} 的 outbox 状态机；DLQ 可查、可重放） */
    MEMORY("memory"),

    /** Kafka（生产目标；本环境无客户端/broker 时降级 memory） */
    KAFKA("kafka");

    private final String value;

    PublisherMode(String value) {
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
    public static PublisherMode fromValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return MEMORY;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (PublisherMode mode : values()) {
            if (mode.value.equals(normalized)) {
                return mode;
            }
        }
        return null;
    }
}
