package com.property.ai.bff.dto;

/**
 * OpenAPI: DunningFunnel（P3 W10 / v1.1.0 增量）。
 * 催缴转化漏斗（P3 W10 增量，产品 FR-BILL-06：触达 → 响应 → 承诺 → 缴费）
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.1.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record DunningFunnel(
        /** 触达户数 */
        int reached,
        /** 有响应户数 */
        int responded,
        /** 登记承诺户数 */
        int promised,
        /** 窗口内缴费户数（以 ERP 实收为准） */
        int paid,
        DunningFunnelConversion conversion,
        DunningFunnelPeriod period,
        String generatedAt) {
}
