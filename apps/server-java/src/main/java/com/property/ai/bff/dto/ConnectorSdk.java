package com.property.ai.bff.dto;

import java.util.List;

/**
 * OpenAPI: ConnectorSdk（连接器 SDK 声明，P5 W19 / v1.3.0，架构 §24.4）。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.3.0 严格对齐；仅 DTO，不含业务逻辑。
 * sdkVersion = 语义化版本；capabilities = 声明的标准能力（⊆ 18 项 CapabilityCode）；
 * fieldCount = 映射字段条目数；limits = 运行限制。**声明先于探测、探测覆盖声明**（以 /probe 为准）。
 */
public record ConnectorSdk(
        String sdkVersion,
        String connectorId,
        List<CapabilityCode> capabilities,
        int fieldCount,
        ConnectorSdkLimits limits) {
}
