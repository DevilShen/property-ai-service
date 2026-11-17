package com.property.ai.bff.dto;

/**
 * OpenAPI: TicketAuditNode（工单节点/审计事件）。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v0.3.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record TicketAuditNode(
        TicketStatus status,
        String statusText,
        String at,
        String who,
        String ownerText) {
}
