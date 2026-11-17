package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: TicketPushStatus（推送状态，v0.3.1）。
 * 契约枚举 code（sent）与 Java 常量名分离，序列化一律走 value()，禁止手写字符串。
 * {@code mocked} = 已由 mock 通道受理并留痕，未真实外发。
 */
public enum TicketPushStatus {
    SENT("sent"),
    PENDING("pending"),
    FAILED("failed"),
    MOCKED("mocked");
    private final String value;

    TicketPushStatus(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static TicketPushStatus fromValue(String value) {
        for (TicketPushStatus item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 TicketPushStatus: " + value);
    }
}
