package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: RelationType（关系人类型）。
 * 契约枚举 code（owner）与 Java 常量名分离，序列化一律走 value()，禁止手写字符串。
 */
public enum RelationType {
    OWNER("owner"),
    FAMILY("family"),
    TENANT("tenant"),
    OTHER("other");
    private final String value;

    RelationType(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static RelationType fromValue(String value) {
        for (RelationType item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 RelationType: " + value);
    }
}
