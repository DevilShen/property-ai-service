package com.property.ai.bff.dto;

import java.util.List;
import java.util.Map;

/**
 * OpenAPI: CopilotReport（P4 W15 / v1.2.0 增量）。
 * Copilot 报表（P4 v1.2.0）：`metrics` 为周期指标（诉求量/工单/投诉/自助解决率/采纳率），
 * `breakdown` 为分组明细。口径与看板一致（README §7.4），数字全部来自 API。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.2.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record CopilotReport(
        /** 周期指标（键值数字） */
        Map<String, Double> metrics,
        /** 分组明细（按场景） */
        List<CopilotReportBreakdown> breakdown,
        /** 生成时间（数据截止） */
        String generatedAt,
        /** 演示标记 */
        boolean mock) {
}
