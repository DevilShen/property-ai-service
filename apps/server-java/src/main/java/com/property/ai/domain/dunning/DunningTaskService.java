package com.property.ai.domain.dunning;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.property.ai.audit.AuditIp;
import com.property.ai.audit.AuditService;
import com.property.ai.bff.dto.Actor;
import com.property.ai.bff.dto.ActorRole;
import com.property.ai.bff.dto.ArrearsBucket;
import com.property.ai.bff.dto.ArrearsItem;
import com.property.ai.bff.dto.AuditAction;
import com.property.ai.bff.dto.AuditResourceType;
import com.property.ai.bff.dto.AuditResult;
import com.property.ai.bff.dto.DunningChannel;
import com.property.ai.bff.dto.DunningCloseRequest;
import com.property.ai.bff.dto.DunningCloseResult;
import com.property.ai.bff.dto.DunningFrequencyCheck;
import com.property.ai.bff.dto.DunningScript;
import com.property.ai.bff.dto.DunningScriptRequest;
import com.property.ai.bff.dto.DunningSendRequest;
import com.property.ai.bff.dto.DunningSendResult;
import com.property.ai.bff.dto.DunningStatus;
import com.property.ai.bff.dto.DunningTask;
import com.property.ai.bff.dto.DunningTaskListResult;
import com.property.ai.bff.dto.DunningTone;
import com.property.ai.bff.dto.EventType;
import com.property.ai.connector.ConnectorException;
import com.property.ai.domain.event.EventStore;
import com.property.ai.domain.project.ProjectService;
import com.property.ai.domain.ticket.TicketTimes;
import com.property.ai.security.PermissionMatrix;

/**
 * 催缴任务领域服务（P3-012/013/014，契约 v1.1.0 {@code /api/v1/dunning/tasks*}）。
 *
 * <h2>任务生成口径（契约未定义 generate 端点 → 惰性生成，不新增端点）</h2>
 * 契约 v1.1.0 只有 {@code GET /dunning/tasks}、详情、{@code script}/{@code send}/{@code close}、{@code funnel}，
 * <b>没有</b>「生成任务」端点；因此本服务在<b>首次读/写任务时从欠费分层数据惰性生成</b>（进程内一次快照）：
 * <ol>
 *   <li>数据源：{@link DunningArrearsSource#allArrears}（= {@code GET /fees/arrears} 同口径 ERP 分层行：
 *       {@code outstanding > 0 && overdueDays > 0}，分层由 ERP {@code arrearsBucket} 或 {@code overdueDays} 推导）；</li>
 *   <li>一行欠费（房号+账期）→ 一个任务：{@code taskId=DUN-yyyyMM-NNNN}、{@code status=pending}、
 *       {@code ownerNameMasked}/{@code period}/{@code outstanding}/{@code bucket} 原样取 ERP；
 *       {@code channel}/{@code dueAt}/{@code assignedTo} 按 {@link DunningPolicy} 分层策略表；</li>
 *   <li>排序：分层严重度降序（D30 优先）+ 房号 + 账期；负责人按生成序号轮转（岗位名，不派真实工单）；</li>
 *   <li>生成失败（ERP 超时/错误）→ 不置位生成标记，向上抛 {@link ConnectorException} → BFF 503 + degraded，
 *       <b>不伪造任务</b>；</li>
 *   <li>demo 存储：进程内 {@link ConcurrentHashMap}（重启清空、重新生成）；P5 迁 PostgreSQL 后任务由定时作业生成。</li>
 * </ol>
 *
 * <h2>动作与红线</h2>
 * <ul>
 *   <li><b>script</b>：生成话术 + 禁区扫描 + 版本号（{@code v1→v2…}）；生成≠外发；已关闭/已缴费 409 {@code E-3002}；</li>
 *   <li><b>send</b>：{@code confirmed:true} 必填（否则 400 {@code E-1001} {@code confirmation_required}）；
 *       话术版本必须与任务最近版本一致（否则 409 {@code E-3002} {@code stale_script}）；
 *       {@code forbiddenHits} 非空拒发（409 {@code E-3002} {@code forbidden_hits}）；
 *       频次红线（单户 7 天 ≤2 次）超限 409 {@code E-3003} {@code frequency_redline}；
 *       成功 → {@link DunningDispatcher} 调 mock-channel，回 {@code DunningSendResult{status:mocked}}；
 *       mock 通道失败 → 记 failed 尝试 + 审计 result=failed，抛 {@link ConnectorException} → 503（不伪造成功）；</li>
 *   <li><b>close</b>：{@code reason} 必填，只改任务状态（不写财务数据）；重复关闭 409 {@code E-3002} {@code already_closed}；</li>
 *   <li><b>审计</b>：{@code dunning.script} / {@code dunning.send} / {@code dunning.close} 显式落
 *       {@link AuditService}（resourceType={@code dunning}、resourceId=taskId、traceId 同源、失败 result=failed）。</li>
 * </ul>
 *
 * <p><b>数据范围（demo 口径）</b>：能力 {@code dunning.task} 由 {@code SecurityFilter} +
 * {@link PermissionMatrix} 统一执行（owner/it/admin 403 {@code E-2001}）；steward「本片区」在 P3 单项目 demo 中等价
 * 全量（片区/楼栋维度数据范围随 P3-028 落地，已在报告列遗留风险）。本服务另做一次能力复校（防御纵深）。
 *
 * <p><b>并发</b>：频次校验 + 发送按房号加锁（同一户不会因并发超发）；其余状态字段为 volatile + 记录内 synchronized。
 */
