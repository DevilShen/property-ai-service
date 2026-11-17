package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: CopilotTone（P4 W15 / v1.2.0 增量）。
 * 话术语气（P4 v1.2.0 三版话术）：`gentle` 温和 / `standard` 标准 / `firm` 坚定。
 * 与催缴 `DunningTone`（friendly/firm，催缴专用）**分列**，互不复用。
 * 契约枚举 code 与 Java 常量名分离，序列化一律走 value()，禁止手写字符串。
 */
public enum CopilotTone {
    GENTLE("gentle"),
    STANDARD("standard"),
    FIRM("firm");
    private final String value;

    CopilotTone(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static CopilotTone fromValue(String value) {
        for (CopilotTone item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 CopilotTone: " + value);
    }
}
