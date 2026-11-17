package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * OpenAPI: TraceEntry（P4 W15 / v1.2.0 增量）。
 * 回溯时间线节点（P4 v1.2.0）。一条 trace 由多个节点组成（AI 路由/工具调用/写操作/拒绝/推送），
 * `role` 复用 `ActorRole`，`result` 复用 `AuditResult`（success/denied/failed）；
 * `durationMs` 为节点耗时（可选，非关键节点不返回）。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.2.0 严格对齐；仅 DTO，不含业务逻辑。
 * 可选字段以 class 级 {@code @JsonInclude(NON_NULL)} 保证未赋值时不序列化（契约默认不返回该字段）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TraceEntry(
        /** 事件时间 */
        String at,
        /** 操作主体（账号/工号，掩码） */
        String actorId,
        /** 主体角色 */
        ActorRole role,
        /** 动作（与审计 action 同口径，如 ticket.create / knowledge.search） */
        String action,
        /** 资源类型（ticket/bill/knowledge/conversation/export…） */
        String resourceType,
        /** 资源 ID */
        String resourceId,
        /** 结果 */
        AuditResult result,
        /** 节点耗时（毫秒，可选） */
        Integer durationMs) {
}
