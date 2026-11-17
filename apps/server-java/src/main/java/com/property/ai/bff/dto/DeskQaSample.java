package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * OpenAPI: DeskQaSample（P4 W15 / v1.2.0 增量）。
 * 质检抽样项（P4 v1.2.0）。规则抽样（SLA 超时/投诉升级/低分回访优先），`score` 为 0—100，
 * `pending` 时不返回 `score`；评分后 `status=scored`。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.2.0 严格对齐；仅 DTO，不含业务逻辑。
 * 可选字段以 class 级 {@code @JsonInclude(NON_NULL)} 保证未赋值时不序列化（契约默认不返回该字段）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeskQaSample(
        /** 抽样 ID */
        String sampleId,
        /** 会话 ID */
        String conversationId,
        /** 被抽检坐席（掩码） */
        String agentId,
        /** 质检得分 0—100（pending 不返回） */
        Double score,
        /** 状态 */
        DeskQaSampleStatus status) {
}
