package com.property.ai.bff.dto;

/**
 * OpenAPI: Project（项目，P5 W19 / v1.3.0 多项目隔离资源，架构 §24.7）。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.3.0 严格对齐；仅 DTO，不含业务逻辑。
 * 演示 3 个项目（shared DEMO_PROJECTS：P-YQY 云栖苑 / P-JZY 金竹苑 / P-LVY 绿苑），
 * roomCount 为房档数量（ERP 口径，BFF 不自算），connectorId 为项目当前主连接器。
 */
public record Project(
        String projectId,
        String name,
        ProjectStatus status,
        int roomCount,
        String connectorId,
        String createdAt) {
}
