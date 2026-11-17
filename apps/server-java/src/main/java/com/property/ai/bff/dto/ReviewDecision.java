package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: ReviewDecision（P4 W15 / v1.2.0 增量）。
 * 审核决定（P4 v1.2.0，知识与费用口径共用）：`approved` 通过 / `rejected` 驳回。
 * **只允许 it/admin 审核**（权限矩阵 README §7.4）；审核写操作必须落审计。
 * 契约枚举 code 与 Java 常量名分离，序列化一律走 value()，禁止手写字符串。
 */
public enum ReviewDecision {
    APPROVED("approved"),
    REJECTED("rejected");
    private final String value;

    ReviewDecision(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static ReviewDecision fromValue(String value) {
        for (ReviewDecision item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 ReviewDecision: " + value);
    }
}
