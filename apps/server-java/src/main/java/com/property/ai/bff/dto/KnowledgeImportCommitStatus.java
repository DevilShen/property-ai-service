package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: KnowledgeImportCommitRequest.status 内联枚举（P6 W34 / 契约 v1.5.0 增量）。
 *
 * <ul>
 *   <li>{@code draft}：只存草稿，不进入审核队列；</li>
 *   <li>{@code pending_review}：待审（<b>缺省</b>，走既有 {@code POST /knowledge/{id}/review}）；</li>
 *   <li>{@code approved}：直审（<b>仅 {@code it}/{@code admin}</b>；其它角色 403 {@code E-2001} +
 *       {@code details.requiredCapability=knowledge.review}，服务端强制，前端隐藏不是安全边界）。</li>
 * </ul>
 *
 * <p>本枚举为<b>契约内联枚举</b>（OpenAPI 在 {@code KnowledgeImportCommitRequest.status} 处内联），
 * 与 {@code packages/shared} 的 {@code KnowledgeImportCommitStatus} 三方同源（check-contract §18.5）。
 */
public enum KnowledgeImportCommitStatus {
    DRAFT("draft"),
    PENDING_REVIEW("pending_review"),
    APPROVED("approved");

    private final String value;

    KnowledgeImportCommitStatus(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static KnowledgeImportCommitStatus fromValue(String value) {
        for (KnowledgeImportCommitStatus item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 KnowledgeImportCommitStatus: " + value);
    }
}
