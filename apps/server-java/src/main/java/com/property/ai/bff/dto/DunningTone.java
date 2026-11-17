package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: DunningTone（P3 W10 / v1.1.0 增量）。
 * 催缴话术语气（P3 W10 增量）：friendly 温和提醒（首次/短期逾期）、firm 明确期限（长期逾期）
 * 契约枚举 code 与 Java 常量名分离，序列化一律走 value()，禁止手写字符串。
 */
public enum DunningTone {
    FRIENDLY("friendly"),
    FIRM("firm");
    private final String value;

    DunningTone(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static DunningTone fromValue(String value) {
        for (DunningTone item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 DunningTone: " + value);
    }
}
