package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: TicketCategory（报修分类）。
 * 契约枚举 code（plumbing）与 Java 常量名分离，序列化一律走 value()，禁止手写字符串。
 */
public enum TicketCategory {
    PLUMBING("plumbing"),
    ELECTRICAL("electrical"),
    ELEVATOR("elevator"),
    DOOR_WINDOW("door_window"),
    PUBLIC_FACILITY("public_facility"),
    OTHER("other");
    private final String value;

    TicketCategory(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static TicketCategory fromValue(String value) {
        for (TicketCategory item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 TicketCategory: " + value);
    }
}
