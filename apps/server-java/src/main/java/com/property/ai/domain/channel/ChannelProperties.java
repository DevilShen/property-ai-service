package com.property.ai.domain.channel;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 外部通道配置（前缀 {@code property.channel}，P6 W25 / P6-005）。
 *
 * <p><b>与既有 {@code com.property.ai.connector.ChannelProperties} 的关系</b>：那个类绑定的是
 * 「推送通道 HTTP 客户端」（{@code base-url/timeout-ms/enabled/fail-mode}，指向
 * {@code services/mock-channel} 测试替身）；本类只绑定 {@code property.channel.<sms|payment|invoice>.*}
 * 三组<b>业务通道</b>配置。两者共用前缀但字段不相交，绑定互不影响（Spring 忽略未知字段）；
 * <b>不修改</b>既有推送通道语义（契约不变）。
 *
 * <pre>
 * property:
 *   channel:
 *     sms:
 *       mode: mock            # disabled | mock（默认）| real
 *       provider: ""          # 真实接入时填运营商/云厂商标识（仅文档模板，仓库不存真值）
 *       endpoint: ""
 *       app-id: ""
 *       app-secret: ""        # 只允许环境变量/密钥管理注入，禁止提交仓库
 *       sign-name: ""
 *       template-id: ""
 *       daily-limit-per-owner: 1
 *     payment:
 *       mode: mock
 *       provider: ""
 *       merchant-id: ""
 *       app-id: ""
 *       api-key: ""
 *       notify-url: ""
 *       pay-url-template: ""  # 仅跳转占位模板，不得伪造支付成功
 *     invoice:
 *       mode: mock
 *       provider: ""
 *       app-id: ""
 *       app-secret: ""
 *       callback-url: ""
 * </pre>
 *
 * <p><b>红线</b>（AGENTS.md NEVER #4 / P6 门禁）：{@code real} 只表示「配置层请求真实通道」，
 * 本仓库<b>没有</b>任何真实通道实现；{@link MockChannelAdapter} 对 {@code real} 一律返回
 * 503 占位（有凭据 = 实现未接入；无凭据 = 列出缺失键名），<b>不发送、不扣款、不开票、不伪造成功</b>。
 *
 * <p><b>密钥管理</b>：本类所有 {@code *-secret/*-key} 字段默认空串，只允许由环境变量
 * （{@code PROPERTY_CHANNEL_SMS_APP_SECRET} 等）或密钥管理系统注入；启动日志只输出「已配置/未配置」，
 * <b>不输出任何凭据值</b>（见 {@link #summary()} / {@code docs/prod/P6-安全加固清单.md}）。
 */
@ConfigurationProperties(prefix = "property.channel")
public class ChannelProperties {

    /** 短信通道配置 */
    private Sms sms = new Sms();

    /** 支付通道配置 */
    private Payment payment = new Payment();

    /** 发票通道配置 */
    private Invoice invoice = new Invoice();

    /** 读取指定通道的原始模式配置值 */
    public String modeRaw(ChannelKind kind) {
        return switch (kind) {
            case SMS -> sms.getMode();
            case PAYMENT -> payment.getMode();
            case INVOICE -> invoice.getMode();
        };
    }

    /** 配置值是否合法（{@code disabled|mock|real}）；供装配期 WARN 用 */
    public boolean isModeValid(ChannelKind kind) {
        return ChannelMode.fromValue(modeRaw(kind)) != null;
    }

    /**
     * 解析通道模式；非法/空值降级为 {@link ChannelMode#MOCK}（占位受理），
     * 由 {@link MockChannelAdapter} 打一次性 WARN 说明配置非法。
     */
    public ChannelMode modeOf(ChannelKind kind) {
        ChannelMode parsed = ChannelMode.fromValue(modeRaw(kind));
        return parsed == null ? ChannelMode.MOCK : parsed;
    }

    /**
     * 真实通道模式下缺失的配置键名（<b>只返回键名，不返回值</b>，可直接进日志/503 原因）。
     *
     * <p>仅 {@code mode=real} 时有意义；返回空列表表示凭据齐全但实现仍未接入。
     */
    public List<String> missingCredentialKeys(ChannelKind kind) {
        List<String> missing = new ArrayList<>();
        switch (kind) {
            case SMS -> {
                requireText(missing, "property.channel.sms.provider", sms.getProvider());
                requireText(missing, "property.channel.sms.endpoint", sms.getEndpoint());
                requireText(missing, "property.channel.sms.app-id", sms.getAppId());
                requireText(missing, "property.channel.sms.app-secret", sms.getAppSecret());
                requireText(missing, "property.channel.sms.template-id", sms.getTemplateId());
            }
            case PAYMENT -> {
                requireText(missing, "property.channel.payment.provider", payment.getProvider());
                requireText(missing, "property.channel.payment.merchant-id", payment.getMerchantId());
                requireText(missing, "property.channel.payment.app-id", payment.getAppId());
                requireText(missing, "property.channel.payment.api-key", payment.getApiKey());
            }
            case INVOICE -> {
                requireText(missing, "property.channel.invoice.provider", invoice.getProvider());
                requireText(missing, "property.channel.invoice.app-id", invoice.getAppId());
                requireText(missing, "property.channel.invoice.app-secret", invoice.getAppSecret());
            }
        }
        return List.copyOf(missing);
    }

    /** 是否存在任一凭据字段已配置（只用于日志「已配置/未配置」，不输出值） */
    public boolean hasAnyCredential(ChannelKind kind) {
        return switch (kind) {
            case SMS -> anyText(sms.getProvider(), sms.getEndpoint(), sms.getAppId(), sms.getAppSecret(),
                    sms.getTemplateId());
            case PAYMENT -> anyText(payment.getProvider(), payment.getMerchantId(), payment.getAppId(),
                    payment.getApiKey());
            case INVOICE -> anyText(invoice.getProvider(), invoice.getAppId(), invoice.getAppSecret());
        };
    }

    /** 启动/运维摘要（**只含模式与「已配置/未配置」，绝不含凭据值**） */
    public String summary() {
        return "sms=" + modeOf(ChannelKind.SMS).value() + "/credentialConfigured="
                + hasAnyCredential(ChannelKind.SMS)
                + " payment=" + modeOf(ChannelKind.PAYMENT).value() + "/credentialConfigured="
                + hasAnyCredential(ChannelKind.PAYMENT)
                + " invoice=" + modeOf(ChannelKind.INVOICE).value() + "/credentialConfigured="
                + hasAnyCredential(ChannelKind.INVOICE);
    }

    private static void requireText(List<String> missing, String key, String value) {
        if (value == null || value.isBlank()) {
            missing.add(key);
        }
    }

    private static boolean anyText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return true;
            }
        }
        return false;
    }

    public Sms getSms() {
        return sms;
    }

    public void setSms(Sms sms) {
        this.sms = sms == null ? new Sms() : sms;
    }

    public Payment getPayment() {
        return payment;
    }

    public void setPayment(Payment payment) {
        this.payment = payment == null ? new Payment() : payment;
    }

    public Invoice getInvoice() {
        return invoice;
    }

    public void setInvoice(Invoice invoice) {
        this.invoice = invoice == null ? new Invoice() : invoice;
    }

    /** 短信通道配置（凭据默认空，禁止提交真值） */
    public static class Sms {

        /** 通道模式：{@code disabled|mock|real}（默认 mock） */
        private String mode = "mock";
        /** 服务商标识（真实接入时才填） */
        private String provider = "";
        /** 服务商 API 地址（真实接入时才填） */
        private String endpoint = "";
        /** 应用 appId（真实接入时才填，可公开） */
        private String appId = "";
        /** 应用密钥（**只允许环境变量注入，禁止入库/入仓/入日志**） */
        private String appSecret = "";
        /** 短信签名（真实接入时填已报备签名） */
        private String signName = "";
        /** 模板 ID（真实接入时填已报备模板） */
        private String templateId = "";
        /** 单业主单日短信上限（防骚扰；P6 合规要求，默认 1） */
        private int dailyLimitPerOwner = 1;

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getAppId() {
            return appId;
        }

        public void setAppId(String appId) {
            this.appId = appId;
        }

        public String getAppSecret() {
            return appSecret;
        }

        public void setAppSecret(String appSecret) {
            this.appSecret = appSecret;
        }

        public String getSignName() {
            return signName;
        }

        public void setSignName(String signName) {
            this.signName = signName;
        }

        public String getTemplateId() {
            return templateId;
        }

        public void setTemplateId(String templateId) {
            this.templateId = templateId;
        }

        public int getDailyLimitPerOwner() {
            return dailyLimitPerOwner;
        }

        public void setDailyLimitPerOwner(int dailyLimitPerOwner) {
            this.dailyLimitPerOwner = dailyLimitPerOwner;
        }
    }

    /** 支付通道配置（只允许跳转/占位，不扣款） */
    public static class Payment {

        /** 通道模式：{@code disabled|mock|real}（默认 mock） */
        private String mode = "mock";
        /** 支付服务商标识（真实接入时才填） */
        private String provider = "";
        /** 商户号（真实接入时才填） */
        private String merchantId = "";
        /** 应用 appId（真实接入时才填） */
        private String appId = "";
        /** API 密钥（**只允许环境变量注入**；不用于扣款，仅占位校验） */
        private String apiKey = "";
        /** 支付结果回调地址（真实接入时才填，必须 HTTPS + 验签 + 幂等） */
        private String notifyUrl = "";
        /** 收银台跳转占位模板（如 {@code https://pay.example.com/cashier?billNo={billNo}}） */
        private String payUrlTemplate = "";

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getMerchantId() {
            return merchantId;
        }

        public void setMerchantId(String merchantId) {
            this.merchantId = merchantId;
        }

        public String getAppId() {
            return appId;
        }

        public void setAppId(String appId) {
            this.appId = appId;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getNotifyUrl() {
            return notifyUrl;
        }

        public void setNotifyUrl(String notifyUrl) {
            this.notifyUrl = notifyUrl;
        }

        public String getPayUrlTemplate() {
            return payUrlTemplate;
        }

        public void setPayUrlTemplate(String payUrlTemplate) {
            this.payUrlTemplate = payUrlTemplate;
        }
    }

    /** 发票通道配置（只允许申请占位，不开票） */
    public static class Invoice {

        /** 通道模式：{@code disabled|mock|real}（默认 mock） */
        private String mode = "mock";
        /** 开票服务商标识（真实接入时才填） */
        private String provider = "";
        /** 应用 appId（真实接入时才填） */
        private String appId = "";
        /** 应用密钥（**只允许环境变量注入**） */
        private String appSecret = "";
        /** 开票结果回调地址（真实接入时才填，必须 HTTPS + 验签 + 幂等） */
        private String callbackUrl = "";

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getAppId() {
            return appId;
        }

        public void setAppId(String appId) {
            this.appId = appId;
        }

        public String getAppSecret() {
            return appSecret;
        }

        public void setAppSecret(String appSecret) {
            this.appSecret = appSecret;
        }

        public String getCallbackUrl() {
            return callbackUrl;
        }

        public void setCallbackUrl(String callbackUrl) {
            this.callbackUrl = callbackUrl;
        }
    }
}
