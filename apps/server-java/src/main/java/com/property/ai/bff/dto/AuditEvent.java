package com.property.ai.bff.dto;

import java.util.List;

/**
 * OpenAPI: AuditEvent（审计事件，v0.3.3；P2-031 / 架构 §11.3：谁在何时对什么资源做了什么 + traceId）。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v0.3.3 严格对齐；仅 DTO，不含业务逻辑。
 * 只记脱敏信息：maskedFields 列已脱敏字段名，不含手机号/姓名/金额原文。
 */
public record AuditEvent(
        String eventId,
        /** 事件时间（ISO 8601） */
        String at,
        String actorId,
        ActorRole actorRole,
        AuditAction action,
        AuditResourceType resourceType,
        /** 资源 ID（工单号/映射版本号/连接器 ID/runId/房号等） */
        String resourceId,
        /** 链路 traceId（与响应信封 traceId 同源） */
        String traceId,
        AuditResult result,
        /** 客户端 IP（可选；演示环境可为 127.0.0.1） */
        String ip,
        /** 已脱敏字段名清单（可选；如 ownerName / phoneTail） */
        List<String> maskedFields) {
}
