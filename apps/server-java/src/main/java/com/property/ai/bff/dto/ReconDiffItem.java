package com.property.ai.bff.dto;

/**
 * OpenAPI: ReconDiffItem（单条对账差异（不含真实业主信息））。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v0.3.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record ReconDiffItem(
        String key,
        ReconDiffKind kind,
        String field,
        String expected,
        String actual,
        String roomCode,
        String billNo,
        String ticketNo) {
}
