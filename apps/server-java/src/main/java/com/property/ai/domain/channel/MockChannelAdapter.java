package com.property.ai.domain.channel;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 通道默认（占位）适配器（P6 W25 / P6-005）：同时实现 {@link SmsChannel} / {@link PaymentChannel} /
 * {@link InvoiceChannel}，由 {@link ChannelConfig} 装配为默认 Bean。
 *
 * <h2>为什么只有占位实现</h2>
 * AGENTS.md NEVER #4 明确：<b>不接真实支付/发票/短信</b>，除非阶段门禁放行到 P6 且拿到真实凭据与资质。
 * 本任务（P6-005）交付的是「适配层接口 + 可切换开关 + 占位降级 + 配置模板」，因此：
 * <ul>
 *   <li>{@code mode=mock}（默认）：返回 {@code status=mocked} 占位回执，{@code delivered=false}，
 *       <b>不产生任何网络请求/短信/扣款/开票</b>；</li>
 *   <li>{@code mode=disabled}：返回 {@code status=disabled}、HTTP 503 占位；</li>
 *   <li>{@code mode=real}：<b>一律降级</b> 为 {@code status=unavailable}、HTTP 503 占位并 WARN
 *       （无凭据 → 原因列出缺失键名；有凭据 → 原因说明「真实实现未接入」）；
 *       绝不因为配置成 real 就假装真实通道可用或伪造成功回执。</li>
 * </ul>
 *
 * <h2>不回伪（关键约束）</h2>
 * <ul>
 *   <li>任何模式 {@code delivered=false} —— 「受理」≠「送达/支付/开票」；</li>
 *   <li>{@code referenceId} 是本地占位受理号（{@code mock-sms-000001}），不是运营商回执号/支付流水号/发票号；</li>
 *   <li>回执 {@code reason} 经 {@link ChannelMasking} 掩码，不含完整手机号/邮箱/密钥；</li>
 *   <li>日志只输出模式与「凭据已配置/未配置」，<b>不输出凭据值</b>。</li>
 * </ul>
 *
 * <p>真实接入路径：新增 {@code RealSmsChannel} 等实现 → 在 {@link ChannelConfig} 里按
 * {@code property.channel.<kind>.mode=real} + 凭据齐全时切换；切换前必须过 P6 合规门禁与
 * {@code docs/prod/P6-通道配置模板.md} 的回执/幂等/对账要求。
 */
public class MockChannelAdapter implements SmsChannel, PaymentChannel, InvoiceChannel {

    private static final Logger log = LoggerFactory.getLogger(MockChannelAdapter.class);

    private final ChannelProperties properties;
    private final AtomicLong sequence = new AtomicLong();
    /** 每个通道只告警一次（真实通道未接入 / 配置非法），避免刷屏 */
    private final AtomicBoolean[] warned = {
            new AtomicBoolean(false), new AtomicBoolean(false), new AtomicBoolean(false)
    };

    public MockChannelAdapter(ChannelProperties properties) {
        this.properties = properties == null ? new ChannelProperties() : properties;
        logEffectiveModes();
    }

    /**
     * 启动期逐通道打一次口径日志（装配期执行）。
     *
     * <p>口径：{@code mock} 打 INFO（占位受理）；{@code disabled} 打 WARN（关闭）；{@code real} 打 WARN
     * （真实通道未接入 + 凭据状况，只给键名/布尔，不输出值）；非法配置值打 WARN（按 mock 兜底）。
     */
    private void logEffectiveModes() {
        for (ChannelKind kind : ChannelKind.values()) {
            ChannelMode mode = properties.modeOf(kind);
            if (!properties.isModeValid(kind)) {
                log.warn("[channel] WARN {} 的配置值非法：{}={}（合法值 disabled|mock|real）→ 按 mock 占位降级；"
                        + "不得表述为真实通道就绪", kind.value(), kind.modeProperty(),
                        properties.modeRaw(kind));
                continue;
            }
            switch (mode) {
                case MOCK -> log.info("[channel] {} mode=mock 占位受理（不产生真实发送/扣款/开票；delivered=false）",
                        kind.value());
                case DISABLED -> log.warn("[channel] WARN {} mode=disabled：通道关闭，受理返回 503 占位", kind.value());
                case REAL -> {
                    List<String> missing = properties.missingCredentialKeys(kind);
                    warned[kind.ordinal()].set(true);
                    if (missing.isEmpty()) {
                        log.warn("[channel] WARN {} mode=real：凭据已配置，但本仓库无真实{}实现（P6 门禁未放行），"
                                + "受理降级为 503 占位，不伪造成功", kind.value(), actionOf(kind));
                    } else {
                        log.warn("[channel] WARN {} mode=real 但缺少凭据 {}（只列键名，不打印值）→ 受理降级为 503 占位，"
                                + "不伪造成功", kind.value(), missing);
                    }
                }
            }
        }
    }

