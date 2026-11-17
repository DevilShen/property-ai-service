package com.property.ai.audit;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Service;

import com.property.ai.bff.dto.Actor;
import com.property.ai.bff.dto.ActorRole;
import com.property.ai.bff.dto.AuditAction;
import com.property.ai.bff.dto.AuditEvent;
import com.property.ai.bff.dto.AuditEventListResult;
import com.property.ai.bff.dto.AuditResourceType;
import com.property.ai.bff.dto.AuditResult;
import com.property.ai.domain.project.ProjectService;
import com.property.ai.security.ActorContext;
import com.property.ai.domain.ticket.TicketTimes;

/**
 * 审计服务（P2-031，契约 v0.3.3 {@code /api/v1/audit/events} / 架构 §11.3）。
 *
 * <p>记录「谁（actorId/actorRole）在何时（at）对什么资源（resourceType/resourceId）做了什么（action）、
 * 结果（success|denied|failed）、traceId、ip、maskedFields」。
 *
 * <h2>P2 存储口径（demo，不得说成生产级）</h2>
 * <ul>
 *   <li><b>进程内内存</b>存储，上限 {@value #MAX_EVENTS} 条（超出淘汰最旧），重启清空；
 *       P5 接 ES/对象存储（留存口径不变）。</li>
 *   <li>线程安全：{@link ConcurrentLinkedDeque} 头插（新→旧），分页/过滤无锁快照遍历。</li>
 *   <li>只存脱敏信息：{@code maskedFields} 只列字段名，<b>不存</b>手机号/姓名/金额原文。</li>
 * </ul>
 *
 * <h2>事件 ID / 时间</h2>
 * {@code AUD-yyyyMMdd-NNNN}（按业务时区 Asia/Shanghai 每天从 0001 递增）；{@code at} 为
 * 业务时区 ISO 8601（{@code 2026-11-15T20:20:00+08:00}，与契约 §6 一致）。
 */
@Service
public class AuditService {

    /** 内存上限（契约/任务卡：2000 条，超出淘汰最旧） */
    public static final int MAX_EVENTS = 2000;

    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 审计事件存储（头插：总是新 → 旧；分页按 at 倒序 = 插入顺序） */
    private final Deque<AuditEvent> events = new ConcurrentLinkedDeque<>();
    /**
     * 事件 → 项目归属（P5 W19 多项目隔离）：{@code AuditEvent} 契约 DTO 无 projectId 字段（不改契约），
     * 归属在写审计时按 {@code Actor.projectId} 记录（平台级主体兜底演示默认项目），
     * 供 {@code GET /audit/events?projectId=} 做快照式项目过滤（P5 W22 迁存储后落 project_id 列）。
     */
    private final ConcurrentMap<String, String> projectOfEvent = new ConcurrentHashMap<>();
    private final AtomicInteger sequence = new AtomicInteger();
    private final AtomicReference<String> sequenceDay = new AtomicReference<>("");
    private final Clock clock;

    public AuditService() {
        this(Clock.systemUTC());
    }

    /** 测试可注入固定时钟（用于「超上限淘汰最旧」「分页/traceId」等用例）。 */
    AuditService(Clock clock) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    // ------------------------------------------------------------------
    // 写入
    // ------------------------------------------------------------------

    /**
     * 落一条审计事件（所有写操作/登录登出/403 拒绝都必须调用）。
     *
     * @param record 审计记录（actor/action/resource/traceId/result/ip/maskedFields）
     * @return 落库后的事件（含 eventId/at，便于调用方回带）
     */
    public AuditEvent record(AuditRecord record) {
        return record(record, currentProjectId());
    }

    /**
     * 落一条审计事件（显式项目归属，P5 W19 多项目隔离）：{@code GET /audit/events?projectId=} 按归属过滤。
     *
     * @param projectId 项目归属（{@code null}/空 → 演示默认项目 {@code P-YQY}）
     */
    public AuditEvent record(AuditRecord record, String projectId) {
        Instant now = clock.instant();
        AuditEvent event = new AuditEvent(
                nextEventId(now),
                TicketTimes.iso(now),
                record.actorId(),
                record.actorRole(),
                record.action(),
                record.resourceType(),
                record.resourceId(),
                record.traceId(),
                record.result(),
                record.ip(),
                record.maskedFields() == null || record.maskedFields().isEmpty()
                        ? null
                        : List.copyOf(record.maskedFields()));
        events.addFirst(event);
        projectOfEvent.put(event.eventId(), ProjectService.projectOf(projectId));
        trim();
        return event;
    }

    /** 当前请求主体的项目归属（平台级/离线演示 → 演示默认项目，避免出现无项目审计） */
    private static String currentProjectId() {
        try {
            Actor actor = ActorContext.actorOrNull();
            return ProjectService.projectOf(actor == null ? null : actor.projectId());
        } catch (RuntimeException ex) {
            return ProjectService.DEFAULT_PROJECT_ID;
        }
    }

    private void trim() {
        while (events.size() > MAX_EVENTS) {
            events.pollLast();
        }
    }

