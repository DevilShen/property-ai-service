package com.property.ai.bff.api.observability;

import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.property.ai.bff.dto.ObservabilityHealth;
import com.property.ai.bff.dto.ObservabilityHealthComponent;
import com.property.ai.bff.dto.ObservabilityMetrics;
import com.property.ai.connector.ChannelProperties;
import com.property.ai.connector.ConnectorDefinition;
import com.property.ai.connector.ConnectorHealth;
import com.property.ai.connector.ConnectorProperties;
import com.property.ai.connector.ConnectorRegistry;
import com.property.ai.connector.ConnectorRouter;
import com.property.ai.connector.ConnectorRuntime;
import com.property.ai.connector.ConnectorTelemetry;
import com.property.ai.connector.ErpConnector;
import com.property.ai.connector.ProbeService;
import com.property.ai.connector.ReadCache;
import com.property.ai.domain.event.EventStore;

/**
 * 可观测性聚合服务（P5-020 / task-65 实现；P5 W24 / task-69 契约 v1.3.1 收口）。
 *
 * <p><b>契约</b>：OpenAPI v1.3.1 已声明 {@code /api/v1/observability/*} 与
 * {@link ObservabilityMetrics} / {@link ObservabilityHealth} / {@link ObservabilityHealthComponent}
 * （P5 W24 契约补口；DTO 已迁 {@code bff/dto} 并与 {@code packages/shared} 三方同源）。
 *
 * <h2>数据来源（全部服务端真实计数，不造数）</h2>
 * <ul>
 *   <li>HTTP 请求/错误：{@link RequestMetrics}（{@link RequestMetricsFilter} 在响应完成后计数）；</li>
 *   <li>连接器健康：各 {@link ErpConnector#health()} 的最近一次出站调用/探测结果（未调用 = unknown）；</li>
 *   <li>最近探测时间：{@link ProbeService#latestReport(String)}（仅已探测连接器有值）；</li>
 *   <li>缓存：{@link ReadCache#stats()}（命中/未命中，进程内计数）；</li>
 *   <li>事件积压：{@link EventStore#counts()}（demo 无 dispatcher → pending，不伪造 published）；</li>
 *   <li>通道：{@link ChannelProperties}（mock 通道开关；不真实发送）。</li>
 * </ul>
 *
 * <h2>边界</h2>
 * 只读聚合，不写业务主数据、不触发 ERP 调用（不会因为查指标打爆上游）；不记录房号/手机号/请求路径。
 * 指标为进程内计数，重启清零（P5 W21—W22 迁 Micrometer/Prometheus，端点形状不变）。
 */
