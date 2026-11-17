package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: ErpWritebackStatus（ERP 回写状态，v0.3.1）。
 * 契约枚举 code（confirmed）与 Java 常量名分离，序列化一律走 value()，禁止手写字符串。
 *
 * <ul>
 *   <li>{@code pending}：30 秒确认窗口内（未确认，前端展示「回写确认中」）；</li>
 *   <li>{@code confirmed}：ERP 侧已确认闭环；</li>
 *   <li>{@code failed}：回写失败，已进人工待办（不自动重写，架构 §24.6）。</li>
 * </ul>
 */
public enum ErpWritebackStatus {
    CONFIRMED("confirmed"),
    PENDING("pending"),
    FAILED("failed");
    private final String value;

    ErpWritebackStatus(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static ErpWritebackStatus fromValue(String value) {
        for (ErpWritebackStatus item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 ErpWritebackStatus: " + value);
    }
}
