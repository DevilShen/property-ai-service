package com.property.ai.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import com.property.ai.domain.event.EventStore;

/**
 * 事件总线装配（P5-019）。
 *
 * <p>默认 {@code property.event.publisher=memory}：{@link InMemoryEventPublisher}，事件进
 * {@link EventStore} outbox 状态机（pending→published/failed/dead），DLQ 可查可重放；
 * {@code publisher=kafka}：{@link KafkaEventPublisher} 占位实现，构造期探测不可用 → WARN + 降级内存投递。
 *
 * <p>不注册自动 dispatcher（不改变既有 pending 观测口径）；派发/重试/DLQ 重放通过
 * {@link OutboxStore} 显式调用（P6 由 Kafka consumer + outbox dispatcher 承担）。
 */
@Configuration
@EnableConfigurationProperties(EventProperties.class)
public class EventConfig {

    private static final Logger log = LoggerFactory.getLogger(EventConfig.class);

    @Bean
    public EventPublisher eventPublisher(EventProperties properties, EventStore eventStore, Environment environment) {
        applyShortFormMode(properties, environment);
        if (!properties.isModeValid()) {
            log.warn("[event] WARN 无法识别的 property.event.publisher={}，按 memory 处理（合法值：memory|kafka）",
                    properties.getPublisher());
        }
        InMemoryEventPublisher memoryPublisher = new InMemoryEventPublisher(eventStore);
        if (properties.isKafkaRequested()) {
            return new KafkaEventPublisher(properties, memoryPublisher);
        }
        log.info("[event] publisher=memory topic={}（进程内 outbox 投递；Kafka 开关见 property.event.publisher）",
                properties.getTopic());
        return memoryPublisher;
    }

    /** 短开关兼容：{@code property.event} → {@code property.event.publisher}（显式长开关优先） */
    static void applyShortFormMode(EventProperties properties, Environment environment) {
        if (environment == null) {
            return;
        }
        String explicitMode = environment.getProperty("property.event.publisher");
        if (explicitMode != null && !explicitMode.isBlank()) {
            return;
        }
        String shortForm = environment.getProperty("property.event");
        if (shortForm != null && !shortForm.isBlank()) {
            properties.setPublisher(shortForm.trim());
            log.info("[event] 使用短开关 property.event={}（等价 property.event.publisher={}）", shortForm.trim(),
                    shortForm.trim());
        }
    }

    @Bean
    public OutboxStore outboxStore(EventStore eventStore, EventPublisher eventPublisher, EventProperties properties) {
        return new InMemoryOutboxStore(eventStore, eventPublisher, properties.getMaxAttempts());
    }
}
