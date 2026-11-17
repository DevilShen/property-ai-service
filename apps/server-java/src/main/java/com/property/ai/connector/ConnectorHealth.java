package com.property.ai.connector;

/**
 * 连接器健康快照（能力探测结果）。
 *
 * <p>P2 第 1 周探测目标是 {@code services/mock-erp} 的 {@code GET /healthz}（测试替身，非正式后端）；
 * 切真实用友 NC 只换 baseUrl/凭据/映射，探测口径不变。健康结果同时挂在
 * Spring Actuator {@code /actuator/health} 的 {@code erpConnector} 条目上。
 */
public record ConnectorHealth(
        State state,
        String vendor,
        boolean mock,
        String baseUrl,
        String checkedAt,
        long latencyMs,
        String errorCode,
        String message) {

    /** 连接器状态：UP 可用 / DOWN 不可用 / UNKNOWN 尚未探测 */
    public enum State {
        UP,
        DOWN,
        UNKNOWN
    }

    public static ConnectorHealth unknown(String vendor, boolean mock, String baseUrl) {
        return new ConnectorHealth(State.UNKNOWN, vendor, mock, baseUrl, null, -1L, null, "尚未探测");
    }

    public static ConnectorHealth up(String vendor, boolean mock, String baseUrl, long latencyMs, String checkedAt) {
        return new ConnectorHealth(State.UP, vendor, mock, baseUrl, checkedAt, latencyMs, null, null);
    }

    public static ConnectorHealth down(String vendor, boolean mock, String baseUrl, String errorCode,
            String message, String checkedAt) {
        return new ConnectorHealth(State.DOWN, vendor, mock, baseUrl, checkedAt, -1L, errorCode, message);
    }

    public boolean available() {
        return state == State.UP;
    }
}
