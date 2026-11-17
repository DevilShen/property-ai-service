package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: BindState（绑定状态）。
 * 契约枚举 code（unbound）与 Java 常量名分离，序列化一律走 value()，禁止手写字符串。
 */
public enum BindState {
    UNBOUND("unbound"),
    BOUND("bound"),
    PENDING("pending");
    private final String value;

    BindState(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static BindState fromValue(String value) {
        for (BindState item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 BindState: " + value);
    }
}
