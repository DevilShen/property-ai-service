package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: CapabilityStatus（能力状态三态（supported 支持 / degraded 降级 / unsupported 不支持），v0.3.2）。
 * 契约枚举 code 与 Java 常量名分离，序列化一律走 value()，禁止手写字符串。
 */
public enum CapabilityStatus {
    SUPPORTED("supported"),
    DEGRADED("degraded"),
    UNSUPPORTED("unsupported");
    private final String value;

    CapabilityStatus(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static CapabilityStatus fromValue(String value) {
        for (CapabilityStatus item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 CapabilityStatus: " + value);
    }
}
