package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: KnowledgeCategory（P4 W15 / v1.2.0 增量）。
 * 知识分类（P4 契约枚举；取值与 shared `KnowledgeCategory` 一致，11 类）。字典由知识运营维护，
 * 检索/统计按此维度聚合；**不锁死前端文案**，前端读 `KNOWLEDGE_CATEGORY_LABELS`。
 * 契约枚举 code 与 Java 常量名分离，序列化一律走 value()，禁止手写字符串。
 */
public enum KnowledgeCategory {
    FEE("fee"),
    UTILITY("utility"),
    PARKING("parking"),
    RENOVATION("renovation"),
    PET("pet"),
    VISITOR("visitor"),
    NOISE("noise"),
    NOTICE("notice"),
    REPAIR("repair"),
    COMPLAINT("complaint"),
    OTHER("other");
    private final String value;

    KnowledgeCategory(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static KnowledgeCategory fromValue(String value) {
        for (KnowledgeCategory item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 KnowledgeCategory: " + value);
    }
}