    @Override
    public ChannelReceipt send(SmsMessage message) {
        String masked = ChannelMasking.maskPhone(message.toMasked());
        String reason = "占位受理（mock 模式）：不产生真实短信发送；to=" + masked + "（若为空表示调用方未传掩码）";
        return receipt(ChannelKind.SMS, message.bizId(), reason);
    }

    @Override
    public ChannelReceipt createPayment(PaymentRequest request) {
        String reason = "占位受理（mock 模式）：不产生真实扣款/支付成功；billNo="
                + ChannelMasking.maskText(request.billNo()) + " amountCents=" + request.amountCents();
        return receipt(ChannelKind.PAYMENT, request.bizId(), reason);
    }

    @Override
    public ChannelReceipt applyInvoice(InvoiceRequest request) {
        String reason = "占位受理（mock 模式）：不产生真实开票/发票号码；billNo="
                + ChannelMasking.maskText(request.billNo()) + " amountCents=" + request.amountCents();
        return receipt(ChannelKind.INVOICE, request.bizId(), reason);
    }

    /** 统一模式闸门：按配置返回占位受理 / 关闭 / 真实不可用（503） */
    private ChannelReceipt receipt(ChannelKind kind, String bizId, String mockReason) {
        if (!properties.isModeValid(kind)) {
            warnOnce(kind, "配置值非法：" + kind.modeProperty() + "=" + properties.modeRaw(kind)
                    + "（合法值 disabled|mock|real）→ 按 mock 占位降级");
            return mocked(kind, bizId, mockReason + "；注意：" + kind.modeProperty() + " 配置值非法，已按 mock 兜底");
        }
        return switch (properties.modeOf(kind)) {
            case MOCK -> mocked(kind, bizId, mockReason);
            case DISABLED -> ChannelReceipt.disabled(kind.value(),
                    "通道已关闭（" + kind.modeProperty() + "=disabled）：不发起真实" + actionOf(kind)
                            + "；受理返回 503 占位，不伪造成功");
            case REAL -> realFallback(kind);
        };
    }

    /** true 模式降级：无凭据 → 列缺失键名；有凭据 → 说明实现未接入；两种情况都是 503 占位 */
    private ChannelReceipt realFallback(ChannelKind kind) {
        List<String> missing = properties.missingCredentialKeys(kind);
        if (!missing.isEmpty()) {
            warnOnce(kind, "mode=real 但缺少凭据（只列键名）：" + String.join(",", missing));
            return ChannelReceipt.unavailable(kind.value(),
                    "请求 " + kind.modeProperty() + "=real 但缺少凭据：" + String.join(",", missing)
                            + "（只列键名）；本仓库无真实" + actionOf(kind) + "实现 → 返回 503 占位，不伪造成功");
        }
        warnOnce(kind, "mode=real 且凭据已配置，但本仓库无真实" + actionOf(kind) + "实现（P6 门禁未放行）");
        return ChannelReceipt.unavailable(kind.value(),
                "请求 " + kind.modeProperty() + "=real 且凭据已配置，但本仓库无真实" + actionOf(kind)
                        + "实现（P6 门禁未放行）→ 返回 503 占位，不伪造成功");
    }

    private ChannelReceipt mocked(ChannelKind kind, String bizId, String reason) {
        String referenceId = "mock-" + kind.value() + "-" + String.format("%06d", sequence.incrementAndGet());
        return ChannelReceipt.mocked(kind.value(), referenceId,
                reason + "；bizId=" + ChannelMasking.maskText(bizId) + "；status=mocked、delivered=false，"
                        + "不得表述为已发送/已支付/已开票");
    }

    /** 每个通道的一次性 WARN（真实通道不可用 / 配置非法），避免逐条刷屏 */
    private void warnOnce(ChannelKind kind, String message) {
        if (warned[kind.ordinal()].compareAndSet(false, true)) {
            log.warn("[channel] WARN {}：{}；后续同类受理直接返回占位/503，不再逐条告警"
                    + "（真实接入前置见 docs/prod/P6-通道配置模板.md）", kind.value(), message);
        }
    }

    private static String actionOf(ChannelKind kind) {
        return switch (kind) {
            case SMS -> "短信发送";
            case PAYMENT -> "支付扣款";
            case INVOICE -> "发票开具";
        };
    }

    /** 指定通道的生效模式（供装配/测试断言） */
    public ChannelMode modeOf(ChannelKind kind) {
        return properties.modeOf(kind);
    }

    /** 是否存在降级（任一通道非 mock，或配置值非法） */
    public boolean degraded() {
        for (ChannelKind kind : ChannelKind.values()) {
            if (!properties.isModeValid(kind) || properties.modeOf(kind) != ChannelMode.MOCK) {
                return true;
            }
        }
        return false;
    }

    /** 启动摘要（只含模式与「凭据已配置/未配置」，不含任何凭据值） */
    public String summary() {
        return "channelMode(" + properties.summary() + ")";
    }

    /** 供测试断言「占位受理号单调递增、前缀固定」 */
    long issuedCount() {
        return sequence.get();
    }
}
