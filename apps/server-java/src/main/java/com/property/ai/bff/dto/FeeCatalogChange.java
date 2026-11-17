package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * OpenAPI: FeeCatalogChange（P4 W15 / v1.2.0 增量）。
 * 费项口径变更单（P4 v1.2.0，P3-032 服务层 `FeeCatalogChangeService` 的 REST 契约化）。
 * **不改已有账单**：只对后续计费生效；审计专用动作 `fee.catalog.change`/`fee.catalog.review`
 * （资源类型 `fee_catalog`），不再复用 `mapping.publish`。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.2.0 严格对齐；仅 DTO，不含业务逻辑。
 * 可选字段以 class 级 {@code @JsonInclude(NON_NULL)} 保证未赋值时不序列化（契约默认不返回该字段）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FeeCatalogChange(
        /** 变更单 ID */
        String changeId,
        /** 费项编码 */
        String feeItemCode,
        /** 新计费公式（口径原文，服务端做语法校验） */
        String newFormula,
        /** 变更原因（必填，落审计） */
        String reason,
        /** 变更单状态 */
        FeeCatalogChangeStatus status,
        /** 提交人（账号/岗位，掩码） */
        String submittedBy,
        /** 审核人（仅审核后返回） */
        String reviewedBy) {
}
