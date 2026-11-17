package com.property.ai.domain.channel;

/**
 * 支付通道适配接口（P6-005，仅领域层 Bean，<b>不新增对外端点</b>）。
 *
 * <p><b>红线</b>（AGENTS.md NEVER #4）：不接真实支付、不扣款、不代收。
 * 占位实现只返回 {@code status=mocked}，且必须保证「没有任何支付成功语义」：
 * 不返回交易流水号、不置 paid、{@code delivered=false}；收银台跳转只允许<b>占位 URL</b>
 * （{@code property.channel.payment.pay-url-template}，默认空 = 不跳转）。
 *
 * <p>P3 既有「去缴费」按钮仍是跳转/占位（前端弹窗说明演示），本接口不改变任何既有端点行为；
 * 真实支付接入需 P6 合规门禁 + 商户号 + 回调验签 + 幂等 + 对账，见
 * {@code docs/prod/P6-通道配置模板.md} 与 {@code docs/prod/P6-安全加固清单.md}。
 */
public interface PaymentChannel {

    /**
     * 受理一次支付请求（占位模式不产生真实扣款）。
     *
     * @param request 支付请求（金额分；{@code billNo} 作为幂等键）
     * @return 通道回执（占位：{@code status=mocked}、{@code delivered=false}，<b>无「支付成功」语义</b>）
     */
    ChannelReceipt createPayment(PaymentRequest request);

    /** 支付请求（领域中立结构；金额单位为分，禁止浮点） */
    record PaymentRequest(String billNo, long amountCents, String scene, String ownerMasked, String bizId) {

        public PaymentRequest {
            billNo = billNo == null ? "" : billNo;
            scene = scene == null ? "" : scene;
            ownerMasked = ownerMasked == null ? "" : ownerMasked;
            bizId = bizId == null || bizId.isBlank() ? billNo : bizId;
            if (amountCents < 0) {
                throw new IllegalArgumentException("amountCents 不得为负（金额单位为分，禁止浮点）");
            }
        }
    }
}
