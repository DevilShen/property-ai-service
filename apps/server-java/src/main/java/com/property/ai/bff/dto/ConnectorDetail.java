package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * OpenAPI: ConnectorDetail（连接器详情（摘要 + 18 项能力探测态），v0.3.2；P5 W19 / v1.3.0 末尾追加可选 sdk）。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.3.0 严格对齐；仅 DTO，不含业务逻辑。
 * v1.3.0 追加（末尾可选，既有必填集不变）：sdk = 连接器 SDK 声明（注册即声明，探测覆盖声明）。
 * 可选字段以 class 级 {@code @JsonInclude(NON_NULL)} 保证未赋值时不序列化（契约默认不返回该字段）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConnectorDetail(
        ConnectorSummary connector,
        List<CapabilityState> capabilities,
        /** 连接器 SDK 声明（v1.3.0 可选；未注册 SDK 时为 null 不序列化） */
        ConnectorSdk sdk) {

    /** v0.3.2 旧构造重载（P2 调用方/测试不破；sdk 为 null）。 */
    public ConnectorDetail(ConnectorSummary connector, List<CapabilityState> capabilities) {
        this(connector, capabilities, null);
    }
}
