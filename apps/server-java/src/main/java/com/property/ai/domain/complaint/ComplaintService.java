package com.property.ai.domain.complaint;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.property.ai.audit.AuditIp;
import com.property.ai.audit.AuditService;
import com.property.ai.bff.dto.Actor;
import com.property.ai.bff.dto.ActorRole;
import com.property.ai.bff.dto.AuditAction;
import com.property.ai.bff.dto.AuditResourceType;
import com.property.ai.bff.dto.AuditResult;
import com.property.ai.bff.dto.Complaint;
import com.property.ai.bff.dto.ComplaintCreateRequest;
import com.property.ai.bff.dto.ComplaintLevel;
import com.property.ai.bff.dto.ComplaintListResult;
import com.property.ai.bff.dto.ComplaintListSort;
import com.property.ai.bff.dto.ComplaintStats;
import com.property.ai.bff.dto.ComplaintStatus;
import com.property.ai.bff.dto.ComplaintTransitionAction;
import com.property.ai.bff.dto.ComplaintTransitionRequest;
import com.property.ai.bff.dto.EventType;
import com.property.ai.bff.dto.TicketListItem;
import com.property.ai.bff.dto.TicketListResult;
import com.property.ai.bff.dto.TicketStatus;
import com.property.ai.connector.Capability;
import com.property.ai.connector.ConnectorException;
import com.property.ai.connector.ErpConnector;
import com.property.ai.domain.ticket.TicketRecord;
import com.property.ai.domain.event.EventStore;
import com.property.ai.domain.project.ProjectService;
import com.property.ai.domain.ticket.TicketRepository;
import com.property.ai.domain.ticket.TicketTimes;
import com.property.ai.security.DemoAccount;
import com.property.ai.security.DemoAccounts;
import com.property.ai.security.PermissionMatrix;

/**
 * 投诉领域服务（P3-021/022/023，契约 v1.1.0 {@code /api/v1/complaints*}）。
 *
 * <h2>职责闭环</h2>
 * <ol>
 *   <li><b>受理</b>（{@code POST /complaints}）：owner 本户 / staff 本项目户；分类 + 内容强制，
 *       级别缺省按契约建议 L3；生成 {@code complaintId}（{@code CMP-yyyyMMdd-NNNN}）与 {@code slaDueAt}；</li>
 *   <li><b>分级 SLA</b>（{@link ComplaintSla}）：L1 30 分钟 / L2 24 小时 / L3 2 小时首响，
 *       首次 {@code first_response} 记 {@code firstResponseAt}（只记第一次）；</li>
 *   <li><b>状态机</b>（{@link ComplaintTransitions}）：非法迁移 409 {@code E-3002}+{@code details{status,allowed}}，
 *       版本不匹配 409 {@code E-3002}+{@code details.reason=version_conflict}（契约 transition 描述原文）；</li>
 *   <li><b>动作级权限</b>：受理/首响/结案 = {@code complaint.handle}（staff），升级 = {@code complaint.escalate}
 *       （<b>仅 supervisor</b>），重开 = {@code complaint.read}（owner 本户 / staff）；拒绝 403 {@code E-2001}；</li>
 *   <li><b>数据范围</b>：owner 只能看/重开<b>本户</b>投诉（跨房 403 {@code E-2002}+{@code out_of_scope}），
 *       staff 本项目内可见；投诉不存在 404 {@code E-3001}（不暴露他人房号是否存在）；</li>
 *   <li><b>统计</b>（{@code GET /complaints/stats}）：total / byStatus（6 态补 0）/ byLevel（L1—L3 补 0）/
 *       avgFirstResponseMinutes / slaBreachCount / period；仅 staff（{@code complaint.stats}）；</li>
 *   <li><b>审计</b>：写操作显式落 {@link AuditService}（{@code complaint.create} / {@code complaint.transition}，
 *       resource = {@code complaint:{id}}，失败迁移落 result=failed）；403 拒绝另由
 *       {@code SecurityFilter} 落 {@code access.deny}（resourceType=complaint）。</li>
 * </ol>
 *
 * <h2>投诉 ↔ 工单联动（P3-022 / P4 task-58 修正）</h2>
 * 本实现采用契约允许的<b>只读关联</b>语义：<b>{@code escalate} 时自动关联「同房最近创建且未结案工单」并回带
 * {@code ticketNo}</b>；<b>不自动创建工单</b>（自动建单需要 ERP 写操作 + 编造报修要素，违反「不伪造数据」红线）。
 *
 * <p>P4 task-58（QA 实测缺陷修复）：候选集不再只扫平台 {@link TicketRepository}（P3 进程内 Map，重启清空后
 * 稀疏，会漏掉 ERP 历史单而挑到更旧单），而是
 * <b>{@code 平台仓储 ∪ ERP 侧该房号未结案工单}</b>（{@link ErpConnector#listTickets} 只读列表能力，
 * 主数据以 ERP 为准），按 {@code createdAt} 取最大、并列按 {@code ticketNo} 字典序兜底；
 * ERP 不可用时降级为平台仓储（warn 日志注明，不编造工单号）。
 *
 * <p>契约 v1.2.0 新增的显式 {@code ComplaintTransitionRequest.ticketNo} 入参本任务不改语义与形状，
 * 仍按「自动关联」执行（显式入参落地为独立契约补口，已列遗留风险）。
 *
 * <h2>存储与时钟</h2>
 * P3 为进程内 {@link ConcurrentHashMap}（demo，重启丢数据；P5 迁 PostgreSQL，接口/口径不变）。
 * 时钟可注入（{@link #setClockForTest(Clock)}），供 SLA/统计测试控制。
 */
