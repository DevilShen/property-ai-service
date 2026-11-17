package com.property.ai.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.property.ai.cache.CacheStore;
import com.property.ai.domain.ticket.TicketRepository;
import com.property.ai.event.EventPublisher;
import com.property.ai.event.OutboxStore;

/**
 * 存储 / 缓存 / 事件三模式启动报告（P5 W21 / task-66）。
 *
 * <p>启动完成后（{@link ApplicationReadyEvent}）打一行**统一口径**日志，便于运维与验收快速判断当前生效实现：
 * <pre>
 * [P5-W21] storageMode=memory cacheMode=memory publisher=memory degraded=false
 * </pre>
 * 任一开关请求了生产实现但探测失败时，再打一条汇总 WARN（具体根因由 {@code JdbcTicketRepository} /
 * {@code RedisCacheStore} / {@code KafkaEventPublisher} 在装配期各自 WARN 一次）。
 *
 * <p>口径：本环境无 PG/Redis/Kafka 实例，默认三模式均为 memory —— 这是 demo 实现，**不代表生产就绪**
 * （AGENTS.md：P1 例外/不得声称生产完成；P5 门禁要求「迁移方案与接口就绪」）。
 */
@Component
public class StorageStartupReporter {

    private static final Logger log = LoggerFactory.getLogger(StorageStartupReporter.class);

    private final TicketRepository ticketRepository;
    private final CacheStore cacheStore;
    private final EventPublisher eventPublisher;
    private final OutboxStore outboxStore;

    public StorageStartupReporter(TicketRepository ticketRepository, CacheStore cacheStore,
            EventPublisher eventPublisher, OutboxStore outboxStore) {
        this.ticketRepository = ticketRepository;
        this.cacheStore = cacheStore;
        this.eventPublisher = eventPublisher;
        this.outboxStore = outboxStore;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void report() {
        String storageMode = storageMode();
        String cacheMode = cacheStore.mode();
        String publisherMode = eventPublisher.mode();
        boolean degraded = isStorageDegraded() || cacheStore.degraded() || eventPublisher.degraded();
        log.info("[P5-W21] storageMode={} cacheMode={} publisher={} outbox={} degraded={}", storageMode, cacheMode,
                publisherMode, outboxStore.mode(), degraded);
        if (degraded) {
            log.warn("[P5-W21] WARN 存储/缓存/事件降级（请求 vs 生效）：storage={} cache={} publisher={}；"
                    + "本环境无 PG/Redis/Kafka 实例，按 AGENTS.md 以「接口 + 可切换 demo 实现 + 迁移方案文档」交付，"
                    + "不得表述为生产就绪", storageSummary(), cacheSummary(), publisherSummary());
        }
    }

    private String storageMode() {
        return ticketRepository instanceof StorageModeAware aware
                ? aware.storageStatus().effectiveMode()
                : StorageMode.MEMORY.value();
    }

    private boolean isStorageDegraded() {
        return ticketRepository instanceof StorageModeAware aware && aware.storageStatus().degraded();
    }

    private String storageSummary() {
        return ticketRepository instanceof StorageModeAware aware
                ? aware.storageStatus().summary()
                : "storageMode=" + StorageMode.MEMORY.value();
    }

    private String cacheSummary() {
        return "cacheMode=" + cacheStore.mode() + " requested=" + cacheStore.requestedMode()
                + (cacheStore.unavailableReason() == null ? "" : " reason=" + cacheStore.unavailableReason());
    }

    private String publisherSummary() {
        return "publisher=" + eventPublisher.mode() + " requested=" + eventPublisher.requestedMode()
                + (eventPublisher.unavailableReason() == null ? "" : " reason=" + eventPublisher.unavailableReason());
    }
}
