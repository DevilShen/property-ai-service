package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: BillListSort（账单列表排序（默认 updatedAt_desc），v0.3.2）。
 * 契约枚举 code 与 Java 常量名分离，序列化一律走 value()，禁止手写字符串。
 */
public enum BillListSort {
    TOTAL_DESC("total_desc"),
    DUE_ASC("due_asc"),
    UPDATED_AT_DESC("updatedAt_desc");
    private final String value;

    BillListSort(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static BillListSort fromValue(String value) {
        for (BillListSort item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 BillListSort: " + value);
    }
}
