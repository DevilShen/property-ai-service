package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: ReporterType（报修人类型）。
 * 契约枚举 code（owner）与 Java 常量名分离，序列化一律走 value()，禁止手写字符串。
 */
public enum ReporterType {
    OWNER("owner"),
    FAMILY("family"),
    TENANT("tenant"),
    STEWARD("steward");
    private final String value;

    ReporterType(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static ReporterType fromValue(String value) {
        for (ReporterType item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 ReporterType: " + value);
    }
}
