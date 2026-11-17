package com.property.ai.bff.dto;

/**
 * OpenAPI: TicketCreateResult（建单受理结果）。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v0.3.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record TicketCreateResult(
        String ticketNo,
        TicketStatus status,
        String team,
        String eta,
        ErpRef erp,
        boolean mock) {
}
