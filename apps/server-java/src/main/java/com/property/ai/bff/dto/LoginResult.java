package com.property.ai.bff.dto;

/**
 * OpenAPI: LoginResult（登录结果：演示 JWT + 主体，v0.3.3）。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v0.3.3 严格对齐；仅 DTO，不含业务逻辑。
 * token 有效期 AUTH_TOKEN_TTL_SECONDS = 7200 秒；mock 恒 true（demo，不是真实鉴权令牌）。
 */
public record LoginResult(
        /** 演示 JWT（Authorization: Bearer &lt;token&gt;） */
        String token,
        /** 固定 Bearer */
        String tokenType,
        /** 过期时间（签发时间 + 7200 秒，ISO 8601） */
        String expiresAt,
        Actor actor,
        /** 演示标记（恒 true） */
        boolean mock) {
}
