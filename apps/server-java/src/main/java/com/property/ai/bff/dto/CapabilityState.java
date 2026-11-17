package com.property.ai.bff.dto;

/**
 * OpenAPI: CapabilityState（单能力探测态（supported/degraded/unsupported + 证据 + 替代通道），v0.3.2）。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v0.3.2 严格对齐；仅 DTO，不含业务逻辑。
 */
public record CapabilityState(
        /** 设计编号 CAP-01…CAP-18（可选） */
        String capabilityCode,
        CapabilityCode code,
        String name,
        CapabilityLevel level,
        CapabilityStatus state,
        String checkedAt,
        /** 探测证据（可选） */
        String evidence,
        /** 替代通道文案（可选） */
        String fallback) {
}
