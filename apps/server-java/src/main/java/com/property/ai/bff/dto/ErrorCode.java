package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: ErrorCode（17 类统一错误码（架构 §3.4））。
 * 契约枚举 code（E-1001）与 Java 常量名分离，序列化一律走 value()，禁止手写字符串。
 */
public enum ErrorCode {
    E_1001("E-1001"),
    E_1002("E-1002"),
    E_1003("E-1003"),
    E_2001("E-2001"),
    E_2002("E-2002"),
    E_2003("E-2003"),
    E_3001("E-3001"),
    E_3002("E-3002"),
    E_3003("E-3003"),
    E_4001("E-4001"),
    E_4002("E-4002"),
    E_4003("E-4003"),
    E_5001("E-5001"),
    E_5002("E-5002"),
    E_6001("E-6001"),
    E_6002("E-6002"),
    E_9001("E-9001");
    private final String value;

    ErrorCode(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static ErrorCode fromValue(String value) {
        for (ErrorCode item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 ErrorCode: " + value);
    }
}
