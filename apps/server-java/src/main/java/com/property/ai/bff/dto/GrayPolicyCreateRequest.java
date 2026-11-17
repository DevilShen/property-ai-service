package com.property.ai.bff.dto;

/**
 * OpenAPI: GrayPolicyCreateRequest（创建灰度策略入参，P5 W19 / v1.3.0）。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.3.0 严格对齐；仅 DTO，不含业务逻辑。
 * 同一 scope+targetId 仅允许一条策略（重复 → 409 E-3002 duplicate_policy）；
 * percent 越界 / 未知 targetId → 400 E-1001。
 */
public record GrayPolicyCreateRequest(
        GrayScope scope,
        String targetId,
        int percent,
        boolean enabled) {
}
