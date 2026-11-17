package com.property.ai.bff.dto;

/**
 * OpenAPI: GrayPolicy（灰度策略，P5 W19 / v1.3.0，架构 §24.4）。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.3.0 严格对齐；仅 DTO，不含业务逻辑。
 * targetId 在 scope=connector 时为连接器 ID、scope=capability 时为 CapabilityCode；
 * percent 0—100 为新版本流量比例（0 = 全量旧版，100 = 全量新版）；
 * enabled=false 暂停策略（等价全量回旧版，回滚 ≤5 分钟）。
 */
public record GrayPolicy(
        String policyId,
        GrayScope scope,
        String targetId,
        int percent,
        boolean enabled,
        String createdAt,
        String updatedAt) {
}
