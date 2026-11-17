package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: CapabilityCode（18 项标准能力编码（架构 §3.2 CAP-01…CAP-18，顺序固定），v0.3.2）。
 * 契约枚举 code 与 Java 常量名分离，序列化一律走 value()，禁止手写字符串。
 */
public enum CapabilityCode {
    ROOM_QUERY("room.query"),
    OWNER_VERIFY("owner.verify"),
    BILL_QUERY("bill.query"),
    PAY_JUMP("pay.jump"),
    INVOICE_APPLY("invoice.apply"),
    TICKET_CREATE("ticket.create"),
    TICKET_ASSIGN("ticket.assign"),
    TICKET_STATUS("ticket.status"),
    TICKET_CLOSE("ticket.close"),
    COMPLAINT_CREATE("complaint.create"),
    NOTICE_PUSH("notice.push"),
    NOTICE_RECEIPT("notice.receipt"),
    VISIT_RECORD("visit.record"),
    DUNNING_TASK("dunning.task"),
    KB_SEARCH("kb.search"),
    VISITOR_AUTH("visitor.auth"),
    PARKING_AUTH("parking.auth"),
    HANDOFF_HUMAN("handoff.human");
    private final String value;

    CapabilityCode(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static CapabilityCode fromValue(String value) {
        for (CapabilityCode item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 CapabilityCode: " + value);
    }
}
