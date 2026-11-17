package com.property.ai.bff.dto;

/**
 * OpenAPI: CopilotWorkspaceInfo（P4 W15 / v1.2.0 增量）。
 * Copilot 工作区元数据项（P4 v1.2.0）。`description` 为一句话职责，供空态/说明文案复用。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.2.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record CopilotWorkspaceInfo(
        /** 工作区 ID */
        CopilotWorkspaceId workspaceId,
        /** 工作区名称（中文） */
        String name,
        /** 可用状态 */
        CopilotWorkspaceStatus status,
        /** 一句话职责 */
        String description) {
}
