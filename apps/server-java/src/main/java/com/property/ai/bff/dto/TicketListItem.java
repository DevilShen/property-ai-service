package com.property.ai.bff.dto;

/**
 * OpenAPI: TicketListItem（工单列表项（不含 nodes））。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v0.3.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record TicketListItem(
        String ticketNo,
        String roomCode,
        TicketCategory category,
        TicketUrgency urgency,
        TicketStatus status,
        String statusText,
        String team,
        String eta,
        int version,
        boolean aiCreated,
        boolean mock,
        String createdAt,
        String updatedAt) {
}
