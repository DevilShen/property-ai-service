package com.property.ai.bff.dto;

/**
 * OpenAPI: DunningCloseRequest（P3 W10 / v1.1.0 增量）。
 * 关闭催缴任务入参（P3 W10 增量）；reason 必填并落审计
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.1.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record DunningCloseRequest(
        /** 关闭原因（业主已缴费/转人工/争议/无需继续等） */
        String reason) {
}
