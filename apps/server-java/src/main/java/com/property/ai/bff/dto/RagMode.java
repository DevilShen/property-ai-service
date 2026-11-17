package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: RagMode（P6 W30 / 契约 v1.4.0 增量；`RagQueryResult.mode`）。
 *
 * <p>链路模式：
 * <ul>
 *   <li>{@code generative}：标准生成（LLM 生成 + 引用校验）；</li>
 *   <li>{@code retrieval_only}：仅检索模式（无 LLM Key / 生成超时降级：原文片段 + {@code [n]} 编号）；</li>
 *   <li>{@code rules}：规则检索兜底（沿用 {@code GET /knowledge/search}）。</li>
 * </ul>
 *
 * <p>契约枚举 code 与 Java 常量名分离，序列化一律走 {@link #value()}，禁止手写字符串；
 * <b>不得把 retrieval_only / rules 说成生成答案</b>（AGENTS.md NEVER #11）。
 */
public enum RagMode {
    GENERATIVE("generative"),
    RETRIEVAL_ONLY("retrieval_only"),
    RULES("rules");

    private final String value;

    RagMode(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static RagMode fromValue(String value) {
        for (RagMode item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 RagMode: " + value);
    }
}
