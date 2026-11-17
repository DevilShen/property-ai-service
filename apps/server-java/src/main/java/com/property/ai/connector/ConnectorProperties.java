package com.property.ai.connector;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 连接器运行时配置（前缀 {@code property.erp}）。
 *
 * <p>默认值面向 P2 第 1 周本地联调：{@code services/mock-erp} 测试替身监听 {@code 127.0.0.1:4200}。
 * 切真实用友 NC 只改 {@code base-url} / 凭据（P3 用 {@code property.erp.credentials.*}）/ {@code nc.*} 映射，
 * **不改对外契约**（契约 §2 mock-erp 边界）。
 *
 * <p>故障注入（仅联调/演练）：{@code property.erp.fail-mode=timeout|500|slow|flaky|ratelimit|502}
 * 会给每个出站 ERP 请求追加 {@code ?fail=<mode>} 与 {@code X-Mock-Fail} 头，用于验证降级/熔断。
 * 生产必须留空。
 */
@ConfigurationProperties(prefix = "property.erp")
public class ConnectorProperties {

    /** ERP 侧基地址（P2 第 1 周为 mock-erp 测试替身，不是 BFF 对外地址） */
    private String baseUrl = "http://127.0.0.1:4200";
    /** 单次调用预算（毫秒）；契约/设计 §24.4：调用方与服务端双重约束取小值 */
    private int timeoutMs = 1500;
    /** 建连超时（毫秒） */
    private int connectTimeoutMs = 800;
    /** 连接器总开关；关闭时所有能力返回 E-5001（能力未开通）并走降级 */
    private boolean enabled = true;
    /** 演示/演练用故障注入模式，生产为空串 */
    private String failMode = "";
    /** ERP 厂商标识（写进 ErpRef.vendor，契约示例 yonyou-nc-mock） */
    private String vendorLabel = "yonyou-nc-mock";
    /** 项目名（mock-erp 未回传项目名时补齐掩码提示用；不对 AI 上下文输出房号/电话） */
    private String projectName = "云栖苑";
    /** 健康探测周期（毫秒） */
    private long healthIntervalMs = 30000L;
    /** 结果缓存 TTL（毫秒），仅用于 ERP 不可用时的降级读（fallback=cache） */
    private long cacheTtlMs = 300000L;
    /**
     * 能力探测场景（P2-025）：透传给 mock-erp {@code /api/v1/meta/capabilities?scenario=}。
     *
     * <p>{@code all_supported}（默认）= 18 项全部支持；{@code degraded} = mock-erp 人为构造的三态演示
     * （5 项 degraded + 2 项 unsupported），用于入口显隐/降级话术/台账联调。
     * <b>该场景是测试替身的人为差异，不代表真实 NC 能力</b>；生产对接真实 NC 时应留空（按真实返回）。
     */
    private String probeScenario = "all_supported";
    /**
     * 建单是否强制要求会话内已完成 owner.verify（默认 true：报修属业主敏感动作，需先核验）。
     *
     * <p>契约 {@code POST /api/v1/tickets} 的 responses 未声明 403（契约缺口，P2-036 复核时补）；
     * 本开关同时支持 `--property.erp.enforce-owner-verification=false` 放宽（联调/e2e 兜底）。
     * 请求携带 {@code contactTail4} 时**无论开关**都强制核验。
     */
    private boolean enforceOwnerVerification = true;
    /** 建单后 owner 核验结论的会话复用时长（毫秒），契约：5 分钟内复用 */
    private long ownerVerificationTtlMs = 300000L;
    /** 幂等键保存窗口（小时），契约要求 ≥24h */
    private int idempotencyWindowHours = 24;

    // ------------------------------------------------------------------
    // P5 W19 增量（task-62 / P5-005/011）：第 2 套连接器（金蝶形态 mock-erp-2）出站配置。
    // 口径：多连接器共用同一套连接器运行时语义（超时/重试/熔断/错误码翻译），
    // 仅 base-url / 能力探测场景 / 厂商标识按连接器隔离；缺省指向 services/mock-erp-kingdee :4400。
    // ------------------------------------------------------------------

    /** 金蝶形态 ERP（测试替身 #2）基地址；生产切真实金蝶只改本值，不改对外契约 */
    private String kingdeeBaseUrl = "http://127.0.0.1:4400";
    /** 金蝶连接器单次调用预算（毫秒） */
    private int kingdeeTimeoutMs = 1500;
    /** 金蝶连接器建连超时（毫秒） */
    private int kingdeeConnectTimeoutMs = 800;
    /** 金蝶连接器总开关；关闭时能力调用返回 E-5001（能力未开通） */
    private boolean kingdeeEnabled = true;
    /** 金蝶连接器厂商标识（写进 ErpRef.vendor） */
    private String kingdeeVendorLabel = "kingdee";
    /** 金蝶能力探测场景：{@code kingdee_default}（14/3/1 三态）或 {@code all_supported} */
    private String kingdeeProbeScenario = "kingdee_default";
    /** 金蝶连接器每页上限声明（契约 ConnectorSdkLimits.pageSizeMax） */
    private int kingdeePageSizeMax = 200;
    /** 金蝶连接器每分钟限流声明（契约 ConnectorSdkLimits.rateLimitPerMin） */
    private int kingdeeRateLimitPerMin = 300;

