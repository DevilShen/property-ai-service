package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: TicketListSort（工单列表排序，v0.3.1）。
 * 契约枚举 code（updatedAt_desc）与 Java 常量名分离，序列化一律走 value()，禁止手写字符串。
 * 默认 {@code updatedAt_desc}（与 shared {@code DEFAULT_TICKET_LIST_SORT} 一致）。
 */
public enum TicketListSort {
    UPDATED_AT_DESC("updatedAt_desc"),
    CREATED_AT_ASC("createdAt_asc"),
    CREATED_AT_DESC("createdAt_desc");
    private final String value;

    TicketListSort(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static TicketListSort fromValue(String value) {
        for (TicketListSort item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 TicketListSort: " + value);
    }
}
