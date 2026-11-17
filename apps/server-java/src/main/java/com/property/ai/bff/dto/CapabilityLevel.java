package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: CapabilityLevel（能力级别（L1 只读 / L2 可写 / L3 不提供，必须拦截转人工），v0.3.2）。
 * 契约枚举 code 与 Java 常量名分离，序列化一律走 value()，禁止手写字符串。
 */
public enum CapabilityLevel {
    L1("L1"),
    L2("L2"),
    L3("L3");
    private final String value;

    CapabilityLevel(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static CapabilityLevel fromValue(String value) {
        for (CapabilityLevel item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 CapabilityLevel: " + value);
    }
}
