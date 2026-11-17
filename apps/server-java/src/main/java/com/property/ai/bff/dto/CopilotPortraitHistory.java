package com.property.ai.bff.dto;

/**
 * OpenAPI: CopilotPortraitHistory（P4 W15 / v1.2.0 增量）。
 * 画像服务历史计数（P4 v1.2.0）：只回计数，不回明细/金额，避免泄露欠费与投诉原文。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.2.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record CopilotPortraitHistory(
        /** 工单数（近 12 个月） */
        int tickets,
        /** 账单/缴费交互次数 */
        int bills,
        /** 投诉次数 */
        int complaints) {
}
