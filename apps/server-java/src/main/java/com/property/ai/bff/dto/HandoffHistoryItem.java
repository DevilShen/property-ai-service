package com.property.ai.bff.dto;

/**
 * OpenAPI: HandoffHistoryItem（脱敏历史片段）。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v0.3.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record HandoffHistoryItem(
        String at,
        HandoffChannel channel,
        String text) {
}
