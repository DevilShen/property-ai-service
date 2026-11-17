package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * OpenAPI: CopilotBroadcastRequest（P4 W15 / v1.2.0 增量）。
 * 群服务群发入参（P4 v1.2.0，**人工确认红线**）：`confirmed` 必须为 `true`（否则 400 `E-1001`）；
 * `targetRooms` 省略 = 不指定房号（由服务端按会话群成员解析）。**不做群内自动回复**，
 * 只做人工确认后经 `services/mock-channel` 留痕（`status: mocked`），不触达真实微信。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.2.0 严格对齐；仅 DTO，不含业务逻辑。
 * 可选字段以 class 级 {@code @JsonInclude(NON_NULL)} 保证未赋值时不序列化（契约默认不返回该字段）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CopilotBroadcastRequest(
        /** 群发正文（已审核文案；禁止含他人房号/欠费信息） */
        String content,
        /** 目标房号（可选；省略=按群成员解析） */
        List<String> targetRooms,
        /** 人工确认（必须 true；未确认 400 `E-1001`） */
        boolean confirmed) {
}
