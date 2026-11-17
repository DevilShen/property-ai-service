package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: ConnectorHealth（连接器健康度四态（健康/降级/异常/未知），v0.3.2）。
 * 契约枚举 code 与 Java 常量名分离，序列化一律走 value()，禁止手写字符串。
 */
public enum ConnectorHealth {
    HEALTHY("healthy"),
    DEGRADED("degraded"),
    ERROR("error"),
    UNKNOWN("unknown");
    private final String value;

    ConnectorHealth(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static ConnectorHealth fromValue(String value) {
        for (ConnectorHealth item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 ConnectorHealth: " + value);
    }
}
