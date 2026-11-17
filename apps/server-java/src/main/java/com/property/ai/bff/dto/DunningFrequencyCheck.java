package com.property.ai.bff.dto;

/**
 * OpenAPI: DunningFrequencyCheck（P3 W10 / v1.1.0 增量）。
 * 频次红线校验（P3 W10 增量，产品 FR-BILL-04）：单户每周外呼 ≤2 次
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.1.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record DunningFrequencyCheck(
        /** 统计窗口天数（单户每周口径 = 7） */
        int windowDays,
        /** 窗口内已触达次数 */
        int used,
        /** 窗口内允许上限（外呼 ≤2；其它渠道按项目策略） */
        int limit,
        /** 当前是否允许触达（false → 前端禁用发送 + 服务端 409 拦截） */
        boolean allowed) {
}
