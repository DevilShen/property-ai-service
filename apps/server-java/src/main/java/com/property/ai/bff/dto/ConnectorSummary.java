package com.property.ai.bff.dto;

/**
 * OpenAPI: ConnectorSummary（连接器摘要（能力计数取最近一次探测/声明），v0.3.2）。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v0.3.2 严格对齐；仅 DTO，不含业务逻辑。
 */
public record ConnectorSummary(
        String connectorId,
        String name,
        String vendor,
        String vendorVersion,
        ConnectorStatus status,
        ConnectorHealth health,
        /** 是否 mock-erp 测试替身 */
        boolean mock,
        /** 能力总数（标准 18 项） */
        int capabilityTotal,
        int supportedCount,
        int degradedCount,
        int unsupportedCount,
        /** 最近一次探测时间（可选） */
        String lastProbeAt,
        /** 当前生效映射版本（可选） */
        String mappingVersion,
        String updatedAt) {
}
