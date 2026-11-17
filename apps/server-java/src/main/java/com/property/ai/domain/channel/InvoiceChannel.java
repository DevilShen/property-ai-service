package com.property.ai.domain.channel;

/**
 * 发票通道适配接口（P6-005，仅领域层 Bean，<b>不新增对外端点</b>）。
 *
 * <p><b>红线</b>（AGENTS.md NEVER #4）：不接真实发票系统、不开票、不伪开发票号。
 * 占位实现只返回 {@code status=mocked}，{@code delivered=false}，{@code referenceId} 为本地占位受理号，
 * <b>不是</b>发票号码；真实接入需税控服务商资质 + 发票抬头/税号校验 + 回执验签 + 幂等 + 对账
 * （见 {@code docs/prod/P6-通道配置模板.md}）。
 */
public interface InvoiceChannel {

    /**
     * 受理一次开票申请（占位模式不产生真实开票）。
     *
     * @param request 开票申请（抬头/税号已掩码/脱敏；{@code billNo} 作为幂等键）
     * @return 通道回执（占位：{@code status=mocked}、{@code delivered=false}，<b>无发票号</b>）
     */
    ChannelReceipt applyInvoice(InvoiceRequest request);

    /** 开票申请（领域中立结构；税号/邮箱只允许掩码或哈希形态） */
    record InvoiceRequest(String billNo, String title, String taxNoMasked, long amountCents, String emailMasked,
            String bizId) {

        public InvoiceRequest {
            billNo = billNo == null ? "" : billNo;
            title = title == null ? "" : title;
            taxNoMasked = taxNoMasked == null ? "" : taxNoMasked;
            emailMasked = emailMasked == null ? "" : emailMasked;
            bizId = bizId == null || bizId.isBlank() ? billNo : bizId;
            if (amountCents < 0) {
                throw new IllegalArgumentException("amountCents 不得为负（金额单位为分，禁止浮点）");
            }
        }
    }
}
