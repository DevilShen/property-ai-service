package com.property.ai.event;

import com.property.ai.bff.dto.EventEnvelope;

/**
 * 事件投递接口（P5-019，架构 §24.6「事务性 outbox + 至少一次投递」）。
 *
 * <h2>语义</h2>
 * <ul>
 *   <li><b>至少一次</b>：同一 {@code eventId} 可能被投递多次；消费方必须按 {@code eventId} 幂等去重。</li>
 *   <li>返回 {@code true} = 通道已受理（内存实现 = 写入 EventStore 状态机并置 published；
 *       Kafka 实现 = producer ack 成功）。抛异常/返回 false = 本次投递失败，由 outbox 记 failed 并可重试。</li>
 *   <li><b>不伪造</b>：{@link #mode()} 返回生效通道；降级时必须是 {@code memory}，
 *       不得把内存投递说成 Kafka 已投递（AGENTS.md NEVER #11）。</li>
 * </ul>
 *
 * <p>实现：{@link InMemoryEventPublisher}（默认）、{@link KafkaEventPublisher}（占位，恒降级）。
 */
public interface EventPublisher {

    /** 请求的投递通道（配置值）：{@code memory} | {@code kafka} */
    String requestedMode();

    /** 生效的投递通道（降级后为 {@code memory}）：{@code memory} | {@code kafka} */
    String mode();

    /** 是否发生降级（请求 kafka 但不可用） */
    boolean degraded();

    /** 降级原因（未降级为 {@code null}） */
    String unavailableReason();

    /**
     * 投递一条事件（至少一次语义）。
     *
     * @return 通道是否已受理
     */
    boolean publish(EventEnvelope event);
}
