package com.property.ai.bff.dto;

import java.util.Map;

/**
 * OpenAPI: EventEnvelope（事件信封，P5 W19 / v1.3.0，架构 §24.6/§24.8）。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.3.0 严格对齐；仅 DTO，不含业务逻辑。
 * payload 为**脱敏**业务载荷（不含真实手机号/证件号）；至少一次投递 + 消费幂等 + 死信队列，
 * dead 事件必须可查、可重放。
 */
public record EventEnvelope(
        String eventId,
        EventType type,
        String projectId,
        /** 业务载荷（脱敏；键随事件类型而定） */
        Map<String, Object> payload,
        String occurredAt,
        EventStatus status) {
}
