package com.property.ai.bff.dto;

import java.util.List;

/**
 * OpenAPI: EventListResult（事件分页结果，P5 W19 / v1.3.0）。
 * 字段名 / 必填与 docs/openapi/openapi.yaml v1.3.0 严格对齐；仅 DTO，不含业务逻辑。
 * 分页约定 items/page/pageSize/total 与其他列表一致；按 occurredAt 倒序。
 */
public record EventListResult(
        List<EventEnvelope> items,
        int page,
        int pageSize,
        int total) {
}
