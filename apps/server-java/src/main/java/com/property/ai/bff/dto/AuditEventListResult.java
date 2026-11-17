package com.property.ai.bff.dto;

import java.util.List;

/**
 * OpenAPI: AuditEventListResult（审计事件分页结果，v0.3.3）。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v0.3.3 严格对齐；仅 DTO，不含业务逻辑。
 * 按 at 倒序（稳定分页），分页字段口径同全局约定。
 */
public record AuditEventListResult(
        List<AuditEvent> items,
        int page,
        int pageSize,
        int total) {
}
