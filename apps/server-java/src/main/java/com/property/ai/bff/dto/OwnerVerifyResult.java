package com.property.ai.bff.dto;

/**
 * OpenAPI: OwnerVerifyResult（业主核验结果）。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v0.3.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record OwnerVerifyResult(
        boolean verified,
        String roomCode,
        String ownerNameMasked,
        String projectName,
        Integer attemptsLeft,
        Boolean locked,
        boolean mock) {
}
