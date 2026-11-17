package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: ReconRunStatus（对账运行状态）。
 * 契约枚举 code（queued）与 Java 常量名分离，序列化一律走 value()，禁止手写字符串。
 */
public enum ReconRunStatus {
    QUEUED("queued"),
    RUNNING("running"),
    COMPLETED("completed"),
    FAILED("failed");
    private final String value;

    ReconRunStatus(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static ReconRunStatus fromValue(String value) {
        for (ReconRunStatus item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 ReconRunStatus: " + value);
    }
}
