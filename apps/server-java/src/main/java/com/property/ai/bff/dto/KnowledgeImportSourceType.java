package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: KnowledgeImportJob.sourceType 内联枚举（P6 W34 / 契约 v1.5.0 增量）。
 *
 * <p>多入口矩阵（设计依据 {@code docs/design/知识导入方案.md} §2）：
 * <ul>
 *   <li>{@code paste}：粘贴文本（E1，P0）；</li>
 *   <li>{@code file}：单文件上传（E2，P0：md/txt/docx/pdf）；</li>
 *   <li>{@code batch}：批量 ZIP（E3，P1；另需 {@code knowledge.import.batch}）；</li>
 *   <li>{@code badcase}：RAG 拒答缺口沉淀（E4，P1）。</li>
 * </ul>
 *
 * <p>本枚举为<b>契约内联枚举</b>（OpenAPI 在 {@code KnowledgeImportJob.sourceType} 处内联），
 * 与 {@code packages/shared} 的 {@code KnowledgeImportSourceType} 三方同源（check-contract §18.5）。
 */
public enum KnowledgeImportSourceType {
    PASTE("paste"),
    FILE("file"),
    BATCH("batch"),
    BADCASE("badcase");

    private final String value;

    KnowledgeImportSourceType(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static KnowledgeImportSourceType fromValue(String value) {
        for (KnowledgeImportSourceType item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 KnowledgeImportSourceType: " + value);
    }
}
