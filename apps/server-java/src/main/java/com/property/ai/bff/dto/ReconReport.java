package com.property.ai.bff.dto;

import java.util.List;

/**
 * OpenAPI: ReconReport（对账报告（P2 门禁：差异 ≤2%））。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v0.3.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record ReconReport(
        String runId,
        ReconScope scope,
        ReconRunStatus status,
        int sampleSize,
        int checked,
        int matched,
        int diffCount,
        double diffRate,
        double threshold,
        boolean passed,
        List<ReconDiffItem> items,
        String startedAt,
        String finishedAt,
        boolean mock) {
}
