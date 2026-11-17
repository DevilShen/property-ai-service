package com.property.ai.bff.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 可观测性指标快照（P5-020 实现 / P5 W24 契约 v1.3.1 收口；
 * {@code GET /api/v1/observability/metrics}）。
 *
 * <p><b>契约</b>：OpenAPI v1.3.1 `components.schemas.ObservabilityMetrics`
 * （P5 W24 / task-69 从 {@code bff/api/observability} 内部 DTO 迁入 {@code bff/dto}，
 * 与 {@code packages/shared} 的 {@code ObservabilityMetrics} 三方同源）。
 * 形状：
 *
 * <pre>
 * {
 *   uptimeSeconds, requests{total, byStatus}, errors{total, rate},
 *   connectors[{connectorId, health, lastProbeAt?, cacheHitRate}],
 *   cache{hits, misses, hitRate}, events{pending, published, failed, dead}, generatedAt
 * }
 * </pre>
 *
 * <p>口径：
 * <ul>
 *   <li>{@code requests}：进程内 HTTP 响应计数（含 401/403/5xx；重启清零）；{@code byStatus} 键为状态码字符串；</li>
 *   <li>{@code errors}：{@code total} = HTTP 状态 ≥400 的响应数；{@code rate} = total/requests.total
 *       （4 位小数，无请求时 0）；</li>
 *   <li>{@code connectors}：仅<b>已接入 mock 测试替身（SPI 实现存在）</b>的连接器；{@code health} =
 *       {@code up|down|unknown}（连接器最近一次出站调用/探测结果，从未调用 = unknown）；
 *       {@code lastProbeAt} 仅探测过的连接器有值（未探测 = null，不伪造）；
 *       {@code cacheHitRate} 取只读缓存全局命中率（当前缓存未按连接器分桶，同值，P5 W21 迁 Redis 后细分）；</li>
 *   <li>{@code events}：进程内 outbox 状态计数（demo 无 dispatcher，未投递一律 pending，不伪造 published）；</li>
 *   <li>{@code uptimeSeconds}：JVM 启动至今秒数。</li>
 * </ul>
 *
 * <p>隐私：不含房号/手机号/姓名/请求路径，只有计数与连接器 ID。
 * <p>权限：{@code observability.read}（仅 it/admin）；只读端点不落审计。
 */
public record ObservabilityMetrics(
        long uptimeSeconds,
        Requests requests,
        Errors errors,
        List<ConnectorMetric> connectors,
        CacheMetrics cache,
        EventMetrics events,
        String generatedAt) {

    /** 请求计数（{@code byStatus} 键 = HTTP 状态码字符串，按码升序） */
    public record Requests(long total, Map<String, Long> byStatus) {
    }

    /** 错误计数与错误率（状态 ≥400，rate 4 位小数） */
    public record Errors(long total, BigDecimal rate) {
    }

    /** 单连接器指标（health: up|down|unknown；lastProbeAt 未探测时为 null） */
    public record ConnectorMetric(String connectorId, String health, String lastProbeAt, double cacheHitRate) {
    }

    /** 只读缓存指标（hitRate 0—1，4 位小数） */
    public record CacheMetrics(long hits, long misses, double hitRate) {
    }

    /** outbox 事件积压指标 */
    public record EventMetrics(long pending, long published, long failed, long dead) {
    }
}
