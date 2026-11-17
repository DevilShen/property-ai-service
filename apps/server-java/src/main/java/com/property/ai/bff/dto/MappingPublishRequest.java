package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * OpenAPI: MappingPublishRequest（映射发布请求，P5 W19 / v1.3.0）。
 * 字段名与 docs/openapi/openapi.yaml v1.3.0 严格对齐；仅 DTO，不含业务逻辑。
 * 请求体**可选**（省略 body 与 v0.3.2 行为完全一致）；connectorId 为连接器作用域：
 * 省略 = 取映射自身 connectorId；显式指定与映射绑定连接器不一致 → 403 E-2002 cross_connector。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MappingPublishRequest(
        /** 连接器作用域（可选；省略 = 映射自身 connectorId） */
        String connectorId) {
}
