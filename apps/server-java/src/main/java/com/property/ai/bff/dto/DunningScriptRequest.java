package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * OpenAPI: DunningScriptRequest（P3 W10 / v1.1.0 增量）。
 * 生成催缴话术入参（P3 W10 增量）；字段全部可选，缺省 tone=friendly、channel=wecom
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.1.0 严格对齐；仅 DTO，不含业务逻辑。
 * 可选字段以 class 级 {@code @JsonInclude(NON_NULL)} 保证未赋值时不序列化（契约默认不返回该字段）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DunningScriptRequest(
        DunningTone tone,
        DunningChannel channel) {
}
