package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * OpenAPI: TraceDetail（P4 W15 / v1.2.0 增量）。
 * 全链路回溯详情（P4 v1.2.0）：`entries[]` 按时间升序；`firstAt`/`lastAt` 为链路首尾时间，
 * 前端可直接渲染时间线/瀑布。`mock` 表示含演示数据（P4 仍以进程内审计为源）。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.2.0 严格对齐；仅 DTO，不含业务逻辑。
 * 可选字段以 class 级 {@code @JsonInclude(NON_NULL)} 保证未赋值时不序列化（契约默认不返回该字段）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TraceDetail(
        /** 链路 ID（贯穿 AI/工具/写操作/推送） */
        String traceId,
        /** 时间线节点（按 at 升序） */
        List<TraceEntry> entries,
        /** 链路首个节点时间 */
        String firstAt,
        /** 链路最后一个节点时间 */
        String lastAt,
        /** 演示标记（含演示数据时为 true） */
        Boolean mock) {
}
