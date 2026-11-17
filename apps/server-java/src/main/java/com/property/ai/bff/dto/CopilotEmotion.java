package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: CopilotEmotion（P4 W15 / v1.2.0 增量）。
 * 业主情绪（P4 v1.2.0 话术入参）：`calm` 平静 / `anxious` 焦虑 / `angry` 激动 / `neutral` 未知。
 * 契约枚举 code 与 Java 常量名分离，序列化一律走 value()，禁止手写字符串。
 */
public enum CopilotEmotion {
    CALM("calm"),
    ANXIOUS("anxious"),
    ANGRY("angry"),
    NEUTRAL("neutral");
    private final String value;

    CopilotEmotion(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static CopilotEmotion fromValue(String value) {
        for (CopilotEmotion item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 CopilotEmotion: " + value);
    }
}
