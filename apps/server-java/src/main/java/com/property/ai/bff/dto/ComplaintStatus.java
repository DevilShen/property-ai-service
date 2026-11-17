package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: ComplaintStatus（P3 W10 / v1.1.0 增量）。
 * 投诉状态机（P3 W10 增量）：submitted 已提交 → accepted 已受理 → processing 处理中 →
 * 契约枚举 code 与 Java 常量名分离，序列化一律走 value()，禁止手写字符串。
 */
public enum ComplaintStatus {
    SUBMITTED("submitted"),
    ACCEPTED("accepted"),
    PROCESSING("processing"),
    ESCALATED("escalated"),
    CLOSED("closed"),
    REOPENED("reopened");
    private final String value;

    ComplaintStatus(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static ComplaintStatus fromValue(String value) {
        for (ComplaintStatus item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 ComplaintStatus: " + value);
    }
}
