package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: DunningStatus（P3 W10 / v1.1.0 增量）。
 * 催缴任务状态（P3 W10 增量）：pending 待触达 → script_ready 话术已生成待确认 →
 * 契约枚举 code 与 Java 常量名分离，序列化一律走 value()，禁止手写字符串。
 */
public enum DunningStatus {
    PENDING("pending"),
    SCRIPT_READY("script_ready"),
    SENT("sent"),
    RESPONDED("responded"),
    PROMISED("promised"),
    PAID("paid"),
    CLOSED("closed");
    private final String value;

    DunningStatus(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static DunningStatus fromValue(String value) {
        for (DunningStatus item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 DunningStatus: " + value);
    }
}
