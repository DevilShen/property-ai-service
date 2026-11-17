package com.property.ai.domain.dunning;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.property.ai.bff.dto.DunningChannel;
import com.property.ai.bff.dto.DunningScript;
import com.property.ai.connector.ConnectorException;
import com.property.ai.connector.PushChannel;
import com.property.ai.domain.ticket.TicketTimes;

/**
 * 催缴触达派发（P3-014，契约 {@code POST /dunning/tasks/{taskId}/send} → {@code DunningSendResult}）。
 *
 * <p><b>只走 {@code services/mock-channel}（测试替身）</b>：{@code POST /channels/push}，
 * 模板用自定义字符串 {@value DunningPolicy#PUSH_TEMPLATE}（mock-channel 允许自定义模板，
 * 记录 {@code templateKnown=false}，文案取 {@code payload.content} 即催缴话术），
 * <b>不接真实短信/企微/微信、不产生真实外发</b>（AGENTS.md NEVER #4）。
 *
 * <p><b>字段映射</b>：
 * <pre>
 * mock-channel              → 契约 DunningSendResult / 平台留痕
 * messageId                 → pushId（成功回执）
 * status（sent/queued）      → 平台统一落 mocked（P3 未真实外发，不得声称已送达业主）
 * sentAt                    → sentAt（缺失时用业务时区当前时间）
 * contentMasked             → 仅日志/对账用，不回给前端（前端用话术正文）
 * ticketNo（请求）           → taskId（关联键，便于 records?ticketNo= 对账）
 * </pre>
 *
 * <p><b>失败语义</b>：通道不可达/超时/上游错误 → {@link ConnectorException}（E-4001/E-4002/E-4003）向上抛，
 * 由 {@link DunningTaskService} 记 failed 尝试 + 审计 result=failed，BFF 返回 503 + degraded（契约 send 503
 * 「失败不伪造成功，进人工待办」）；<b>不重试、不伪造成功、不阻塞其它任务</b>。
 */
@Component
public class DunningDispatcher {

    private static final Logger log = LoggerFactory.getLogger(DunningDispatcher.class);
    /** 接收方掩码前缀（企微 openid 口径：只给掩码标识，不给真实手机号/微信号） */
    private static final String TO_MASKED_PREFIX = "owner:";

    private final PushChannel channel;
    /** 通道不可达只告警一次（连接器式降级：一次性日志，不逐条刷屏） */
    private final AtomicBoolean unreachableLogged = new AtomicBoolean(false);

    public DunningDispatcher(PushChannel channel) {
        this.channel = channel;
    }

    /**
     * 派发一次催缴触达（单户单次，<b>不群发</b>）。
     *
     * @param task    任务记录（{@code assignedTo}/{@code bucket}/{@code period} 进 payload 便于 mock 侧排查）
     * @param script  已生成话术（含版本；内容已过禁区扫描）
     * @param channelOverride 本次发送渠道（{@code null} → 话术渠道）；渠道值取契约枚举
     * @param traceId 链路 ID（透传 mock-channel {@code X-Trace-Id}）
     * @return mock 通道回执（{@code status} 恒 {@code mocked}）
     * @throws ConnectorException 通道不可达/超时/上游错误（由上层记 failed，不伪造成功）
     */
    public DispatchReceipt dispatch(DunningTaskRecord task, DunningScript script, DunningChannel channelOverride,
            String traceId) {
        DunningChannel effectiveChannel = channelOverride != null
                ? channelOverride
                : (script != null && script.channel() != null ? script.channel() : task.channel());
        String content = script == null ? "" : script.content();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("content", content);
        payload.put("dunningTaskId", task.taskId());
        payload.put("period", task.period());
        payload.put("bucket", task.bucket() == null ? "" : task.bucket().value());
        payload.put("tone", script == null || script.tone() == null ? "" : script.tone().value());
        payload.put("scriptVersion", script == null ? "" : script.version());
        try {
            PushChannel.PushReceipt receipt = channel.push(new PushChannel.PushMessage(
                    effectiveChannel.value(),
                    task.taskId(),
                    DunningPolicy.PUSH_TEMPLATE,
                    payload,
                    TO_MASKED_PREFIX + (task.ownerNameMasked() == null ? "" : task.ownerNameMasked())));
            String sentAt = receipt.sentAt() == null || receipt.sentAt().isBlank()
                    ? TicketTimes.nowIso()
                    : receipt.sentAt();
            log.info("[dunning] 触达已由 mock 通道受理（未真实外发）taskId={} channel={} pushId={} traceId={}",
                    task.taskId(), effectiveChannel.value(), receipt.pushId(), traceId);
            return new DispatchReceipt(receipt.pushId(), "mocked", sentAt, effectiveChannel,
                    receipt.contentMasked());
        } catch (ConnectorException ex) {
            if (unreachableLogged.compareAndSet(false, true)) {
                log.warn("[dunning] mock 催缴通道异常（{}）：本次记 failed，不伪造成功、不自动重试，"
                        + "后续异常不再逐条告警", ex.code().value());
            }
            throw ex;
        }
    }

    /** 供测试断言「通道异常只告警一次」 */
    boolean unreachableLogged() {
        return unreachableLogged.get();
    }

    /**
     * 派发回执（{@code status} 恒 {@code mocked}：mock 通道已受理留痕，未真实外发）。
     *
     * @param pushId       mock-channel {@code messageId}（可回溯触达记录）
     * @param status       固定 {@code mocked}（契约 {@code DunningSendResult.status} const）
     * @param sentAt       通道记录时间（业务时区 ISO 8601）
     * @param channel      实际使用渠道
     * @param contentMasked mock 侧脱敏文案副本（只用于日志/对账，不回前端）
     */
    public record DispatchReceipt(String pushId, String status, String sentAt, DunningChannel channel,
            String contentMasked) {
    }
}
