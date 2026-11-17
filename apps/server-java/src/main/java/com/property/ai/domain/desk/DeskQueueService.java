package com.property.ai.domain.desk;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.property.ai.bff.api.ticket.ApiSupport;
import com.property.ai.bff.dto.Actor;
import com.property.ai.bff.dto.DeskConversationStatus;
import com.property.ai.bff.dto.DeskQueue;
import com.property.ai.bff.dto.DeskQueueItem;
import com.property.ai.bff.dto.DeskQueueSummary;
import com.property.ai.bff.dto.DeskReleaseResult;
import com.property.ai.bff.dto.DeskSla;
import com.property.ai.bff.dto.DeskSlaHour;
import com.property.ai.bff.dto.DeskTakeoverRequest;
import com.property.ai.bff.dto.DeskTakeoverResult;
import com.property.ai.bff.dto.ErrorCode;
import com.property.ai.domain.ticket.DomainException;
import com.property.ai.domain.ticket.TicketTimes;

/**
 * 坐席队列 + 接管/释放 + SLA（P4-012，契约 v1.2.0 {@code /desk/queue*}、{@code /desk/sla}）。
 *
 * <h2>口径（逐条对齐契约 §1.5 / §7.4）</h2>
 * <ul>
 *   <li><b>队列</b>：只列 {@code status=waiting} 会话，按 {@code waitingSeconds} 降序；
 *       {@code waitingSeconds}/{@code slaDueAt} 由服务端计算（前端只做倒计时）；</li>
 *   <li><b>接管互斥</b>：同一会话同时只允许一个坐席 → 已被接管 409 {@code E-3002}
 *       （{@code details.reason=already_taken, currentAgent}）；</li>
 *   <li><b>释放</b>：只有当前接管人可释放（非本人 403 {@code E-2001}）；回队列
 *       （{@code status=waiting}）或结束（{@code closed}），状态不允许 → 409 {@code E-3002}；</li>
 *   <li><b>SLA</b>：首响 = 坐席第一条人工回复（demo 以「接管时间」近似）；
 *       {@code breachRate = 超时会话 / 窗口内会话}（分母 0 记 0，不编造）；{@code byHour} 0—23 时缺数据记 0。</li>
 * </ul>
 *
 * <p><b>权限</b>：端点级由 {@code PermissionMatrix}（{@code desk.queue/desk.takeover/desk.release/desk.sla}）
 * 统一拦截，Controller/Service 不写角色判断；写操作审计 {@code desk.takeover} 由 {@code AuditFilter}
 * 按端点规则落库（{@code resourceType=conversation}、{@code resourceId=conversationId}）。
 */
@Service
public class DeskQueueService {

    private static final Logger log = LoggerFactory.getLogger(DeskQueueService.class);

    private final DeskConversationRepository repository;

    public DeskQueueService(DeskConversationRepository repository) {
        this.repository = repository;
    }

    // ==================================================================
    // 队列
    // ==================================================================

    /** 坐席队列（按等待时长降序）+ 顶部汇总指标。 */
    public DeskQueue queue(Actor actor) {
        Instant now = repository.clock().instant();
        List<DeskQueueItem> items = new ArrayList<>();
        int waiting = 0;
        long waitSum = 0;
        int breach = 0;
        for (DeskConversation conversation : repository.all()) {
            if (conversation.status() != DeskConversationStatus.WAITING) {
                continue;
            }
            int waitingSeconds = waitingSeconds(conversation, now);
            boolean breached = conversation.slaBreached(now);
            waiting++;
            waitSum += waitingSeconds;
            if (breached) {
                breach++;
            }
            items.add(new DeskQueueItem(
                    conversation.conversationId(),
                    conversation.roomCode(),
                    conversation.ownerNameMasked(),
                    conversation.channel(),
                    waitingSeconds,
                    TicketTimes.iso(conversation.slaDueAt()),
                    conversation.status(),
                    conversation.preview()));
        }
        items.sort(Comparator.comparingInt(DeskQueueItem::waitingSeconds).reversed()
                .thenComparing(DeskQueueItem::conversationId));
        int avgWaitSeconds = waiting == 0 ? 0 : (int) Math.round((double) waitSum / waiting);
        log.debug("[desk] queue role={} waiting={} breach={} avgWait={}",
                actor == null ? "anonymous" : actor.role(), waiting, breach, avgWaitSeconds);
        return new DeskQueue(List.copyOf(items), new DeskQueueSummary(waiting, avgWaitSeconds, breach));
    }

