package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: PushTemplate（P4 W15 / v1.2.0 增量）。
 * 推送模板（P4 v1.2.0 首次固化为契约枚举）：`accepted` 接单 / `dispatched` 派单 /
 * `completed` 完工 / `dunning` 催缴（mock-channel 自定义模板）/ `on_site` 上门（**v1.2.0 末尾追加**）。
 * 既有 4 个模板取值 **原样保留**，实际生产值集合不变；`on_site` 用于上门前提醒，仍为 mock 通道留痕。
 * 契约枚举 code 与 Java 常量名分离，序列化一律走 value()，禁止手写字符串。
 */
public enum PushTemplate {
    ACCEPTED("accepted"),
    DISPATCHED("dispatched"),
    COMPLETED("completed"),
    DUNNING("dunning"),
    ON_SITE("on_site");
    private final String value;

    PushTemplate(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static PushTemplate fromValue(String value) {
        for (PushTemplate item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 PushTemplate: " + value);
    }
}
