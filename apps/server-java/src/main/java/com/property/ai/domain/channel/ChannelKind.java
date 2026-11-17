package com.property.ai.domain.channel;

/**
 * 外部通道种类（P6-005）：短信 / 支付 / 发票。
 *
 * <p>只用于领域层「配置键名 + 日志 + 占位回执」的中立标识；<b>不对应任何对外端点/契约字段</b>
 * （本任务不新增对外端点，契约 v1.3.1 冻结不变）。
 */
public enum ChannelKind {

    /** 短信（SMS） */
    SMS("sms", "property.channel.sms.mode"),

    /** 支付（Payment，仅限跳转/占位，不扣款） */
    PAYMENT("payment", "property.channel.payment.mode"),

    /** 发票（Invoice，仅限申请占位，不开票） */
    INVOICE("invoice", "property.channel.invoice.mode");

    private final String value;
    private final String modeProperty;

    ChannelKind(String value, String modeProperty) {
        this.value = value;
        this.modeProperty = modeProperty;
    }

    /** 小写标识（{@code sms|payment|invoice}），用于回执 {@code channel} 字段与日志 */
    public String value() {
        return value;
    }

    /** 对应模式配置键（{@code property.channel.<kind>.mode}），用于 503 降级原因与文档对齐 */
    public String modeProperty() {
        return modeProperty;
    }
}
