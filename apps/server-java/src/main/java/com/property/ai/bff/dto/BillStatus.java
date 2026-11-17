package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: BillStatus（账单状态）。
 * 契约枚举 code（unpaid）与 Java 常量名分离，序列化一律走 value()，禁止手写字符串。
 */
public enum BillStatus {
    UNPAID("unpaid"),
    PARTIAL("partial"),
    PAID("paid"),
    OVERDUE("overdue"),
    VOID("void");
    private final String value;

    BillStatus(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static BillStatus fromValue(String value) {
        for (BillStatus item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 BillStatus: " + value);
    }
}
