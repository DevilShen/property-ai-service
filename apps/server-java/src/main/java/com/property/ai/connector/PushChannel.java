package com.property.ai.connector;

import java.util.Map;

/**
 * 推送通道接口（P2 W6：只有 mock 通道实现，不接真实短信/企微/微信）。
 *
 * <p>边界（架构 §24.11 / 契约 v0.3.1 {@code TicketPush}）：
 * <ul>
 *   <li>通道只负责「受理 + 脱敏留痕」，返回的 {@code contentMasked} 才允许落库/下发；</li>
 *   <li>实现不得抛非受检异常之外的东西给主流程：失败一律 {@link ConnectorException}
 *       （E-4001 不可达 / E-4002 上游错误 / E-4003 限流），由 {@code PushService} 记 failed，<b>不阻塞工单主流程</b>；</li>
 *   <li>写/推不自动重试（契约：写操作不自动重试），由人工/后续任务补偿。</li>
 * </ul>
 *
 * <p>⚠️ P2 W6 的 {@link HttpPushChannel} 对接的是 {@code services/mock-channel} 测试替身，
 * 所有回执 {@code mock=true}；**不得**在对外材料中声称真实外发。
 */
public interface PushChannel {

    /**
     * 发一次推送（一次调用一条留痕记录）。
     *
     * @param message 推送请求（{@code channel} 只允许 wecom/sms/inapp；{@code payload} 不得含完整手机号/地址）
     * @return 通道回执（mock 通道：{@code mock=true}，{@code status} 为 sent/queued/failed 的通道侧口径）
     */
    PushReceipt push(PushMessage message);

    /** 推送请求（契约 DTO 之前的中立结构，避免 domain 依赖通道私有形态） */
    record PushMessage(String channel, String ticketNo, String template, Map<String, Object> payload,
            String toMasked) {

        public PushMessage {
            payload = payload == null ? Map.of() : Map.copyOf(payload);
        }
    }

    /** 通道回执（{@code pushId} = mock-channel 的 {@code messageId}，用于契约 {@code TicketPush.pushId}） */
    record PushReceipt(String pushId, String channel, String status, String sentAt, String contentMasked,
            boolean mock) {
    }
}
