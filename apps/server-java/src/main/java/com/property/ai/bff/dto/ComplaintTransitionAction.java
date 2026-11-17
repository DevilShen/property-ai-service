package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: ComplaintTransitionAction（P3 W10 / v1.1.0 增量）。
 * 投诉流转动作（P3 W10 增量），对应 POST /api/v1/complaints/{complaintId}/transition：
 * 契约枚举 code 与 Java 常量名分离，序列化一律走 value()，禁止手写字符串。
 */
public enum ComplaintTransitionAction {
    ACCEPT("accept"),
    FIRST_RESPONSE("first_response"),
    ESCALATE("escalate"),
    CLOSE("close"),
    REOPEN("reopen");
    private final String value;

    ComplaintTransitionAction(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static ComplaintTransitionAction fromValue(String value) {
        for (ComplaintTransitionAction item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 ComplaintTransitionAction: " + value);
    }
}
