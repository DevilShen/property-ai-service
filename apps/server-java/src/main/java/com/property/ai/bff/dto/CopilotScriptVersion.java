package com.property.ai.bff.dto;

import java.util.List;

/**
 * OpenAPI: CopilotScriptVersion（P4 W15 / v1.2.0 增量）。
 * 单版话术（P4 v1.2.0）。`forbiddenHits` 为禁区词命中（命中非空时前端必须提示改写，
 * 不得直接复制外发：禁承诺/禁威胁/禁泄露他人信息）。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.2.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record CopilotScriptVersion(
        /** 语气档位 */
        CopilotTone tone,
        /** 话术正文（可直接复制） */
        String content,
        /** 禁区词命中（空数组 = 通过扫描） */
        List<String> forbiddenHits) {
}
