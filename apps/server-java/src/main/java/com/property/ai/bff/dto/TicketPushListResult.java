package com.property.ai.bff.dto;

import java.util.List;

/**
 * OpenAPI: TicketPushListResult（推送记录列表，v0.3.1）。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v0.3.1 严格对齐；仅 DTO，不含业务逻辑。
 *
 * <p>按 {@code sentAt} 倒序；无记录返回空数组（不造数）。</p>
 */
public record TicketPushListResult(
        List<TicketPush> items) {
}
