package com.property.ai.bff.dto;

import java.util.List;

/**
 * OpenAPI: RoomListResult（房档列表分页结果，v0.3.2）。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v0.3.2 严格对齐；仅 DTO，不含业务逻辑。
 */
public record RoomListResult(
        List<RoomInfo> items,
        int page,
        int pageSize,
        int total) {
}
