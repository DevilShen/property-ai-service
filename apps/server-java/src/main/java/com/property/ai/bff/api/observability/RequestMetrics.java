package com.property.ai.bff.api.observability;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

/**
 * 进程内 HTTP 请求计数（P5-020 / task-65，{@code GET /api/v1/observability/metrics} 的数据源之一）。
 *
 * <p>口径（轻量计数器，不引入 Micrometer/Prometheus 依赖）：
 * <ul>
 *   <li>{@code total}：经过 {@link RequestMetricsFilter} 的全部 HTTP 响应数（含公开端点/401/403/5xx）；</li>
 *   <li>{@code byStatus}：按 HTTP 状态码分组（键为状态码字符串，快照按状态码升序输出，形状稳定）；</li>
 *   <li>{@code errorTotal}：状态码 ≥400 的响应数（含客户端错误与上游失败转 5xx），供 {@code errors.rate} 使用。</li>
 * </ul>
 *
 * <p><b>边界</b>：进程内计数、重启清零；不记录路径/角色/请求体（避免把房号/手机号写进指标）。
 * 生产由 P5 W21—W22 的指标系统（Micrometer + Prometheus 导出）替换本类，端点形状不变。
 */
@Component
public class RequestMetrics {

    private final AtomicLong total = new AtomicLong();
    private final AtomicLong errorTotal = new AtomicLong();
    private final ConcurrentMap<Integer, AtomicLong> byStatus = new ConcurrentHashMap<>();

    /** 记录一次响应（请求完成后调用） */
    public void record(int status) {
        total.incrementAndGet();
        byStatus.computeIfAbsent(status, key -> new AtomicLong()).incrementAndGet();
        if (status >= 400) {
            errorTotal.incrementAndGet();
        }
    }

    /** 请求计数快照（不可变） */
    public record Snapshot(long total, long errorTotal, Map<String, Long> byStatus) {
    }

    /** 取快照：byStatus 按状态码升序（契约/测试形状稳定；用 LinkedHashMap 保留插入顺序） */
    public Snapshot snapshot() {
        Map<String, Long> ordered = new LinkedHashMap<>();
        byStatus.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> ordered.put(String.valueOf(entry.getKey()), entry.getValue().get()));
        return new Snapshot(total.get(), errorTotal.get(),
                java.util.Collections.unmodifiableMap(new LinkedHashMap<>(ordered)));
    }

    /** 重置计数（仅测试/演示用） */
    public void resetForTest() {
        total.set(0);
        errorTotal.set(0);
        byStatus.clear();
    }
}
