package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: EventType（事件类型（outbox / 事件总线口径），P5 W19 / v1.3.0，架构 §24.6）。
 * 契约枚举 code 与 Java 常量名分离，序列化一律走 value()，禁止手写字符串。
 * 顺序固定（与 shared EVENT_TYPES / OpenAPI enum 一致）；新增事件类型只允许**末尾追加**（向后兼容）。
 */
public enum EventType {
    TICKET_CREATED("ticket.created"),
    TICKET_TRANSITIONED("ticket.transitioned"),
    TICKET_FEEDBACK("ticket.feedback"),
    COMPLAINT_CREATED("complaint.created"),
    DUNNING_SENT("dunning.sent"),
    KNOWLEDGE_CHANGED("knowledge.changed"),
    RECON_COMPLETED("recon.completed"),
    GRAY_POLICY_UPDATED("gray.policy.updated");
    private final String value;

    EventType(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static EventType fromValue(String value) {
        for (EventType item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 EventType: " + value);
    }
}