@Service
public class ComplaintService {

    private static final Logger log = LoggerFactory.getLogger(ComplaintService.class);

    private static final int MAX_PAGE_SIZE = 100;
    /** 房号归一后格式（契约 §2：楼栋-单元-房间，如 1-1-101 / 3-2-1802） */
    private static final Pattern ROOM_CODE_PATTERN = Pattern.compile("^\\d{1,2}-\\d{1,2}-\\d{3,4}$");
    /** 内容脱敏：11 位手机号 → 保留前 3 + 后 4，中间 4 位打码 */
    private static final Pattern MOBILE_PATTERN = Pattern.compile("(?<!\\d)(1[3-9]\\d)(\\d{4})(\\d{4})(?!\\d)");
    /** 内容脱敏：18 位身份证号 → 保留前 6 + 后 4（中间 8 位打码） */
    private static final Pattern ID_CARD_PATTERN = Pattern.compile("(?<![0-9Xx])(\\d{6})(\\d{8})(\\d{3}[0-9Xx])(?![0-9Xx])");
    private static final String ACTION_SUBMITTED = "submitted";
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;
    /** 已结案工单状态（联动时不关联这些单；其余状态或状态缺失视为未结案） */
    private static final List<TicketStatus> TERMINAL_TICKET_STATUSES =
            List.of(TicketStatus.COMPLETED, TicketStatus.REVIEWED, TicketStatus.ARCHIVED);
    /**
     * ERP 工单列表采样上限（P4 task-58）：连接器 {@code ticket.list} 只支持 status/team 下推，
     * 没有 roomCode 参数 → 取一页足够大的样本后本地按房号过滤（与 mock-erp 单页上限 500 一致）；
     * P5 数据量上来后改为 ERP 侧房号下推。
     */
    private static final int ERP_TICKET_SAMPLE_LIMIT = 500;

    private final ConcurrentMap<String, ComplaintRecord> records = new ConcurrentHashMap<>();
    private final AtomicInteger sequence = new AtomicInteger();
    private final AtomicReference<String> sequenceDay = new AtomicReference<>("");

    private final AuditService auditService;
    private final TicketRepository ticketRepository;
    private final ErpConnector erpConnector;

    private Clock clock = Clock.system(TicketTimes.BUSINESS_ZONE);
    /** outbox 事件存储（P5-019；可选注入：保持既有单测构造不破，未注入时只记日志） */
    private EventStore eventStore;

    /**
     * 生产构造（Spring 注入）：平台仓储 + ERP 连接器（升级联动候选集 = 平台 ∪ ERP）。
     */
    @Autowired
    public ComplaintService(AuditService auditService, TicketRepository ticketRepository, ErpConnector erpConnector) {
        this.auditService = auditService;
        this.ticketRepository = ticketRepository;
        this.erpConnector = erpConnector;
    }

    /**
     * P3 兼容构造（单测/离线演示，无 ERP 连接器）：升级联动只扫平台仓储。
     *
     * <p>保留该重载是为了不破坏既有调用点（{@code ComplaintServiceTest}）；生产一律走
     * {@link #ComplaintService(AuditService, TicketRepository, ErpConnector)}。
     */
    public ComplaintService(AuditService auditService, TicketRepository ticketRepository) {
        this(auditService, ticketRepository, null);
    }

    /** 事件存储注入（可选：P5-019 outbox；单测不构造时为 null） */
    @Autowired(required = false)
    public void setEventStore(EventStore eventStore) {
        this.eventStore = eventStore;
    }

    /** 测试注入固定时钟（SLA/统计口径用；生产不调用）。 */
    void setClockForTest(Clock clock) {
        this.clock = clock == null ? Clock.system(TicketTimes.BUSINESS_ZONE) : clock;
    }

    // ==================================================================
    // 受理（POST /complaints）
    // ==================================================================

