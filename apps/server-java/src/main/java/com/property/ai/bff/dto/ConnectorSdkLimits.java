package com.property.ai.bff.dto;

/**
 * OpenAPI: ConnectorSdkLimits（连接器 SDK 运行限制，P5 W19 / v1.3.0）。
 * 字段名 / 必填与 docs/openapi/openapi.yaml v1.3.0 严格对齐；仅 DTO，不含业务逻辑。
 * timeoutMs 单次调用超时（毫秒）、pageSizeMax 分页上限（条）、rateLimitPerMin 每分钟限流（次/连接器），
 * 由连接器运行时强制（超时/分页/限流），不在前端计算。
 */
public record ConnectorSdkLimits(
        int timeoutMs,
        int pageSizeMax,
        int rateLimitPerMin) {
}
