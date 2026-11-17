package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * OpenAPI: ComplaintTransitionRequest（P3 W10 / v1.1.0 增量，P4 W15 / v1.2.0 末尾追加可选字段）。
 * 投诉流转入参；version 乐观锁必填（不匹配 → 409 E-3002）。
 * v1.2.0 追加（P4-002，末尾追加可选、既有必填集不变）：
 * target = escalate 升级目标岗位（省略沿用默认「客服主管」）；
 * ticketNo = 显式关联工单号（省略按「同房未结案工单」自动关联）。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.2.0 严格对齐；仅 DTO，不含业务逻辑。
 * 可选字段以 class 级 {@code @JsonInclude(NON_NULL)} 保证未赋值时不序列化（契约默认不返回该字段）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ComplaintTransitionRequest(
        ComplaintTransitionAction action,
        /** 备注（落 audit[]，如升级原因/结案说明） */
        String note,
        /** 当前投诉版本号（乐观锁） */
        int version,
        /** escalate 升级目标岗位（v1.2.0 可选；非 escalate 忽略） */
        String target,
        /** 显式关联工单号（v1.2.0 可选；省略按同房未结案工单关联） */
        String ticketNo) {

    /** v1.1.0 旧构造重载（P3 调用方/测试不破；target/ticketNo 为 null）。 */
    public ComplaintTransitionRequest(ComplaintTransitionAction action, String note, int version) {
        this(action, note, version, null, null);
    }
}
