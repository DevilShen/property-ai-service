package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: AccountStatus（账户状态）。
 * 契约枚举 code（normal）与 Java 常量名分离，序列化一律走 value()，禁止手写字符串。
 */
public enum AccountStatus {
    NORMAL("normal"),
    ARREARS("arrears"),
    PREPAID("prepaid"),
    UNVERIFIED("unverified");
    private final String value;

    AccountStatus(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static AccountStatus fromValue(String value) {
        for (AccountStatus item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 AccountStatus: " + value);
    }
}
