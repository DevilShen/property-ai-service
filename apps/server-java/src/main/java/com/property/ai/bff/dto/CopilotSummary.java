package com.property.ai.bff.dto;

import java.util.List;

/**
 * OpenAPI: CopilotSummary（P4 W15 / v1.2.0 增量）。
 * 工单摘要结果（P4 v1.2.0）：`summary` 为 5 行摘要（≤5 条）、`todos` 为 3 条待办（≤3 条）；
 * `cites` 为知识引用（可空，空则前端提示「无出处，需人工确认」）。模型调用走 Python 编排层，
 * Java 只编排与落库；`mock: true` 表示演示编排未接真实模型。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.2.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record CopilotSummary(
        /** 摘要（≤5 行） */
        List<String> summary,
        /** 待办（≤3 条） */
        List<String> todos,
        /** 知识引用（可空；空则提示无出处） */
        List<KnowledgeRef> cites,
        /** 演示编排标记（未接真实模型时为 true） */
        boolean mock) {
}
