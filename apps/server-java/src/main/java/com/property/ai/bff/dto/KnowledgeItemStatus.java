package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: KnowledgeItemStatus（P4 W15 / v1.2.0 增量）。
 * P4 知识状态（v1.2.0 契约枚举）。`published` 生效中 / `expired` 已过期 / `draft` 草稿 /
 * `pending_review` 待审核 / `rejected` 审核驳回。**只读接口只返回 `published` 且在生效期内的条目**；
 * 过期/草稿/待审/驳回一律不进入检索结果（检索不分叉、不编造）。
 * 与 shared P1 设计口径枚举 `KnowledgeStatus`（draft/reviewing/published/expired/withdrawn，用于
 * `KnowledgeDoc` 演示类型）**并列保留**，本枚举是 P4 契约唯一取值来源。
 * 契约枚举 code 与 Java 常量名分离，序列化一律走 value()，禁止手写字符串。
 */
public enum KnowledgeItemStatus {
    PUBLISHED("published"),
    EXPIRED("expired"),
    DRAFT("draft"),
    PENDING_REVIEW("pending_review"),
    REJECTED("rejected");
    private final String value;

    KnowledgeItemStatus(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static KnowledgeItemStatus fromValue(String value) {
        for (KnowledgeItemStatus item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 KnowledgeItemStatus: " + value);
    }
}
