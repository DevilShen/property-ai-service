package com.property.ai.domain.dunning;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.property.ai.bff.dto.ArrearsItem;
import com.property.ai.bff.dto.Actor;
import com.property.ai.bff.dto.DunningFunnel;
import com.property.ai.bff.dto.DunningFunnelConversion;
import com.property.ai.bff.dto.DunningFunnelPeriod;
import com.property.ai.bff.dto.DunningStatus;
import com.property.ai.connector.ConnectorException;
import com.property.ai.domain.ticket.TicketTimes;
import com.property.ai.security.PermissionMatrix;

/**
 * 催缴转化漏斗（P3-015，契约 {@code GET /api/v1/dunning/funnel} / 产品 FR-BILL-06）。
 *
 * <h2>口径（可解释的数据源）</h2>
 * <pre>
 * 指标       定义                                                   数据源
 * reached    窗口内成功 mock 触达的<b>户数</b>（按 roomCode 去重）      任务发送留痕（status=mocked，跨任务按户聚合）
 * responded  上述户中任务状态 ∈ responded / promised / paid 的户数     任务状态（P3 无业主响应入库端点 → 常态为 0）
 * promised   上述户中任务状态 ∈ promised / paid 的户数                 任务状态（P3 无承诺登记端点 → 常态为 0）
 * paid       上述户中「已缴费」户数                                    ① 任务状态 paid；② ERP 实收核对（优先）：
 *                                                                    该户已触达账期已不在欠费分层台账（outstanding 清零）
 * </pre>
 * <p>窗口：{@code from}—{@code to} 业务日（含端点，Asia/Shanghai）；省略时默认最近 7 天
 * （{@code from = to - 6 天}，{@code to = 当前业务日}）；{@code from > to} → 400 {@code E-1001}。
 * <p>转化率：{@code 分子/分母} 小数，保留 4 位（HALF_UP），分母为 0 记 0；四个比率
 * {@code reachedToResponded / respondedToPromised / promisedToPaid / reachedToPaid}。
 * <p>ERP 核对失败（超时/上游错误）→ 记 WARN 并降级为「仅按任务状态统计 paid」，<b>不伪造缴费数据</b>；
 * 漏斗响应仍 200（读端点，带可解释的 period/generatedAt）。
 * <p>只读端点不落 {@code AuditEvent}；能力 {@code dunning.task}（steward/agent/supervisor）。
 */
@Service
public class DunningFunnelService {

    private static final Logger log = LoggerFactory.getLogger(DunningFunnelService.class);
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;
    /** 默认窗口天数（契约：省略时最近 7 天，含 to） */
    public static final int DEFAULT_WINDOW_DAYS = 7;
    private static final String KEY_SEPARATOR = "|";

    private final DunningTaskService taskService;
    private final DunningArrearsSource arrearsSource;
    private Clock clock = Clock.system(TicketTimes.BUSINESS_ZONE);

    public DunningFunnelService(DunningTaskService taskService, DunningArrearsSource arrearsSource) {
        this.taskService = taskService;
        this.arrearsSource = arrearsSource;
    }

    /** 测试注入固定时钟（默认窗口/生成时间口径用；生产不调用）。 */
    void setClockForTest(Clock clock) {
        this.clock = clock == null ? Clock.system(TicketTimes.BUSINESS_ZONE) : clock;
    }

    /**
     * 漏斗统计（契约 {@code dunningFunnel}）。
     *
     * @param fromRaw {@code YYYY-MM-DD}（可选；默认 {@code to - 6 天}）
     * @param toRaw   {@code YYYY-MM-DD}（可选；默认当前业务日）
     */
    public DunningFunnel funnel(String fromRaw, String toRaw, Actor actor, String traceId) {
        assertCapability(actor);
        LocalDate today = LocalDate.now(clock.withZone(TicketTimes.BUSINESS_ZONE));
        LocalDate to = parseDate(toRaw, "to", today);
        LocalDate from = parseDate(fromRaw, "from", to.minusDays(DEFAULT_WINDOW_DAYS - 1L));
        if (from.isAfter(to)) {
            throw DunningException.badRequest("时间窗非法：from 不能晚于 to",
                    Map.of("field", "from", "from", from.toString(), "to", to.toString()));
        }

        List<DunningTaskRecord> records = taskService.snapshot(traceId);
        List<Activity> activities = new ArrayList<>(records.size());
        for (DunningTaskRecord record : records) {
            // 触达时间取「任务级」成功尝试（含 pushId），同一户多账期任务各自独立，
            // 避免把同户其它账期的触达误算进本账期的「已缴清」判定。
            List<Instant> sends = new ArrayList<>();
            for (DunningTaskRecord.SendAttempt attempt : record.sendAttempts()) {
                if (attempt.success()) {
                    Instant at = TicketTimes.parseInstant(attempt.at());
                    if (at != null) {
                        sends.add(at);
                    }
                }
            }
            activities.add(new Activity(record.roomCode(), record.period(), record.status(), sends));
        }
        return compute(from, to, activities, probeStillOverdue(traceId), clock.instant());
    }

