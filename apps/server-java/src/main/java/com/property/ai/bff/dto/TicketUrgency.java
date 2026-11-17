package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: TicketUrgency（紧急程度）。
 * 契约枚举 code（normal）与 Java 常量名分离，序列化一律走 value()，禁止手写字符串。
 */
public enum TicketUrgency {
    NORMAL("normal"),
    URGENT("urgent"),
    EMERGENCY("emergency");
    private final String value;

    TicketUrgency(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static TicketUrgency fromValue(String value) {
        for (TicketUrgency item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 TicketUrgency: " + value);
    }
}
