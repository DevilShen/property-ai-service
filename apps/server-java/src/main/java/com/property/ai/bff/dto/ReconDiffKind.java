package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: ReconDiffKind（对账差异类型）。
 * 契约枚举 code（missing）与 Java 常量名分离，序列化一律走 value()，禁止手写字符串。
 */
public enum ReconDiffKind {
    MISSING("missing"),
    FIELD_DRIFT("field_drift"),
    AMOUNT_MISMATCH("amount_mismatch"),
    STATUS_MISMATCH("status_mismatch");
    private final String value;

    ReconDiffKind(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static ReconDiffKind fromValue(String value) {
        for (ReconDiffKind item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 ReconDiffKind: " + value);
    }
}
