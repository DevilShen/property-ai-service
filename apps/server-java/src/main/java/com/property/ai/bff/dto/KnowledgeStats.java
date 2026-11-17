package com.property.ai.bff.dto;

import java.util.List;
import java.util.Map;

/**
 * OpenAPI: KnowledgeStats（P4 W15 / v1.2.0 增量）。
 * 知识采纳与缺口统计（P4 v1.2.0）。口径：`adoptionRate = adopted / (adopted + rejected)`
 * （无引用时为 0，不编造）；`gaps` 为未命中 TopN；`expiredSoon` = 30 天内到期条目数；
 * `owners` 为责任人分布（过期/待审追到人）。统计读权限 = staff / it / admin（owner 403）。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.2.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record KnowledgeStats(
        /** 知识总数 */
        int total,
        /** 各状态条目数（键取 KnowledgeItemStatus，缺失状态记 0） */
        Map<String, Integer> byStatus,
        /** 采纳率 = adopted/(adopted+rejected) */
        double adoptionRate,
        /** 被采纳（引用且未被驳回）次数 */
        int adopted,
        /** 被驳回（答案不可用/badcase）次数 */
        int rejected,
        /** 未命中缺口 TopN */
        List<KnowledgeGap> gaps,
        /** 30 天内到期条目数 */
        int expiredSoon,
        /** 责任人分布 */
        List<KnowledgeOwnerStat> owners) {
}
