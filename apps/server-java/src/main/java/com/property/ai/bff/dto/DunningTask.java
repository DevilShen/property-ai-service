package com.property.ai.bff.dto;

import java.math.BigDecimal;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * OpenAPI: DunningTask（P3 W10 / v1.1.0 增量）。
 * 催缴任务（P3 W10 增量）。由欠费分层策略生成（P3-012）；frequencyCheck 是发送前必须读的红线校验结果
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.1.0 严格对齐；仅 DTO，不含业务逻辑。
 * 可选字段以 class 级 {@code @JsonInclude(NON_NULL)} 保证未赋值时不序列化（契约默认不返回该字段）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DunningTask(
        String taskId,
        String roomCode,
        String ownerNameMasked,
        /** 账期 YYYY-MM */
        String period,
        /** 欠费金额（ERP 口径） */
        BigDecimal outstanding,
        ArrearsBucket bucket,
        DunningStatus status,
        DunningChannel channel,
        /** 最近一次生成的话术版本（send 必须回传；不一致 → 409 E-3002） */
        String scriptVersion,
        /** 负责人（岗位名/掩码，如「李管家」） */
        String assignedTo,
        String createdAt,
        /** 下一次应触达时间（分层节奏） */
        String dueAt,
        /** 最近一次动作时间（生成话术/发送/关闭） */
        String lastActionAt,
        DunningFrequencyCheck frequencyCheck) {
}
