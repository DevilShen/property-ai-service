package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * OpenAPI: GrayPolicyUpdateRequest（更新灰度策略入参，P5 W19 / v1.3.0）。
 * 字段名 / 必填与 docs/openapi/openapi.yaml v1.3.0 严格对齐；仅 DTO，不含业务逻辑。
 * **至少提供一项**（空 body → 400 E-1001）；enabled=false 即暂停/回滚（≤5 分钟生效）。
 * 可选字段以 class 级 {@code @JsonInclude(NON_NULL)} 保证未赋值时不序列化。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GrayPolicyUpdateRequest(
        /** 新版本流量比例（0—100，可选） */
        Integer percent,
        /** 是否启用（false = 暂停灰度/回滚，可选） */
        Boolean enabled) {
}