@Service
public class ObservabilityService {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityService.class);

    /** outbox pending 超过该阈值即视为积压（health 组件降级；demo 为 1000 上限，阈值取 500） */
    private static final long EVENT_PENDING_ALERT_THRESHOLD = 500L;

    private static final String INTERNAL_CONTRACT_NOTE =
            "契约边界：metrics 为进程内计数（无 Micrometer/Prometheus 导出），重启清零；"
                    + "端点与 DTO 已于 P5-024 契约 v1.3.1 声明（GET /api/v1/observability/*）";

    private final ConnectorRegistry registry;
    private final ConnectorRouter router;
    private final ProbeService probeService;
    private final ReadCache readCache;
    private final EventStore eventStore;
    private final RequestMetrics requestMetrics;
    private final ConnectorProperties connectorProperties;
    private final ChannelProperties channelProperties;
    private final ConnectorRuntime connectorRuntime;

    public ObservabilityService(ConnectorRegistry registry, ConnectorRouter router, ProbeService probeService,
            ReadCache readCache, EventStore eventStore, RequestMetrics requestMetrics,
            ConnectorProperties connectorProperties, ChannelProperties channelProperties,
            ConnectorRuntime connectorRuntime) {
        this.registry = registry;
        this.router = router;
        this.probeService = probeService;
        this.readCache = readCache;
        this.eventStore = eventStore;
        this.requestMetrics = requestMetrics;
        this.connectorProperties = connectorProperties;
        this.channelProperties = channelProperties;
        this.connectorRuntime = connectorRuntime;
    }

    // ==================================================================
    // GET /api/v1/observability/metrics
    // ==================================================================

    /** 指标快照（{@link ObservabilityMetrics}；纯读，无副作用） */
    public ObservabilityMetrics metrics() {
        RequestMetrics.Snapshot snapshot = requestMetrics.snapshot();
        BigDecimal rate = snapshot.total() == 0
                ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)
                : BigDecimal.valueOf(snapshot.errorTotal())
                        .divide(BigDecimal.valueOf(snapshot.total()), 4, RoundingMode.HALF_UP);
        ReadCache.Stats cacheStats = readCache.stats();
        EventStore.EventCounts eventCounts = eventStore.counts();
        ObservabilityMetrics result = new ObservabilityMetrics(
                uptimeSeconds(),
                new ObservabilityMetrics.Requests(snapshot.total(), snapshot.byStatus()),
                new ObservabilityMetrics.Errors(snapshot.errorTotal(), rate),
                connectorMetrics(cacheStats.hitRate()),
                new ObservabilityMetrics.CacheMetrics(cacheStats.hits(), cacheStats.misses(), cacheStats.hitRate()),
                new ObservabilityMetrics.EventMetrics(eventCounts.pending(), eventCounts.published(),
                        eventCounts.failed(), eventCounts.dead()),
                nowIso());
        log.debug("[observability] metrics requests={} errors={} connectors={} cacheHits={} eventPending={}",
                snapshot.total(), snapshot.errorTotal(), result.connectors().size(), cacheStats.hits(),
                eventCounts.pending());
        return result;
    }

    /** 已接入 mock（有 SPI 实现）的连接器指标；未接入的注册声明连接器不展示（避免把声明当已接入） */
    private List<ObservabilityMetrics.ConnectorMetric> connectorMetrics(double cacheHitRate) {
        List<ObservabilityMetrics.ConnectorMetric> rows = new ArrayList<>();
        for (ConnectorDefinition definition : registry.all()) {
            ErpConnector connector = router.find(definition.connectorId()).orElse(null);
            if (connector == null) {
                continue;
            }
            rows.add(new ObservabilityMetrics.ConnectorMetric(
                    definition.connectorId(),
                    healthLabel(connector.health()),
                    probeService.latestReport(definition.connectorId())
                            .map(report -> report.finishedAt())
                            .orElse(null),
                    cacheHitRate));
        }
        return List.copyOf(rows);
    }

    // ==================================================================
    // GET /api/v1/observability/health
    // ==================================================================

    /** 组件健康汇总（{@link ObservabilityHealth}；纯读，无副作用） */
    public ObservabilityHealth health() {
        List<ObservabilityHealthComponent> components = new ArrayList<>();
        List<String> degradations = new ArrayList<>();

        // 1) 默认连接器（NC）：健康 + 熔断状态（能力网关主链路）
        ConnectorHealth primary = connectorRuntime.health();
        components.add(new ObservabilityHealthComponent("erp",
                healthLabel(primary),
                "默认连接器=" + ConnectorRegistry.NC_MOCK + "，熔断=" + connectorRuntime.circuitState().name()
                        + "，baseUrl=" + safe(primary.baseUrl())));
        if (primary.state() != ConnectorHealth.State.UP) {
            degradations.add("erp：默认连接器最近一次探测状态=" + healthLabel(primary)
                    + "（未探测/不可达时能力调用走降级阶梯 cache→human）");
        }
        ConnectorTelemetry.Metrics erpMetrics = connectorRuntime.metrics();
        components.add(new ObservabilityHealthComponent("erp.telemetry",
                erpMetrics.failures() > 0 ? "degraded" : "up",
                "requests=" + erpMetrics.requests() + "，failures=" + erpMetrics.failures()
                        + "，timeouts=" + erpMetrics.timeouts()
                        + "，degradedFallbacks=" + erpMetrics.degradedFallbacks()));

        // 2) 每个已接入连接器（第 2/3 套）：健康 = 最近一次出站调用结果
        for (ConnectorDefinition definition : registry.all()) {
            ErpConnector connector = router.find(definition.connectorId()).orElse(null);
            if (connector == null) {
                continue;
            }
            ConnectorHealth health = connector.health();
            components.add(new ObservabilityHealthComponent("connector:" + definition.connectorId(),
                    healthLabel(health),
                    "vendor=" + definition.vendor() + "，mock=" + definition.mock()
                            + "，status=" + definition.status().value()
                            + (health.message() == null ? "" : "，" + health.message())));
            if (health.state() == ConnectorHealth.State.DOWN) {
                degradations.add("connector：" + definition.connectorId()
                        + " 最近一次调用失败（" + safe(health.errorCode()) + "），该连接器能力调用会转降级/转人工");
            }
        }

        // 3) 推送通道（mock 通道；关闭时推送落 failed，不影响工单主流程）
        boolean channelEnabled = channelProperties.isEnabled();
        components.add(new ObservabilityHealthComponent("channel", channelEnabled ? "up" : "disabled",
                "mock 通道=" + safe(channelProperties.getBaseUrl())
                        + "（测试替身，不接真实短信/企微/微信）"));
        if (!channelEnabled) {
            degradations.add("channel：推送通道已关闭（property.channel.enabled=false），推送会落 failed 待人工重试");
        }

        // 4) 只读缓存（L2 降级阶梯）
        ReadCache.Stats cacheStats = readCache.stats();
        boolean cacheEnabled = connectorProperties.getCacheTtlMs() > 0;
        components.add(new ObservabilityHealthComponent("cache", cacheEnabled ? "up" : "disabled",
                "entries=" + readCache.size() + "，ttlSeconds=" + readCache.ttlSeconds()
                        + "，hits=" + cacheStats.hits() + "，misses=" + cacheStats.misses()
                        + "，hitRate=" + cacheStats.hitRate()));
        if (!cacheEnabled) {
            degradations.add("cache：只读缓存 TTL=0（property.erp.cache-ttl-ms），ERP 不可用时无缓存回落");
        }

        // 5) outbox 事件（demo 无 dispatcher：pending 属预期，不伪造 published）
        EventStore.EventCounts eventCounts = eventStore.counts();
        String eventState = eventCounts.pending() > EVENT_PENDING_ALERT_THRESHOLD ? "degraded" : "up";
        components.add(new ObservabilityHealthComponent("event", eventState,
                "pending=" + eventCounts.pending() + "，published=" + eventCounts.published()
                        + "，failed=" + eventCounts.failed() + "，dead=" + eventCounts.dead()));
        if (eventCounts.pending() > 0) {
            degradations.add("event：outbox 有 " + eventCounts.pending()
                    + " 条 pending（demo 无 Kafka dispatcher，属预期；P5 W21 接入投递器后收敛）");
        }

        // 6) 指标系统自身边界（必须显式声明，避免把进程内计数当生产监控）
        degradations.add("observability：metrics 为进程内计数（无 Micrometer/Prometheus 导出），重启清零；"
                + INTERNAL_CONTRACT_NOTE);

        boolean degraded = components.stream().anyMatch(component ->
                "down".equals(component.status()) || "degraded".equals(component.status())
                        || "disabled".equals(component.status()) || "unknown".equals(component.status()));
        ObservabilityHealth result = new ObservabilityHealth(degraded ? "degraded" : "ok",
                List.copyOf(components), List.copyOf(degradations), nowIso());
        log.debug("[observability] health status={} components={} degradations={}", result.status(),
                components.size(), degradations.size());
        return result;
    }

    // ==================================================================
    // 内部
    // ==================================================================

    private static String healthLabel(ConnectorHealth health) {
        if (health == null) {
            return "unknown";
        }
        return switch (health.state()) {
            case UP -> "up";
            case DOWN -> "down";
            case UNKNOWN -> "unknown";
        };
    }

    private static long uptimeSeconds() {
        long startedAt = ManagementFactory.getRuntimeMXBean().getStartTime();
        long elapsed = System.currentTimeMillis() - startedAt;
        return Math.max(0L, elapsed / 1000L);
    }

    private static String nowIso() {
        return ConnectorTelemetry.now();
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    /** 供测试/诊断：内部演示端点的契约补口说明（与 Controller javadoc 同源） */
    public static String contractNote() {
        return INTERNAL_CONTRACT_NOTE;
    }

    /** 供测试：当前 JVM 启动时间（ISO，UTC 便于断言） */
    public static String jvmStartedAtIso() {
        return Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime()).toString();
    }
}
