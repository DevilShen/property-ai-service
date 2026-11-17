package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: TicketPushChannel（推送通道，v0.3.1）。
 * 契约枚举 code（wecom）与 Java 常量名分离，序列化一律走 value()，禁止手写字符串。
 * P2 W6 三通道全部为 mock 测试替身（services/mock-channel），不接真实企微/短信。
 */
public enum TicketPushChannel {
    WECOM("wecom"),
    SMS("sms"),
    INAPP("inapp");
    private final String value;

    TicketPushChannel(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static TicketPushChannel fromValue(String value) {
        for (TicketPushChannel item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 TicketPushChannel: " + value);
    }
}