    /**
     * 纯函数漏斗聚合（无 IO，可直接单测）。
     *
     * @param activities       任务活动快照（房号/账期/状态/该户触达时间）
     * @param stillOverdueKeys ERP 当前欠费台账键集合（{@code roomCode|period}）；
     *                         {@code null} = ERP 口径不可用 → 只按任务状态判 paid
     */
    public static DunningFunnel compute(LocalDate from, LocalDate to, List<Activity> activities,
            Set<String> stillOverdueKeys, Instant generatedAt) {
        Instant windowStart = from.atStartOfDay(TicketTimes.BUSINESS_ZONE).toInstant();
        Instant windowEnd = to.plusDays(1).atStartOfDay(TicketTimes.BUSINESS_ZONE).toInstant();
        Map<String, List<Activity>> byRoom = new LinkedHashMap<>();
        if (activities != null) {
            for (Activity activity : activities) {
                if (activity == null || activity.roomCode() == null) {
                    continue;
                }
                byRoom.computeIfAbsent(activity.roomCode(), key -> new ArrayList<>()).add(activity);
            }
        }

        int reached = 0;
        int responded = 0;
        int promised = 0;
        int paid = 0;
        for (Map.Entry<String, List<Activity>> entry : byRoom.entrySet()) {
            String roomCode = entry.getKey();
            List<Activity> roomActivities = entry.getValue();
            Set<String> sentPeriods = new LinkedHashSet<>();
            boolean roomReached = false;
            int bestRank = Integer.MIN_VALUE;
            for (Activity activity : roomActivities) {
                for (Instant sentAt : activity.sentAt()) {
                    if (sentAt != null && !sentAt.isBefore(windowStart) && sentAt.isBefore(windowEnd)) {
                        roomReached = true;
                        sentPeriods.add(activity.period());
                    }
                }
                bestRank = Math.max(bestRank, rank(activity.status()));
            }
            if (!roomReached) {
                continue;
            }
            reached++;
            boolean isResponded = bestRank >= rank(DunningStatus.RESPONDED);
            boolean isPromised = bestRank >= rank(DunningStatus.PROMISED);
            boolean isPaid = bestRank >= rank(DunningStatus.PAID);
            if (!isPaid && stillOverdueKeys != null && !sentPeriods.isEmpty()) {
                // ERP 实收核对：该户窗口内已触达账期都已不在欠费台账 → 视为窗口内缴费（口径见类注释）
                boolean cleared = true;
                for (String period : sentPeriods) {
                    if (period == null || stillOverdueKeys.contains(roomCode + KEY_SEPARATOR + period)) {
                        cleared = false;
                        break;
                    }
                }
                isPaid = cleared;
            }
            if (isResponded) {
                responded++;
            }
            if (isPromised) {
                promised++;
            }
            if (isPaid) {
                paid++;
            }
        }

        DunningFunnelConversion conversion = new DunningFunnelConversion(
                ratio(responded, reached), ratio(promised, responded), ratio(paid, promised), ratio(paid, reached));
        DunningFunnelPeriod period = new DunningFunnelPeriod(from.toString(), to.toString());
        return new DunningFunnel(reached, responded, promised, paid, conversion, period,
                TicketTimes.iso(generatedAt));
    }

    /** 任务状态在漏斗中的推进档位（closed 视同已触达但未响应）。 */
    private static int rank(DunningStatus status) {
        if (status == null) {
            return 0;
        }
        return switch (status) {
            case PENDING -> 0;
            case SCRIPT_READY -> 1;
            case SENT, CLOSED -> 2;
            case RESPONDED -> 3;
            case PROMISED -> 4;
            case PAID -> 5;
        };
    }

    /** 比率（保留 4 位、HALF_UP；分母 0 → 0）。 */
    private static double ratio(int numerator, int denominator) {
        if (denominator <= 0) {
            return 0.0;
        }
        double value = numerator * 1.0d / denominator;
        return Math.round(value * 10_000d) / 10_000d;
    }

    /** ERP 当前欠费台账（{@code roomCode|period} 键集）；失败降级返回 {@code null}（只按任务状态判 paid）。 */
    private Set<String> probeStillOverdue(String traceId) {
        try {
            List<ArrearsItem> rows = arrearsSource.allArrears(traceId);
            Set<String> keys = new LinkedHashSet<>();
            for (ArrearsItem row : rows) {
                if (row != null && row.roomCode() != null && row.period() != null) {
                    keys.add(row.roomCode() + KEY_SEPARATOR + row.period());
                }
            }
            return keys;
        } catch (ConnectorException ex) {
            log.warn("[dunning] 漏斗 ERP 实收核对降级（{}）：仅按任务状态统计 paid，不伪造缴费数据 traceId={}",
                    ex.code().value(), traceId);
            return null;
        }
    }

    private static LocalDate parseDate(String raw, String field, LocalDate fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(raw.trim(), DATE_FORMAT);
        } catch (DateTimeParseException ex) {
            throw DunningException.badRequest(field + " 必须为 YYYY-MM-DD（如 2026-11-08）",
                    Map.of("field", field, "value", raw.trim()));
        }
    }

    private static void assertCapability(Actor actor) {
        if (actor == null) {
            return;
        }
        if (!PermissionMatrix.allows(actor.role(), PermissionMatrix.DUNNING_TASK)) {
            throw DunningException.forbiddenCapability(PermissionMatrix.DUNNING_TASK,
                    actor.role() == null ? "" : actor.role().value());
        }
    }

    /**
     * 任务活动快照（漏斗计算输入；同一户可有多个任务/账期）。
     *
     * @param roomCode 房号
     * @param period   账期 {@code YYYY-MM}
     * @param status   任务当前状态
     * @param sentAt   该户成功触达时间（可含窗口外历史）
     */
    public record Activity(String roomCode, String period, DunningStatus status, List<Instant> sentAt) {

        public Activity {
            sentAt = sentAt == null ? List.of() : List.copyOf(sentAt);
        }
    }
}
