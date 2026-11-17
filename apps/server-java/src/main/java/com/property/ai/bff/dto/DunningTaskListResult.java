package com.property.ai.bff.dto;

import java.util.List;

/**
 * OpenAPI: DunningTaskListResult（P3 W10 / v1.1.0 增量）。
 * 催缴任务分页结果（分页约定与 §5 一致）
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.1.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record DunningTaskListResult(
        List<DunningTask> items,
        int page,
        int pageSize,
        long total) {
}
