package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: ComplaintLevel（P3 W10 / v1.1.0 增量）。
 * 投诉级别（P3 W10 增量，产品 §8.3）：L1 一级（人身安全/≥5 户群体/媒体/诉讼/政府转办
 * 契约枚举 code 与 Java 常量名分离，序列化一律走 value()，禁止手写字符串。
 */
public enum ComplaintLevel {
    L1("L1"),
    L2("L2"),
    L3("L3");
    private final String value;

    ComplaintLevel(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static ComplaintLevel fromValue(String value) {
        for (ComplaintLevel item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 ComplaintLevel: " + value);
    }
}
