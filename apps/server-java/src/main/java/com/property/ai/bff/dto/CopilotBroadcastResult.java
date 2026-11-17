package com.property.ai.bff.dto;

import java.util.List;

/**
 * OpenAPI: CopilotBroadcastResult（P4 W15 / v1.2.0 增量）。
 * 群服务群发结果（P4 v1.2.0）：`pushIds` 为 mock 通道受理 ID 列表（可回溯），
 * `status` 恒为 `mocked`（未真实外发），`sentAt` 为受理时间。**不得对客户声称已送达**。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.2.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record CopilotBroadcastResult(
        /** mock 通道受理 ID 列表 */
        List<String> pushIds,
        /** 受理状态（恒为 mocked） */
        TicketPushStatus status,
        /** 受理时间 */
        String sentAt) {
}
