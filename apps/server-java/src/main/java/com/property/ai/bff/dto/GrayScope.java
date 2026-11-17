package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: GrayScope（灰度作用域（连接器 / 单能力），P5 W19 / v1.3.0）。
 * 契约枚举 code 与 Java 常量名分离，序列化一律走 value()，禁止手写字符串。
 */
public enum GrayScope {
    CONNECTOR("connector"),
    CAPABILITY("capability");
    private final String value;

    GrayScope(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static GrayScope fromValue(String value) {
        for (GrayScope item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 GrayScope: " + value);
    }
}
