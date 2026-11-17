package com.property.ai.bff.dto;

import java.util.List;

/**
 * OpenAPI: TicketListResult（工单分页结果（items/page/pageSize/total））。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v0.3.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record TicketListResult(
        List<TicketListItem> items,
        int page,
        int pageSize,
        long total) {
}
