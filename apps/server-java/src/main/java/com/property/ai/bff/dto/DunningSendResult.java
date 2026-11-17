package com.property.ai.bff.dto;

/**
 * OpenAPI: DunningSendResult（P3 W10 / v1.1.0 增量）。
 * 发送结果（P3 W10 增量；P3 由 services/mock-channel 受理并留痕，未真实外发）
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.1.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record DunningSendResult(
        /** mock-channel 消息 ID（可回溯触达记录） */
        String pushId,
        /** P3 恒为 mocked（mock 通道已受理，未真实外发；不得声称已送达业主） */
        String status,
        String sentAt,
        DunningFrequencyCheck frequencyAfter) {
}
