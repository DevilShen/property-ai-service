package com.property.ai.bff.dto;

/**
 * OpenAPI: LoginRequest（演示登录入参，v0.3.3）。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v0.3.3 严格对齐；仅 DTO，不含业务逻辑。
 * 账号取 shared {@code DEMO_ACCOUNTS}（6 个演示账号），口令统一 demo123；不是真实账号体系。
 */
public record LoginRequest(
        /** 演示账号（owner-3-2-1802 / steward-01 / agent-01 / supervisor-01 / it-01 / admin-01） */
        String account,
        /** 演示口令（可选；demo 固定 demo123） */
        String password) {
}
