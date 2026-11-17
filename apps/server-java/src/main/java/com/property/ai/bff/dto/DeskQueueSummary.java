package com.property.ai.bff.dto;

/**
 * OpenAPI: DeskQueueSummary（P4 W15 / v1.2.0 增量）。
 * 队列汇总（P4 v1.2.0）：`waiting` 排队数 / `avgWaitSeconds` 平均等待秒数 / `slaBreach` 超 SLA 数。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.2.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record DeskQueueSummary(
        /** 排队会话数 */
        int waiting,
        /** 平均等待秒数 */
        int avgWaitSeconds,
        /** 超 SLA 会话数 */
        int slaBreach) {
}
