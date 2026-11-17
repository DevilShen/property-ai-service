package com.property.ai.domain.ticket;

import com.property.ai.bff.dto.DegradedInfo;

/**
 * 只读结果 + 可选降级标记（降级不是错误：仍 2xx，由 Controller 用 {@code ApiResponse.okDegraded} 下发）。
 *
 * <p>用于工单详情/列表/转人工摘要等「ERP 不可用时可用平台侧快照降级」的读路径。
 */
public record FetchResult<T>(T data, DegradedInfo degraded) {

    public static <T> FetchResult<T> ok(T data) {
        return new FetchResult<>(data, null);
    }

    /** 是否发生降级（降级不是错误：仍 2xx + 替代路径） */
    public boolean isDegraded() {
        return degraded != null;
    }
}
