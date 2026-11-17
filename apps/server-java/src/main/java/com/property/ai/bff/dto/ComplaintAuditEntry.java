package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * OpenAPI: ComplaintAuditEntry（P3 W10 / v1.1.0 增量）。
 * 投诉动作留痕（P3 W10 增量；按时间升序）
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.1.0 严格对齐；仅 DTO，不含业务逻辑。
 * 可选字段以 class 级 {@code @JsonInclude(NON_NULL)} 保证未赋值时不序列化（契约默认不返回该字段）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ComplaintAuditEntry(
        String at,
        /** 动作/状态 code（submitted / accept / first_response / escalate / close / reopen / note） */
        String action,
        /** 操作人（岗位名/掩码；不返回真实手机号/工号） */
        String who,
        /** 备注（如升级原因、结案说明） */
        String note) {
}
