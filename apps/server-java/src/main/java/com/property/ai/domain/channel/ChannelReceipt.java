package com.property.ai.domain.channel;

/**
 * 通道受理回执（P6-005，领域层内部结构，<b>不对外暴露、不新增端点/契约字段</b>）。
 *
 * <p><b>语义（不得伪造成功）</b>：
 * <ul>
 *   <li>{@code status=mocked}：占位受理成功（模式 {@code mock}）；{@code delivered=false} ——
 *       「受理」不等于「送达/扣款/开票」，任何调用方<b>不得</b>据此宣称已发送/已支付/已开票；</li>
 *   <li>{@code status=disabled}：通道关闭（模式 {@code disabled}），{@code httpStatus=503}；</li>
 *   <li>{@code status=unavailable}：请求真实通道但不可用（模式 {@code real}：无凭据或实现未接入），
 *       {@code httpStatus=503}，{@code reason} 说明缺失键名/未接入事实。</li>
 * </ul>
 *
 * <p>{@code referenceId} 为<b>本地占位受理号</b>（{@code mock-sms-000001} 形态），仅用于对账/幂等演练，
 * <b>不是</b>运营商回执号/支付交易号/发票号；{@code reason} 已脱敏（不得含完整手机号/邮箱/密钥）。
 *
 * <p>将来接入真实通道时，{@code status} 增加 {@code accepted/failed} 等通道侧口径，
 * {@code delivered} 仍取通道回执的客观结果，禁止默认成 true。
 */
public record ChannelReceipt(String channel, String mode, String status, int httpStatus, boolean delivered,
        String referenceId, String reason) {

    /** 占位受理成功（不真实外发） */
    public static final String STATUS_MOCKED = "mocked";
    /** 通道关闭 */
    public static final String STATUS_DISABLED = "disabled";
    /** 真实通道不可用（无凭据 / 实现未接入） */
    public static final String STATUS_UNAVAILABLE = "unavailable";

    /** 占位受理（模式 mock，HTTP 200 口径） */
    public static ChannelReceipt mocked(String channel, String referenceId, String reason) {
        return new ChannelReceipt(channel, ChannelMode.MOCK.value(), STATUS_MOCKED, 200, false, referenceId,
                ChannelMasking.maskText(reason));
    }

    /** 通道关闭（HTTP 503 口径） */
    public static ChannelReceipt disabled(String channel, String reason) {
        return new ChannelReceipt(channel, ChannelMode.DISABLED.value(), STATUS_DISABLED, 503, false, "",
                ChannelMasking.maskText(reason));
    }

    /** 真实通道不可用（HTTP 503 口径） */
    public static ChannelReceipt unavailable(String channel, String reason) {
        return new ChannelReceipt(channel, ChannelMode.REAL.value(), STATUS_UNAVAILABLE, 503, false, "",
                ChannelMasking.maskText(reason));
    }

    /** 是否属于「占位受理成功」（模式 mock 且已受理） */
    public boolean mocked() {
        return STATUS_MOCKED.equals(status);
    }

    /** 是否应当按 503 返回（通道关闭 / 真实通道不可用） */
    public boolean serviceUnavailable() {
        return httpStatus == 503;
    }
}
