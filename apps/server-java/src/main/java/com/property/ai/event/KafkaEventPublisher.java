package com.property.ai.event;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.property.ai.bff.dto.EventEnvelope;

/**
 * Kafka 事件投递（P5-019，<b>占位实现</b>；{@code property.event.publisher=kafka}）。
 *
 * <h2>为什么是占位实现</h2>
 * AGENTS.md 要求本环境**不新增 Maven 依赖**（无 {@code kafka-clients}），因此本类不实现 producer/
 * serializer；它把「切 Kafka」的开关与降级路径先打通：
 * ① {@link EventPublisher} 接口已就绪（P6 加 kafka-clients 后只换实现）；
 * ② 启动期显式 WARN 并给出根因（无客户端依赖 / 无 broker / 未配置 bootstrap servers）；
 * ③ 所有投递委托 {@link InMemoryEventPublisher}，**不假装已投 Kafka**。
 *
 * <h2>降级语义</h2>
 * {@link #requestedMode()} = {@code kafka}，{@link #mode()} = {@code memory}，{@link #degraded()} = true；
 * 事件仍进 outbox 状态机（pending→published/failed/dead），DLQ 可查、可重放；但**没有跨进程投递**。
 *
 * <p>生产接入：加 {@code org.apache.kafka:kafka-clients:3.9.x} → 实现 producer（acks=all、
 * enable.idempotence=true、key=projectId）→ {@code property.event.bootstrap-servers}；
 * topic/分区键/消费幂等/DLQ 设计见 {@code docs/ops/Kafka-迁移方案.md}。
 */
public class KafkaEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisher.class);

    /** 客户端类名（存在即认为依赖已就位；本环境恒不存在） */
    private static final String KAFKA_PRODUCER_CLASS = "org.apache.kafka.clients.producer.KafkaProducer";

    private final EventProperties properties;
    private final EventPublisher memoryFallback;
    private final String unavailableReason;

    public KafkaEventPublisher(EventProperties properties, EventPublisher memoryFallback) {
        this.properties = properties == null ? new EventProperties() : properties;
        this.memoryFallback = memoryFallback;
        this.unavailableReason = probe();
        log.warn("[event] WARN 请求 publisher=kafka 但不可用，已降级为内存 outbox 投递（事件可查/可重试/DLQ，"
                + "但不会跨进程投递）：{}（接入步骤见 docs/ops/Kafka-迁移方案.md §2；降级期间不得表述为 Kafka 就绪）",
                unavailableReason);
    }

    /** 探测降级原因；本占位实现恒返回非空（不假装 Kafka 就绪） */
    private String probe() {
        if (!properties.isModeValid()) {
            return "无法识别的 property.event.publisher=" + properties.getPublisher() + "（合法值：memory|kafka）";
        }
        if (properties.getBootstrapServers() == null || properties.getBootstrapServers().isBlank()) {
            return "property.event.bootstrap-servers 未配置（示例 127.0.0.1:9092）";
        }
        if (!clientOnClasspath()) {
            return "classpath 缺少 kafka-clients 依赖（按 AGENTS.md 本环境不新增 Maven 依赖，"
                    + "生产坐标 org.apache.kafka:kafka-clients:3.9.1）";
        }
        return "KafkaEventPublisher 为占位实现（客户端已就位但未实现 producer；P6 补真实实现。"
                + "连通性诊断=" + tcpDiagnostic(properties.getBootstrapServers()) + "）";
    }

    private boolean clientOnClasspath() {
        try {
            Class.forName(KAFKA_PRODUCER_CLASS);
            return true;
        } catch (ClassNotFoundException | LinkageError ignored) {
            return false;
        }
    }

    /** 纯 JDK TCP 连通性诊断（只用于日志/报告，不作为「Kafka 就绪」依据） */
    private String tcpDiagnostic(String bootstrapServers) {
        String first = bootstrapServers.split(",")[0].trim();
        String[] parts = first.split(":");
        if (parts.length != 2) {
            return "bootstrap servers 无法解析（期望 host:port[,host:port]）";
        }
        try {
            int port = Integer.parseInt(parts[1]);
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(parts[0].toLowerCase(Locale.ROOT), port),
                        Math.max(50, properties.getConnectTimeoutMs()));
                return "可达 " + first;
            }
        } catch (Exception e) {
            return "不可达 " + first + "（" + e.getMessage() + "）";
        }
    }

    @Override
    public String requestedMode() {
        return PublisherMode.KAFKA.value();
    }

    @Override
    public String mode() {
        return PublisherMode.MEMORY.value();
    }

    @Override
    public boolean degraded() {
        return true;
    }

    @Override
    public String unavailableReason() {
        return unavailableReason;
    }

    @Override
    public boolean publish(EventEnvelope event) {
        return memoryFallback.publish(event);
    }
}
