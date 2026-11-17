package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: IssueScope（影响范围/是否漏到楼下）。
 * 契约枚举 code（none）与 Java 常量名分离，序列化一律走 value()，禁止手写字符串。
 */
public enum IssueScope {
    NONE("none"),
    DOWNSTAIRS("downstairs"),
    UNKNOWN("unknown");
    private final String value;

    IssueScope(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static IssueScope fromValue(String value) {
        for (IssueScope item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 IssueScope: " + value);
    }
}
