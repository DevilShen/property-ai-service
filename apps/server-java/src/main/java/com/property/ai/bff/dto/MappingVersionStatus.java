package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: MappingVersionStatus（映射版本状态机（draft → trial_passed → published → rolled_back），v0.3.2）。
 * 契约枚举 code 与 Java 常量名分离，序列化一律走 value()，禁止手写字符串。
 */
public enum MappingVersionStatus {
    DRAFT("draft"),
    TRIAL_PASSED("trial_passed"),
    PUBLISHED("published"),
    ROLLED_BACK("rolled_back");
    private final String value;

    MappingVersionStatus(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static MappingVersionStatus fromValue(String value) {
        for (MappingVersionStatus item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 MappingVersionStatus: " + value);
    }
}
