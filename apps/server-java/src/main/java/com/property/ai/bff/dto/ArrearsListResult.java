package com.property.ai.bff.dto;

import java.util.List;

/**
 * OpenAPI: ArrearsListResult（P3 W10 / v1.1.0 增量）。
 * 欠费台账分页结果（分页约定与 §5 一致；summary 为当前筛选条件下的分层统计）
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.1.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record ArrearsListResult(
        List<ArrearsItem> items,
        int page,
        int pageSize,
        /** 过滤后总条数 */
        long total,
        ArrearsSummary summary) {
}
