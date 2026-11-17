package com.property.ai.domain.channel;

import java.util.Map;

/**
 * 短信通道适配接口（P6-005，仅领域层 Bean，<b>不新增对外端点</b>）。
 *
 * <p><b>红线</b>（AGENTS.md NEVER #4/#8）：本仓库不接真实短信，{@link MockChannelAdapter} 只返回
 * {@code status=mocked} 的占位回执；<b>不伪造发送成功</b>（{@code delivered=false}）。
 *
 * <p><b>调用约定</b>：
 * <ul>
 *   <li>{@code toMasked} 必须是掩码形态（如 {@code 138****8000}）；误传完整号码时适配层会再次掩码，
 *       但调用方仍应遵守「只传掩码」；</li>
 *   <li>{@code templateId} 只允许已报备模板（真实接入时）；{@code variables} 不得含完整手机号/房号/欠费金额
 *       组合到可识别个人身份的明文；</li>
 *   <li>实现不得抛非受检异常给主流程：失败口径由回执 {@code status/httpStatus} 表达（503 占位降级）。</li>
 * </ul>
 */
public interface SmsChannel {

    /**
     * 受理一条短信（占位模式不产生真实发送）。
     *
     * @param message 短信请求（{@code toMasked} 已掩码；{@code bizId} 幂等键，可为业务单号）
     * @return 通道回执（占位：{@code status=mocked}、{@code delivered=false}）
     */
    ChannelReceipt send(SmsMessage message);

    /** 短信请求（领域中立结构；不直接复用 HTTP DTO，避免领域依赖通道私有形态） */
    record SmsMessage(String toMasked, String templateId, Map<String, String> variables, String bizId) {

        public SmsMessage {
            variables = variables == null ? Map.of() : Map.copyOf(variables);
            toMasked = toMasked == null ? "" : toMasked;
            templateId = templateId == null ? "" : templateId;
            bizId = bizId == null ? "" : bizId;
        }
    }
}
