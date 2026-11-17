package com.property.ai.bff.dto;

/**
 * OpenAPI: DispatchPayload（派单动作参数，v0.3.1 有名字段）。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v0.3.1 严格对齐；仅 DTO，不含业务逻辑。
 *
 * <p>兼容：W5 客户端仍可只传 {@code {team, eta}}，服务端按 {@code team} ≈ {@code teamName} 映射。
 * 本 record 只承载契约里的有名字段；旧字段 {@code team} 由 Service 层（P2 W6）做兼容映射，不在此新增。</p>
 */
public record DispatchPayload(
        /** 班组 ID（ERP 侧主数据，优先于 teamName） */
        String teamId,
        /** 班组名（展示口径，如「水电二班」） */
        String teamName,
        /** 预计上门时间（ISO 8601）或人话文案（如「预计今天 17:00 前」，W5 兼容） */
        String eta,
        /** 技能标签（派单匹配用，如「水暖」「强电」） */
        String skillTag,
        /** 接单人（工程师姓名/工号掩码；空表示班组待接单） */
        String assignee) {
}
