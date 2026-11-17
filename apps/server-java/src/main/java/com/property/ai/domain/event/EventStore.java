package com.property.ai.domain.event;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.property.ai.bff.dto.EventEnvelope;
import com.property.ai.bff.dto.EventListResult;
import com.property.ai.bff.dto.EventStatus;
import com.property.ai.bff.dto.EventType;
import com.property.ai.connector.ConnectorTimes;

/**
 * 内存 outbox 事件存储（P5-019，契约 v1.3.0 {@code EventEnvelope} / {@code GET /events}，
 * 架构 §24.6「事务性 outbox + 至少一次投递 + 消费幂等 + 死信队列」）。
 *
 * <h2>范围与边界（demo 口径，不得表述为生产就绪）</h2>
 * <ul>
 *   <li><b>进程内内存实现</b>（上限 {@value #MAX_EVENTS} 条，超出淘汰最旧，重启清空）；
 *       生产由 PG {@code events_outbox}（{@code docs/ops/PG-schema.sql}）+ Kafka 投递替换，
 *       见 {@code docs/ops/Kafka-迁移方案.md}；</li>
 *   <li>写操作挂钩（ticket.created/transitioned/feedback、complaint.created、dunning.sent、
 *       knowledge.changed、recon.completed、gray.policy.updated）通过
 *       {@code @Autowired(required=false)} setter 注入，既有单测直接 new 领域服务不受影响；</li>
 *   <li><b>不伪造投递结果</b>：落 outbox 时恒为 {@link EventStatus#PENDING}；只有
 *       {@link #markPublished(String)}（由 {@code com.property.ai.event.EventPublisher} 投递成功后调用）
 *       才会变成 {@code published}，内存 publisher 也非 Kafka 投递。</li>
 *   <li><b>payload 脱敏</b>：只放业务主键与枚举/编号（ticketNo / roomCode / status / policyId 等），
 *       不放手机号、证件号、姓名原文、金额明细原文。</li>
 * </ul>
 *
 * <h2>P5 W21 状态机（task-66 / P5-019，lead 批准的范围扩展）</h2>
 * <pre>
 * pending ──投递成功──► published（终态）
 *    │  └─投递失败──► failed ──retry──► pending ──投递成功──► published
 *    │                   │  └── markDead（超限/人工）──► dead（DLQ，可查 dlq()、可 replay 重放）
 *    └────────────────────────────────────────────────► dead
 * dead ──replay（人工重放，retryCount 归零）──► pending
 * </pre>
 * 非法转换一律返回 {@link Optional#empty()} 并 WARN（不静默改状态）。
 * 对外 {@code publish/list/counts/size/clear} 签名与语义保持 P5 W19/W20 兼容。
 *
 * <p>事件 ID：{@code EVT-yyyyMMdd-NNNN}（业务时区 Asia/Shanghai，每天从 0001 递增；与契约示例一致）。
 */
@Service
public class EventStore {

    private static final Logger log = LoggerFactory.getLogger(EventStore.class);

    /** 内存上限（契约/任务卡：1000 条，超出淘汰最旧） */
    public static final int MAX_EVENTS = 1000;

    /**
     * 内部 outbox 记录（可变：status / retryCount / lastError 由状态机更新；业务字段只读）。
     *
     * <p>为什么不用 {@link EventEnvelope} 直接存：契约 DTO 是 record（不可变），状态推进需要原地更新；
     * 对外仍只暴露不可变 {@link EventEnvelope}（{@link #toEnvelope()}），DTO/契约零改动。
     */
    private static final class OutboxRecord {
        final String eventId;
        final EventType type;
        final String projectId;
        final Map<String, Object> payload;
        final String occurredAt;
        volatile EventStatus status = EventStatus.PENDING;
        volatile int retryCount;
        volatile String lastError;

        OutboxRecord(String eventId, EventType type, String projectId, Map<String, Object> payload,
                String occurredAt) {
            this.eventId = eventId;
            this.type = type;
            this.projectId = projectId;
            this.payload = payload;
            this.occurredAt = occurredAt;
        }

        EventEnvelope toEnvelope() {
            return new EventEnvelope(eventId, type, projectId, payload, occurredAt, status);
        }
    }

    /** 记录存储（头插：总是新 → 旧；查询按 occurredAt 倒序 = 插入顺序） */
    private final Deque<OutboxRecord> events = new ConcurrentLinkedDeque<>();
    private final AtomicInteger sequence = new AtomicInteger();
    private final AtomicReference<String> sequenceDay = new AtomicReference<>("");

    // ------------------------------------------------------------------
    // 写入（领域写操作挂钩）
    // ------------------------------------------------------------------

