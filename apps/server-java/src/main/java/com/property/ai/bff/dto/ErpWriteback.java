package com.property.ai.bff.dto;

/**
 * OpenAPI: ErpWriteback（ERP 写操作回写结果，v0.3.1）。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v0.3.1 严格对齐；仅 DTO，不含业务逻辑。
 *
 * <p>写操作不自动重试：{@code failed} 由人工待办处理；{@code pending} 由服务端 30 秒后再查一次、
 * 5 分钟未确认告警转人工（架构 §24.6）。</p>
 */
public record ErpWriteback(
        ErpWritebackStatus status,
        /** 已尝试回写次数（0 = 尚未发出；不自动重试，超限即进人工待办） */
        int attempts,
        /** 下次确认/查询时间（仅 pending 时有意义；服务端 30 秒后确认） */
        String nextRetryAt,
        /** ERP 侧回写单号/引用 */
        String erpRef,
        /** 回写结果人话说明（失败原因，不含 ERP 凭据/敏感信息） */
        String message) {
}
