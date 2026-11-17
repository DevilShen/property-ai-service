package com.property.ai.bff.dto;

import java.util.Map;

/**
 * OpenAPI: TicketTransitionRequest（状态迁移请求（写操作，必须带 Idempotency-Key））。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v0.3.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record TicketTransitionRequest(
        TicketTransitionAction action,
        /** 动作参数（dispatch/on_site/complete/reschedule/urge） */
        Map<String, Object> payload,
        /** 乐观锁版本号 */
        int version) {
}
