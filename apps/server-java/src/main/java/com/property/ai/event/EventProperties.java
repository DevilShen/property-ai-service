package com.property.ai.event;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 事件总线配置（前缀 {@code property.event}，P5-019）。
 *
 * <p>开关语义：
 * <ul>
 *   <li>{@code publisher=memory}（默认）：{@link InMemoryEventPublisher}，投递进 {@code EventStore}
 *       状态机（pending→published/failed/dead），DLQ 可查、可重放；</li>
 *   <li>{@code publisher=kafka}：{@link KafkaEventPublisher} <b>占位实现</b>；无客户端依赖/无 broker 时
 *       启动期 WARN + 降级内存投递（不假装已投 Kafka）。</li>
 * </ul>
 *
 * <p><b>依赖边界（AGENTS.md）</b>：不引入 kafka-clients；生产依赖坐标、topic/分区键/消费幂等设计
 * 见 {@code docs/ops/Kafka-迁移方案.md}。
 */
@ConfigurationProperties(prefix = "property.event")
public class EventProperties {

    /** 投递通道：{@code memory}（默认）| {@code kafka} */
    private String publisher = "memory";

    /** Kafka bootstrap servers（{@code publisher=kafka} 时必填；示例 127.0.0.1:9092） */
    private String bootstrapServers = "";

    /** 业务事件 topic（生产建议 {@code property.events.v1}；分区键 = projectId，保证同项目有序） */
    private String topic = "property.events.v1";

    /** 死信 topic（超重试上限/人工移入；生产 {@code property.events.dlq.v1}） */
    private String dlqTopic = "property.events.dlq.v1";

    /**
     * 最大投递尝试次数（超过 → 移入 DLQ，{@code status=dead}）。
     *
     * <p>语义：至少一次投递下，同一 eventId 允许被重复投递；消费方必须按 eventId 幂等去重。
     */
    private int maxAttempts = 3;

    /** 连通性探测超时（毫秒，仅日志诊断；不引客户端） */
    private int connectTimeoutMs = 500;

    /** 请求的投递通道（非法配置值 → memory） */
    public PublisherMode requestedMode() {
        PublisherMode parsed = PublisherMode.fromValue(publisher);
        return parsed == null ? PublisherMode.MEMORY : parsed;
    }

    /** 是否请求了 Kafka */
    public boolean isKafkaRequested() {
        return requestedMode() == PublisherMode.KAFKA;
    }

    /** 配置值是否为合法模式（供装配期 WARN 用） */
    public boolean isModeValid() {
        return PublisherMode.fromValue(publisher) != null;
    }

    public String getPublisher() {
        return publisher;
    }

    public void setPublisher(String publisher) {
        this.publisher = publisher;
    }

    public String getBootstrapServers() {
        return bootstrapServers;
    }

    public void setBootstrapServers(String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public String getDlqTopic() {
        return dlqTopic;
    }

    public void setDlqTopic(String dlqTopic) {
        this.dlqTopic = dlqTopic;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }
}
