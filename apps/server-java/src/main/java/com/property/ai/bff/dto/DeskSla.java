package com.property.ai.bff.dto;

import java.util.List;

/**
 * OpenAPI: DeskSla（P4 W15 / v1.2.0 增量）。
 * 坐席 SLA（P4 v1.2.0）：`avgFirstResponseSeconds` 平均首响秒数、`breachRate` 超时率（0—1）、
 * `byHour[]` 分时明细。口径：首响 = 坐席第一条人工回复；SLA 超时以 `slaDueAt` 判定。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.2.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record DeskSla(
        /** 平均首响秒数 */
        int avgFirstResponseSeconds,
        /** SLA 超时率（0—1） */
        double breachRate,
        /** 分时明细（0—23 时，缺数据小时记 0） */
        List<DeskSlaHour> byHour) {
}
