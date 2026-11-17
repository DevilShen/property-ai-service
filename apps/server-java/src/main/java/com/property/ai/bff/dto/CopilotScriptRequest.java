package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * OpenAPI: CopilotScriptRequest（P4 W15 / v1.2.0 增量）。
 * 话术生成入参（P4 v1.2.0）：场景 × 情绪 × 渠道 → 3 版话术 + 禁区扫描。
 * `channel` 复用推送通道枚举（wecom/sms/inapp），**只生成不外发**（触达需人工确认）。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.2.0 严格对齐；仅 DTO，不含业务逻辑。
 * 可选字段以 class 级 {@code @JsonInclude(NON_NULL)} 保证未赋值时不序列化（契约默认不返回该字段）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CopilotScriptRequest(
        /** 场景（如「装修噪声」「催缴首响」「投诉安抚」「上门预约」） */
        String scenario,
        /** 业主情绪（影响语气与开场） */
        CopilotEmotion emotion,
        /** 拟用渠道（只影响文案长度/格式，不触达） */
        TicketPushChannel channel,
        /** 关联工单号（可选，便于引用上下文） */
        String ticketNo) {
}
