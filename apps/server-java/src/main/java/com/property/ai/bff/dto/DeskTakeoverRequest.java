package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * OpenAPI: DeskTakeoverRequest（P4 W15 / v1.2.0 增量）。
 * 接管入参（P4 v1.2.0）：`note` 可选（如「业主重复来电，优先处理」），落审计。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.2.0 严格对齐；仅 DTO，不含业务逻辑。
 * 可选字段以 class 级 {@code @JsonInclude(NON_NULL)} 保证未赋值时不序列化（契约默认不返回该字段）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeskTakeoverRequest(
        /** 接管备注（可选，落审计） */
        String note) {
}
