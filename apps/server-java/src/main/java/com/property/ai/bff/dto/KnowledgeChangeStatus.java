package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: KnowledgeChangeStatus（P4 W15 / v1.2.0 增量）。
 * 知识变更单状态（P4 v1.2.0）：`pending_review` 待审核 / `approved` 已通过（生效）/ `rejected` 已驳回。
 * 状态机单向：`pending_review → approved | rejected`；同一知识同时只允许一个待审变更单（409 `E-3002`）。
 * 契约枚举 code 与 Java 常量名分离，序列化一律走 value()，禁止手写字符串。
 */
public enum KnowledgeChangeStatus {
    PENDING_REVIEW("pending_review"),
    APPROVED("approved"),
    REJECTED("rejected");
    private final String value;

    KnowledgeChangeStatus(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static KnowledgeChangeStatus fromValue(String value) {
        for (KnowledgeChangeStatus item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 KnowledgeChangeStatus: " + value);
    }
}
