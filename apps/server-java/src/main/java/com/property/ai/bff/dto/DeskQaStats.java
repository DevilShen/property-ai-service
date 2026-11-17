package com.property.ai.bff.dto;

import java.util.List;

/**
 * OpenAPI: DeskQaStats（P4 W15 / v1.2.0 增量）。
 * 质检统计（P4 v1.2.0）：`samples` 抽样数、`scored` 已评分数、`avgScore` 平均分（0—100，
 * 无评分记 0）、`byAgent[]` 坐席分布。权限：supervisor / it / admin（agent 只能提交自己的评分）。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.2.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record DeskQaStats(
        /** 抽样数 */
        int samples,
        /** 已评分数 */
        int scored,
        /** 平均分（0—100） */
        double avgScore,
        /** 坐席分布 */
        List<DeskQaAgentStat> byAgent) {
}