    /** 已等待秒数（已接管/已结束会话取接管时刻，避免接管后继续计时）。 */
    private static int waitingSeconds(DeskConversation conversation, Instant now) {
        Instant end = conversation.takenAt() != null ? conversation.takenAt()
                : conversation.releasedAt() != null ? conversation.releasedAt() : now;
        long seconds = end.getEpochSecond() - conversation.enqueuedAt().getEpochSecond();
        return (int) Math.max(0L, seconds);
    }

    // ==================================================================
    // 接管 / 释放
    // ==================================================================

    /**
     * 接管会话（写操作）：{@code waiting → taken_over}；已被他人（或任何人）接管 → 409 {@code E-3002}。
     *
     * @param note 接管备注（可选，落审计的脱敏字段名清单已登记，正文不进日志）
     */
    public DeskTakeoverResult takeover(String conversationId, DeskTakeoverRequest request, Actor actor,
            String traceId) {
        if (conversationId == null || conversationId.isBlank()) {
            throw DomainException.badRequest("conversationId 必填", Map.of("field", "conversationId",
                    "required", true));
        }
        DeskConversation conversation = require(conversationId);
        if (conversation.status() == DeskConversationStatus.TAKEN_OVER) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("reason", "already_taken");
            details.put("conversationId", conversation.conversationId());
            details.put("currentAgent", conversation.agentId() == null ? "" : conversation.agentId());
            throw DomainException.conflict("该会话已被坐席接管，请刷新队列", details);
        }
        if (conversation.status() == DeskConversationStatus.CLOSED) {
            throw DomainException.conflict("该会话已结束，不能接管", Map.of(
                    "reason", "invalid_status",
                    "conversationId", conversation.conversationId(),
                    "status", conversation.status().value(),
                    "allowed", List.of("waiting")));
        }
        String agentId = actor == null || actor.actorId() == null || actor.actorId().isBlank()
                ? "agent"
                : actor.actorId();
        Instant now = repository.clock().instant();
        conversation.markTakenOver(agentId, now);
        log.info("[desk] takeover conversationId={} agent={} note={} traceId={}", conversation.conversationId(),
                agentId, request != null && request.note() != null, traceId);
        return new DeskTakeoverResult(conversation.conversationId(), agentId, TicketTimes.iso(now),
                conversation.status());
    }

    /** 释放会话（写操作）：仅当前接管人可释放，{@code taken_over → waiting}。 */
    public DeskReleaseResult release(String conversationId, Actor actor) {
        if (conversationId == null || conversationId.isBlank()) {
            throw DomainException.badRequest("conversationId 必填", Map.of("field", "conversationId",
                    "required", true));
        }
        DeskConversation conversation = require(conversationId);
        if (conversation.status() != DeskConversationStatus.TAKEN_OVER) {
            throw DomainException.conflict("该会话当前状态不允许释放", Map.of(
                    "reason", "invalid_status",
                    "conversationId", conversation.conversationId(),
                    "status", conversation.status().value(),
                    "allowed", List.of("taken_over")));
        }
        String actorId = actor == null ? null : actor.actorId();
        if (actorId == null || !actorId.equals(conversation.agentId())) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("reason", "not_owner");
            details.put("conversationId", conversation.conversationId());
            details.put("currentAgent", conversation.agentId() == null ? "" : conversation.agentId());
            throw DomainException.forbidden("只能释放自己接管的会话", details);
        }
        Instant now = repository.clock().instant();
        conversation.markWaiting(now);
        log.info("[desk] release conversationId={} agent={}", conversation.conversationId(), actorId);
        return new DeskReleaseResult(conversation.conversationId(), TicketTimes.iso(now),
                conversation.status());
    }

    private DeskConversation require(String conversationId) {
        return repository.find(conversationId).orElseThrow(() -> DomainException.notFound(ErrorCode.E_3001,
                "会话不存在：" + conversationId, Map.of("conversationId", conversationId)));
    }

    // ==================================================================
    // SLA
    // ==================================================================

    /**
     * 坐席 SLA（{@code GET /desk/sla?from=&to=}）：首响均值/超时率/分时明细。
     *
     * @param fromParam 统计起点（{@code YYYY-MM-DD}，省略 = 今天 00:00）
     * @param toParam   统计终点（{@code YYYY-MM-DD}，含当日，省略 = 今天）
     */
    public DeskSla sla(String fromParam, String toParam) {
        ZoneId zone = TicketTimes.BUSINESS_ZONE;
        LocalDate today = LocalDate.now(repository.clock().withZone(zone));
        LocalDate from = parseDate("from", fromParam, today);
        LocalDate to = parseDate("to", toParam, today);
        if (from.isAfter(to)) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("field", "from");
            details.put("reason", "invalid_range");
            details.put("from", from.toString());
            details.put("to", to.toString());
            throw DomainException.badRequest("from 不能晚于 to", details);
        }
        Instant windowStart = LocalDateTime.of(from, LocalTime.MIN).atZone(zone).toInstant();
        Instant windowEnd = LocalDateTime.of(to.plusDays(1), LocalTime.MIN).atZone(zone).toInstant();
        Instant now = repository.clock().instant();

        long[] responseSum = new long[24];
        int[] responseCount = new int[24];
        int[] breachCount = new int[24];
        int total = 0;
        int breachTotal = 0;
        long allResponseSum = 0;
        int responded = 0;
        for (DeskConversation conversation : repository.all()) {
            Instant enqueuedAt = conversation.enqueuedAt();
            if (enqueuedAt.isBefore(windowStart) || !enqueuedAt.isBefore(windowEnd)) {
                continue;
            }
            total++;
            int hour = enqueuedAt.atZone(zone).getHour();
            boolean breached = conversation.slaBreached(now);
            if (breached) {
                breachTotal++;
                breachCount[hour]++;
            }
            if (conversation.takenAt() != null) {
                long seconds = Math.max(0L,
                        conversation.takenAt().getEpochSecond() - enqueuedAt.getEpochSecond());
                responseSum[hour] += seconds;
                responseCount[hour]++;
                allResponseSum += seconds;
                responded++;
            }
        }
        List<DeskSlaHour> byHour = new ArrayList<>(24);
        for (int hour = 0; hour < 24; hour++) {
            int avg = responseCount[hour] == 0 ? 0 : (int) Math.round((double) responseSum[hour]
                    / responseCount[hour]);
            byHour.add(new DeskSlaHour(hour, avg, breachCount[hour]));
        }
        int avgFirstResponseSeconds = responded == 0 ? 0 : (int) Math.round((double) allResponseSum / responded);
        double breachRate = total == 0 ? 0.0 : Math.round((double) breachTotal / total * 10000.0) / 10000.0;
        log.debug("[desk] sla window={}~{} total={} responded={} breach={}", from, to, total, responded,
                breachTotal);
        return new DeskSla(avgFirstResponseSeconds, breachRate, List.copyOf(byHour));
    }

    /** SLA 口径常量（报告/测试断言用）：首响 SLA 窗口秒数。 */
    public int slaFirstResponseSeconds() {
        return repository.slaFirstResponseSeconds();
    }

    private static LocalDate parseDate(String field, String raw, LocalDate fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException ex) {
            throw DomainException.badRequest(field + " 必须是 YYYY-MM-DD 日期",
                    Map.of("field", field, "value", raw, "format", "YYYY-MM-DD"));
        }
    }

    /** 分页/能力工具复用（保持与既有端点一致的分页口径）。 */
    public static void requirePageParams(int page, int pageSize) {
        ApiSupport.requirePageParams(page, pageSize);
    }
}
