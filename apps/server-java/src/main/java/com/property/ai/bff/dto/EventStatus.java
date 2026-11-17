package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: EventStatus（事件投递状态，P5 W19 / v1.3.0，架构 §24.6）。
 * 契约枚举 code 与 Java 常量名分离，序列化一律走 value()，禁止手写字符串。
 * pending 待投递 / published 已投递 / failed 投递失败（可重试）/ dead 死信队列（超重试上限，不静默丢失）。
 */
public enum EventStatus {
    PENDING("pending"),
    PUBLISHED("published"),
    FAILED("failed"),
    DEAD("dead");
    private final String value;

    EventStatus(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static EventStatus fromValue(String value) {
        for (EventStatus item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 EventStatus: " + value);
    }
}
