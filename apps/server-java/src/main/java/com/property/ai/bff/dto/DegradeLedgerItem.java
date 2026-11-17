package com.property.ai.bff.dto;

/**
 * OpenAPI: DegradeLedgerItem（降级台账条目（连接器 × 能力 × 替代路径 命中聚合），v0.3.2）。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v0.3.2 严格对齐；仅 DTO，不含业务逻辑。
 */
public record DegradeLedgerItem(
        String id,
        String connectorId,
        CapabilityCode capability,
        /** 降级原因（人话 + 错误码） */
        String reason,
        /** 替代路径（与 DegradedInfo.fallback 同口径） */
        DegradedFallback fallback,
        /** 命中次数 */
        int count,
        String firstHitAt,
        String lastHitAt) {
}
