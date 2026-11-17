package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: ProjectStatus（项目生命周期状态（正常/灰度/已归档），P5 W19 / v1.3.0）。
 * 契约枚举 code 与 Java 常量名分离，序列化一律走 value()，禁止手写字符串。
 * 与 ConnectorStatus（draft/gray/published/retired）分列，不复用。
 */
public enum ProjectStatus {
    ACTIVE("active"),
    GRAY("gray"),
    ARCHIVED("archived");
    private final String value;

    ProjectStatus(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static ProjectStatus fromValue(String value) {
        for (ProjectStatus item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 ProjectStatus: " + value);
    }
}
