package com.property.ai.connector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.property.ai.bff.dto.DegradedFallback;
import com.property.ai.bff.dto.DegradedInfo;
import com.property.ai.bff.dto.ErrorCode;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * 连接器运行时（能力网关的执行侧）：健康探测、能力矩阵、熔断状态、降级构造、traceId 透传约定。
 *
 * <p>职责边界（设计 §24.4）：
 * <ul>
 *   <li>启动探测 + 周期探测 ERP {@code /healthz}，结果进 {@link ConnectorHealth} 与 Actuator（{@code erpConnector}）；</li>
 *   <li>提供 {@code degraded{reason,fallback,retryable,code,dataAsOf}} 构造器，统一降级口径
 *       （降级不是错误：仍返回 2xx + 替代路径；写操作失败则转人工待办，不假成功）；</li>
 *   <li>透传 {@code X-Trace-Id}（由 {@link HttpErpClient} 注入到每个 ERP 请求）；
 *       本类不直接对外提供 HTTP，能力调用一律经 {@link ErpConnector}。</li>
 * </ul>
 *
 * <p>P2 第 1 周为进程内实现（单实例）；P5 起熔断/限流/健康状态迁移到 Redis + 指标导出。
 */
@Component
public class ConnectorRuntime {

    private static final Logger log = LoggerFactory.getLogger(ConnectorRuntime.class);

    private final HttpErpClient httpErpClient;
    private final ConnectorProperties properties;
    private final CircuitBreaker circuitBreaker;
    private final ConnectorTelemetry telemetry;
    private final ReadCache readCache;
    private final ObjectProvider<ErpConnector> connectorProvider;

    private final AtomicReference<ConnectorHealth> health;
    private volatile ScheduledExecutorService scheduler;
    /** 降级台账（P2-025）：连接器 fallback 命中时落台账；单测手工构造时可为 null（只记遥测） */
    private volatile DegradeLedger degradeLedger;

    public ConnectorRuntime(HttpErpClient httpErpClient, ConnectorProperties properties,
            CircuitBreaker circuitBreaker, ConnectorTelemetry telemetry, ReadCache readCache,
            ObjectProvider<ErpConnector> connectorProvider) {
        this.httpErpClient = httpErpClient;
        this.properties = properties;
        this.circuitBreaker = circuitBreaker;
        this.telemetry = telemetry;
        this.readCache = readCache;
        this.connectorProvider = connectorProvider;
        this.health = new AtomicReference<>(ConnectorHealth.unknown(
                properties.getVendorLabel(), true, properties.getBaseUrl()));
    }

