package com.property.ai.bff.dto;

/**
 * OpenAPI: TraceSearchItem（P4 W15 / v1.2.0 增量）。
 * 回溯检索项（P4 v1.2.0）：每个 `traceId` 一行，`entries` 为节点数（不展开节点内容，避免大响应）。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.2.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record TraceSearchItem(
        /** 链路 ID */
        String traceId,
        /** 首个节点时间 */
        String firstAt,
        /** 最后一个节点时间 */
        String lastAt,
        /** 主要操作主体（掩码） */
        String actorId,
        /** 首个动作（与审计 action 同口径） */
        String action,
        /** 节点数 */
        int entries) {
}
