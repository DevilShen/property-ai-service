package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: CopilotWorkspaceStatus（P4 W15 / v1.2.0 增量）。
 * 工作区可用状态（P4 v1.2.0）：`available` 已接真实数据 / `degraded` 部分降级（标注 mock 或缺数据）/
 * `planned` 未上线（入口置灰，不得展示编造数据）。前端入口显隐读本状态，**不得页面硬编码**。
 * 契约枚举 code 与 Java 常量名分离，序列化一律走 value()，禁止手写字符串。
 */
public enum CopilotWorkspaceStatus {
    AVAILABLE("available"),
    DEGRADED("degraded"),
    PLANNED("planned");
    private final String value;

    CopilotWorkspaceStatus(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static CopilotWorkspaceStatus fromValue(String value) {
        for (CopilotWorkspaceStatus item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 CopilotWorkspaceStatus: " + value);
    }
}
