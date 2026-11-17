package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: DeskQaSampleStatus（P4 W15 / v1.2.0 增量）。
 * 质检抽样状态（P4 v1.2.0）：`pending` 待评分 / `scored` 已评分。
 * 契约枚举 code 与 Java 常量名分离，序列化一律走 value()，禁止手写字符串。
 */
public enum DeskQaSampleStatus {
    PENDING("pending"),
    SCORED("scored");
    private final String value;

    DeskQaSampleStatus(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static DeskQaSampleStatus fromValue(String value) {
        for (DeskQaSampleStatus item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 DeskQaSampleStatus: " + value);
    }
}