@Service
public class DunningTaskService {

    private static final Logger log = LoggerFactory.getLogger(DunningTaskService.class);

    /** 契约分页上限（{@code pageSize} 1—100，Controller 已校验，这里再防御） */
    private static final int MAX_PAGE_SIZE = 100;
    /** 任务 ID 前缀（契约示例 {@code DUN-202611-0001}） */
    private static final String TASK_ID_PREFIX = "DUN-";
    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyyMM");
    private static final String IDEMPOTENCY_KEY_FIELD = "Idempotency-Key";
    private static final int IDEMPOTENCY_KEY_MIN = 8;
    private static final int IDEMPOTENCY_KEY_MAX = 64;

    /** 任务仓储（taskId → record；demo 内存） */
    private final ConcurrentMap<String, DunningTaskRecord> tasks = new ConcurrentHashMap<>();
    /** 单户成功触达时间（频次窗口口径：按房号聚合，跨任务） */
    private final ConcurrentMap<String, List<Instant>> roomSendTimes = new ConcurrentHashMap<>();
    /** 单户发送互斥锁（并发 send 不超发） */
    private final ConcurrentMap<String, Object> roomLocks = new ConcurrentHashMap<>();
    /** 幂等键 → 已受理发送（重放同载荷返回首次结果；不同载荷 409 E-3003） */
    private final ConcurrentMap<String, IdempotentSend> idempotentSends = new ConcurrentHashMap<>();

    private final Object generationLock = new Object();
    private final AtomicBoolean generated = new AtomicBoolean(false);
    private final AtomicInteger taskSequence = new AtomicInteger();
    private final AtomicReference<String> taskSequenceMonth = new AtomicReference<>("");

    private final DunningArrearsSource arrearsSource;
    private final DunningScriptGenerator scriptGenerator;
    private final DunningDispatcher dispatcher;
    private final AuditService auditService;
    /** outbox 事件存储（P5-019；可选注入：保持既有单测构造不破，未注入时只记日志） */
    private EventStore eventStore;

    private Clock clock = Clock.system(TicketTimes.BUSINESS_ZONE);
    private volatile String generatedAt;
    private volatile int generatedRows;

    /** 事件存储注入（可选：P5-019 outbox；单测不构造时为 null） */
    @Autowired(required = false)
    public void setEventStore(EventStore eventStore) {
        this.eventStore = eventStore;
    }

    public DunningTaskService(DunningArrearsSource arrearsSource, DunningScriptGenerator scriptGenerator,
            DunningDispatcher dispatcher, AuditService auditService) {
        this.arrearsSource = arrearsSource;
        this.scriptGenerator = scriptGenerator;
        this.dispatcher = dispatcher;
        this.auditService = auditService;
    }

    /** 测试注入固定时钟（频次窗口/生成时间口径用；生产不调用）。 */
    void setClockForTest(Clock clock) {
        this.clock = clock == null ? Clock.system(TicketTimes.BUSINESS_ZONE) : clock;
    }

