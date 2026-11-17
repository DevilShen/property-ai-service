package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: AuditResult（审计结果，v0.3.3）。
 * 契约枚举 code 与 Java 常量名分离，序列化一律走 value()，禁止手写字符串。
 * success 成功 / denied 越权拒绝 / failed 执行失败。
 */
public enum AuditResult {
    SUCCESS("success"),
    DENIED("denied"),
    FAILED("failed");

    private final String value;

    AuditResult(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static AuditResult fromValue(String value) {
        for (AuditResult item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 AuditResult: " + value);
    }
}
