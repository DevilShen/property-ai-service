package com.property.ai.bff.dto;

/**
 * OpenAPI: DunningCloseResult（P3 W10 / v1.1.0 增量）。
 * 关闭结果（P3 W10 增量；只改任务状态，不写财务数据）
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.1.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record DunningCloseResult(
        String taskId,
        DunningStatus status,
        String closedAt) {
}
