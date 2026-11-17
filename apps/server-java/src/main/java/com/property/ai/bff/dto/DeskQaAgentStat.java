package com.property.ai.bff.dto;

/**
 * OpenAPI: DeskQaAgentStat（P4 W15 / v1.2.0 增量）。
 * 坐席质检分布项（P4 v1.2.0）：用于主管看「谁需要辅导」。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.2.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record DeskQaAgentStat(
        /** 坐席（掩码） */
        String agentId,
        /** 抽检数 */
        int samples,
        /** 平均得分（0—100） */
        double avgScore) {
}
