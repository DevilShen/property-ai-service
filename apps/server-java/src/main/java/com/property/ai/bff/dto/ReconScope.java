package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: ReconScope（对账范围）。
 * 契约枚举 code（room）与 Java 常量名分离，序列化一律走 value()，禁止手写字符串。
 */
public enum ReconScope {
    ROOM("room"),
    BILL("bill"),
    TICKET("ticket");
    private final String value;

    ReconScope(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static ReconScope fromValue(String value) {
        for (ReconScope item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 ReconScope: " + value);
    }
}