    /** 测试复位（清空任务/触达/幂等与生成标记，模拟进程重启）。 */
    void resetForTest() {
        tasks.clear();
        roomSendTimes.clear();
        roomLocks.clear();
        idempotentSends.clear();
        generated.set(false);
        taskSequence.set(0);
        taskSequenceMonth.set("");
        generatedAt = null;
        generatedRows = 0;
    }

    // ==================================================================
    // 列表 / 详情（GET）
    // ==================================================================

    /**
     * 任务列表（契约 {@code dunningTaskList}：status/bucket/assignee 筛选 + 分页 + {@code frequencyCheck}）。
     *
     * @param status   任务状态 code（{@code null}/空 = 全部；非法 → 400 {@code E-1002}）
     * @param bucket   逾期分层 code（{@code null}/空 = 全部；非法 → 400 {@code E-1002}）
     * @param assignee 负责人（精确匹配；{@code null}/空 = 不限）
     */
    public DunningTaskListResult list(String status, String bucket, String assignee, int page, int pageSize,
            Actor actor, String traceId) {
        assertCapability(actor);
        ensureGenerated(traceId);
        DunningStatus statusFilter = parseStatus(status);
        ArrearsBucket bucketFilter = parseBucket(bucket);
        String assigneeFilter = blankToNull(assignee);

        List<DunningTaskRecord> filtered = new ArrayList<>();
        for (DunningTaskRecord record : tasks.values()) {
            if (statusFilter != null && record.status() != statusFilter) {
                continue;
            }
            if (bucketFilter != null && record.bucket() != bucketFilter) {
                continue;
            }
            if (assigneeFilter != null && !assigneeFilter.equals(record.assignedTo())) {
                continue;
            }
            filtered.add(record);
        }
        filtered.sort(TASK_ORDER);

        int safePage = Math.max(1, page);
        int safePageSize = Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);
        int from = Math.min((safePage - 1) * safePageSize, filtered.size());
        int to = Math.min(from + safePageSize, filtered.size());
        Instant now = clock.instant();
        List<DunningTask> items = new ArrayList<>(to - from);
        for (DunningTaskRecord record : filtered.subList(from, to)) {
            items.add(record.toDto(frequencyCheck(record.roomCode(), now)));
        }
        return new DunningTaskListResult(List.copyOf(items), safePage, safePageSize, filtered.size());
    }

    /** 任务详情（契约 {@code dunningTaskGet}；不存在 → 404 {@code E-3001}）。 */
    public DunningTask get(String taskId, Actor actor, String traceId) {
        assertCapability(actor);
        ensureGenerated(traceId);
        DunningTaskRecord record = requireTask(taskId);
        return record.toDto(frequencyCheck(record.roomCode(), clock.instant()));
    }

    // ==================================================================
    // 生成话术（POST /script）
    // ==================================================================

    /**
     * 生成话术（契约 {@code dunningScript}）：模板 + 禁区扫描 + 版本号；<b>生成不等于外发</b>。
     *
     * <p>{@code tone}/{@code channel} 缺省值按契约：{@code tone=friendly}、{@code channel=wecom}。
     * 已关闭/已缴费任务 → 409 {@code E-3002}（{@code details.reason=already_closed}）。
     * 审计 {@code dunning.script}（resourceId=taskId）。
     */
    public DunningScript generateScript(String taskId, DunningScriptRequest request, Actor actor, String traceId,
            String clientIp) {
        assertCapability(actor);
        ensureGenerated(traceId);
        DunningTaskRecord record = requireTask(taskId);
        if (record.status() == DunningStatus.CLOSED || record.status() == DunningStatus.PAID) {
            audit(actor, AuditAction.DUNNING_SCRIPT, taskId, traceId, clientIp, AuditResult.FAILED,
                    List.of("content", "ownerNameMasked"));
            throw DunningException.alreadyClosed(record.status());
        }
        DunningTone tone = request == null || request.tone() == null ? DunningTone.FRIENDLY : request.tone();
        DunningChannel channel = request == null || request.channel() == null
                ? DunningChannel.WECOM
                : request.channel();
        Instant now = clock.instant();
        String actionAt = TicketTimes.iso(now);
        DunningScript script = scriptGenerator.generate(record, tone, channel, record.nextScriptSequence());
        record.applyScript(script, actionAt);
        audit(actor, AuditAction.DUNNING_SCRIPT, taskId, traceId, clientIp, AuditResult.SUCCESS,
                List.of("content", "ownerNameMasked"));
        log.info("[dunning] 话术已生成（未外发）taskId={} version={} tone={} forbiddenHits={} traceId={}",
                taskId, script.version(), tone.value(), script.forbiddenHits().size(), traceId);
        return script;
    }

    // ==================================================================
    // 发送（POST /send）
    // ==================================================================

    /**
     * 发送催缴（契约 {@code dunningSend}）：人工确认 + 话术版本复核 + 频次红线 + mock 通道触达。
     *
     * @param idempotencyKey 可选 {@code Idempotency-Key}（8—64 字符；同键同载荷重放返回首次结果，
     *                       同键不同载荷 409 {@code E-3003} {@code idempotency_conflict}）
     * @return {@code DunningSendResult{pushId, status:mocked, sentAt, frequencyAfter}}
     * @throws DunningException     400/403/404/409（确认缺失/频次红线/话术过期/任务不存在）
     * @throws ConnectorException   mock 通道失败（→ 503 + degraded，已记 failed 尝试与审计，不伪造成功）
     */
    public DunningSendResult send(String taskId, DunningSendRequest request, String idempotencyKey, Actor actor,
            String traceId, String clientIp) {
        assertCapability(actor);
        ensureGenerated(traceId);
        DunningTaskRecord record = requireTask(taskId);

        // 1) 人工确认（契约：缺失/false → 400 E-1001 details.reason=confirmation_required）
        if (request == null || !request.confirmed()) {
            audit(actor, AuditAction.DUNNING_SEND, taskId, traceId, clientIp, AuditResult.FAILED,
                    List.of("ownerNameMasked", "content"));
            throw DunningException.confirmationRequired();
        }
        // 可选幂等键格式先校验（与任务状态无关的入参合法性）
        String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
        String requestedVersion = blankToNull(request.scriptVersion());
        if (requestedVersion == null) {
            throw DunningException.badRequest("scriptVersion 必填（回传最近生成的话术版本，如 v1）",
                    Map.of("field", "scriptVersion", "reason", "script_version_required"));
        }

        // 2) 任务状态 / 话术版本复核（契约 409 E-3002）
        if (record.status() == DunningStatus.CLOSED || record.status() == DunningStatus.PAID) {
            throw DunningException.alreadyClosed(record.status());
        }
        DunningScript script = record.latestScript();
        if (script == null || record.scriptVersion() == null) {
            throw DunningException.staleScript(requestedVersion, null);
        }
        if (!record.scriptVersion().equals(requestedVersion)) {
            throw DunningException.staleScript(requestedVersion, record.scriptVersion());
        }
        if (script.forbiddenHits() != null && !script.forbiddenHits().isEmpty()) {
            throw DunningException.forbiddenScript(script.forbiddenHits());
        }

        DunningChannel channel = request.channel() != null ? request.channel() : script.channel();

        // 3) 幂等重放（同键同载荷 → 首次结果；同键不同载荷 → 409 E-3003）
        if (normalizedKey != null) {
            IdempotentSend prior = idempotentSends.get(normalizedKey);
            if (prior != null) {
                return prior.replay(normalizedKey, taskId, requestedVersion, channel);
            }
        }

        // 4) 频次红线 + 触达（按房号串行，避免并发超发）
        Object roomLock = roomLocks.computeIfAbsent(record.roomCode(), key -> new Object());
        synchronized (roomLock) {
            Instant now = clock.instant();
            DunningFrequencyCheck check = frequencyCheck(record.roomCode(), now);
            if (!check.allowed()) {
                audit(actor, AuditAction.DUNNING_SEND, taskId, traceId, clientIp, AuditResult.FAILED,
                        List.of("ownerNameMasked", "content"));
                log.warn("[dunning] 频次红线拦截 taskId={} room={} used={}/{} windowDays={} traceId={}",
                        taskId, record.roomCode(), check.used(), check.limit(), check.windowDays(), traceId);
                throw DunningException.frequencyRedline(check);
            }
            String attemptAt = TicketTimes.iso(now);
            try {
                DunningDispatcher.DispatchReceipt receipt = dispatcher.dispatch(record, script, channel, traceId);
                // 频次窗口按「平台发送动作时间」计（不依赖通道回执时间，避免上下游时钟偏差导致额度漂移）
                roomSendTimes.computeIfAbsent(record.roomCode(), key -> Collections.synchronizedList(new ArrayList<>()))
                        .add(now);
                record.markSent(attemptAt, receipt.pushId(), receipt.channel(), requestedVersion);
                DunningFrequencyCheck after = frequencyCheck(record.roomCode(), now);
                DunningSendResult result = new DunningSendResult(receipt.pushId(), "mocked", receipt.sentAt(), after);
                if (normalizedKey != null) {
                    idempotentSends.put(normalizedKey,
                            new IdempotentSend(taskId, requestedVersion, channel.value(), result));
                }
                audit(actor, AuditAction.DUNNING_SEND, taskId, traceId, clientIp, AuditResult.SUCCESS,
                        List.of("ownerNameMasked", "content"));
                log.info("[dunning] 触达受理（mock，未真实外发）taskId={} pushId={} channel={} used={}/{} traceId={}",
                        taskId, receipt.pushId(), receipt.channel().value(), after.used(), after.limit(), traceId);
                publishSentEvent(record, receipt, actor);
                return result;
            } catch (ConnectorException ex) {
                // 失败不伪造成功：记 failed 尝试（不计频次）+ 审计 failed，向上抛 → 503 + degraded
                record.recordFailedSend(attemptAt, channel, requestedVersion, ex.code().value());
                audit(actor, AuditAction.DUNNING_SEND, taskId, traceId, clientIp, AuditResult.FAILED,
                        List.of("ownerNameMasked", "content"));
                log.warn("[dunning] mock 通道失败，未伪造成功 taskId={} code={} traceId={}",
                        taskId, ex.code().value(), traceId);
                throw ex;
            }
        }
    }

    // ==================================================================
    // 关闭（POST /close）
    // ==================================================================

    /**
     * 关闭任务（契约 {@code dunningTaskClose}）：{@code reason} 必填，只改任务状态（不写财务数据）。
     * 已关闭/已缴费重复关闭 → 409 {@code E-3002}（{@code details.reason=already_closed}）。
     */
    public DunningCloseResult close(String taskId, DunningCloseRequest request, Actor actor, String traceId,
            String clientIp) {
        assertCapability(actor);
        ensureGenerated(traceId);
        DunningTaskRecord record = requireTask(taskId);
        String reason = request == null ? null : blankToNull(request.reason());
        if (reason == null) {
            audit(actor, AuditAction.DUNNING_CLOSE, taskId, traceId, clientIp, AuditResult.FAILED,
                    List.of("reason"));
            throw DunningException.badRequest("reason 必填（业主已缴费/转人工/争议/无需继续等）",
                    Map.of("field", "reason"));
        }
        synchronized (record) {
            if (record.status() == DunningStatus.CLOSED || record.status() == DunningStatus.PAID) {
                audit(actor, AuditAction.DUNNING_CLOSE, taskId, traceId, clientIp, AuditResult.FAILED,
                        List.of("reason"));
                throw DunningException.alreadyClosed(record.status());
            }
            Instant now = clock.instant();
            String actionAt = TicketTimes.iso(now);
            record.markClosed(actionAt, reason, actionAt);
            audit(actor, AuditAction.DUNNING_CLOSE, taskId, traceId, clientIp, AuditResult.SUCCESS,
                    List.of("reason"));
            log.info("[dunning] 任务已关闭 taskId={} status=closed traceId={}", taskId, traceId);
            return new DunningCloseResult(taskId, DunningStatus.CLOSED, actionAt);
        }
    }

    // ==================================================================
    // 惰性生成（内部）
    // ==================================================================

    /** 供漏斗/测试读取任务快照（读前保证已生成）。 */
    List<DunningTaskRecord> snapshot(String traceId) {
        ensureGenerated(traceId);
        return List.copyOf(tasks.values());
    }

    /** 生成口径证据（报告/诊断用）：生成时间 + 行数。 */
    Map<String, Object> generationInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("generated", generated.get());
        info.put("rows", generatedRows);
        info.put("generatedAt", generatedAt);
        info.put("tasks", tasks.size());
        return Map.copyOf(info);
    }

    private void ensureGenerated(String traceId) {
        if (generated.get()) {
            return;
        }
        synchronized (generationLock) {
            if (generated.get()) {
                return;
            }
            List<ArrearsItem> rows = new ArrayList<>(arrearsSource.allArrears(traceId));
            rows.sort(ARREARS_ORDER);
            Instant now = clock.instant();
            String nowIso = TicketTimes.iso(now);
            int index = 0;
            for (ArrearsItem row : rows) {
                if (row == null || row.roomCode() == null || row.period() == null) {
                    continue;
                }
                DunningPolicy.BucketPolicy policy = DunningPolicy.bucketPolicy(row.bucket());
                String taskId = nextTaskId(now);
                DunningTaskRecord record = new DunningTaskRecord(
                        taskId,
                        row.roomCode(),
                        row.ownerNameMasked(),
                        row.period(),
                        row.outstanding(),
                        row.lateFee(),
                        row.overdueDays(),
                        row.bucket(),
                        policy.channel(),
                        DunningPolicy.assigneeAt(index),
                        nowIso,
                        TicketTimes.iso(now.plus(Duration.ofDays(policy.dueInDays()))));
                tasks.put(taskId, record);
                index++;
            }
            generatedRows = index;
            generatedAt = nowIso;
            generated.set(true);
            log.info("[dunning] 首次访问惰性生成催缴任务 {} 条（来源费用域欠费分层数据 D1/D7/D15/D30，进程内一次）traceId={}",
                    index, traceId);
        }
    }

    private String nextTaskId(Instant now) {
        String month = MONTH_FORMAT.format(ZonedDateTime.ofInstant(now, TicketTimes.BUSINESS_ZONE));
        synchronized (taskSequence) {
            if (!month.equals(taskSequenceMonth.get())) {
                taskSequenceMonth.set(month);
                taskSequence.set(0);
            }
            return TASK_ID_PREFIX + month + "-" + String.format("%04d", taskSequence.incrementAndGet());
        }
    }

    // ==================================================================
    // 频次 / 参数 / 审计工具
    // ==================================================================

    /** 当前窗口频次校验（消息渠道口径；成功触达按房号聚合）。 */
    public DunningFrequencyCheck frequencyCheck(String roomCode, Instant now) {
        List<Instant> sends = roomSendTimes.get(roomCode);
        List<Instant> snapshot;
        if (sends == null) {
            snapshot = List.of();
        } else {
            synchronized (sends) {
                snapshot = List.copyOf(sends);
            }
        }
        return DunningFrequencyPolicy.check(snapshot, now);
    }

    private static DunningStatus parseStatus(String raw) {
        String value = blankToNull(raw);
        if (value == null) {
            return null;
        }
        try {
            return DunningStatus.fromValue(value.toLowerCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw DunningException.badEnum("status", value, statusCodes());
        }
    }

    private static ArrearsBucket parseBucket(String raw) {
        String value = blankToNull(raw);
        if (value == null) {
            return null;
        }
        try {
            return ArrearsBucket.fromValue(value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw DunningException.badEnum("bucket", value, bucketCodes());
        }
    }

    private static List<String> statusCodes() {
        List<String> codes = new ArrayList<>();
        for (DunningStatus status : DunningStatus.values()) {
            codes.add(status.value());
        }
        return List.copyOf(codes);
    }

    private static List<String> bucketCodes() {
        List<String> codes = new ArrayList<>();
        for (ArrearsBucket bucket : ArrearsBucket.values()) {
            codes.add(bucket.value());
        }
        return List.copyOf(codes);
    }

    private DunningTaskRecord requireTask(String taskId) {
        String normalized = blankToNull(taskId);
        DunningTaskRecord record = normalized == null ? null : tasks.get(normalized);
        if (record == null) {
            throw DunningException.notFound(taskId);
        }
        return record;
    }

    /** 幂等键规整（可空）：格式非法 → 400 {@code E-1001}。同键重放由调用方比对载荷。 */
    private static String normalizeIdempotencyKey(String raw) {
        String value = blankToNull(raw);
        if (value == null) {
            return null;
        }
        if (value.length() < IDEMPOTENCY_KEY_MIN || value.length() > IDEMPOTENCY_KEY_MAX) {
            throw DunningException.badRequest("Idempotency-Key 长度必须为 8—64 字符",
                    Map.of("field", IDEMPOTENCY_KEY_FIELD, "length", value.length()));
        }
        return value;
    }

    /** 能力复校（Filter 层已按 {@code dunning.task} 拦截；领域层防御纵深，未鉴权上下文跳过）。 */
    private static void assertCapability(Actor actor) {
        if (actor == null) {
            return;
        }
        if (!PermissionMatrix.allows(actor.role(), PermissionMatrix.DUNNING_TASK)) {
            throw DunningException.forbiddenCapability(PermissionMatrix.DUNNING_TASK,
                    actor.role() == null ? "" : actor.role().value());
        }
    }

    private void audit(Actor actor, AuditAction action, String taskId, String traceId, String clientIp,
            AuditResult result, List<String> maskedFields) {
        try {
            auditService.record(new AuditService.AuditRecord(
                    actor == null || actor.actorId() == null ? "anonymous" : actor.actorId(),
                    actor == null || actor.role() == null ? ActorRole.OWNER : actor.role(),
                    action,
                    AuditResourceType.DUNNING,
                    taskId,
                    traceId,
                    result,
                    AuditIp.sanitize(clientIp),
                    maskedFields));
        } catch (RuntimeException ex) {
            // 审计失败不得影响业务响应（不落请求/响应原文、不落 token）
            log.warn("[dunning] 审计写入失败 taskId={} action={} err={}", taskId, action.value(),
                    ex.getClass().getSimpleName());
        }
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 发 outbox 事件 dunning.sent（P5-019）：payload 脱敏——只放 taskId/房号/渠道/pushId，
     * <b>不放话术原文、业主姓名、手机号</b>（催缴内容含敏感信息，事件总线只作投递通知）。
     */
    private void publishSentEvent(DunningTaskRecord record, DunningDispatcher.DispatchReceipt receipt, Actor actor) {
        if (eventStore == null) {
            log.debug("[dunning] 事件存储未注入，跳过 dunning.sent taskId={}", record.taskId());
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", record.taskId());
        payload.put("roomCode", record.roomCode());
        payload.put("channel", receipt.channel() == null ? null : receipt.channel().value());
        payload.put("pushId", receipt.pushId());
        eventStore.publish(EventType.DUNNING_SENT,
                ProjectService.projectOf(actor == null ? null : actor.projectId()), payload);
    }

    /** 分层严重度降序 → 房号升序 → 账期降序（稳定分页/生成顺序）。 */
    private static final Comparator<ArrearsItem> ARREARS_ORDER = Comparator
            .comparingInt((ArrearsItem item) -> DunningPolicy.severity(item.bucket())).reversed()
            .thenComparing(ArrearsItem::roomCode, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(ArrearsItem::period, Comparator.nullsLast(Comparator.reverseOrder()));

    /** 任务列表排序：分层严重度降序 → taskId 升序（ID 内含月份+序号，等价于生成顺序）。 */
    private static final Comparator<DunningTaskRecord> TASK_ORDER = Comparator
            .comparingInt((DunningTaskRecord record) -> DunningPolicy.severity(record.bucket())).reversed()
            .thenComparing(DunningTaskRecord::taskId);

    /**
     * 幂等发送留痕（同一 {@code Idempotency-Key} 重放同载荷返回首次结果，不重复触达/不重复计频次）。
     *
     * @param taskId        首次受理的 taskId
     * @param scriptVersion 首次话术版本
     * @param channelCode   首次渠道 code
     * @param result        首次 `DunningSendResult`
     */
    private record IdempotentSend(String taskId, String scriptVersion, String channelCode,
            DunningSendResult result) {

        /** 同载荷重放返回首次结果；不同载荷 → 409 {@code E-3003} 幂等冲突。 */
        DunningSendResult replay(String key, String requestedTaskId, String requestedVersion,
                DunningChannel requestedChannel) {
            boolean samePayload = taskId.equals(requestedTaskId)
                    && scriptVersion.equals(requestedVersion)
                    && (requestedChannel == null || channelCode.equals(requestedChannel.value()));
            if (!samePayload) {
                throw DunningException.idempotencyConflict(key);
            }
            return result;
        }
    }
}