    /**
     * 落一条 outbox 事件（写操作成功后调用；幂等由业务侧幂等键保证，本方法只追加）。
     *
     * @param type      事件类型（契约 {@link EventType}）
     * @param projectId 项目归属（空 → 演示默认项目 P-YQY，避免出现无项目事件）
     * @param payload   脱敏业务载荷（见类注释；调用方不得放手机号/证件号/姓名原文）
     */
    public EventEnvelope publish(EventType type, String projectId, Map<String, Object> payload) {
        String occurredAt = ConnectorTimes.nowIso();
        OutboxRecord record = new OutboxRecord(nextEventId(occurredAt), type,
                projectId == null || projectId.isBlank()
                        ? com.property.ai.domain.project.ProjectService.DEFAULT_PROJECT_ID
                        : projectId,
                payload == null ? Map.of() : new LinkedHashMap<>(payload), occurredAt);
        events.addFirst(record);
        trim();
        log.info("[event] {} eventId={} projectId={} payloadKeys={}", type.value(), record.eventId,
                record.projectId, record.payload.keySet());
        return record.toEnvelope();
    }

    /** 便捷：省略 payload（只记事件发生） */
    public EventEnvelope publish(EventType type, String projectId) {
        return publish(type, projectId, Map.of());
    }

    private void trim() {
        while (events.size() > MAX_EVENTS) {
            events.pollLast();
        }
    }

    private String nextEventId(String occurredAt) {
        OffsetDateTime at = ConnectorTimes.parseIsoOrNull(occurredAt);
        String day = at == null
                ? ConnectorTimes.todayCompact()
                : String.format("%04d%02d%02d", at.getYear(), at.getMonthValue(), at.getDayOfMonth());
        synchronized (sequence) {
            if (!day.equals(sequenceDay.get())) {
                sequenceDay.set(day);
                sequence.set(0);
            }
            return "EVT-" + day + "-" + String.format("%04d", sequence.incrementAndGet());
        }
    }

    // ------------------------------------------------------------------
    // 状态机（P5 W21 / P5-019）
    // ------------------------------------------------------------------

    /**
     * 按 eventId 查询事件（不存在返回 empty）。
     *
     * @param eventId {@code EVT-yyyyMMdd-NNNN}
     */
    public Optional<EventEnvelope> find(String eventId) {
        OutboxRecord record = findRecord(eventId);
        return record == null ? Optional.empty() : Optional.of(record.toEnvelope());
    }

    /** 按状态查询（新→旧；供 DLQ/pending 视图与 outbox 运维） */
    public List<EventEnvelope> byStatus(EventStatus status) {
        if (status == null) {
            return List.of();
        }
        List<EventEnvelope> matched = new ArrayList<>();
        for (OutboxRecord record : events) {
            if (record.status == status) {
                matched.add(record.toEnvelope());
            }
        }
        return List.copyOf(matched);
    }

    /** 死信队列（{@code status=dead}，新→旧；DLQ 可查、可重放） */
    public List<EventEnvelope> dlq() {
        return byStatus(EventStatus.DEAD);
    }

    /**
     * 手动重试一次：{@code pending|failed|dead → pending}，重试计数 +1（投递动作由
     * {@code com.property.ai.event.OutboxStore#retry} 调用 publisher 后回写 published/failed）。
     *
     * @return 更新后的事件；事件不存在或状态不允许时返回 empty（published 为终态，重复重试应幂等跳过）
     */
    public Optional<EventEnvelope> retry(String eventId) {
        OutboxRecord record = findRecord(eventId);
        if (record == null) {
            return Optional.empty();
        }
        return transition(record, EventStatus.PENDING, "retry", true);
    }

    /**
     * 标记投递成功：{@code pending|failed → published}（终态）。
     *
     * <p>只由 {@code EventPublisher} 的成功回调调用；内存 publisher 的 published 仅代表「进程内受理」，
     * 不等于 Kafka 投递。
     */
    public Optional<EventEnvelope> markPublished(String eventId) {
        OutboxRecord record = findRecord(eventId);
        if (record == null) {
            return Optional.empty();
        }
        return transition(record, EventStatus.PUBLISHED, null, false);
    }

    /**
     * 标记投递失败：{@code pending|failed → failed}（可重试），记录 lastError（脱敏原因，不含敏感数据）。
     */
    public Optional<EventEnvelope> markFailed(String eventId, String error) {
        OutboxRecord record = findRecord(eventId);
        if (record == null) {
            return Optional.empty();
        }
        Optional<EventEnvelope> updated = transition(record, EventStatus.FAILED,
                error == null ? "投递失败（未提供原因）" : error, false);
        return updated;
    }

    /**
     * 移入死信队列：{@code pending|failed → dead}（超重试上限/人工移入），记录 lastError。
     *
     * <p>dead 事件不静默丢失：{@link #dlq()} 可查，{@link #replay(String)} 可重放。
     */
    public Optional<EventEnvelope> markDead(String eventId, String reason) {
        OutboxRecord record = findRecord(eventId);
        if (record == null) {
            return Optional.empty();
        }
        return transition(record, EventStatus.DEAD, reason == null ? "移入死信队列（未提供原因）" : reason, false);
    }

