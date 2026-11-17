package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: TicketStatus（工单状态机 7 节点）。
 * 契约枚举 code（CREATED）与 Java 常量名分离，序列化一律走 value()，禁止手写字符串。
 */
public enum TicketStatus {
    CREATED("CREATED"),
    ACCEPTED("ACCEPTED"),
    DISPATCHED("DISPATCHED"),
    ON_SITE("ON_SITE"),
    COMPLETED("COMPLETED"),
    REVIEWED("REVIEWED"),
    ARCHIVED("ARCHIVED");
    private final String value;

    TicketStatus(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static TicketStatus fromValue(String value) {
        for (TicketStatus item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 TicketStatus: " + value);
    }
}
