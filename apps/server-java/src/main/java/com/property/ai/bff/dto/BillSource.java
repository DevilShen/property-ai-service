package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: BillSource（账单数据来源）。
 * 契约枚举 code（erp）与 Java 常量名分离，序列化一律走 value()，禁止手写字符串。
 */
public enum BillSource {
    ERP("erp"),
    MOCK("mock"),
    CACHE("cache");
    private final String value;

    BillSource(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static BillSource fromValue(String value) {
        for (BillSource item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 BillSource: " + value);
    }
}
