package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * OpenAPI: DeskQaScoreRequest（P4 W15 / v1.2.0 增量）。
 * 质检评分入参（P4 v1.2.0）：`score` 0—100 整数（超范围/非整数 → 400 `E-1001`）；`note` 可选。
 * 评分是写操作，落审计 `desk.qa.score`（资源类型 `conversation`）。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.2.0 严格对齐；仅 DTO，不含业务逻辑。
 * 可选字段以 class 级 {@code @JsonInclude(NON_NULL)} 保证未赋值时不序列化（契约默认不返回该字段）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeskQaScoreRequest(
        /** 质检得分（0—100 整数） */
        int score,
        /** 评分说明/扣分项（可选） */
        String note) {
}
