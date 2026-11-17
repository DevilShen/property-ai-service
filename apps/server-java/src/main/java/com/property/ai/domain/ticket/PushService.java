package com.property.ai.domain.ticket;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.property.ai.bff.dto.TicketPush;
import com.property.ai.bff.dto.TicketPushChannel;
import com.property.ai.bff.dto.TicketPushStatus;
import com.property.ai.connector.ConnectorException;
import com.property.ai.connector.PushChannel;

/**
 * 三次推送服务（P2-019）：受理（accepted）→ 出发/派单（dispatched）→ 完工（completed）。
 *
 * <p>口径（架构 §24.11 / 契约 v0.3.1 {@code TicketPush}）：
 * <ul>
 *   <li>推送在工单状态提交**之后**调用（不参与事务），失败**不阻塞主流程**、**不伪造成功**；</li>
 *   <li>通道回执成功 → 平台记 {@code status=mocked}（P2 W6 全部为 mock 通道，未真实外发，契约 §1.1）；</li>
 *   <li>通道失败/不可达 → 平台记 {@code status=failed}（{@code pushId} 为平台侧本地编号），一次性日志；</li>
 *   <li>三次推送允许**重复触发**（如重复派单）：每次调用追加一条记录，不覆盖历史；</li>
 *   <li>正文只落通道回执的 {@code contentMasked}（手机号只留后四位/地址隐藏）；本地不拼真实号码。</li>
 * </ul>
 *
 * <p>数据源说明：查询走 {@link PushRepository}（平台侧），mock-channel 记录只作留痕核对。
 */
@Service
public class PushService {

    private static final Logger log = LoggerFactory.getLogger(PushService.class);

    /** 契约模板编码（mock-channel 三个主模板；服务端给出，前端不硬编码） */
    public static final String TEMPLATE_ACCEPTED = "accepted";
    public static final String TEMPLATE_DISPATCHED = "dispatched";
    public static final String TEMPLATE_COMPLETED = "completed";

    /** 通道分配（demo 口径）：受理走企微、派单/完工走短信；P2 W6 全部由 mock-channel 受理 */
    private static final TicketPushChannel ACCEPTED_CHANNEL = TicketPushChannel.WECOM;
    private static final TicketPushChannel DISPATCHED_CHANNEL = TicketPushChannel.SMS;
    private static final TicketPushChannel COMPLETED_CHANNEL = TicketPushChannel.SMS;

    private static final int MAX_CONTENT_CHARS = 120;
    /** 11 位手机号（推送正文脱敏用；不含边界数字） */
    private static final Pattern PHONE_PATTERN = Pattern.compile("(?<!\\d)(1\\d{10})(?!\\d)");

    private final PushChannel channel;
    private final PushRepository repository;
    private final AtomicInteger failedSeq = new AtomicInteger(0);

    public PushService(PushChannel channel, PushRepository repository) {
        this.channel = channel;
        this.repository = repository;
    }

    /** 建单受理后推送（模板 accepted） */
    public Optional<TicketPush> pushAccepted(TicketRecord record) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("slot", scrub(record.slot));
        payload.put("category", record.category == null ? "" : record.category.value());
        return send(record, ACCEPTED_CHANNEL, TEMPLATE_ACCEPTED, payload);
    }

    /** 派单/出发后推送（模板 dispatched，含班组与 ETA） */
    public Optional<TicketPush> pushDispatched(TicketRecord record) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("team", scrub(record.team));
        payload.put("etaText", scrub(record.eta));
        return send(record, DISPATCHED_CHANNEL, TEMPLATE_DISPATCHED, payload);
    }

    /** 完工后推送（模板 completed，含结案说明） */
    public Optional<TicketPush> pushCompleted(TicketRecord record, String resolution) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("resolution", truncate(scrub(resolution)));
        payload.put("team", scrub(record.team));
        return send(record, COMPLETED_CHANNEL, TEMPLATE_COMPLETED, payload);
    }

    /** 页面查询：平台侧推送记录（最新在前；无记录返回空列表，不造数） */
    public List<TicketPush> listByTicket(String ticketNo) {
        return repository.findByTicketNo(ticketNo);
    }

    /** 测试/演示重置 */
    public void resetForTest() {
        repository.clear();
        failedSeq.set(0);
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private Optional<TicketPush> send(TicketRecord record, TicketPushChannel pushChannel, String template,
            Map<String, Object> payload) {
        try {
            PushChannel.PushReceipt receipt = channel.push(new PushChannel.PushMessage(pushChannel.value(),
                    record.ticketNo, template, payload, null));
            TicketPush push = new TicketPush(
                    receipt.pushId() == null || receipt.pushId().isBlank() ? localPushId() : receipt.pushId(),
                    record.ticketNo,
                    pushChannel,
                    template,
                    mapStatus(receipt.status()),
                    receipt.sentAt(),
                    receipt.contentMasked(),
                    true);
            repository.save(push);
            log.info("[push] 推送已受理（mock 通道，未真实外发）ticketNo={} template={} status={} pushId={}",
                    record.ticketNo, template, push.status().value(), push.pushId());
            return Optional.of(push);
        } catch (ConnectorException ex) {
            // 通道失败：记录 failed，不阻塞主流程、不伪造成功、不自动重试
            TicketPush failed = new TicketPush(localPushId(), record.ticketNo, pushChannel, template,
                    TicketPushStatus.FAILED, null, null, true);
            repository.save(failed);
            log.warn("[push] 推送未送达，已记录 failed（不阻塞主流程，不自动重试）ticketNo={} template={} code={}",
                    record.ticketNo, template, ex.code().value());
            return Optional.of(failed);
        }
    }

    /**
     * 通道状态 → 契约推送状态：
     * mock 通道的 {@code sent}/{@code queued} 都未真实外发，统一落 {@code mocked}（契约 §1.1 示例口径）；
     * {@code failed} 直传 {@code failed}。
     */
    static TicketPushStatus mapStatus(String channelStatus) {
        if (channelStatus != null && "failed".equalsIgnoreCase(channelStatus.trim())) {
            return TicketPushStatus.FAILED;
        }
        return TicketPushStatus.MOCKED;
    }

    /** 平台侧本地推送编号（通道失败时无 messageId；仍可回溯） */
    private String localPushId() {
        String date = TicketTimes.compactDate();
        return "PUSH-" + date + "-LOCAL-" + String.format("%04d", failedSeq.incrementAndGet());
    }

    private static String truncate(String value) {
        String text = nz(value);
        return text.length() <= MAX_CONTENT_CHARS ? text : text.substring(0, MAX_CONTENT_CHARS) + "…";
    }

    /** 推送正文脱敏：11 位手机号只留前 3/后 4（不打真实号码；日志与 payload 同口径） */
    static String scrub(String value) {
        String text = nz(value);
        if (text.isEmpty()) {
            return text;
        }
        return PHONE_PATTERN.matcher(text).replaceAll(match -> maskPhone(match.group(1)));
    }

    private static String maskPhone(String phone) {
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }

    private static String nz(String value) {
        return value == null ? "" : value.trim();
    }
}