    @PostConstruct
    void scheduleHealthProbes() {
        if (!properties.isEnabled() || properties.getHealthIntervalMs() <= 0) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "erp-connector-health");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::probeQuietly,
                properties.getHealthIntervalMs(), properties.getHealthIntervalMs(), TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void shutdown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /** 启动后立即探测一次（契约/设计：连接器加载即做能力探测） */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        probeQuietly();
    }

    /** 探测一次并更新健康状态（失败不外抛，避免影响启动） */
    public ConnectorHealth probe() {
        if (!properties.isEnabled()) {
            ConnectorHealth disabled = ConnectorHealth.down(properties.getVendorLabel(), true,
                    properties.getBaseUrl(), ErrorCode.E_5001.value(), "连接器未启用", ConnectorTelemetry.now());
            health.set(disabled);
            return disabled;
        }
        long startedAt = System.nanoTime();
        try {
            JsonNode envelope = httpErpClient.probeHealth();
            long latencyMs = (System.nanoTime() - startedAt) / 1_000_000L;
            boolean mock = envelope.path("mock").asBoolean(true);
            ConnectorHealth up = ConnectorHealth.up(properties.getVendorLabel(), mock,
                    properties.getBaseUrl(), latencyMs, ConnectorTelemetry.now());
            health.set(up);
            log.info("[erp] 健康探测 UP（{}，{}ms，mock={}）", properties.getBaseUrl(), latencyMs, mock);
            return up;
        } catch (ConnectorException ex) {
            ConnectorHealth down = ConnectorHealth.down(properties.getVendorLabel(), true,
                    properties.getBaseUrl(), ex.code().value(), ex.getMessage(), ConnectorTelemetry.now());
            health.set(down);
            log.warn("[erp] 健康探测 DOWN（{}）：{} {}", properties.getBaseUrl(), ex.code().value(), ex.getMessage());
            return down;
        }
    }

    private void probeQuietly() {
        try {
            probe();
        } catch (RuntimeException ex) {
            log.warn("[erp] 健康探测异常：{}", ex.toString());
        }
    }

    public ConnectorHealth health() {
        return health.get();
    }

    public boolean available() {
        return health.get().available() && circuitBreaker.allowRequest();
    }

    public CircuitBreaker.State circuitState() {
        return circuitBreaker.state();
    }

    public ConnectorTelemetry.Metrics metrics() {
        return telemetry.metrics();
    }

    public ReadCache cache() {
        return readCache;
    }

    public void recordDegradedFallback() {
        telemetry.recordDegradedFallback();
    }

    /** 降级台账装配（setter 注入：保持既有 6 参构造兼容 P2 W5/W6 单测） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setDegradeLedger(DegradeLedger degradeLedger) {
        this.degradeLedger = degradeLedger;
    }

    public DegradeLedger degradeLedger() {
        return degradeLedger;
    }

    /**
     * 记录一次连接器降级（P2-025）：遥测计数 + 降级台账聚合（连接器 × 能力 × 替代路径）。
     *
     * @param capability 失败的能力（决定台账 capability 列的契约能力码）
     * @param fallback   替代路径（cache/human/partial/alt_connector/mock）
     * @param failure    触发降级的上游异常（其错误码/人话原因进台账 reason）
     * @return 统一降级信封（供 2xx + degraded 返回）
     */
    public DegradedInfo recordDegrade(Capability capability, DegradedFallback fallback, ConnectorException failure) {
        return recordDegrade(capability, fallback, failure, null);
    }

    public DegradedInfo recordDegrade(Capability capability, DegradedFallback fallback, ConnectorException failure,
            String dataAsOf) {
        DegradedInfo degraded = degradedFromFailure(failure, fallback, dataAsOf);
        recordDegradedFallback();
        DegradeLedger ledger = degradeLedger;
        if (ledger != null) {
            ledger.record(properties.getVendorLabel(), capability.contractCode(), fallback, describeForLedger(failure));
        }
        return degraded;
    }

    /** 台账 reason 口径：人话 + 错误码（不含房号/手机号） */
    private static String describeForLedger(ConnectorException failure) {
        return "ERP " + describe(failure.code()) + "（" + failure.code().value() + "）";
    }

    /** 能力矩阵（能力声明 + 当前可用性；缺能力时前端隐藏入口 + 替代通道） */
    public List<CapabilityStatus> capabilityMatrix() {
        ErpConnector connector = connectorProvider.getIfAvailable();
        List<CapabilityStatus> rows = new ArrayList<>();
        for (Capability capability : Capability.values()) {
            boolean declared = connector != null && connector.supports(capability);
            rows.add(new CapabilityStatus(capability.code(), capability.label(), capability.readOnly(),
                    declared, stateLabel(declared)));
        }
        return rows;
    }

    private String stateLabel(boolean declared) {
        if (!declared) {
            return "unsupported";
        }
        if (!properties.isEnabled()) {
            return "disabled";
        }
        if (circuitState() == CircuitBreaker.State.OPEN) {
            return "circuit_open";
        }
        return health.get().available() ? "up" : "unknown";
    }

    /** 能力状态行（供 ERP 适配中心/P2-025 能力探测日报复用） */
    public record CapabilityStatus(String code, String capability, boolean readOnly, boolean supported,
            String state) {
    }

    // ------------------------------------------------------------------
    // 降级构造（统一口径：降级不是错误，返回 2xx + degraded）
    // ------------------------------------------------------------------

    public DegradedInfo degraded(String reason, DegradedFallback fallback, boolean retryable, ErrorCode code,
            String dataAsOf) {
        return new DegradedInfo(reason, fallback, retryable, code, dataAsOf);
    }

    /** 由上游失败构造降级（读缓存回落/转人工时使用） */
    public DegradedInfo degradedFromFailure(ConnectorException ex, DegradedFallback fallback) {
        return degradedFromFailure(ex, fallback, null);
    }

    public DegradedInfo degradedFromFailure(ConnectorException ex, DegradedFallback fallback, String dataAsOf) {
        String reason = "ERP " + describe(ex.code()) + "（" + ex.code().value() + "），" + switch (fallback) {
            case CACHE -> "已返回缓存数据";
            case HUMAN -> "已转人工处理";
            case MOCK -> "已返回演示数据（mock）";
            case PARTIAL -> "仅返回已确认部分";
            case ALT_CONNECTOR -> "已切换备用连接器";
            case NONE -> "无替代路径";
        };
        return new DegradedInfo(reason, fallback, ex.retryable(), ex.code(), dataAsOf);
    }

    private static String describe(ErrorCode code) {
        return switch (code) {
            case E_4001 -> "超时/不可达";
            case E_4002 -> "上游错误";
            case E_4003 -> "限流";
            case E_5001 -> "能力未开通";
            case E_5002 -> "返回字段缺失/映射失败";
            case E_3001 -> "无该记录";
            default -> "调用失败";
        };
    }

    /** 健康快照 + 熔断 + 指标的一次性摘要（报告/Actuator 使用） */
    public Map<String, Object> statusSummary() {
        ConnectorTelemetry.Metrics metrics = telemetry.metrics();
        return Map.of(
                "health", health.get(),
                "circuitState", circuitState().name(),
                "consecutiveFailures", circuitBreaker.consecutiveFailures(),
                "requests", metrics.requests(),
                "failures", metrics.failures(),
                "degradedFallbacks", metrics.degradedFallbacks());
    }
}
