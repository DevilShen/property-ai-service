package com.property.ai.bff.dto;

/**
 * OpenAPI: DegradedInfo（降级标记）。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v0.3.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record DegradedInfo(
        /** 降级原因（人话） */
        String reason,
        /** 替代路径 */
        DegradedFallback fallback,
        /** 是否值得稍后重试 */
        boolean retryable,
        /** 触发降级的错误码（可选） */
        ErrorCode code,
        /** 降级数据时点 ISO 8601（可选） */
        String dataAsOf) {
}
