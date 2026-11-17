package com.property.ai.bff.dto;

import java.util.List;

/**
 * OpenAPI: DeskQaSampleListResult（P4 W15 / v1.2.0 增量）。
 * 质检抽样分页结果（P4 v1.2.0，分页约定同 README §2）。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.2.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record DeskQaSampleListResult(
        /** 抽样项 */
        List<DeskQaSample> items,
        /** 页码 */
        int page,
        /** 每页条数 */
        int pageSize,
        /** 总条数 */
        int total) {
}
