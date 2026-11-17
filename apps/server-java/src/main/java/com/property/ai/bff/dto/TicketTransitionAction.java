package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: TicketTransitionAction（状态迁移动作）。
 * 契约枚举 code（dispatch）与 Java 常量名分离，序列化一律走 value()，禁止手写字符串。
 */
public enum TicketTransitionAction {
    DISPATCH("dispatch"),
    ON_SITE("on_site"),
    COMPLETE("complete"),
    RESCHEDULE("reschedule"),
    URGE("urge");
    private final String value;

    TicketTransitionAction(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static TicketTransitionAction fromValue(String value) {
        for (TicketTransitionAction item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 TicketTransitionAction: " + value);
    }
}
