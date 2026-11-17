package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * OpenAPI: MappingRollbackRequest（映射回滚请求（toVersion 省略 = 上一个已发布版本），v0.3.2；
 * P5 W19 / v1.3.0 末尾追加可选 connectorId）。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.3.0 严格对齐；仅 DTO，不含业务逻辑。
 * v1.3.0 追加（末尾可选，既有字段与语义不变）：connectorId = 连接器作用域（省略 = 映射自身
 * connectorId；显式指定且与映射绑定连接器不一致 → 403 E-2002 cross_connector）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MappingRollbackRequest(
        /** 目标版本号（省略 = 上一个已发布版本） */
        String toVersion,
        /** 回滚原因（进回滚记录，不含敏感信息） */
        String reason,
        /** 连接器作用域（v1.3.0 可选；省略 = 映射自身 connectorId） */
        String connectorId) {

    /** v0.3.2 旧构造重载（P2 调用方/测试不破；connectorId 为 null）。 */
    public MappingRollbackRequest(String toVersion, String reason) {
        this(toVersion, reason, null);
    }
}
