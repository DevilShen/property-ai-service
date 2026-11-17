package com.property.ai.bff.dto;

import java.util.List;

/**
 * OpenAPI: DeskQueue（P4 W15 / v1.2.0 增量）。
 * 坐席队列（P4 v1.2.0）：`items` 按等待时长降序；`summary` 为顶部指标卡数据。
 * 权限：agent / supervisor（本项目）；steward / owner / it / admin → 403 `E-2001`。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.2.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record DeskQueue(
        /** 队列项（按 waitingSeconds 降序） */
        List<DeskQueueItem> items,
        /** 汇总指标 */
        DeskQueueSummary summary) {
}
