package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: MappingDiffKind（映射试跑差异类型，v0.3.2）。
 * 契约枚举 code 与 Java 常量名分离，序列化一律走 value()，禁止手写字符串。
 */
public enum MappingDiffKind {
    MISSING("missing"),
    TYPE_MISMATCH("type_mismatch"),
    REQUIRED_MISSING("required_missing");
    private final String value;

    MappingDiffKind(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static MappingDiffKind fromValue(String value) {
        for (MappingDiffKind item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 MappingDiffKind: " + value);
    }
}
