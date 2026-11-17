package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: ConnectorStatus（连接器生命周期状态（草稿/灰度/已发布/已退役），v0.3.2）。
 * 契约枚举 code 与 Java 常量名分离，序列化一律走 value()，禁止手写字符串。
 */
public enum ConnectorStatus {
    DRAFT("draft"),
    GRAY("gray"),
    PUBLISHED("published"),
    RETIRED("retired");
    private final String value;

    ConnectorStatus(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static ConnectorStatus fromValue(String value) {
        for (ConnectorStatus item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 ConnectorStatus: " + value);
    }
}
