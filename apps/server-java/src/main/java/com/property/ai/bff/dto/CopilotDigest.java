package com.property.ai.bff.dto;

import java.util.List;
import java.util.Map;

/**
 * OpenAPI: CopilotDigest（P4 W15 / v1.2.0 增量）。
 * 日报/摘要（P4 v1.2.0）：`summary` 为条目化摘要，`metrics` 为当日指标（键值数字，口径见 README §7.4）。
 * 数字必须来自 API/数据库聚合，**不得前端编造**；无数据日期回空数组与 0 值。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.2.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record CopilotDigest(
        /** 日报日期 `YYYY-MM-DD` */
        String date,
        /** 日报摘要（条目化） */
        List<String> summary,
        /** 当日指标（诉求量/工单/投诉/欠费变动/首响均值） */
        Map<String, Double> metrics,
        /** 演示标记 */
        boolean mock) {
}
