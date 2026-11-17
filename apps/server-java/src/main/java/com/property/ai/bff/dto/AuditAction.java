package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: AuditAction（审计动作（写操作必须落审计），v0.3.3 / v1.1.0 追加）。
 * 契约枚举 code 与 Java 常量名分离，序列化一律走 value()，禁止手写字符串。
 * v1.1.0 增量（P3 W10，末尾追加）：complaint.create / complaint.transition / dunning.script /
 * dunning.send / dunning.close / recon.export（既有取值与顺序不变）。
 * v1.2.0 增量（P4 W15，末尾追加）：knowledge.change / knowledge.review / copilot.broadcast /
 * desk.takeover / desk.qa.score / fee.catalog.change / fee.catalog.review / trace.export。
 * v1.3.0 增量（P5 W19，末尾追加）：gray.policy.create / gray.policy.update（灰度策略变更）。
 * v1.4.0 增量（P6 W30，末尾追加）：knowledge.reindex（知识重建索引，仅 it/admin，资源类型 knowledge；
 * RAG 问答/健康为只读端点，不落写审计）。
 */
public enum AuditAction {
    TICKET_CREATE("ticket.create"),
    TICKET_TRANSITION("ticket.transition"),
    TICKET_FEEDBACK("ticket.feedback"),
    MAPPING_PUBLISH("mapping.publish"),
    MAPPING_ROLLBACK("mapping.rollback"),
    CONNECTOR_PROBE("connector.probe"),
    RECON_RUN("recon.run"),
    OWNER_VERIFY("owner.verify"),
    AUTH_LOGIN("auth.login"),
    AUTH_LOGOUT("auth.logout"),
    ACCESS_DENY("access.deny"),
    COMPLAINT_CREATE("complaint.create"),
    COMPLAINT_TRANSITION("complaint.transition"),
    DUNNING_SCRIPT("dunning.script"),
    DUNNING_SEND("dunning.send"),
    DUNNING_CLOSE("dunning.close"),
    RECON_EXPORT("recon.export"),
    KNOWLEDGE_CHANGE("knowledge.change"),
    KNOWLEDGE_REVIEW("knowledge.review"),
    COPILOT_BROADCAST("copilot.broadcast"),
    DESK_TAKEOVER("desk.takeover"),
    DESK_QA_SCORE("desk.qa.score"),
    FEE_CATALOG_CHANGE("fee.catalog.change"),
    FEE_CATALOG_REVIEW("fee.catalog.review"),
    TRACE_EXPORT("trace.export"),
    GRAY_POLICY_CREATE("gray.policy.create"),
    GRAY_POLICY_UPDATE("gray.policy.update"),
    KNOWLEDGE_REINDEX("knowledge.reindex");

    private final String value;

    AuditAction(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static AuditAction fromValue(String value) {
        for (AuditAction item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 AuditAction: " + value);
    }
}
