package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: KnowledgeImportJob.status 内联枚举（P6 W34 / 契约 v1.5.0 增量）。
 *
 * <p>导入任务状态机（设计依据 {@code docs/design/知识导入方案.md} §4.2 / §5.1）：
 * <ul>
 *   <li>{@code pending}：已受理；</li>
 *   <li>{@code parsing}：解析中（编排层 {@code /internal/rag/preview}）；</li>
 *   <li>{@code preview_ready}：预览就绪——<b>唯一可 commit 状态</b>；</li>
 *   <li>{@code committing}：提交中（生成知识草稿/待审）；</li>
 *   <li>{@code indexing}：索引中（编排层 {@code /internal/rag/index}）；</li>
 *   <li>{@code completed}：完成；</li>
 *   <li>{@code failed}：失败（见 {@link KnowledgeImportError}）；</li>
 *   <li>{@code cancelled}：已取消（终态）。</li>
 * </ul>
 *
 * <p>契约枚举 code 与 Java 常量名分离，序列化一律走 {@link #value()}；终态不可再次 cancel（409
 * {@code E-3002}）。本枚举为<b>契约内联枚举</b>（OpenAPI 在 {@code KnowledgeImportJob.status} 处内联），
 * 与 {@code packages/shared} 的 {@code KnowledgeImportJobStatus} 三方同源（check-contract §18.5 校验）。
 */
public enum KnowledgeImportJobStatus {
    PENDING("pending"),
    PARSING("parsing"),
    PREVIEW_READY("preview_ready"),
    COMMITTING("committing"),
    INDEXING("indexing"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled");

    private final String value;

    KnowledgeImportJobStatus(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static KnowledgeImportJobStatus fromValue(String value) {
        for (KnowledgeImportJobStatus item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 KnowledgeImportJobStatus: " + value);
    }
}