    /**
     * 提交投诉（契约 {@code complaintCreate}，201）。
     *
     * @param request 入参（服务端只强制 roomCode + category + content）
     * @param actor   当前演示主体（{@code null} = security 关闭的离线演示/测试模式，不做范围与能力校验）
     * @param traceId 链路 ID（与响应信封同源，写审计可回灌）
     * @param clientIp 客户端地址（审计脱敏后落库，见 {@link AuditIp}）
     */
    public Complaint create(ComplaintCreateRequest request, Actor actor, String traceId, String clientIp) {
        assertCapability(actor, PermissionMatrix.COMPLAINT_CREATE);
        Map<String, Object> missing = new LinkedHashMap<>();
        List<String> missingFields = new ArrayList<>();
        if (request == null) {
            missingFields.addAll(List.of("roomCode", "category", "content"));
        } else {
            if (blank(request.roomCode())) {
                missingFields.add("roomCode");
            }
            if (blank(request.category())) {
                missingFields.add("category");
            }
            if (blank(request.content())) {
                missingFields.add("content");
            }
        }
        if (!missingFields.isEmpty()) {
            missing.put("missing", missingFields);
            throw ComplaintException.badRequest("投诉要素缺失：" + String.join("、", missingFields)
                    + "（服务端强制 roomCode + category + content）", missing);
        }

        String roomCode = normalizeRoomCode(request.roomCode());
        if (!ROOM_CODE_PATTERN.matcher(roomCode).matches()) {
            throw ComplaintException.badRequest("roomCode 无法识别（楼栋-单元-房间，如 1-1-101）",
                    Map.of("field", "roomCode", "roomCode", roomCode));
        }
        if (request.contactTail4() != null && !request.contactTail4().isBlank()
                && !request.contactTail4().trim().matches("\\d{4}")) {
            throw ComplaintException.badRequest("contactTail4 必须为 4 位数字（只收后四位，不收完整手机号）",
                    Map.of("field", "contactTail4"));
        }
        if (request.attachments() < 0 || request.attachments() > 9) {
            throw ComplaintException.badRequest("attachments 必须在 0—9（P3 只计数，不传文件）",
                    Map.of("field", "attachments", "value", request.attachments()));
        }
        assertCreateScope(actor, roomCode);

        ComplaintLevel level = request.level() == null ? ComplaintLevel.L3 : request.level();
        Instant now = clock.instant();
        String createdAt = TicketTimes.iso(now);

        ComplaintRecord record = new ComplaintRecord();
        record.complaintId = nextComplaintId(now);
        // P5 W19 项目归属：提交主体所属项目（平台级 it/admin 兜底演示默认项目，避免无项目脏数据）
        record.projectId = ProjectService.projectOf(actor == null ? null : actor.projectId());
        record.roomCode = roomCode;
        record.ownerActorId = isOwner(actor) ? actor.actorId() : "";
        record.ownerNameMasked = ownerNameMasked(actor, roomCode);
        record.category = request.category().trim();
        record.level = level;
        record.status = ComplaintStatus.SUBMITTED;
        record.content = maskContent(request.content().trim());
        record.contactTail4 = request.contactTail4() == null || request.contactTail4().isBlank()
                ? null
                : request.contactTail4().trim();
        record.attachments = request.attachments();
        record.slaDueAt = TicketTimes.iso(ComplaintSla.dueAt(now, level));
        record.createdAt = createdAt;
        record.updatedAt = createdAt;
        record.version = 1;
        record.appendAudit(createdAt, ACTION_SUBMITTED, whoOf(actor), null);
        records.put(record.complaintId, record);

        recordAudit(actor, AuditAction.COMPLAINT_CREATE, record.complaintId, traceId, clientIp,
                AuditResult.SUCCESS, List.of("contactTail4", "content"));

        log.info("[complaint] create ok complaintId={} room={} level={} status={} slaDueAt={} traceId={}",
                record.complaintId, record.roomCode, level.value(), record.status.value(), record.slaDueAt, traceId);
        publishEvent(record);
        return record.toDto();
    }