    // ------------------------------------------------------------------
    // P5 W20 增量（task-65 / P5-012）：第 3 套连接器（自研形态 self-built-erp）。
    // 口径：仍是 **mock 测试替身**（数据面复用已起 mock，不接真实自研 ERP）；
    // 连接器"自有形态"协议由 connector/selfbuilt 的 wire 适配层模拟，切真实自研 ERP 只改本组配置 + Wire 层。
    // ------------------------------------------------------------------

    /** 自研形态 ERP（测试替身）基地址；默认复用 NC mock 数据面 :4200（也可指到金蝶替身 :4400） */
    private String selfBuiltBaseUrl = "http://127.0.0.1:4200";
    /** 自研连接器单次调用预算（毫秒；比 NC/金蝶更小，体现自有形态限流口径差异） */
    private int selfBuiltTimeoutMs = 1200;
    /** 自研连接器建连超时（毫秒） */
    private int selfBuiltConnectTimeoutMs = 800;
    /** 自研连接器总开关；关闭时能力调用返回 E-5001（能力未开通） */
    private boolean selfBuiltEnabled = true;
    /** 自研连接器厂商标识（写进 ErpRef.vendor，形如 self-built） */
    private String selfBuiltVendorLabel = "self-built";
    /** 自研连接器每页上限声明（契约 ConnectorSdkLimits.pageSizeMax；与 NC 200/金蝶 200 分列） */
    private int selfBuiltPageSizeMax = 100;
    /** 自研连接器每分钟限流声明（契约 ConnectorSdkLimits.rateLimitPerMin；与 NC 600/金蝶 300 分列） */
    private int selfBuiltRateLimitPerMin = 120;

    private Retry retry = new Retry();
    private CircuitBreakerSettings circuitBreaker = new CircuitBreakerSettings();

    /** 重试策略（只读能力可重试，写能力不重试） */
    public static class Retry {
        /** 只读能力总尝试次数（默认 2 = 首次 + 1 次重试，契约 §4：读操作可重试 1 次） */
        private int readMaxAttempts = 2;
        /** 重试退避（毫秒），契约 §4：退避 200ms */
        private long backoffMs = 200L;

        public int getReadMaxAttempts() {
            return readMaxAttempts;
        }

        public void setReadMaxAttempts(int readMaxAttempts) {
            this.readMaxAttempts = readMaxAttempts;
        }

        public long getBackoffMs() {
            return backoffMs;
        }

        public void setBackoffMs(long backoffMs) {
            this.backoffMs = backoffMs;
        }
    }

    /** 熔断策略（设计 §24.4：失败率熔断 + 半开探测） */
    public static class CircuitBreakerSettings {
        /** 连续失败阈值（达到后打开熔断） */
        private int failureThreshold = 5;
        /** 冷却时长（毫秒），冷却后进入半开放行一次探测 */
        private long cooldownMs = 30000L;
        /** 熔断开关（单测/联调可关闭） */
        private boolean enabled = true;

        public int getFailureThreshold() {
            return failureThreshold;
        }

        public void setFailureThreshold(int failureThreshold) {
            this.failureThreshold = failureThreshold;
        }

        public long getCooldownMs() {
            return cooldownMs;
        }

