package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: AuditResourceType（审计资源类型，v0.3.3 / v1.1.0 追加）。
 * 契约枚举 code 与 Java 常量名分离，序列化一律走 value()，禁止手写字符串。
 * export = 导出/下载类动作（脱敏后留痕）。
 * v1.1.0 增量（P3 W10，末尾追加）：complaint（投诉） / dunning（催缴任务）；既有取值与顺序不变。
 * v1.2.0 增量（P4 W15，末尾追加）：knowledge（知识/变更审核） / fee_catalog（费项口径变更） /
 * conversation（坐席会话/质检） / trace（全链路回溯）。
 * v1.3.0 增量（P5 W19，末尾追加）：gray_policy（灰度策略） / project（项目隔离拒绝/项目级资源）。
 */
public enum AuditResourceType {
    TICKET("ticket"),
    MAPPING("mapping"),
    CONNECTOR("connector"),
    RECON("recon"),
    OWNER("owner"),
    AUTH("auth"),
    EXPORT("export"),
    COMPLAINT("complaint"),
    DUNNING("dunning"),
    KNOWLEDGE("knowledge"),
    FEE_CATALOG("fee_catalog"),
    CONVERSATION("conversation"),
    TRACE("trace"),
    GRAY_POLICY("gray_policy"),
    PROJECT("project");

    private final String value;

    AuditResourceType(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static AuditResourceType fromValue(String value) {
        for (AuditResourceType item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 AuditResourceType: " + value);
    }
}
