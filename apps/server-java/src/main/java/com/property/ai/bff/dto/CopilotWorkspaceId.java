package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: CopilotWorkspaceId（P4 W15 / v1.2.0 增量）。
 * P4 Copilot 8 工作区 ID（v1.2.0 契约枚举，**顺序固定**）：`summary` 工单摘要 / `script` 话术生成 /
 * `portrait` 业主画像 / `digest` 日报摘要 / `group` 群服务 / `knowledge` 知识秒查 / `report` 报表 /
 * `handoff` 转人工交接。与 P1 设计口径 `CopilotWorkspace`（knowledge/script/summary/profile/report/
 * contribute/history/group，前端路由用）的映射见 README §7.4 表；**契约以本枚举为准**。
 * 契约枚举 code 与 Java 常量名分离，序列化一律走 value()，禁止手写字符串。
 */
public enum CopilotWorkspaceId {
    SUMMARY("summary"),
    SCRIPT("script"),
    PORTRAIT("portrait"),
    DIGEST("digest"),
    GROUP("group"),
    KNOWLEDGE("knowledge"),
    REPORT("report"),
    HANDOFF("handoff");
    private final String value;

    CopilotWorkspaceId(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static CopilotWorkspaceId fromValue(String value) {
        for (CopilotWorkspaceId item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 CopilotWorkspaceId: " + value);
    }
}
