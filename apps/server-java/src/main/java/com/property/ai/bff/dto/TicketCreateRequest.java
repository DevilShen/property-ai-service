package com.property.ai.bff.dto;

/**
 * OpenAPI: TicketCreateRequest（报修建单请求（写操作，必须带 Idempotency-Key））。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v0.3.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record TicketCreateRequest(
        String roomCode,
        TicketCategory category,
        TicketUrgency urgency,
        String location,
        IssueScope downstairs,
        String duration,
        String slot,
        String note,
        ReporterType reporterType,
        String contactTail4,
        String traceId) {
}
