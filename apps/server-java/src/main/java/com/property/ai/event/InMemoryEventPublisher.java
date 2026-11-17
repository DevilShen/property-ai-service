package com.property.ai.event;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.property.ai.bff.dto.EventEnvelope;
import com.property.ai.bff.dto.EventStatus;
import com.property.ai.domain.event.EventStore;

/**
 * 内存事件投递（P5-019 默认；{@code property.event.publisher=memory}）。
 *
 * <p>复用既有 outbox 存储 {@link EventStore}：投递成功 = 状态机 {@code pending|failed → published}。
 * 语义边界：<b>「进程内受理」≠「Kafka 已投递」</b>——本实现没有 broker、没有跨进程消费方，
 * 事件只在当前进程可查（{@code GET /api/v1/events}）/可重试/DLQ 可查，重启清空。
 * 该口径必须如实写进文档与汇报（AGENTS.md NEVER #11）。
 */
public class InMemoryEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(InMemoryEventPublisher.class);

    private final EventStore eventStore;

    public InMemoryEventPublisher(EventStore eventStore) {
        this.eventStore = eventStore;
    }

    @Override
    public String requestedMode() {
        return PublisherMode.MEMORY.value();
    }

    @Override
    public String mode() {
        return PublisherMode.MEMORY.value();
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
    public boolean publish(EventEnvelope event) {
        if (event == null || event.eventId() == null || event.eventId().isBlank()) {
            return false;
        }
        Optional<EventEnvelope> current = eventStore.find(event.eventId());
        if (current.isEmpty()) {
            log.warn("[event] 内存投递失败：事件不在 outbox eventId={}（先 publish 落 outbox 再投递）", event.eventId());
            return false;
        }
        if (current.get().status() == EventStatus.PUBLISHED) {
            // 至少一次语义：重复投递同一 eventId 视为成功（消费方按 eventId 幂等去重）
            return true;
        }
        boolean marked = eventStore.markPublished(event.eventId()).isPresent();
        if (marked) {
            log.debug("[event] 内存投递 accepted eventId={} type={} projectId={}（非 Kafka 投递）",
                    event.eventId(), event.type() == null ? null : event.type().value(), event.projectId());
        } else {
            log.warn("[event] 内存投递被拒：状态机不允许 eventId={} status={}（dead 需先 replay）",
                    event.eventId(), current.get().status().value());
        }
        return marked;
    }
}
