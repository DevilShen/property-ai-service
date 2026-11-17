package com.property.ai.bff.dto;

import java.util.List;

/**
 * OpenAPI: CopilotPortrait（P4 W15 / v1.2.0 增量）。
 * 业主画像（P4 v1.2.0，**脱敏**）：`ownerNameMasked` 只回掩码名；`roomCode` 为归一房号；
 * `tags`/`risks` 为运营标签与沟通禁忌（如「夜间沟通敏感」）；不回手机号/身份证/欠费金额明文。
 * 数据范围：staff 限本项目/本片区（owner 403 `E-2001`），越权拒绝落 `access.deny` 审计。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.2.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record CopilotPortrait(
        /** 归一房号 */
        String roomCode,
        /** 业主姓名（掩码） */
        String ownerNameMasked,
        /** 运营标签 */
        List<String> tags,
        /** 服务历史计数 */
        CopilotPortraitHistory history,
        /** 风险与沟通禁忌 */
        List<String> risks,
        /** 演示标记（画像为派生数据） */
        boolean mock) {
}
