package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: FeeCatalogChangeStatus（P4 W15 / v1.2.0 增量）。
 * 费项口径变更单状态（P4 v1.2.0）：`pending_review` 待审核 / `approved` 已通过（生效）/ `rejected` 已驳回。
 * 状态机单向 `pending_review → approved | rejected`；通过后新口径生效并落审计 `fee.catalog.review`。
 * 契约枚举 code 与 Java 常量名分离，序列化一律走 value()，禁止手写字符串。
 */
public enum FeeCatalogChangeStatus {
    PENDING_REVIEW("pending_review"),
    APPROVED("approved"),
    REJECTED("rejected");
    private final String value;

    FeeCatalogChangeStatus(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static FeeCatalogChangeStatus fromValue(String value) {
        for (FeeCatalogChangeStatus item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 FeeCatalogChangeStatus: " + value);
    }
}
