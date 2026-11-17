package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * OpenAPI: CopilotReportBreakdown（P4 W15 / v1.2.0 增量）。
 * 报表分组项（P4 v1.2.0）：按维度（场景/渠道/坐席）分组的计数与占比。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.2.0 严格对齐；仅 DTO，不含业务逻辑。
 * 可选字段以 class 级 {@code @JsonInclude(NON_NULL)} 保证未赋值时不序列化（契约默认不返回该字段）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CopilotReportBreakdown(
        /** 分组名（场景/渠道/坐席） */
        String name,
        /** 计数 */
        double value,
        /** 占比（0—1，可选） */
        Double ratio) {
}
