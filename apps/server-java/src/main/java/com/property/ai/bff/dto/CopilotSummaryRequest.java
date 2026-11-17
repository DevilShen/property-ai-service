package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * OpenAPI: CopilotSummaryRequest（P4 W15 / v1.2.0 增量）。
 * 工单摘要入参（P4 v1.2.0）。`ticketNo` 与 `messages` 二选一（都缺 → 400 `E-1001`）：
 * 给 `ticketNo` 由服务端读工单/会话；给 `messages` 直接压摘要。**入参不得含手机号/地址明文**。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.2.0 严格对齐；仅 DTO，不含业务逻辑。
 * 可选字段以 class 级 {@code @JsonInclude(NON_NULL)} 保证未赋值时不序列化（契约默认不返回该字段）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CopilotSummaryRequest(
        /** 工单号（读工单与会话上下文） */
        String ticketNo,
        /** 会话/群消息原文（已脱敏；与 ticketNo 二选一） */
        List<String> messages) {
}
