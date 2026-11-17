package com.property.ai.bff.dto;

/**
 * OpenAPI: DunningSendRequest（P3 W10 / v1.1.0 增量）。
 * 发送催缴入参（P3 W10 增量）。confirmed 必须为 true（人工确认后才可触达；缺失/false → 400 E-1001
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.1.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record DunningSendRequest(
        /** 待发送话术版本（DunningScript.version） */
        String scriptVersion,
        DunningChannel channel,
        /** 人工确认标记（必须为 true；P3 无自动发送/群发） */
        boolean confirmed) {
}
