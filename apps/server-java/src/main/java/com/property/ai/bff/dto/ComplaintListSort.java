package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: ComplaintListSort（P3 W10 / v1.1.0 增量）。
 * 投诉列表排序（P3 W10 增量；默认 createdAt_desc）
 * 契约枚举 code 与 Java 常量名分离，序列化一律走 value()，禁止手写字符串。
 */
public enum ComplaintListSort {
    CREATEDAT_DESC("createdAt_desc"),
    SLADUEAT_ASC("slaDueAt_asc");
    private final String value;

    ComplaintListSort(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static ComplaintListSort fromValue(String value) {
        for (ComplaintListSort item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 ComplaintListSort: " + value);
    }
}