    /** 发 outbox 事件 complaint.created（P5-019；payload 脱敏：只放 id/房号/等级/状态/项目） */
    private void publishEvent(ComplaintRecord record) {
        if (eventStore == null) {
            log.debug("[complaint] 事件存储未注入，跳过 complaint.created complaintId={}", record.complaintId);
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("complaintId", record.complaintId);
        payload.put("roomCode", record.roomCode);
        payload.put("level", record.level == null ? null : record.level.value());
        payload.put("status", record.status == null ? null : record.status.value());
        eventStore.publish(EventType.COMPLAINT_CREATED, record.projectId, payload);
    }

    // ==================================================================
    // 详情 / 列表（GET）
    // ==================================================================

    /**
     * 投诉归属项目（P5 W19 项目隔离）：平台记录 → {@code projectId}，缺失按演示默认项目归属；
     * 用于详情端点的跨项目 403 判定（{@code Complaint} DTO 无 projectId 字段，不回传）。
     */
    public String projectIdOf(String complaintId) {
        return ProjectService.projectOf(requireRecord(complaintId).projectId);
    }

    /** 投诉详情（owner 本户否则 403 E-2002；不存在 404 E-3001）。 */
    public Complaint get(String complaintId, Actor actor) {
        assertCapability(actor, PermissionMatrix.COMPLAINT_READ);
        ComplaintRecord record = requireRecord(complaintId);
        assertReadScope(actor, record);
        return record.toDto();
    }

    /**
     * 投诉列表（契约筛选 status/level/roomCode + 分页 + 排序）。
     *
     * @param status   {@code ComplaintStatus} code（非法 → 400 E-1002）
     * @param level    {@code ComplaintLevel} code（非法 → 400 E-1002）
     * @param roomCode 房号筛选（owner 传他人房号 → 403 E-2002）
     * @param sort     {@code createdAt_desc}（默认）/ {@code slaDueAt_asc}
     */
    public ComplaintListResult list(String status, String level, String roomCode, String sort, int page, int pageSize,
            Actor actor) {
        return list(status, level, roomCode, sort, page, pageSize, actor, null);
    }

    /**
     * 投诉列表（P5 W19 增量：项目作用域过滤）。
     *
     * @param scopeProjectId 项目作用域（{@code null} = 不过滤；非空时只返回该项目的投诉，
     *                       资源项目缺失按演示默认项目归属）——越权判定（跨项目 403）由
     *                       {@code domain/project/ProjectService} 在 Controller 层先行完成
     */
    public ComplaintListResult list(String status, String level, String roomCode, String sort, int page, int pageSize,
            Actor actor, String scopeProjectId) {
        assertCapability(actor, PermissionMatrix.COMPLAINT_READ);
        if (page < 1) {
            throw ComplaintException.badRequest("page 必须 ≥ 1", Map.of("field", "page", "page", page));
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw ComplaintException.badRequest("pageSize 必须在 1—" + MAX_PAGE_SIZE + " 之间",
                    Map.of("field", "pageSize", "pageSize", pageSize, "maximum", MAX_PAGE_SIZE));
        }
        ComplaintStatus statusFilter = parseStatus(status);
        ComplaintLevel levelFilter = parseLevel(level);
        ComplaintListSort sortKey = parseSort(sort);
        String roomFilter = blank(roomCode) ? null : normalizeRoomCode(roomCode);
        String ownerRoom = ownerRoomOf(actor);
        if (ownerRoom != null) {
            if (roomFilter != null && !roomFilter.equals(ownerRoom)) {
                throw ComplaintException.outOfScope("complaint", "", roomFilter);
            }
            roomFilter = ownerRoom;
        }

        List<ComplaintRecord> matched = new ArrayList<>();
        for (ComplaintRecord record : records.values()) {
            if (statusFilter != null && record.status != statusFilter) {
                continue;
            }
            if (levelFilter != null && record.level != levelFilter) {
                continue;
            }
            if (roomFilter != null && !roomFilter.equals(record.roomCode)) {
                continue;
            }
            if (scopeProjectId != null && !scopeProjectId.equals(ProjectService.projectOf(record.projectId))) {
                continue;
            }
            matched.add(record);
        }
        matched.sort(comparatorOf(sortKey));

        int total = matched.size();
        int from = (page - 1) * pageSize;
        if (from >= total) {
            return new ComplaintListResult(List.of(), page, pageSize, total);
        }
        int to = Math.min(total, from + pageSize);
        List<Complaint> items = new ArrayList<>();
        for (ComplaintRecord record : matched.subList(from, to)) {
            items.add(record.toDto());
        }
        return new ComplaintListResult(List.copyOf(items), page, pageSize, total);
    }

    // ==================================================================
    // 统计（GET /complaints/stats）
    // ==================================================================

    /**
     * 投诉统计（契约 {@code complaintStats}）：管理视图，仅 staff（{@code complaint.stats}）。
     *
     * @param from 起始日（含，{@code YYYY-MM-DD}；省略 = 当前自然月 1 日）
     * @param to   截止日（含，{@code YYYY-MM-DD}；省略 = 当前业务日）
     */
    public ComplaintStats stats(String from, String to, String level, Actor actor) {
        assertCapability(actor, PermissionMatrix.COMPLAINT_STATS);
        ComplaintLevel levelFilter = parseLevel(level);
        LocalDate today = LocalDate.now(clock);
        boolean explicitWindow = !blank(from) || !blank(to);
        LocalDate fromDate = blank(from) ? today.withDayOfMonth(1) : parseDate("from", from);
        LocalDate toDate = blank(to) ? today : parseDate("to", to);
        if (fromDate.isAfter(toDate)) {
            throw ComplaintException.badRequest("from 不能晚于 to",
                    Map.of("field", "from", "from", fromDate.toString(), "to", toDate.toString()));
        }

        Map<String, Integer> byStatus = new LinkedHashMap<>();
        for (ComplaintStatus status : ComplaintStatus.values()) {
            byStatus.put(status.value(), 0);
        }
        Map<String, Integer> byLevel = new LinkedHashMap<>();
        for (ComplaintLevel item : ComplaintLevel.values()) {
            byLevel.put(item.value(), 0);
        }
        long total = 0;
        double responseMinutesSum = 0.0;
        int respondedCount = 0;
        int breachedCount = 0;
        Instant now = clock.instant();

        for (ComplaintRecord record : records.values()) {
            LocalDate createdDate = createdDateOf(record);
            if (createdDate == null || createdDate.isBefore(fromDate) || createdDate.isAfter(toDate)) {
                continue;
            }
            if (levelFilter != null && record.level != levelFilter) {
                continue;
            }
            total++;
            byStatus.merge(record.status.value(), 1, Integer::sum);
            byLevel.merge(record.level.value(), 1, Integer::sum);
            Double minutes = ComplaintSla.firstResponseMinutes(record.createdAt, record.firstResponseAt);
            if (minutes != null) {
                responseMinutesSum += minutes;
                respondedCount++;
            }
            if (ComplaintSla.breached(record.firstResponseAt, record.slaDueAt, now)) {
                breachedCount++;
            }
        }

        String period = explicitWindow
                ? fromDate.toString() + "~" + toDate.toString()
                : MONTH_FORMAT.format(fromDate);
        return new ComplaintStats(total, byStatus, byLevel,
                ComplaintSla.averageOf(responseMinutesSum, respondedCount), breachedCount, period);
    }

    // ==================================================================
    // 流转（POST /complaints/{id}/transition）
    // ==================================================================

    /**
     * 投诉流转（accept / first_response / escalate / close / reopen）。
     *
     * <p>校验顺序（与契约响应语义一致）：参数（400）→ 记录存在（404）→ 动作级能力（403 E-2001）
     * → 数据范围（403 E-2002）→ 乐观锁版本（409 version_conflict）→ 状态机（409 status/allowed）
     * → 业务必填（首响/结案 note，400）。
     */
    public Complaint transition(String complaintId, ComplaintTransitionRequest request, Actor actor, String traceId,
            String clientIp) {
        if (request == null) {
            throw ComplaintException.badRequest("请求体不能为空", Map.of("field", "body", "required", true));
        }
        if (request.action() == null) {
            throw ComplaintException.badRequest("action 必填（accept / first_response / escalate / close / reopen）",
                    Map.of("field", "action", "required", true));
        }
        if (request.version() < 1) {
            throw ComplaintException.badRequest("version 必填且 ≥1（取自投诉详情 version）",
                    Map.of("field", "version", "required", true));
        }
        ComplaintRecord record = requireRecord(complaintId);
        ComplaintTransitionAction action = request.action();
        assertCapability(actor, PermissionMatrix.complaintTransitionCapability(action));
        assertTransitionScope(actor, record);

        try {
            synchronized (record) {
                if (record.version != request.version()) {
                    throw ComplaintException.versionConflict(request.version(), record.version);
                }
                if (!ComplaintTransitions.allows(action, record.status)) {
                    throw ComplaintException.invalidTransition(record.status, action);
                }
                validateNote(action, request.note());
                applyTransition(record, action, request.note(), actor, traceId);
                recordAudit(actor, AuditAction.COMPLAINT_TRANSITION, record.complaintId, traceId, clientIp,
                        AuditResult.SUCCESS, List.of());
                log.info("[complaint] transition ok complaintId={} action={} status={} version={} traceId={}",
                        record.complaintId, action.value(), record.status.value(), record.version, traceId);
                return record.toDto();
            }
        } catch (ComplaintException ex) {
            // 写操作失败也留痕（与 W8 审计口径一致：result=failed；403 拒绝由 SecurityFilter 记 access.deny）
            recordAudit(actor, AuditAction.COMPLAINT_TRANSITION, complaintId, traceId, clientIp,
                    AuditResult.FAILED, List.of());
            throw ex;
        }
    }

    // ==================================================================
    // 内部：迁移副作用
    // ==================================================================

    private void applyTransition(ComplaintRecord record, ComplaintTransitionAction action, String note, Actor actor,
            String traceId) {
        Instant now = clock.instant();
        String at = TicketTimes.iso(now);
        String auditNote = note == null || note.isBlank() ? null : note.trim();
        switch (action) {
            case ACCEPT -> record.status = ComplaintStatus.ACCEPTED;
            case FIRST_RESPONSE -> {
                record.status = ComplaintStatus.PROCESSING;
                if (!record.responded()) {
                    record.firstResponseAt = at;
                }
            }
            case ESCALATE -> {
                record.status = ComplaintStatus.ESCALATED;
                record.escalatedTo = "客服主管";
                // P3-022 只读联动：同房最近未结案工单（契约 transition 无 ticketNo 入参，不自动建单）
                // P4 task-58：候选集 = 平台仓储 ∪ ERP 工单列表，按 createdAt 取最新（ERP 不可用降级平台）
                if (blank(record.ticketNo)) {
                    String linked = findLinkableTicket(record.roomCode, traceId).orElse(null);
                    if (linked != null) {
                        record.ticketNo = linked;
                        auditNote = blank(auditNote) ? "已关联工单 " + linked : auditNote + "；已关联工单 " + linked;
                    }
                }
            }
            case CLOSE -> {
                record.status = ComplaintStatus.CLOSED;
                record.resolution = auditNote;
            }
            case REOPEN -> record.status = ComplaintStatus.REOPENED;
        }
        record.version = record.version + 1;
        record.updatedAt = at;
        record.appendAudit(at, action.value(), whoOf(actor), auditNote);
    }

    /** 首响必须留 note（响应内容）；结案必须给 resolution（落 audit[].note 与内部字段）。 */
    private void validateNote(ComplaintTransitionAction action, String note) {
        if (action == ComplaintTransitionAction.FIRST_RESPONSE && blank(note)) {
            throw ComplaintException.badRequest("首次响应必须填写 note（本次联系/处理记录）",
                    Map.of("field", "note", "action", action.value(), "required", true));
        }
        if (action == ComplaintTransitionAction.CLOSE && blank(note)) {
            throw ComplaintException.badRequest("结案必须提供 resolution（结案说明，落 audit[].note）",
                    Map.of("field", "note", "action", action.value(), "required", true));
        }
    }

    /** 升级时只读联动（兼容旧调用口径；无 traceId 时按空 trace 记日志）。 */
    private Optional<String> findLinkableTicket(String roomCode) {
        return findLinkableTicket(roomCode, null);
    }

    /**
     * 升级时只读联动：同房「最近创建且未结案」工单号（P4 task-58 修正）。
     *
     * <p><b>候选集</b>（去重后按 {@code createdAt} 取最大，并列按 {@code ticketNo} 字典序取较大者 = 编号较新）：
     * <ol>
     *   <li>平台 {@link TicketRepository}（P3 进程内 {@code InMemoryTicketRepository}，重启后稀疏，
     *       但包含「本轮新建/读过」的工单与回访结论；同号时状态以 ERP 为准合并）；</li>
     *   <li>ERP 侧该房号的工单列表（{@link ErpConnector#listTickets} 只读能力；主数据以 ERP 为准，
     *       {@code ticket.list} 无 roomCode 下推参数，取样本上限 {@value #ERP_TICKET_SAMPLE_LIMIT} 后本地按房号过滤）。</li>
     * </ol>
     *
     * <p><b>未结案口径</b>：{@code COMPLETED / REVIEWED / ARCHIVED} 视为结案并排除；其余状态
     * （{@code CREATED/ACCEPTED/DISPATCHED/ON_SITE}）或状态缺失视为未结案。
     * 同一 {@code ticketNo} 在两侧都出现时以 ERP 状态为准（避免平台缓存状态滞后把已结案单再关联出来）。
     *
     * <p><b>降级</b>：ERP 连接器缺失 / 未开通 {@code ticket.list} 能力 / 调用失败（{@link ConnectorException}
     * 或运行时异常）时，只用平台仓储候选并落 {@code warn} 日志注明降级（<b>不编造工单号</b>）；
     * 两侧都无候选 → 返回空（升级照常成功、{@code ticketNo} 不回带，保持既有语义）。
     *
     * @param roomCode 投诉房号（归一后与工单 {@code roomCode} 全等比较，不跨房）
     * @param traceId  链路 ID（透传 ERP 出站请求；可为 {@code null}）
     */
    private Optional<String> findLinkableTicket(String roomCode, String traceId) {
        if (blank(roomCode)) {
            return Optional.empty();
        }
        String room = normalizeRoomCode(roomCode);
        Map<String, LinkableTicketCandidate> candidates = new LinkedHashMap<>();
        int platformCount = 0;

        // ① 平台仓储（P3 进程内；重启后只有「本轮建过/读过」的单）
        if (ticketRepository != null) {
            for (TicketRecord record : ticketRepository.findAll()) {
                if (record == null || blank(record.ticketNo) || !room.equals(normalizeRoomCode(record.roomCode))) {
                    continue;
                }
                platformCount++;
                candidates.put(record.ticketNo,
                        new LinkableTicketCandidate(record.ticketNo, record.roomCode, record.createdAt, record.status));
            }
        }

        // ② ERP 侧列表（主数据权威；不可用 → 降级为平台仓储，不编造）
        boolean erpAvailable = false;
        int erpCount = 0;
        if (erpConnector == null) {
            log.info("[complaint] 升级联动未注入 ERP 连接器，仅用平台仓储候选 room={} platform={} traceId={}",
                    room, platformCount, traceId);
        } else if (!erpConnector.supports(Capability.TICKET_LIST)) {
            log.warn("[complaint] 升级联动降级：ERP 未开通 ticket.list 能力，仅用平台仓储候选 room={} platform={} traceId={}",
                    room, platformCount, traceId);
        } else {
            try {
                TicketListResult sample = erpConnector.listTickets(null, null, 1, ERP_TICKET_SAMPLE_LIMIT, traceId);
                erpAvailable = true;
                if (sample != null && sample.items() != null) {
                    for (TicketListItem item : sample.items()) {
                        if (item == null || blank(item.ticketNo())
                                || !room.equals(normalizeRoomCode(item.roomCode()))) {
                            continue;
                        }
                        erpCount++;
                        LinkableTicketCandidate erpCandidate = new LinkableTicketCandidate(
                                item.ticketNo(), item.roomCode(), item.createdAt(), item.status());
                        // 同号合并：ERP 状态/时间权威（平台缓存可能滞后），createdAt 缺省时回落平台值
                        candidates.merge(item.ticketNo(), erpCandidate, ComplaintService::preferErpCandidate);
                    }
                }
            } catch (ConnectorException ex) {
                log.warn("[complaint] 升级联动降级：ERP 工单列表不可用（code={}），仅用平台仓储候选 room={} platform={} traceId={}",
                        ex.code().value(), room, platformCount, traceId);
            } catch (RuntimeException ex) {
                log.warn("[complaint] 升级联动降级：ERP 工单列表调用异常（{}），仅用平台仓储候选 room={} platform={} traceId={}",
                        ex.getClass().getSimpleName(), room, platformCount, traceId);
            }
        }

        Optional<String> linked = candidates.values().stream()
                .filter(candidate -> !isTerminalTicketStatus(candidate.status()))
                .max(Comparator
                        .comparing(LinkableTicketCandidate::createdInstant,
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(LinkableTicketCandidate::ticketNo, Comparator.nullsFirst(
                                Comparator.naturalOrder())))
                .map(LinkableTicketCandidate::ticketNo);
        if (linked.isPresent()) {
            log.info("[complaint] 升级联动工单 room={} ticketNo={} candidates={} platform={} erp={} erpAvailable={} traceId={}",
                    room, linked.get(), candidates.size(), platformCount, erpCount, erpAvailable, traceId);
        } else {
            log.info("[complaint] 升级联动无同房未结案工单，关联留空 room={} platform={} erp={} erpAvailable={} traceId={}",
                    room, platformCount, erpCount, erpAvailable, traceId);
        }
        return linked;
    }

    /** 同号候选合并：ERP 为准（状态/房号），createdAt 取两侧非空值（ERP 优先，缺省回落平台）。 */
    private static LinkableTicketCandidate preferErpCandidate(LinkableTicketCandidate platform,
            LinkableTicketCandidate erp) {
        String createdAt = blank(erp.createdAt()) ? platform.createdAt() : erp.createdAt();
        return new LinkableTicketCandidate(erp.ticketNo(), erp.roomCode(), createdAt, erp.status());
    }

    /** 结案口径：COMPLETED / REVIEWED / ARCHIVED 为结案；其余（含 null）视为未结案。 */
    private static boolean isTerminalTicketStatus(TicketStatus status) {
        return status != null && TERMINAL_TICKET_STATUSES.contains(status);
    }

    /**
     * 升级联动候选（平台/ERP 合并后的最小字段集）。
     *
     * @param ticketNo  工单号
     * @param roomCode  房号（已按投诉房号过滤）
     * @param createdAt 创建时间（ISO 8601 字符串；缺省视为最旧）
     * @param status    工单状态（{@code null} 视为未结案）
     */
    private record LinkableTicketCandidate(String ticketNo, String roomCode, String createdAt, TicketStatus status) {

        /** 解析后的创建时间（ISO 8601；解析失败 → {@code null}，比较时视为最旧）。 */
        Instant createdInstant() {
            return TicketTimes.parseInstant(createdAt);
        }
    }

    // ==================================================================
    // 内部：数据范围 / 能力
    // ==================================================================

    private void assertCreateScope(Actor actor, String roomCode) {
        String ownerRoom = ownerRoomOf(actor);
        if (ownerRoom != null && !ownerRoom.equals(roomCode)) {
            throw ComplaintException.outOfScope("complaint", "", roomCode);
        }
    }

    private void assertReadScope(Actor actor, ComplaintRecord record) {
        String ownerRoom = ownerRoomOf(actor);
        if (ownerRoom != null && !ownerRoom.equals(record.roomCode)) {
            throw ComplaintException.outOfScope("complaint", record.complaintId, record.roomCode);
        }
    }

    private void assertTransitionScope(Actor actor, ComplaintRecord record) {
        if (isOwner(actor)) {
            String ownerRoom = ownerRoomOf(actor);
            if (ownerRoom == null || !ownerRoom.equals(record.roomCode)) {
                throw ComplaintException.outOfScope("complaint", record.complaintId, record.roomCode);
            }
        }
    }

    private void assertCapability(Actor actor, String capability) {
        if (actor == null) {
            // security 关闭的离线演示/测试模式（无 ActorContext）：与既有端点一致，不做能力拦截
            log.warn("[complaint] 无演示主体（security 关闭），跳过能力校验 capability={}", capability);
            return;
        }
        if (!PermissionMatrix.allows(actor.role(), capability)) {
            throw ComplaintException.forbiddenCapability(capability, actor.role() == null ? "" : actor.role().value());
        }
    }

    private static boolean isOwner(Actor actor) {
        return actor != null && actor.role() == ActorRole.OWNER;
    }

    /**
     * owner 本户房号：契约 §7.2「owner 的 roomCode 归属由登录主体绑定」。
     *
     * <p>v1.1.0 的 {@code Actor} 无 householdId 字段（冻结），P3 用演示账号约定
     * {@code owner-<roomCode>}（如 {@code owner-3-2-1802}）解析本户房号；staff / 平台角色返回 {@code null}
     * （= 不受 owner 数据范围限制）。P5 接真实身份体系后由 SSO 声明注入。
     */
    static String ownerRoomOf(Actor actor) {
        if (!isOwner(actor)) {
            return null;
        }
        return ownerRoomOfActorId(actor.actorId());
    }

    static String ownerRoomOfActorId(String actorId) {
        if (blank(actorId) || !actorId.startsWith("owner-")) {
            return null;
        }
        String room = actorId.substring("owner-".length()).trim();
        return ROOM_CODE_PATTERN.matcher(room).matches() ? room : null;
    }

    // ==================================================================
    // 内部：文案 / 脱敏
    // ==================================================================

    /** 审计/留痕的操作人：owner 掩码为「业主·王**」，staff 用演示显示名。 */
    private String whoOf(Actor actor) {
        if (actor == null) {
            return "系统";
        }
        if (isOwner(actor)) {
            return "业主·" + mask(actor.name());
        }
        return blank(actor.name()) ? actor.actorId() : actor.name();
    }

    /** 业主姓名掩码（王女士 → 王**；已掩码/空值幂等）。 */
    static String mask(String name) {
        if (blank(name)) {
            return "**";
        }
        String trimmed = name.trim();
        if (trimmed.contains("*")) {
            return trimmed;
        }
        return trimmed.substring(0, 1) + "**";
    }

    private String ownerNameMasked(Actor actor, String roomCode) {
        if (isOwner(actor)) {
            return mask(actor.name());
        }
        // staff 代提：按房号匹配演示业主账号得到掩码姓名；无匹配用「业主」占位（不编造姓名）
        for (DemoAccount account : DemoAccounts.ACCOUNTS) {
            if (account.role() != ActorRole.OWNER) {
                continue;
            }
            String room = ownerRoomOfActorId(account.actorId());
            if (roomCode.equals(room)) {
                return mask(account.name());
            }
        }
        return "业主";
    }

    /** 内容脱敏：手机号/身份证号打码后落地（契约「content 落地前脱敏」）。 */
    static String maskContent(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        Matcher mobile = MOBILE_PATTERN.matcher(content);
        String masked = mobile.replaceAll("$1****$3");
        Matcher idCard = ID_CARD_PATTERN.matcher(masked);
        return idCard.replaceAll("$1********$3");
    }

    // ==================================================================
    // 内部：解析 / 校验
    // ==================================================================

    private ComplaintStatus parseStatus(String raw) {
        if (blank(raw)) {
            return null;
        }
        try {
            return ComplaintStatus.fromValue(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw ComplaintException.badEnum("status", raw, enumValues(ComplaintStatus.values()));
        }
    }

    private ComplaintLevel parseLevel(String raw) {
        if (blank(raw)) {
            return null;
        }
        try {
            return ComplaintLevel.fromValue(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw ComplaintException.badEnum("level", raw, enumValues(ComplaintLevel.values()));
        }
    }

    private ComplaintListSort parseSort(String raw) {
        if (blank(raw)) {
            return ComplaintListSort.CREATEDAT_DESC;
        }
        try {
            return ComplaintListSort.fromValue(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw ComplaintException.badEnum("sort", raw, enumValues(ComplaintListSort.values()));
        }
    }

    private static List<String> enumValues(ComplaintStatus[] values) {
        List<String> result = new ArrayList<>();
        for (ComplaintStatus value : values) {
            result.add(value.value());
        }
        return result;
    }

    private static List<String> enumValues(ComplaintLevel[] values) {
        List<String> result = new ArrayList<>();
        for (ComplaintLevel value : values) {
            result.add(value.value());
        }
        return result;
    }

    private static List<String> enumValues(ComplaintListSort[] values) {
        List<String> result = new ArrayList<>();
        for (ComplaintListSort value : values) {
            result.add(value.value());
        }
        return result;
    }

    private LocalDate parseDate(String field, String raw) {
        try {
            return LocalDate.parse(raw.trim(), DATE_FORMAT);
        } catch (DateTimeParseException | NullPointerException ex) {
            throw ComplaintException.badRequest(field + " 必须为 YYYY-MM-DD（如 2026-11-01）",
                    Map.of("field", field, "value", raw == null ? "" : raw));
        }
    }

    private LocalDate createdDateOf(ComplaintRecord record) {
        Instant created = TicketTimes.parseInstant(record.createdAt);
        return created == null ? null : created.atZone(TicketTimes.BUSINESS_ZONE).toLocalDate();
    }

    private ComplaintRecord requireRecord(String complaintId) {
        String id = complaintId == null ? "" : complaintId.trim();
        ComplaintRecord record = id.isEmpty() ? null : records.get(id);
        if (record == null) {
            throw ComplaintException.notFound(complaintId == null ? "" : complaintId);
        }
        return record;
    }

    private Comparator<ComplaintRecord> comparatorOf(ComplaintListSort sort) {
        Comparator<ComplaintRecord> byCreatedAt = Comparator.comparing(
                record -> Objects.requireNonNullElse(record.createdAt, ""));
        Comparator<ComplaintRecord> bySlaDueAt = Comparator.comparing(
                record -> Objects.requireNonNullElse(record.slaDueAt, ""));
        Comparator<ComplaintRecord> tieBreak = Comparator.comparing(
                record -> Objects.requireNonNullElse(record.complaintId, ""));
        if (sort == ComplaintListSort.SLADUEAT_ASC) {
            return bySlaDueAt.thenComparing(tieBreak);
        }
        return byCreatedAt.reversed().thenComparing(tieBreak);
    }

    // ==================================================================
    // 内部：ID / 审计 / 工具
    // ==================================================================

    /** 投诉号 {@code CMP-yyyyMMdd-NNNN}（业务时区每日从 0001 递增；demo 单实例）。 */
    private String nextComplaintId(Instant at) {
        String day = DAY_FORMAT.format(at.atZone(TicketTimes.BUSINESS_ZONE));
        synchronized (sequence) {
            if (!day.equals(sequenceDay.get())) {
                sequenceDay.set(day);
                sequence.set(0);
            }
            return "CMP-" + day + "-" + String.format("%04d", sequence.incrementAndGet());
        }
    }

    /** 写审计（失败只 warn，不影响业务；不落 token/手机号/内容原文）。 */
    private void recordAudit(Actor actor, AuditAction action, String complaintId, String traceId, String clientIp,
            AuditResult result, List<String> maskedFields) {
        try {
            auditService.record(new AuditService.AuditRecord(
                    actor == null ? "anonymous" : actor.actorId(),
                    actor == null || actor.role() == null ? ActorRole.OWNER : actor.role(),
                    action,
                    AuditResourceType.COMPLAINT,
                    "complaint:" + complaintId,
                    traceId,
                    result,
                    AuditIp.sanitize(clientIp),
                    maskedFields));
        } catch (RuntimeException ex) {
            log.warn("[complaint] 审计写入失败 complaintId={} err={}", complaintId, ex.getClass().getSimpleName());
        }
    }

    /** 房号归一（全角连字符/空格容错，与 {@code ApiSupport.requireRoomCode} 同口径）。 */
    static String normalizeRoomCode(String raw) {
        return raw == null ? "" : raw.trim().replace('－', '-').replace('—', '-').replace(" ", "");
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
