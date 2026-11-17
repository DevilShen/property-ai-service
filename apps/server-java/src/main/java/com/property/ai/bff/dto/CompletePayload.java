package com.property.ai.bff.dto;

/**
 * OpenAPI: CompletePayload（完工结案参数，v0.3.1 / CAP-09 ticket.close）。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v0.3.1 严格对齐；仅 DTO，不含业务逻辑。
 *
 * <p>{@code resolution} 必填（结案说明，业主可见）；{@code erpCloseRef} 为 ERP 侧结案单号/引用
 * （真实单号来自 ERP，不得编造）。</p>
 */
public record CompletePayload(
        /** 结案说明（业主可见处理结果；必填） */
        String resolution,
        /** 内部备注（不进业主文案） */
        String note,
        /** ERP 侧结案单号/引用（可选） */
        String erpCloseRef) {
}
