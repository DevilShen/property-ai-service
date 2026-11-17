package com.property.ai.bff.dto;

import java.util.List;

/**
 * OpenAPI: FeeCatalogChangeListResult（P4 W15 / v1.2.0 增量）。
 * 费项口径变更分页结果（P4 v1.2.0，分页约定同 README §2）。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.2.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record FeeCatalogChangeListResult(
        /** 变更单 */
        List<FeeCatalogChange> items,
        /** 页码 */
        int page,
        /** 每页条数 */
        int pageSize,
        /** 总条数 */
        int total) {
}
