package com.property.ai.bff.dto;

import java.util.List;

/**
 * OpenAPI: CopilotScript（P4 W15 / v1.2.0 增量）。
 * 话术生成结果（P4 v1.2.0）：固定 3 版（gentle/standard/firm）。**只生成不外发**；
 * 真实触达必须人工确认（AGENTS.md NEVER #4/#7）。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.2.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record CopilotScript(
        /** 3 版话术（按 CopilotTone 顺序） */
        List<CopilotScriptVersion> versions,
        /** 演示编排标记 */
        boolean mock) {
}
