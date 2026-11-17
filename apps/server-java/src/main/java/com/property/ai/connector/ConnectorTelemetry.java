package com.property.ai.connector;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

/**
 * 连接器遥测（进程内计数，供健康探测/报告/后续指标导出使用）。
 *
 * <p>P2 第 1 周只做内存计数与最近错误快照（`/actuator/health` 的 {@code erpConnector} 条目与验收报告用）；
 * P5 起替换为 Micrometer 指标（QPS/P95/超时率/降级率，设计 §24.8）。
 *
 * <p>隐私：只记录错误码与能力名，不记录房号/手机号/姓名。
 */
@Component
public class ConnectorTelemetry {

    private static final DateTimeFormatter ISO_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX").withZone(ZoneId.of("Asia/Shanghai"));

    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong timeouts = new AtomicLong();
    private final AtomicLong degradedFallbacks = new AtomicLong();
    private final AtomicReference<Snapshot> lastFailure = new AtomicReference<>();
    private final AtomicReference<String> lastSuccessAt = new AtomicReference<>();

    /** 最近一次失败快照（不含敏感数据） */
    public record Snapshot(String code, String capability, String message, String at) {
    }

    /** 对外指标快照 */
    public record Metrics(long requests, long failures, long timeouts, long degradedFallbacks,
            Snapshot lastFailure, String lastSuccessAt) {
    }

    public void recordRequest() {
        requests.incrementAndGet();
    }

    public void recordSuccess() {
        lastSuccessAt.set(now());
    }

    public void recordFailure(ConnectorException exception) {
        failures.incrementAndGet();
        if (exception.code() == com.property.ai.bff.dto.ErrorCode.E_4001) {
            timeouts.incrementAndGet();
        }
        String capability = String.valueOf(exception.details().getOrDefault("capability", "unknown"));
        lastFailure.set(new Snapshot(exception.code().value(), capability, exception.getMessage(), now()));
    }

    public void recordDegradedFallback() {
        degradedFallbacks.incrementAndGet();
    }

    public Metrics metrics() {
        return new Metrics(requests.get(), failures.get(), timeouts.get(), degradedFallbacks.get(),
                lastFailure.get(), lastSuccessAt.get());
    }

    public static String now() {
        return ISO_FORMATTER.format(Instant.now());
    }
}
