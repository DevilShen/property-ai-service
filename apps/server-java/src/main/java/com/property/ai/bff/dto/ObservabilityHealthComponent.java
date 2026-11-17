package com.property.ai.bff.dto;

/**
 * 可观测性单组件健康（P5-020 实现 / P5 W24 契约 v1.3.1 收口；{@code ObservabilityHealth.components[]}）。
 *
 * <p><b>契约</b>：OpenAPI v1.3.1 `components.schemas.ObservabilityHealthComponent`
 * （P5 W24 / task-69 新增；P5-020 时为 {@code ObservabilityHealth} 内部 record {@code Component}）。
 *
 * <p>字段：
 * <ul>
 *   <li>{@code name}：组件名——{@code erp}（默认连接器）、{@code erp.telemetry}、
 *       每个已接入连接器（{@code connector:<id>}）、{@code channel}（mock 推送通道）、{@code cache}、{@code event}；</li>
 *   <li>{@code status}：{@code up|down|unknown|disabled|degraded}（{@code disabled} = 该能力被配置关闭）；</li>
 *   <li>{@code detail}：人话说明（不含房号/手机号/姓名；只含计数、连接器 ID 与配置项）。</li>
 * </ul>
 */
public record ObservabilityHealthComponent(String name, String status, String detail) {
}
