package com.property.ai.event;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.property.ai.bff.dto.EventEnvelope;
import com.property.ai.bff.dto.EventStatus;
import com.property.ai.domain.event.EventStore;

/**
 * 内存 Outbox 仓储（P5-019 默认实现；状态机在 {@link EventStore} 内部维护）。
 *
 * <p>职责：把「查询/重试/DLQ/重放/批量派发」这些运维动作组合到 {@link EventPublisher} 上，
 * 让演示环境也能验证「至少一次投递 + 失败重试 + 超限入 DLQ + DLQ 可查可重放」的完整链路。
 *
 * <p><b>边界</b>：进程内、重启清空；没有自动调度（不注册 @Scheduled，避免改变 P5 W19/W20 既有事件
 * pending 观测口径与技术演示语义）；生产由 Kafka 消费方 + outbox dispatcher 承担，见
 * {@code docs/ops/Kafka-迁移方案.md}。
 */
public class InMemoryOutboxStore implements OutboxStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryOutboxStore.class);

    private final EventStore eventStore;
    private final EventPublisher publisher;
    private final int maxAttempts;

    public InMemoryOutboxStore(EventStore eventStore, EventPublisher publisher, int maxAttempts) {
        this.eventStore = eventStore;
        this.publisher = publisher;
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    @Override
    public String mode() {
        return publisher.mode();
    }

    @Override
    public Optional<EventEnvelope> find(String eventId) {
        return eventStore.find(eventId);
    }

    @Override
    public List<EventEnvelope> pending() {
        return eventStore.byStatus(EventStatus.PENDING);
    }

    @Override
    public List<EventEnvelope> dlq() {
        return eventStore.dlq();
    }

    @Override
    public int retryCount(String eventId) {
        return eventStore.retryCount(eventId);
    }

    @Override
    public EventEnvelope retry(String eventId) {
        EventEnvelope current = eventStore.find(eventId)
                .orElseThrow(() -> new IllegalArgumentException("事件不存在：" + eventId));
        if (current.status() == EventStatus.PUBLISHED) {
            // 至少一次语义：已投递事件重复重试是幂等的
            return current;
        }
        if (eventStore.retryCount(eventId) >= maxAttempts) {
            log.warn("[event] 重试超限，移入死信队列 eventId={} retryCount={} maxAttempts={}",
                    eventId, eventStore.retryCount(eventId), maxAttempts);
            return eventStore.markDead(eventId, "超过最大投递重试次数 maxAttempts=" + maxAttempts)
                    .orElseGet(() -> eventStore.find(eventId).orElse(current));
        }
        EventEnvelope attempt = eventStore.retry(eventId)
                .orElseThrow(() -> new IllegalStateException("事件状态不允许重试：eventId=" + eventId
                        + " status=" + current.status().value()));
        return attemptDelivery(attempt);
    }

    /** 投递一次：通道受理 → published；异常/拒绝 → failed（保留 lastError） */
    private EventEnvelope attemptDelivery(EventEnvelope event) {
        try {
            boolean accepted = publisher.publish(event);
            if (!accepted) {
                throw new IllegalStateException("投递通道未受理（requestedMode=" + publisher.requestedMode()
                        + " mode=" + publisher.mode() + "）");
            }
            Optional<EventEnvelope> afterPublish = eventStore.find(event.eventId());
            if (afterPublish.isPresent() && afterPublish.get().status() == EventStatus.PUBLISHED) {
                return afterPublish.get();
            }
            return eventStore.markPublished(event.eventId())
                    .orElseGet(() -> eventStore.find(event.eventId()).orElse(event));
        } catch (RuntimeException e) {
            String reason = e.getMessage() == null || e.getMessage().isBlank()
                    ? e.getClass().getSimpleName()
                    : e.getMessage();
            log.warn("[event] 投递失败 eventId={} mode={} error={}", event.eventId(), publisher.mode(), reason);
            return eventStore.markFailed(event.eventId(), reason)
                    .orElseGet(() -> eventStore.find(event.eventId()).orElse(event));
        }
    }

    @Override
    public EventEnvelope deadLetter(String eventId) {
        EventEnvelope current = eventStore.find(eventId)
                .orElseThrow(() -> new IllegalArgumentException("事件不存在：" + eventId));
        if (current.status() == EventStatus.PUBLISHED) {
            throw new IllegalStateException("已投递（published）事件不允许回退到 DLQ：eventId=" + eventId);
        }
        return eventStore.markDead(eventId, "人工移入死信队列（manual dead-letter）")
                .orElseGet(() -> eventStore.find(eventId).orElse(current));
    }

    @Override
    public int dispatchPending() {
        List<EventEnvelope> snapshot = new ArrayList<>(pending());
        int published = 0;
        for (EventEnvelope event : snapshot) {
            EventEnvelope result = retry(event.eventId());
            if (result.status() == EventStatus.PUBLISHED) {
                published++;
            }
        }
        if (!snapshot.isEmpty()) {
            log.info("[event] outbox 派发完成 pending={} published={} mode={}", snapshot.size(), published,
                    publisher.mode());
        }
        return published;
    }

    @Override
    public int replayDead() {
        List<EventEnvelope> dead = new ArrayList<>(dlq());
        int published = 0;
        for (EventEnvelope event : dead) {
            // 人工重放：重试计数归零后重新投递（dead → pending → published/failed）
            if (eventStore.replay(event.eventId()).isEmpty()) {
                log.warn("[event] 死信重放被拒（状态已变化）eventId={}", event.eventId());
                continue;
            }
            EventEnvelope result = retry(event.eventId());
            if (result.status() == EventStatus.PUBLISHED) {
                published++;
            }
        }
        if (!dead.isEmpty()) {
            log.info("[event] DLQ 重放完成 dead={} published={} mode={}", dead.size(), published, publisher.mode());
        }
        return published;
    }

    /** 最大投递尝试次数（诊断/测试用） */
    public int maxAttempts() {
        return maxAttempts;
    }
}
