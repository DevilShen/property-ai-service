package com.property.ai.bff.dto;

import java.util.List;

/**
 * OpenAPI: CopilotWorkspaceListResult（P4 W15 / v1.2.0 增量）。
 * Copilot 工作区列表（P4 v1.2.0，固定 8 项、顺序与 `CopilotWorkspaceId` 一致，不分页）。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.2.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record CopilotWorkspaceListResult(
        /** 8 个工作区（顺序固定） */
        List<CopilotWorkspaceInfo> items,
        /** 工作区数（恒为 8） */
        int total) {
}
