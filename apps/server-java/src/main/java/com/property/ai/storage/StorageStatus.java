package com.property.ai.storage;

/**
 * 存储生效状态快照（P5-017）。
 *
 * <p>{@code requested} 是配置请求值，{@code effective} 是启动期探测后真正生效的值：
 * 两者不一致即 {@link #degraded()} = true（本环境请求 postgres 时恒降级 memory，并带 {@code reason} 说明根因）。
 *
 * @param requested 请求的存储模式（{@code property.storage.mode}）
 * @param effective 生效的存储模式（探测后）
 * @param reason    降级原因（未降级时为 {@code null}）
 */
public record StorageStatus(StorageMode requested, StorageMode effective, String reason) {

    /** 是否发生降级（请求值与生效值不同） */
    public boolean degraded() {
        return requested != effective;
    }

    /** 请求模式的小写值（日志/文档口径） */
    public String requestedMode() {
        return requested.value();
    }

    /** 生效模式的小写值（日志/文档口径） */
    public String effectiveMode() {
        return effective.value();
    }

    /** 诊断行（启动日志/报告用）：{@code storageMode=memory requested=postgres degraded=true reason=...} */
    public String summary() {
        return "storageMode=" + effectiveMode() + " requested=" + requestedMode() + " degraded=" + degraded()
                + (reason == null ? "" : " reason=" + reason);
    }
}
