package com.property.ai.connector;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 推送通道配置（前缀 {@code property.channel}）。
 *
 * <p>P2 W6 只有 {@code services/mock-channel} 测试替身（默认 {@code 127.0.0.1:4300}）：
 * <b>不接真实短信/企微/微信</b>，通道受理只做脱敏留痕，不产生真实发送。切真实通道属 P6 门禁，
 * 届时只换 {@code base-url}/凭据，**不改对外契约**（契约 §1.1 / 架构 §24.11）。
 *
 * <p>故障注入（仅联调/演练）：{@code property.channel.fail-mode=timeout|500|rate_limit|502|slow|flaky}
 * 会给每个出站推送追加 {@code ?fail=<mode>}（mock-channel README 口径）。生产必须留空。
 */
@ConfigurationProperties(prefix = "property.channel")
public class ChannelProperties {

    /** 推送通道基地址（P2 W6 为 mock-channel 测试替身，不是真实通道） */
    private String baseUrl = "http://127.0.0.1:4300";
    /** 单次推送预算（毫秒）；写操作不阻塞主流程，预算取小值 */
    private int timeoutMs = 1500;
    /** 建连超时（毫秒） */
    private int connectTimeoutMs = 800;
    /** 通道总开关；关闭时推送直接落 failed（一次性日志），不影响工单主流程 */
    private boolean enabled = true;
    /** 演示/演练用故障注入模式，生产为空串 */
    private String failMode = "";

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getFailMode() {
        return failMode;
    }

    public void setFailMode(String failMode) {
        this.failMode = failMode;
    }
}
