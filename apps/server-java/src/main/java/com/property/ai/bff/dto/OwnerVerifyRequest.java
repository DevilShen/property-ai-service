package com.property.ai.bff.dto;

/**
 * OpenAPI: OwnerVerifyRequest（业主核验请求）。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v0.3.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record OwnerVerifyRequest(
        /** 归一房号（可空） */
        String roomCode,
        /** 手机号后四位 */
        String phoneTail) {
}
