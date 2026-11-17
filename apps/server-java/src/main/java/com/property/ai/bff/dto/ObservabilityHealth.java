package com.property.ai.bff.dto;

import java.util.List;

/**
 * 可观测性组件健康汇总（P5-020 实现 / P5 W24 契约 v1.3.1 收口；
 * {@code GET /api/v1/observability/health}）。
 *
 * <p><b>契约</b>：OpenAPI v1.3.1 `components.schemas.ObservabilityHealth`
 * （P5 W24 / task-69 从 {@code bff/api/observability} 内部 DTO 迁入 {@code bff/dto}；
 * 组件类型拆为 {@link ObservabilityHealthComponent} 与契约同名 schema 对齐）。
 *
 * <p>字段：
 * <ul>
 *   <li>{@code status}：{@code ok}（全部组件 up）/ {@code degraded}（存在 down/unknown/disabled/degraded）；</li>
 *   <li>{@code components[]}：组件名 + 状态 + 人话说明（见 {@link ObservabilityHealthComponent}）；</li>
 *   <li>{@code degradations[]}：降级说明（为什么 degraded、当前 mock/demo 边界）；</li>
 *   <li>{@code generatedAt}：生成时间（ISO 8601，业务时区）。</li>
 * </ul>
 *
 * <p>权限：{@code observability.read}（仅 it/admin）；只读端点不落审计；
 * {@code degradations} 必须显式写明「进程内计数、无 Micrometer/Prometheus 导出」边界，
 * 不得把 demo 指标声称成生产监控（AGENTS.md NEVER #11）。
 */
public record ObservabilityHealth(
        String status,
        List<ObservabilityHealthComponent> components,
        List<String> degradations,
        String generatedAt) {
}
