package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: OwnerTicketCapability（P3 W10 / v1.1.0 增量）。
 * owner 自有单专用能力码（P3 W10 增量；**不属于 18 项标准 CapabilityCode**，是数据范围内的
 * 契约枚举 code 与 Java 常量名分离，序列化一律走 value()，禁止手写字符串。
 */
public enum OwnerTicketCapability {
    TICKET_RESCHEDULE_OWN("ticket.reschedule.own"),
    TICKET_URGE_OWN("ticket.urge.own");
    private final String value;

    OwnerTicketCapability(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static OwnerTicketCapability fromValue(String value) {
        for (OwnerTicketCapability item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 OwnerTicketCapability: " + value);
    }
}
