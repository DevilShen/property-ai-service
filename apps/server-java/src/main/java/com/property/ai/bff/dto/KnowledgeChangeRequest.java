package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * OpenAPI: KnowledgeChangeRequest（P4 W15 / v1.2.0 增量）。
 * 知识变更提交入参（P4 v1.2.0）。`version` 不在此入参：服务端按当前版本自动递增并回带新版本号。
 * `effectiveFrom` 必须 ≤ `effectiveTo`（否则 400 `E-1001`）。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.2.0 严格对齐；仅 DTO，不含业务逻辑。
 * 可选字段以 class 级 {@code @JsonInclude(NON_NULL)} 保证未赋值时不序列化（契约默认不返回该字段）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KnowledgeChangeRequest(
        /** 新标题（不传沿用原标题） */
        String title,
        /** 新正文（必填，条款原文；服务端落库前脱敏） */
        String content,
        /** 新生效日 */
        String effectiveFrom,
        /** 新失效日 */
        String effectiveTo,
        /** 变更原因（必填，落审计与版本历史） */
        String reason) {
}
