package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: ActorRole（演示角色（与 P1 Role 同值，角色 × 数据范围见架构 §14.2），v0.3.3）。
 * 契约枚举 code 与 Java 常量名分离，序列化一律走 value()，禁止手写字符串。
 * demo 身份：不是真实账号体系。
 */
public enum ActorRole {
    OWNER("owner"),
    STEWARD("steward"),
    AGENT("agent"),
    SUPERVISOR("supervisor"),
    IT("it"),
    ADMIN("admin");

    private final String value;

    ActorRole(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static ActorRole fromValue(String value) {
        for (ActorRole item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 ActorRole: " + value);
    }
}
