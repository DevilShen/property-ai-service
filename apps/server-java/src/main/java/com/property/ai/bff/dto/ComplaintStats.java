package com.property.ai.bff.dto;

import java.util.Map;

/**
 * OpenAPI: ComplaintStats（P3 W10 / v1.1.0 增量）。
 * 投诉统计（P3 W10 增量）。avgFirstResponseMinutes = 已首响投诉的平均首响分钟（无数据为 0）
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.1.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record ComplaintStats(
        long total,
        /** 各状态投诉数（键取 ComplaintStatus，缺失状态记 0） */
        Map<String, Integer> byStatus,
        /** 各级别投诉数（键取 ComplaintLevel，缺失级别记 0） */
        Map<String, Integer> byLevel,
        /** 平均首响分钟（无数据为 0） */
        double avgFirstResponseMinutes,
        /** 超 SLA 首响的投诉数 */
        int slaBreachCount,
        /** 统计周期（YYYY-MM 或 YYYY-MM-DD~YYYY-MM-DD） */
        String period) {
}
