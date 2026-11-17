package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: RoomListSort（房档列表排序（默认 roomCode_asc），v0.3.2）。
 * 契约枚举 code 与 Java 常量名分离，序列化一律走 value()，禁止手写字符串。
 */
public enum RoomListSort {
    ROOM_CODE_ASC("roomCode_asc"),
    UPDATED_AT_DESC("updatedAt_desc");
    private final String value;

    RoomListSort(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static RoomListSort fromValue(String value) {
        for (RoomListSort item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 RoomListSort: " + value);
    }
}
