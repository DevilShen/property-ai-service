package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: DegradedFallback（降级替代路径）。
 * 契约枚举 code（alt_connector）与 Java 常量名分离，序列化一律走 value()，禁止手写字符串。
 */
public enum DegradedFallback {
    ALT_CONNECTOR("alt_connector"),
    CACHE("cache"),
    MOCK("mock"),
    HUMAN("human"),
    PARTIAL("partial"),
    NONE("none");
    private final String value;

    DegradedFallback(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static DegradedFallback fromValue(String value) {
        for (DegradedFallback item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 DegradedFallback: " + value);
    }
}