    private String nextEventId(Instant at) {
        String day = DAY_FORMAT.format(at.atZone(TicketTimes.BUSINESS_ZONE));
        // 换日重置序号（演示单实例；P5 迁存储后由存储层生成）
        synchronized (sequence) {
            if (!day.equals(sequenceDay.get())) {
                sequenceDay.set(day);
                sequence.set(0);
            }
            return "AUD-" + day + "-" + String.format("%04d", sequence.incrementAndGet());
        }
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /**
     * 分页 + 过滤查询（新→旧；过滤条件全部可选，{@code from}/{@code to} 含端点）。
     *
     * <p>过滤字段：actorId/resourceId 精确匹配；action/resourceType 枚举匹配；from/to 按 at 过滤。
     */
    public AuditEventListResult list(AuditQuery query) {
        return list(query, null);
    }

    /**
     * 分页 + 过滤查询（P5 W19 增量：项目作用域）。
     *
     * @param projectId 项目作用域（{@code null} = 全部；非空时只返回归属该项目的审计事件，
     *                  越权判定由 {@code domain/project/ProjectService} 在 Controller 层先行完成）
     */
    public AuditEventListResult list(AuditQuery query, String projectId) {
        AuditQuery effective = query == null ? AuditQuery.defaults() : query;
        List<AuditEvent> matched = new ArrayList<>();
        for (AuditEvent event : events) {
            if (!matches(event, effective)) {
                continue;
            }
            if (projectId != null && !projectId.isBlank()
                    && !projectId.equals(projectOfEvent.get(event.eventId()))) {
                continue;
            }
            matched.add(event);
        }
        int total = matched.size();
        int page = Math.max(1, effective.page());
        int pageSize = Math.max(1, effective.pageSize());
        int offset = (page - 1) * pageSize;
        if (offset >= total) {
            return new AuditEventListResult(List.of(), page, pageSize, total);
        }
        int end = Math.min(total, offset + pageSize);
        return new AuditEventListResult(List.copyOf(matched.subList(offset, end)), page, pageSize, total);
    }

    /** 按 eventId 查单条（不存在返回空 → Controller 转 404 E-3001）。 */
    public Optional<AuditEvent> find(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return Optional.empty();
        }
        String normalized = eventId.trim();
        for (AuditEvent event : events) {
            if (event.eventId().equals(normalized)) {
                return Optional.of(event);
            }
        }
        return Optional.empty();
    }

    /**
     * 按 {@code traceId} 取全部事件（P4-018 全链路回溯数据源；新 → 旧，由调用方排序）。
     *
     * <p>口径：一次请求的业务事件（写审计/403 拒绝/登录）共享同一 {@code traceId}；
     * 跨请求链路（同一 {@code X-Trace-Id} 手工贯穿调用）也会被聚合到一起，这是回溯要求的
     * 「一条链路多个节点」的能力来源。不存在返回空列表（调用方转 404 {@code E-3001}）。
     */
    public List<AuditEvent> byTraceId(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return List.of();
        }
        String normalized = traceId.trim();
        List<AuditEvent> matched = new ArrayList<>();
        for (AuditEvent event : events) {
            if (normalized.equals(event.traceId())) {
                matched.add(event);
            }
        }
        return List.copyOf(matched);
    }

    /**
     * 全量事件快照（新 → 旧）：P4-018 回溯检索按 {@code traceId} 分组时使用；
     * 上限 {@value #MAX_EVENTS} 条（进程内 demo 存储口径，P5 迁 ES 后改为索引查询）。
     */
    public List<AuditEvent> snapshot() {
        return List.copyOf(events);
    }

    /** 当前事件数（仅诊断/测试用）。 */
    public int size() {
        return events.size();
    }

    private static boolean matches(AuditEvent event, AuditQuery query) {
        if (query.actorId() != null && !query.actorId().equals(event.actorId())) {
            return false;
        }
        if (query.action() != null && query.action() != event.action()) {
            return false;
        }
        if (query.resourceType() != null && query.resourceType() != event.resourceType()) {
            return false;
        }
        if (query.resourceId() != null && !query.resourceId().equals(event.resourceId())) {
            return false;
        }
        if (query.from() != null || query.to() != null) {
            Instant at = TicketTimes.parseInstant(event.at());
            if (at == null) {
                return false;
            }
            if (query.from() != null && at.isBefore(query.from())) {
                return false;
            }
            if (query.to() != null && at.isAfter(query.to())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 审计写入记录（调用方填：谁/什么动作/什么资源/结果/ip/脱敏字段）。
     *
     * @param actorId      操作人（匿名拒绝记 {@code anonymous}）
     * @param actorRole    演示角色
     * @param action       审计动作（契约 11 项）
     * @param resourceType 资源类型（契约 7 项）
     * @param resourceId   资源 ID（工单号/映射 ID/连接器 ID/runId/房号/账号）
     * @param traceId      链路 traceId（与响应信封同源）
     * @param result       结果 success|denied|failed
     * @param ip           客户端 IP（{@link AuditIp#sanitize(String)} 脱敏后）
     * @param maskedFields 已脱敏字段名清单（不含原文）
     */
    public record AuditRecord(String actorId, ActorRole actorRole, AuditAction action,
            AuditResourceType resourceType, String resourceId, String traceId, AuditResult result,
            String ip, List<String> maskedFields) {
    }

    /**
     * 审计查询条件（分页 + 过滤；全部可选）。
     */
    public record AuditQuery(String actorId, AuditAction action, AuditResourceType resourceType,
            String resourceId, Instant from, Instant to, int page, int pageSize) {

        public static AuditQuery defaults() {
            return new AuditQuery(null, null, null, null, null, null, 1, 20);
        }
    }
}
