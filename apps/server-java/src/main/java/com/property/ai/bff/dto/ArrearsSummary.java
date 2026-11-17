package com.property.ai.bff.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * OpenAPI: ArrearsSummary（P3 W10 / v1.1.0 增量）。
 * 欠费台账分层统计（buckets 顺序固定 D1 → D7 → D15 → D30）
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.1.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record ArrearsSummary(
        List<ArrearsBucketStat> buckets,
        /** 当前筛选条件下的欠费户数合计 */
        int totalRooms,
        /** 当前筛选条件下的欠费金额合计（元） */
        BigDecimal totalAmount) {
}
