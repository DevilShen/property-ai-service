package com.property.ai.bff.dto;

/**
 * OpenAPI: KnowledgeRef（P4 W15 / v1.2.0 增量）。
 * 回答引用项（P4 v1.2.0）。四要素 = `knowledgeId` + `version` + `owner` + `title`；
 * **无引用则 AI 必须拒答**（不得编造出处），前端逐条渲染「依据」。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.2.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record KnowledgeRef(
        /** 知识 ID */
        String knowledgeId,
        /** 条目标题 */
        String title,
        /** 版本号 */
        String version,
        /** 责任人（岗位/掩码名） */
        String owner) {
}
