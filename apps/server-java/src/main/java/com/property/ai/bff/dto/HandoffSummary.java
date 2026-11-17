package com.property.ai.bff.dto;

import java.util.List;
import java.util.Map;

/**
 * OpenAPI: HandoffSummary（转人工同屏摘要（CAP-18 handoff.human））。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v0.3.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record HandoffSummary(
        String ticketNo,
        String roomCode,
        String ownerNameMasked,
        String title,
        TicketStatus status,
        String statusText,
        /** 摘要（≤5 行） */
        List<String> summary,
        /** 已采集要素键值 */
        Map<String, String> elements,
        String emotion,
        List<HandoffHistoryItem> history,
        List<String> suggestedActions,
        List<String> pendingActions,
        String generatedAt,
        boolean mock) {
}
