package com.property.ai.bff.dto;

import java.util.List;

/**
 * OpenAPI: MappingTrialReport（映射试跑报告（diffRate ≤ threshold 通过），v0.3.2）。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v0.3.2 严格对齐；仅 DTO，不含业务逻辑。
 */
public record MappingTrialReport(
        String runId,
        String mappingId,
        int checked,
        int matched,
        int diffCount,
        double diffRate,
        /** P2 门禁 0.02 */
        double threshold,
        boolean passed,
        List<MappingDiffItem> items,
        String startedAt,
        String finishedAt) {
}
