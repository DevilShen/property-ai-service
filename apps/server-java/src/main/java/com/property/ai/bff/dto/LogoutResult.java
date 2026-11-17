package com.property.ai.bff.dto;

/**
 * OpenAPI: LogoutResult（演示登出结果，v0.3.3）。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v0.3.3 严格对齐；仅 DTO，不含业务逻辑。
 * {@code ok} 恒 true：演示登出幂等（重复登出仍 200），token 加入本地撤销表并落 auth.logout 审计。
 */
public record LogoutResult(
        /** 固定 true（登出幂等） */
        boolean ok) {
}
