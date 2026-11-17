package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: DunningChannel（P3 W10 / v1.1.0 增量）。
 * 催缴触达渠道（P3 W10 增量）：wecom 企微 1v1 / sms 短信 / inapp 站内信
 * 契约枚举 code 与 Java 常量名分离，序列化一律走 value()，禁止手写字符串。
 */
public enum DunningChannel {
    WECOM("wecom"),
    SMS("sms"),
    INAPP("inapp");
    private final String value;

    DunningChannel(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static DunningChannel fromValue(String value) {
        for (DunningChannel item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 DunningChannel: " + value);
    }
}
