package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: RagRefusalReason（P6 W30 / 契约 v1.4.0 增量；`RagQueryResult.refusalReason`）。
 *
 * <p>拒答原因（仅 {@code answerable=false} 时返回）：
 * <ul>
 *   <li>{@code no_evidence}：召回为空；</li>
 *   <li>{@code low_confidence}：最高分低于阈值；</li>
 *   <li>{@code citation_invalid}：答案引用越界/缺失（{@code [n]} 不 ⊆ {@code sources}）；</li>
 *   <li>{@code out_of_scope}：越权或跨项目/超知识范围。</li>
 * </ul>
 *
 * <p>拒答一律<b>转人工（handoff）</b>并记缺口，不编造答案（错误码口径 {@code E-6002}，不新造错误码）。
 */
public enum RagRefusalReason {
    NO_EVIDENCE("no_evidence"),
    LOW_CONFIDENCE("low_confidence"),
    CITATION_INVALID("citation_invalid"),
    OUT_OF_SCOPE("out_of_scope");

    private final String value;

    RagRefusalReason(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static RagRefusalReason fromValue(String value) {
        for (RagRefusalReason item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 RagRefusalReason: " + value);
    }
}
