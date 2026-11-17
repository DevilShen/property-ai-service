package com.property.ai.bff.dto;

/**
 * OpenAPI: DeskSlaHour（P4 W15 / v1.2.0 增量）。
 * SLA 分时项（P4 v1.2.0）：按小时聚合的首响均值与超时数（0—23 时）。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.2.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record DeskSlaHour(
        /** 小时（0—23） */
        int hour,
        /** 该小时平均首响秒数 */
        int avgFirstResponseSeconds,
        /** 该小时超 SLA 数 */
        int breaches) {
}
