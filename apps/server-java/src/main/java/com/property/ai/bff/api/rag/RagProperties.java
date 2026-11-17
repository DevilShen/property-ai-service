package com.property.ai.bff.api.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RAG 编排层接线配置（前缀 {@code property.rag}，P6 W30 / task-80）。
 *
 * <p>数据面归 Python 编排层（{@code services/ai-orchestrator}，内网 {@code /internal/rag/*}），
 * Java BFF 只做<b>出站 HTTP 调用 + 契约映射 + 降级</b>；<b>Java 侧不引入任何模型 SDK</b>
 * （AGENTS.md §1 / NEVER #2，架构 §24.9 AI 编排 = Python）。
 *
 * <h2>默认值（本地联调口径，不是生产配置）</h2>
 * <ul>
 *   <li>{@code base-url}：{@code http://127.0.0.1:4110}（W32/W33 编排层内网端口）；</li>
 *   <li>{@code internal-token}：默认空 → 编排层按 fail-closed 返回 401，Java 侧走规则检索降级；
 *       联调/部署用环境变量 {@code RAG_INTERNAL_TOKEN} 注入（<b>仓库内不落真实 token</b>）；</li>
 *   <li>{@code timeout-ms}：8000（编排层生成预算 20s，Java 侧取更小的 8s 单次预算，
 *       超时即降级规则检索，避免 BFF 线程长时间占用）；</li>
 *   <li>{@code enabled}：true（关闭 = 直接走规则检索降级，用于离线演示/演练）。</li>
 * </ul>
 *
 * <p><b>安全口径</b>：token 只用于出站 {@code X-Internal-Token} 头，<b>不写日志、不回显、不进 health 响应</b>
 * （health 只报「已配置/未配置」布尔）。base-url 属内网地址，同样不进 health 的 {@code components[].detail}。
 */
@ConfigurationProperties(prefix = "property.rag")
public class RagProperties {

    /** 编排层内网基地址（W32/W33 {@code services/ai-orchestrator}） */
    private String baseUrl = "http://127.0.0.1:4110";
    /** 内网共享 token（`X-Internal-Token`；默认空 = 未配置 → 编排层 401，Java 侧降级） */
    private String internalToken = "";
    /** 单次调用预算（毫秒）：问答/健康/重建索引共用；超时按「降级」处理，不重试 */
    private int timeoutMs = 8000;
    /** RAG 出站总开关：false = 不调编排层（查询走规则检索降级、重建索引 503） */
    private boolean enabled = true;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getInternalToken() {
        return internalToken;
    }

    public void setInternalToken(String internalToken) {
        this.internalToken = internalToken;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** 生效预算（防御性下限 300ms：太小会让所有请求都超时降级，掩盖真实问题） */
    public int effectiveTimeoutMs() {
        return Math.max(300, timeoutMs);
    }

    /** 建连预算：不超过单次预算，默认 800ms */
    public int effectiveConnectTimeoutMs() {
        return Math.max(200, Math.min(800, effectiveTimeoutMs()));
    }

    /** token 是否已配置（health/日志只报布尔，不回显值） */
    public boolean hasInternalToken() {
        return internalToken != null && !internalToken.isBlank();
    }
}
