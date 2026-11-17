package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: DeskConversationStatus（P4 W15 / v1.2.0 增量）。
 * 坐席会话状态（P4 v1.2.0）：`waiting` 排队等待 / `taken_over` 已接管 / `closed` 已结束。
 * 状态机：`waiting → taken_over → closed`；同一会话同时只允许一个坐席接管（409 `E-3002`）。
 * 契约枚举 code 与 Java 常量名分离，序列化一律走 value()，禁止手写字符串。
 */
public enum DeskConversationStatus {
    WAITING("waiting"),
    TAKEN_OVER("taken_over"),
    CLOSED("closed");
    private final String value;

    DeskConversationStatus(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static DeskConversationStatus fromValue(String value) {
        for (DeskConversationStatus item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 DeskConversationStatus: " + value);
    }
}