        public void setCooldownMs(long cooldownMs) {
            this.cooldownMs = cooldownMs;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

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

    public String getVendorLabel() {
        return vendorLabel;
    }

    public void setVendorLabel(String vendorLabel) {
        this.vendorLabel = vendorLabel;
    }

    public String getProjectName() {
        return projectName;
    }

    public void setProjectName(String projectName) {
        this.projectName = projectName;
    }

    public long getHealthIntervalMs() {
        return healthIntervalMs;
    }

    public void setHealthIntervalMs(long healthIntervalMs) {
        this.healthIntervalMs = healthIntervalMs;
    }

    public long getCacheTtlMs() {
        return cacheTtlMs;
    }

    public void setCacheTtlMs(long cacheTtlMs) {
        this.cacheTtlMs = cacheTtlMs;
    }

    public String getProbeScenario() {
        return probeScenario;
    }

    public void setProbeScenario(String probeScenario) {
        this.probeScenario = probeScenario;
    }

    public boolean isEnforceOwnerVerification() {
        return enforceOwnerVerification;
    }

    public void setEnforceOwnerVerification(boolean enforceOwnerVerification) {
        this.enforceOwnerVerification = enforceOwnerVerification;
    }

    public long getOwnerVerificationTtlMs() {
        return ownerVerificationTtlMs;
    }

    public void setOwnerVerificationTtlMs(long ownerVerificationTtlMs) {
        this.ownerVerificationTtlMs = ownerVerificationTtlMs;
    }

    public int getIdempotencyWindowHours() {
        return idempotencyWindowHours;
    }

    public void setIdempotencyWindowHours(int idempotencyWindowHours) {
        this.idempotencyWindowHours = idempotencyWindowHours;
    }

    public Retry getRetry() {
        return retry;
    }

    public void setRetry(Retry retry) {
        this.retry = retry;
    }

    public CircuitBreakerSettings getCircuitBreaker() {
        return circuitBreaker;
    }

    public void setCircuitBreaker(CircuitBreakerSettings circuitBreaker) {
        this.circuitBreaker = circuitBreaker;
    }

    // ---------------- P5 W19：第 2 套连接器（金蝶 mock-erp-2）配置 ----------------

    public String getKingdeeBaseUrl() {
        return kingdeeBaseUrl;
    }

    public void setKingdeeBaseUrl(String kingdeeBaseUrl) {
        this.kingdeeBaseUrl = kingdeeBaseUrl;
    }

    public int getKingdeeTimeoutMs() {
        return kingdeeTimeoutMs;
    }

    public void setKingdeeTimeoutMs(int kingdeeTimeoutMs) {
        this.kingdeeTimeoutMs = kingdeeTimeoutMs;
    }

    public int getKingdeeConnectTimeoutMs() {
        return kingdeeConnectTimeoutMs;
    }

    public void setKingdeeConnectTimeoutMs(int kingdeeConnectTimeoutMs) {
        this.kingdeeConnectTimeoutMs = kingdeeConnectTimeoutMs;
    }

    public boolean isKingdeeEnabled() {
        return kingdeeEnabled;
    }

    public void setKingdeeEnabled(boolean kingdeeEnabled) {
        this.kingdeeEnabled = kingdeeEnabled;
    }

    public String getKingdeeVendorLabel() {
        return kingdeeVendorLabel;
    }

    public void setKingdeeVendorLabel(String kingdeeVendorLabel) {
        this.kingdeeVendorLabel = kingdeeVendorLabel;
    }

    public String getKingdeeProbeScenario() {
        return kingdeeProbeScenario;
    }

    public void setKingdeeProbeScenario(String kingdeeProbeScenario) {
        this.kingdeeProbeScenario = kingdeeProbeScenario;
    }

    public int getKingdeePageSizeMax() {
        return kingdeePageSizeMax;
    }

    public void setKingdeePageSizeMax(int kingdeePageSizeMax) {
        this.kingdeePageSizeMax = kingdeePageSizeMax;
    }

    public int getKingdeeRateLimitPerMin() {
        return kingdeeRateLimitPerMin;
    }

    public void setKingdeeRateLimitPerMin(int kingdeeRateLimitPerMin) {
        this.kingdeeRateLimitPerMin = kingdeeRateLimitPerMin;
    }

    // ---------------- P5 W20：第 3 套连接器（自研形态 self-built-erp）配置 ----------------

    public String getSelfBuiltBaseUrl() {
        return selfBuiltBaseUrl;
    }

    public void setSelfBuiltBaseUrl(String selfBuiltBaseUrl) {
        this.selfBuiltBaseUrl = selfBuiltBaseUrl;
    }

    public int getSelfBuiltTimeoutMs() {
        return selfBuiltTimeoutMs;
    }

    public void setSelfBuiltTimeoutMs(int selfBuiltTimeoutMs) {
        this.selfBuiltTimeoutMs = selfBuiltTimeoutMs;
    }

    public int getSelfBuiltConnectTimeoutMs() {
        return selfBuiltConnectTimeoutMs;
    }

    public void setSelfBuiltConnectTimeoutMs(int selfBuiltConnectTimeoutMs) {
        this.selfBuiltConnectTimeoutMs = selfBuiltConnectTimeoutMs;
    }

    public boolean isSelfBuiltEnabled() {
        return selfBuiltEnabled;
    }

    public void setSelfBuiltEnabled(boolean selfBuiltEnabled) {
        this.selfBuiltEnabled = selfBuiltEnabled;
    }

    public String getSelfBuiltVendorLabel() {
        return selfBuiltVendorLabel;
    }

    public void setSelfBuiltVendorLabel(String selfBuiltVendorLabel) {
        this.selfBuiltVendorLabel = selfBuiltVendorLabel;
    }

    public int getSelfBuiltPageSizeMax() {
        return selfBuiltPageSizeMax;
    }

    public void setSelfBuiltPageSizeMax(int selfBuiltPageSizeMax) {
        this.selfBuiltPageSizeMax = selfBuiltPageSizeMax;
    }

    public int getSelfBuiltRateLimitPerMin() {
        return selfBuiltRateLimitPerMin;
    }

    public void setSelfBuiltRateLimitPerMin(int selfBuiltRateLimitPerMin) {
        this.selfBuiltRateLimitPerMin = selfBuiltRateLimitPerMin;
    }
}
