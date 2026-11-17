package com.property.ai.connector;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Actuator 健康指示器：{@code GET /actuator/health} 的 {@code erpConnector} 条目。
 *
 * <p>口径：连接器 UP/DOWN + 熔断状态 + 最近失败（不含房号/手机号）。
 * 与业务健康 {@code GET /healthz}（Java 自身）区分：ERP 不可用不应让 Java 整体 DOWN，
 * 而是走降级阶梯（契约 degraded），因此这里只作为子条目展示。
 */
@Component("erpConnector")
public class ErpConnectorHealthIndicator implements HealthIndicator {

    private final ConnectorRuntime runtime;

    public ErpConnectorHealthIndicator(ConnectorRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public Health health() {
        ConnectorHealth snapshot = runtime.health();
        ConnectorTelemetry.Metrics metrics = runtime.metrics();
        Health.Builder builder = snapshot.available() ? Health.up() : Health.status("DEGRADED");
        builder.withDetail("state", snapshot.state().name())
                .withDetail("vendor", snapshot.vendor())
                .withDetail("mock", snapshot.mock())
                .withDetail("baseUrl", snapshot.baseUrl())
                .withDetail("checkedAt", snapshot.checkedAt())
                .withDetail("latencyMs", snapshot.latencyMs())
                .withDetail("circuitState", runtime.circuitState().name())
                .withDetail("requests", metrics.requests())
                .withDetail("failures", metrics.failures())
                .withDetail("degradedFallbacks", metrics.degradedFallbacks());
        if (snapshot.errorCode() != null) {
            builder.withDetail("errorCode", snapshot.errorCode()).withDetail("message", snapshot.message());
        }
        return builder.build();
    }
}