    /**
     * 死信重放：{@code dead → pending}，重试计数归零（人工确认可重放后调用）。
     */
    public Optional<EventEnvelope> replay(String eventId) {
        OutboxRecord record = findRecord(eventId);
        if (record == null) {
            return Optional.empty();
        }
        if (record.status != EventStatus.DEAD) {
            log.warn("[event] 非法状态转换：replay 仅允许 dead → pending，eventId={} status={}",
                    eventId, record.status.value());
            return Optional.empty();
        }
        synchronized (record) {
            record.status = EventStatus.PENDING;
            record.retryCount = 0;
            record.lastError = null;
            log.info("[event] 死信重放 eventId={} → pending（重试计数归零）", eventId);
            return Optional.of(record.toEnvelope());
        }
    }

    /** 已重试次数（不存在为 0） */
    public int retryCount(String eventId) {
        OutboxRecord record = findRecord(eventId);
        return record == null ? 0 : record.retryCount;
    }

    /** 最近一次失败/死信原因（无则 null；脱敏文本） */
    public String lastError(String eventId) {
        OutboxRecord record = findRecord(eventId);
        return record == null ? null : record.lastError;
    }

    private Optional<EventEnvelope> transition(OutboxRecord record, EventStatus target, String error,
            boolean countAttempt) {
        synchronized (record) {
            if (!canTransition(record.status, target)) {
                log.warn("[event] 非法状态转换：{} → {}，eventId={}（已忽略）", record.status.value(),
                        target.value(), record.eventId);
                return Optional.empty();
            }
            record.status = target;
            if (countAttempt) {
                record.retryCount++;
            }
            if (error != null) {
                record.lastError = error;
            }
            return Optional.of(record.toEnvelope());
        }
    }

    /**
     * 状态机合法转换表：
     * <ul>
     *   <li>{@code → pending}：failed / dead / pending（重试；dead 重放走 {@link #replay(String)} 归零计数）；</li>
     *   <li>{@code → published}：pending / failed（投递成功，终态）；</li>
     *   <li>{@code → failed}：pending / failed（投递失败，可重试）；</li>
     *   <li>{@code → dead}：pending / failed（超限或人工移入 DLQ）。</li>
     * </ul>
     */
    private static boolean canTransition(EventStatus from, EventStatus target) {
        return switch (target) {
            case PENDING -> from != EventStatus.PUBLISHED;
            case PUBLISHED, FAILED, DEAD -> from == EventStatus.PENDING || from == EventStatus.FAILED;
        };
    }

    private OutboxRecord findRecord(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return null;
        }
        for (OutboxRecord record : events) {
            if (record.eventId.equals(eventId)) {
                return record;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 查询（GET /events）
    // ------------------------------------------------------------------

    /**
     * 分页 + 过滤查询（新 → 旧）。
     *
     * @param type      事件类型（null = 全部）
     * @param projectId 项目作用域（null = 全部；调用方已做越权校验）
     * @param from      起始时间（含，按 occurredAt；null = 不限）
     * @param to        结束时间（含，按 occurredAt；null = 不限）
     */
    public EventListResult list(EventType type, String projectId, OffsetDateTime from, OffsetDateTime to, int page,
            int pageSize) {
        List<EventEnvelope> matched = new ArrayList<>();
        for (OutboxRecord record : events) {
            if (type != null && record.type != type) {
                continue;
            }
            if (projectId != null && !projectId.isBlank() && !projectId.equals(record.projectId)) {
                continue;
            }
            if (from != null || to != null) {
                OffsetDateTime occurredAt = ConnectorTimes.parseIsoOrNull(record.occurredAt);
                if (occurredAt == null) {
                    continue;
                }
                if (from != null && occurredAt.isBefore(from)) {
                    continue;
                }
                if (to != null && occurredAt.isAfter(to)) {
                    continue;
                }
            }
            matched.add(record.toEnvelope());
        }
        int total = matched.size();
        int effectivePage = Math.max(1, page);
        int effectivePageSize = Math.max(1, pageSize);
        int offset = (effectivePage - 1) * effectivePageSize;
        if (offset >= total) {
            return new EventListResult(List.of(), effectivePage, effectivePageSize, total);
        }
        int end = Math.min(total, offset + effectivePageSize);
        return new EventListResult(List.copyOf(matched.subList(offset, end)), effectivePage, effectivePageSize, total);
    }

    /** 当前事件数（诊断/测试用） */
    public int size() {
        return events.size();
    }

    /**
     * 事件状态计数快照（P5 W20 可观测性，{@code GET /api/v1/observability/metrics} 的 {@code events{...}}；
     * P5 W21 起反映真实状态机：只有投递成功才会出现 published，超限/人工移入才会出现 dead）。
     */
    public EventCounts counts() {
        long pending = 0;
        long published = 0;
        long failed = 0;
        long dead = 0;
        for (OutboxRecord record : events) {
            switch (record.status) {
                case PENDING -> pending++;
                case PUBLISHED -> published++;
                case FAILED -> failed++;
                case DEAD -> dead++;
            }
        }
        return new EventCounts(pending, published, failed, dead);
    }

    /** 事件状态计数（pending 待投递 / published 已投递 / failed 可重试失败 / dead 死信） */
    public record EventCounts(long pending, long published, long failed, long dead) {
    }

    /** 清空（仅测试/演示重置用；生产事件存储不允许运行时清空） */
    public void clear() {
        events.clear();
        sequence.set(0);
        sequenceDay.set("");
    }
}
