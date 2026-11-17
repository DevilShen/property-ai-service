package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * OpenAPI: KnowledgeSource（P4 W15 / v1.2.0 增量）。
 * 知识出处（P1 设计口径 `KnowledgeSource` 的契约化，v1.2.0 首次进 OpenAPI）。
 * 引用展示口径「依据 K-018 V1.2 · 装修管理规定 §3.1」：`doc` 必填，`clause`/`page` 可选。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.2.0 严格对齐；仅 DTO，不含业务逻辑。
 * 可选字段以 class 级 {@code @JsonInclude(NON_NULL)} 保证未赋值时不序列化（契约默认不返回该字段）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KnowledgeSource(
        /** 制度/文件名称（如「装修管理规定」） */
        String doc,
        /** 条款位置，如「§3.1」 */
        String clause,
        /** 页码/条目号（可选） */
        String page) {
}
