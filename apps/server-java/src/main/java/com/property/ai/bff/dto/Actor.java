package com.property.ai.bff.dto;

/**
 * OpenAPI: Actor（演示主体，v0.3.3）。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v0.3.3 严格对齐；仅 DTO，不含业务逻辑。
 * {@code projectId} 缺省 = 平台级（IT/管理员跨项目）；{@code mock} 恒 true（demo 身份，不是真实鉴权）。
 */
public record Actor(
        /** 演示账号 ID（如 steward-01） */
        String actorId,
        ActorRole role,
        /** 演示显示名（非真实姓名） */
        String name,
        /** 所属项目（可选；缺省 = 平台级） */
        String projectId,
        /** 演示标记（恒 true） */
        boolean mock) {
}
