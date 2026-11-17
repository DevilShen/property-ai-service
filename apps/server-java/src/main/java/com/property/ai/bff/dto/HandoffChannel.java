package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: HandoffChannel（转人工历史渠道）。
 * 契约枚举 code（chat）与 Java 常量名分离，序列化一律走 value()，禁止手写字符串。
 */
public enum HandoffChannel {
    CHAT("chat"),
    PHONE("phone"),
    WECHAT("wechat"),
    SYSTEM("system");
    private final String value;

    HandoffChannel(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static HandoffChannel fromValue(String value) {
        for (HandoffChannel item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 HandoffChannel: " + value);
    }
}
