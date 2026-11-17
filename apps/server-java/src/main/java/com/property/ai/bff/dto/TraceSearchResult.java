package com.property.ai.bff.dto;

import java.util.List;

/**
 * OpenAPI: TraceSearchResult（P4 W15 / v1.2.0 增量）。
 * 回溯检索结果（P4 v1.2.0，分页约定同 README §2）。筛选条件全部可选，按 `lastAt` 降序。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.2.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record TraceSearchResult(
        /** 命中链路 */
        List<TraceSearchItem> items,
        /** 页码 */
        int page,
        /** 每页条数 */
        int pageSize,
        /** 总条数 */
        int total) {
}
