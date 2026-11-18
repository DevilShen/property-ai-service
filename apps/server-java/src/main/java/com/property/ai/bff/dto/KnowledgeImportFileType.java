package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAPI: KnowledgeImportJob.fileType 内联枚举（P6 W34 / 契约 v1.5.0 增量）。
 *
 * <p>类型白名单（P0：{@code txt}/{@code md}/{@code docx}/{@code pdf}；P1 批量：{@code zip}）；
 * 白名单外 → 400 {@code E-1001}（{@code details.reason=unsupported_file_type}），
 * 单文件 &gt;10MB → 413 {@code E-1001}（{@code payload_too_large}）。服务端按后缀 + 内容嗅探双重校验，
 * <b>不信任客户端 MIME</b>。本枚举为<b>契约内联枚举</b>，与 {@code packages/shared} 的
 * {@code KnowledgeImportFileType} 三方同源（check-contract §18.5）。
 */
public enum KnowledgeImportFileType {
    TXT("txt"),
    MD("md"),
    DOCX("docx"),
    PDF("pdf"),
    ZIP("zip");

    private final String value;

    KnowledgeImportFileType(String value) {
        this.value = value;
    }

    /** 契约 code（JSON 值） */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static KnowledgeImportFileType fromValue(String value) {
        for (KnowledgeImportFileType item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 KnowledgeImportFileType: " + value);
    }
}
