package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: ArrearsBucket（P3 W10 / v1.1.0 增量）。
 * 欠费逾期分层（P3 W10 增量）：D1 = 逾期 1—7 天、D7 = 8—15 天、D15 = 16—30 天、
 * 契约枚举 code 与 Java 常量名分离，序列化一律走 value()，禁止手写字符串。
 */
public enum ArrearsBucket {
    D1("D1"),
    D7("D7"),
    D15("D15"),
    D30("D30");
    private final String value;

    ArrearsBucket(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static ArrearsBucket fromValue(String value) {
        for (ArrearsBucket item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 ArrearsBucket: " + value);
    }
}
