package com.property.ai.domain.ticket;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.property.ai.bff.dto.Actor;
import com.property.ai.bff.dto.DegradedFallback;
import com.property.ai.bff.dto.DegradedInfo;
import com.property.ai.bff.dto.DispatchPayload;
import com.property.ai.bff.dto.ErrorCode;
import com.property.ai.bff.dto.ErpWriteback;
import com.property.ai.bff.dto.ErpWritebackStatus;
import com.property.ai.bff.dto.EventType;
import com.property.ai.bff.dto.HandoffSummary;
import com.property.ai.bff.dto.IssueScope;
import com.property.ai.bff.dto.OwnerVerifyRequest;
import com.property.ai.bff.dto.OwnerVerifyResult;
import com.property.ai.bff.dto.ReporterType;
import com.property.ai.bff.dto.RoomInfo;
import com.property.ai.bff.dto.Ticket;
import com.property.ai.bff.dto.TicketAuditNode;
import com.property.ai.bff.dto.TicketCategory;
import com.property.ai.bff.dto.TicketCreateRequest;
import com.property.ai.bff.dto.TicketCreateResult;
import com.property.ai.bff.dto.TicketFeedbackRequest;
import com.property.ai.bff.dto.TicketFeedbackResult;
import com.property.ai.bff.dto.TicketFeedbackSummary;
import com.property.ai.bff.dto.TicketListItem;
import com.property.ai.bff.dto.TicketListResult;
import com.property.ai.bff.dto.TicketListSort;
import com.property.ai.bff.dto.TicketPush;
import com.property.ai.bff.dto.TicketPushListResult;
import com.property.ai.bff.dto.TicketStatus;
import com.property.ai.bff.dto.TicketTransitionAction;
import com.property.ai.bff.dto.TicketTransitionResult;
import com.property.ai.connector.Capability;
import com.property.ai.connector.ConnectorException;
import com.property.ai.connector.ConnectorProperties;
import com.property.ai.connector.ConnectorRuntime;
import com.property.ai.connector.ErpConnector;
import com.property.ai.connector.nc.ErpFieldMapper;
import com.property.ai.domain.event.EventStore;
import com.property.ai.domain.project.ProjectService;
import com.property.ai.security.OwnerScope;
import com.property.ai.security.PermissionMatrix;

/**
 * 工单领域服务：报修建单 → 7 节点状态机 → 派单规则/ETA → 完工回写 → 三次推送 → 回访 → 转人工摘要。
 *
 * <p>关键口径（契约 v0.3.1 / shared §11 / 设计 §24.4/§24.6/§24.11）：
 * <ol>
 *   <li><b>主数据以 ERP 为准</b>：状态/班组/ETA/审计节点以连接器返回为准，平台只维护乐观锁版本、
 *       平台侧补充节点、回写确认状态、推送留痕与回访结论（见 {@link TicketRecord}）；</li>
 *   <li><b>写操作不自动重试 + 幂等键 24h</b>：同键同载荷返回首次结果，同键不同载荷 409 E-3003；
 *       派单失败 503 + {@code degraded{fallback:human}}；<b>完工回写失败不提交完工（保持 ON_SITE）</b>，
 *       返回 {@code erpWriteback=failed} + 人工待办 + {@code degraded{fallback:human}}；</li>
 *   <li><b>状态机严格按 shared TICKET_TRANSITIONS</b>（{@link TicketTransitions}）：非法迁移 409 E-3002 并回
 *       {@code details.allowed}；版本不匹配 409 E-3002 {@code details.reason=version_conflict}；</li>
 *   <li><b>P2-017 派单</b>：{@code action=dispatch} 未带 teamName/teamId → 按技能/楼栋/队列自动派单并回显
 *       {@code dispatch}；带则校验班组存在（非法 → 400 E-1003）；</li>
 *   <li><b>P2-018 完工回写（30 秒确认语义）</b>：写成功 → {@code erpWriteback.pending}（nextRetryAt=+30s）；
 *       后续读/迁移过了 30 秒且 ERP 状态一致 → {@code confirmed}；5 分钟未确认 → {@code failed} + 人工待办 + 降级；
 *       <b>不自动重写</b>；</li>
 *   <li><b>P2-019 三次推送</b>：ACCEPTED / DISPATCHED / COMPLETED 节点提交后调用推送通道（P2 W6 为 mock-channel），
 *       通道失败只记 {@code TicketPush.status=failed}，<b>不阻塞主流程、不伪造成功</b>；</li>
 *   <li><b>回访落库</b>：{@code Ticket.feedback} 随详情返回（刷新不丢评分），回访结果同时回显 {@code feedback}；</li>
 *   <li><b>降级</b>：读路径 ERP 不可用时用平台侧快照降级（{@code fallback=cache} + dataAsOf）；
 *       写路径不可用转人工，不返回伪造数据。</li>
 *   <li><b>P3-028/043 数据范围（服务端强制）</b>：owner 只能访问**本户**数据——读范围 =
 *       {@code TicketRecord.ownerId} 归属等于本人 <b>或</b> {@code roomCode} 等于本人房号
 *       （故历史/ERP 种子单无平台归属时按房号放行）；{@code GET /tickets?mine} 对 owner
 *       恒按本户过滤（含本户房号单）；跨房 → 403 {@code E-2002} + {@code details.reason=out_of_scope}；
 *       staff 不受本户限制（楼栋/片区细化见交付报告遗留项）。</li>
 *   <li><b>P3-043 staff 代报建单</b>：{@code steward}/{@code agent}/{@code supervisor} 可在项目内任意房号
 *       代业主报修（{@code ticket.create}），归属回填 {@code owner-<roomCode>}；owner 仅限本户房号；
 *       {@code it}/{@code admin} → 403 {@code E-2001}（服务层与端点级同口径，不依赖前端隐藏入口）。</li>
 *   <li><b>P3-029 owner 自有单能力</b>：owner 仅可对自有单 {@code reschedule}/{@code urge}
 *       （{@code ticket.reschedule.own}/{@code ticket.urge.own}），其它动作 403 {@code E-2001}
 *       {@code requiredCapability=ticket.assign}；staff 仍用 {@code ticket.assign} 执行全部动作。</li>
 * </ol>
 *
 * <p>P2 存储：{@link InMemoryTicketRepository} + {@link IdempotencyStore} + {@link InMemoryPushRepository}
 * （均进程内）。已知限制：重启/多实例会丢版本号、回写状态、推送记录与平台侧节点；P5 按仓储接口说明迁 PostgreSQL。
 */
@Service
public class TicketService {

    private static final Logger log = LoggerFactory.getLogger(TicketService.class);

    private static final int MAX_PAGE_SIZE = 100;
    /**
     * 平台侧筛选样本页大小：ERP 侧只支持 status/page 下推（mock-erp pageSize 上限 500），
     * category/dateFrom/dateTo/sort 由平台侧在样本内过滤排序。
     */
    private static final int LOCAL_FILTER_SAMPLE = 500;
    /**
     * 样本最多回源页数（P5 W19 修复 task-64）：列表 total 必须是「过滤后真实总数」，
     * 单页 500 条样本在 P5 演示数据（682 单）下会截断 → 逐页回源至 ERP total；上限 500 × 20 = 10000 条。
     */
    private static final int LOCAL_FILTER_SAMPLE_MAX_PAGES = 20;
    private static final Duration DUPLICATE_WINDOW = Duration.ofHours(24);

    private final ErpConnector connector;
    private final TicketRepository repository;
    private final IdempotencyStore idempotencyStore;
    private final ConnectorRuntime runtime;
    private final ConnectorProperties properties;
    private final HandoffSummaryFactory handoffSummaryFactory;
    private final DispatchService dispatchService;
    private final PushService pushService;

    /** 会话内核验结论（房号 → 核验成功时间；契约：5 分钟内在会话内复用） */
    private final ConcurrentMap<String, Instant> verifiedRooms = new ConcurrentHashMap<>();
    /** 人工待办（写失败/回写未确认降级用；不假成功） */
    private final List<PendingHumanTask> pendingHumanTasks = new CopyOnWriteArrayList<>();

    private Clock clock = Clock.system(TicketTimes.BUSINESS_ZONE);
    /** outbox 事件存储（P5-019；可选注入：保持既有单测构造不破，未注入时只记日志） */
    private EventStore eventStore;

    public TicketService(ErpConnector connector, TicketRepository repository, IdempotencyStore idempotencyStore,
            ConnectorRuntime runtime, ConnectorProperties properties, HandoffSummaryFactory handoffSummaryFactory,
            DispatchService dispatchService, PushService pushService) {
        this.connector = connector;
        this.repository = repository;
        this.idempotencyStore = idempotencyStore;
        this.runtime = runtime;
        this.properties = properties;
        this.handoffSummaryFactory = handoffSummaryFactory;
        this.dispatchService = dispatchService;
        this.pushService = pushService;
    }

    /** 事件存储注入（可选：P5-019 outbox；单测不构造时为 null） */
    @Autowired(required = false)
    public void setEventStore(EventStore eventStore) {
        this.eventStore = eventStore;
    }

    /** 测试注入时钟（30 秒确认窗口/ETA 用；生产不调用） */
    void setClockForTest(Clock clock) {
        this.clock = clock == null ? Clock.system(TicketTimes.BUSINESS_ZONE) : clock;
    }

    // ==================================================================
    // 建单
    // ==================================================================

    /**
     * 报修建单（CAP-06，写操作）。成功后触发第一次推送（模板 accepted，不阻塞主流程）。
     *
     * @param idempotencyKey 幂等键（Controller 已校验 8—64 字符）
     */
    public TicketCreateResult create(TicketCreateRequest request, String idempotencyKey, String traceId) {
        return create(request, idempotencyKey, traceId, null);
    }

    /**
     * 报修建单（带演示主体，P3-028/043 数据范围）。
     *
     * <p>数据范围（P3 W13 task-43 修正）：
     * <ul>
     *   <li>{@code owner}：只能为**本户房号**建单（跨房 → 403 {@code E-2002} + {@code out_of_scope}）；</li>
     *   <li>{@code steward}/{@code agent}/{@code supervisor}：<b>项目内任意房号代业主报修</b>
     *       （能力码 {@code ticket.create}），建单归属回填该房演示业主 {@code owner-<roomCode>}；</li>
     *   <li>{@code it}/{@code admin}：403 {@code E-2001}（端点级 {@code ticket.create} + 服务层二次校验）。</li>
     * </ul>
     * 建单时写入 {@code TicketRecord.ownerId} 归属（owner = 自己；staff 代报按房号约定回填），
     * 供后续 get/list(mine)/transition 的本户数据范围判定。
     *
     * @param actor 当前演示主体（{@code null} = security 关闭的离线演示/测试模式，不启用数据范围）
     */
    public TicketCreateResult create(TicketCreateRequest request, String idempotencyKey, String traceId,
            Actor actor) {
        validateCreateRequest(request);
        assertCreateCapability(actor);
        String fingerprint = createFingerprint(request);
        Optional<IdempotencyStore.Entry> replay = checkIdempotency(idempotencyKey, fingerprint);
        if (replay.isPresent()) {
            log.info("[ticket] create 幂等重放 key={} traceId={}", idempotencyKey, traceId);
            return castResult(replay.get().result(), TicketCreateResult.class);
        }

        String requestedRoom = request.roomCode().trim();
        // P3-028/043：owner 只能为本户房号建单（先判范围，避免用 ERP 房档存在性泄露他人房号）；
        // staff 代报（steward/agent/supervisor）项目内任意房号放行
        assertCreateScope(actor, requestedRoom);
        RoomInfo room = queryRoomForCreate(requestedRoom, traceId);
        String roomCode = room.roomCode() == null || room.roomCode().isBlank() ? requestedRoom : room.roomCode();
        assertCreateScope(actor, roomCode);
        assertOwnerVerified(request, roomCode, traceId);
        ensureNoDuplicate(roomCode, request);

        Ticket erpTicket;
        try {
            erpTicket = connector.createTicket(request, idempotencyKey, traceId);
        } catch (ConnectorException ex) {
            recordPendingHuman("ticket.create", "", roomCode, ex);
            throw DomainException.writeFailed(ex.code(),
                    "建单未成功（" + ex.getMessage() + "），已转人工建单待办；请勿换新幂等键重复提交",
                    Map.of("capability", Capability.TICKET_CREATE.label(), "roomCode", roomCode),
                    runtime.degradedFromFailure(ex, DegradedFallback.HUMAN));
        }
        if (erpTicket.ticketNo() == null || erpTicket.ticketNo().isBlank()) {
            recordPendingHuman("ticket.create", "", roomCode,
                    new ConnectorException(ErrorCode.E_5002, "ERP 建单未返回工单号", false));
            throw DomainException.writeFailed(ErrorCode.E_5002, "建单结果缺少工单号，已转人工核实",
                    Map.of("capability", Capability.TICKET_CREATE.label()),
                    runtime.degraded("ERP 建单未返回工单号", DegradedFallback.HUMAN, false, ErrorCode.E_5002, null));
        }

        TicketRecord record = new TicketRecord();
        record.traceId = traceId;
        record.applyFrom(erpTicket);
        record.version = 1;
        // P3-028/043：建单即写归属（owner = 自己；staff 代报按房号约定回填 owner-<roomCode>；解析不到留空，不编造）
        record.ownerId = resolveOwnerId(actor, record.roomCode);
        // 平台侧保留业主填报要素（ERP 侧只回 symptom/expectTime，位置/时长/时段以报修五问为准）
        record.location = firstNonBlank(request.location(), record.location);
        record.duration = firstNonBlank(request.duration(), record.duration);
        record.slot = firstNonBlank(request.slot(), record.slot);
        record.note = request.note() != null ? request.note() : record.note;
        record.updatedAt = firstNonBlank(record.updatedAt, TicketTimes.nowIso());
        record.createdAt = firstNonBlank(record.createdAt, record.updatedAt);
        repository.save(record);

        // P2-019 第一次推送：受理（accepted）。通道失败只记 failed，不影响建单结果
        pushService.pushAccepted(record);

        TicketCreateResult result = new TicketCreateResult(
                record.ticketNo,
                record.status,
                blankToEmpty(record.team),
                blankToEmpty(record.eta),
                record.erp,
                record.mock);
        idempotencyStore.save(idempotencyKey, fingerprint, result);
        log.info("[ticket] create ok ticketNo={} room={} status={} mock={} traceId={}",
                record.ticketNo, record.roomCode, record.status.value(), record.mock, traceId);
        publishTicketEvent(EventType.TICKET_CREATED, record, record.category == null ? null : record.category.value());
        return result;
    }

    // ==================================================================
    // 详情 / 列表 / 推送记录（只读，可降级）
    // ==================================================================

    public FetchResult<Ticket> get(String ticketNo, String traceId) {
        return get(ticketNo, traceId, null);
    }

    /**
     * 工单详情（带演示主体，P3-028/043 数据范围）。
     *
     * <p>owner 读范围（P3 W13 task-43 修正）= <b>本户</b>：{@code TicketRecord.ownerId} 归属等于本人
     * <b>或</b> 工单 {@code roomCode} 等于本人房号；因此本户房号下的历史/ERP 种子单（无 ownerId）
     * 也可读。跨房 → 403 {@code E-2002} + {@code details.reason=out_of_scope}；staff/平台角色不受本户限制。
     * 拒绝本身落 {@code access.deny} 审计（SecurityFilter/AuditFilter 口径）。
     */
    public FetchResult<Ticket> get(String ticketNo, String traceId, Actor actor) {
        requireTicketNo(ticketNo);
        // owner 无平台记录时按 ERP 房号判定本户范围（顺带预取，避免同一请求查两次 ERP）
        Optional<Ticket> prefetched = assertReadScope(ticketNo, actor, traceId);
        try {
            Ticket erpTicket = prefetched.orElseGet(() -> connector.getTicket(ticketNo, traceId));
            TicketRecord record = repository.find(ticketNo).orElseGet(() -> newRecord(ticketNo));
            int version = record.version;
            record.applyFrom(erpTicket);
            record.version = version;
            record.updatedAt = firstNonBlank(record.updatedAt, TicketTimes.nowIso());
            // P2-018：读路径顺带确认 30 秒回写窗口（pending → confirmed / failed）
            WritebackRefresh refresh = refreshWriteback(record, erpTicket, traceId);
            repository.save(record);
            Ticket ticket = record.toTicket();
            return refresh.failed() ? new FetchResult<>(ticket, writebackFailedDegraded()) : FetchResult.ok(ticket);
        } catch (ConnectorException ex) {
            if (ex.code() == ErrorCode.E_3001) {
                throw DomainException.notFound(ErrorCode.E_3001, "工单不存在：" + ticketNo, Map.of("ticketNo", ticketNo));
            }
            Optional<TicketRecord> local = repository.find(ticketNo);
            if (local.isPresent()) {
                log.warn("[ticket] 详情降级为平台快照 ticketNo={} code={} traceId={}", ticketNo,
                        ex.code().value(), traceId);
                WritebackRefresh refresh = refreshWriteback(local.get(), null, traceId);
                repository.save(local.get());
                DegradedInfo degraded = runtime.degradedFromFailure(ex, DegradedFallback.CACHE,
                        local.get().updatedAt);
                return new FetchResult<>(local.get().toTicket(),
                        refresh.failed() ? writebackFailedDegraded() : degraded);
            }
            throw DomainException.upstream(ex.code(), "工单查询失败：" + ex.getMessage(),
                    Map.of("capability", Capability.TICKET_STATUS.label()),
                    runtime.degradedFromFailure(ex, DegradedFallback.HUMAN), ex.retryable());
        }
    }

    /** 兼容 W5 调用（无 v0.3.1 筛选/排序参数） */
    public FetchResult<TicketListResult> list(TicketStatus status, String team, int page, int pageSize,
            String traceId) {
        return list(status, team, null, null, null, null, page, pageSize, false, null, traceId);
    }

    /**
     * 工单列表（v0.3.1：{@code category}/{@code dateFrom}/{@code dateTo}/{@code sort} 增量）。
     *
     * <p>{@code dateFrom}/{@code dateTo} 为 ISO 8601，按 {@code updatedAt} 闭区间过滤；
     * {@code sort} 缺省 {@code updatedAt_desc}，非法值 → 400 E-1001。
     */
    public FetchResult<TicketListResult> list(TicketStatus status, String team, String category,
            String dateFrom, String dateTo, String sort, int page, int pageSize, String traceId) {
        return list(status, team, category, dateFrom, dateTo, sort, page, pageSize, false, null, traceId);
    }

    /**
     * 工单列表（v1.1.0 数据范围增量：{@code mine} + 演示主体，P3-028；P3-043 修正读范围）。
     *
     * <p><b>数据范围始终由服务端强制</b>（契约 {@code GET /tickets} description）：
     * <ul>
     *   <li>{@code owner}：无论 {@code mine} 与否都强制只看**本户**工单——{@code TicketRecord.ownerId}
     *       归属等于本人 <b>或</b> 工单 {@code roomCode} 等于本人房号（历史/ERP 种子单无平台归属时按房号放行）；</li>
     *   <li>{@code steward}/{@code agent}/{@code supervisor}/{@code it}/{@code admin}：本任务不受本户范围限制
     *       （单项目演示 = 本项目/平台范围）；契约里 steward/agent 的 {@code mine=true}「分配给自己的」
     *       需要工单接单人归属字段（契约 {@code assignedTo}）落库，P3 尚未建单接单人归属 → 列为遗留风险。</li>
     * </ul>
     *
     * @param mine 契约 v1.1.0 可选 query（owner 本户过滤开关；owner 恒按本户过滤，staff 暂不受限）
     */
    public FetchResult<TicketListResult> list(TicketStatus status, String team, String category,
            String dateFrom, String dateTo, String sort, int page, int pageSize, boolean mine, Actor actor,
            String traceId) {
        return list(status, team, category, dateFrom, dateTo, sort, page, pageSize, mine, actor, null, traceId);
    }

    /**
     * 工单列表（P5 W19 修复 task-64：项目作用域下沉到「分页前」）。
     *
     * <p>修复前由 {@code TicketController} 先取一页再按项目过滤并用过滤后条数回填 {@code total}，
     * 导致 {@code total} 恒等于本页条数。现将项目作用域作为过滤条件在**全量样本**上先行过滤，
     * 再排序、再分页，{@code total} = 本项目命中总数（契约 v1.3.0 形状不变）。
     *
     * @param scopeProjectId 生效项目作用域（{@code null} = 平台级不过滤）；列表项无 projectId 字段（不改契约），
     *                       按 {@link #projectIdOf(String)} 取平台记录归属
     */
    public FetchResult<TicketListResult> list(TicketStatus status, String team, String category,
            String dateFrom, String dateTo, String sort, int page, int pageSize, boolean mine, Actor actor,
            String scopeProjectId, String traceId) {
        if (page < 1) {
            throw DomainException.badRequest("page 必须 ≥ 1", Map.of("page", page));
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw DomainException.badRequest("pageSize 必须在 1—" + MAX_PAGE_SIZE + " 之间",
                    Map.of("pageSize", pageSize, "maximum", MAX_PAGE_SIZE));
        }
        TicketListSort sortKey = parseSort(sort);
        TicketCategory categoryFilter = parseCategory(category);
        Instant from = parseDateParam("dateFrom", dateFrom);
        Instant to = parseDateParam("dateTo", dateTo);
        if (from != null && to != null && from.isAfter(to)) {
            throw DomainException.badRequest("dateFrom 不能晚于 dateTo",
                    Map.of("field", "dateFrom", "dateFrom", dateFrom, "dateTo", dateTo));
        }
        String teamFilter = team == null || team.isBlank() ? null : team.trim();
        // 状态可下推 ERP（CREATED/REVIEWED/ARCHIVED 为平台侧状态，下推 null 后本地过滤）
        TicketStatus pushdown = ErpFieldMapper.erpStatus(status) == null ? null : status;
        OwnerReadScope ownerScope = ownerReadScope(actor);
        if (mine && log.isDebugEnabled()) {
            log.debug("[ticket] list mine=true role={} ownerRoom={} traceId={}", actor == null ? null : actor.role(),
                    OwnerScope.ownerRoom(actor).orElse(null), traceId);
        }
        try {
            List<TicketListItem> sample = loadTicketSample(pushdown, traceId);
            List<TicketListItem> items = new ArrayList<>();
            for (TicketListItem item : sample) {
                TicketListItem overlaid = overlayListItem(item);
                if (!matchesFilter(overlaid, status, teamFilter, categoryFilter, from, to)) {
                    continue;
                }
                if (!inOwnerScope(overlaid, ownerScope)) {
                    continue;
                }
                if (!inProjectScope(overlaid, scopeProjectId)) {
                    continue;
                }
                items.add(overlaid);
            }
            sortItems(items, sortKey);
            return FetchResult.ok(paginate(items, page, pageSize));
        } catch (ConnectorException ex) {
            List<TicketListItem> items = new ArrayList<>();
            for (TicketRecord record : repository.findAll()) {
                TicketListItem item = record.toListItem();
                if (!matchesFilter(item, status, teamFilter, categoryFilter, from, to)) {
                    continue;
                }
                if (!inOwnerScope(item, ownerScope)) {
                    continue;
                }
                if (!inProjectScope(item, scopeProjectId)) {
                    continue;
                }
                items.add(item);
            }
            sortItems(items, sortKey);
            log.warn("[ticket] 列表降级为平台快照 code={} size={} traceId={}", ex.code().value(), items.size(), traceId);
            return new FetchResult<>(paginate(items, page, pageSize),
                    runtime.degradedFromFailure(ex, DegradedFallback.CACHE));
        }
    }

    /** 项目作用域过滤（P5 W19）：{@code scopeProjectId == null} 不过滤；否则按工单归属项目精确匹配 */
    private boolean inProjectScope(TicketListItem item, String scopeProjectId) {
        if (scopeProjectId == null) {
            return true;
        }
        return scopeProjectId.equals(projectIdOf(item.ticketNo()));
    }

    /**
     * 取「状态下推后的全量工单样本」（P5 W19 修复 task-64）：ERP 逐页回源至其 {@code total}，
     * 保证后续 category/date/team/owner/项目过滤后的 {@code total} 是真实命中数，而不是单页样本上限。
     *
     * <p>页数受 {@link #LOCAL_FILTER_SAMPLE_MAX_PAGES} 限制（防 ERP 分页无上限拖垮请求）；
     * ERP 不返回 {@code total}（=0）时按「返回条数 < 页大小」终止。
     */
    private List<TicketListItem> loadTicketSample(TicketStatus pushdown, String traceId) {
        List<TicketListItem> all = new ArrayList<>();
        for (int pageNo = 1; pageNo <= LOCAL_FILTER_SAMPLE_MAX_PAGES; pageNo++) {
            TicketListResult chunk = connector.listTickets(pushdown, null, pageNo, LOCAL_FILTER_SAMPLE, traceId);
            if (chunk.items().isEmpty()) {
                break;
            }
            all.addAll(chunk.items());
            if (all.size() >= chunk.total() || chunk.items().size() < LOCAL_FILTER_SAMPLE) {
                break;
            }
        }
        return List.copyOf(all);
    }

    /**
     * 推送记录（P2-019 / 契约 v0.3.1 {@code GET /tickets/{ticketNo}/pushes}）。
     *
     * <p>数据源 = 平台侧 {@link PushRepository}（内存实现 + 接口，P5 迁 PG）；mock-channel 的
     * {@code /channels/records} 只作留痕核对。工单不存在 → 404 E-3001（平台无记录时向 ERP 核验存在性）。
     */
    public FetchResult<TicketPushListResult> pushes(String ticketNo, String traceId) {
        return pushes(ticketNo, traceId, null);
    }

    /**
     * 推送记录（带演示主体，P3-028/043 数据范围）：owner 只能看本户（本户自有单或本户房号单）的推送留痕，
     * 跨房 → 403 {@code E-2002}（staff 不受本户限制）。
     */
    public FetchResult<TicketPushListResult> pushes(String ticketNo, String traceId, Actor actor) {
        requireTicketNo(ticketNo);
        Optional<Ticket> prefetched = assertReadScope(ticketNo, actor, traceId);
        List<TicketPush> items = pushService.listByTicket(ticketNo);
        TicketPushListResult result = new TicketPushListResult(items);
        if (repository.find(ticketNo).isPresent() || prefetched.isPresent()) {
            return FetchResult.ok(result);
        }
        try {
            connector.getTicket(ticketNo, traceId);
            return FetchResult.ok(result);
        } catch (ConnectorException ex) {
            if (ex.code() == ErrorCode.E_3001) {
                throw DomainException.notFound(ErrorCode.E_3001, "工单不存在：" + ticketNo,
                        Map.of("ticketNo", ticketNo));
            }
            // 推送记录是平台侧数据：ERP 不可用不影响返回平台已留痕记录，标降级即可
            return new FetchResult<>(result, runtime.degradedFromFailure(ex, DegradedFallback.CACHE));
        }
    }

    // ==================================================================
    // 状态迁移（含 P2-017 派单 / P2-018 完工回写 / P2-019 推送）
    // ==================================================================

    /**
     * 状态迁移（写操作：幂等键必填 + 乐观锁 version）。
     *
     * <p>返回 {@link FetchResult}：正常迁移无 {@code degraded}；<b>完工回写失败</b>时 200 + {@code degraded{human}}
     * + {@code erpWriteback.failed}（工单保持 ON_SITE，未提交完工，不自动重试）。
     */
    public FetchResult<TicketTransitionResult> transition(String ticketNo, TicketTransitionAction action,
            Map<String, Object> payload, int version, String idempotencyKey, String traceId) {
        return transition(ticketNo, action, payload, version, idempotencyKey, traceId, null);
    }

    /**
     * 状态迁移（带演示主体，P3-029 动作级能力 + P3-028 数据范围二次校验）。
     *
     * <p>校验顺序（与契约响应语义一致）：参数（400）→ 动作级能力（403 {@code E-2001}）→
     * 数据范围（403 {@code E-2002}）→ 幂等 → 版本（409）→ 状态机（409）。
     *
     * <p><b>动作级能力</b>（契约 v1.1.0 §7.2）：owner 只能对本户自有单执行
     * {@code reschedule}/{@code urge}（能力码 {@code ticket.reschedule.own}/{@code ticket.urge.own}）；
     * owner 的 {@code dispatch}/{@code on_site}/{@code complete} → 403 {@code E-2001}
     * {@code details.requiredCapability=ticket.assign}；staff 仍需 {@code ticket.assign}。
     * <b>数据范围</b>：owner 操作他人/历史种子单 → 403 {@code E-2002}
     * {@code details{reason=out_of_scope, resourceType=ticket, resourceId=ticketNo}}。
     */
    public FetchResult<TicketTransitionResult> transition(String ticketNo, TicketTransitionAction action,
            Map<String, Object> payload, int version, String idempotencyKey, String traceId, Actor actor) {
        requireTicketNo(ticketNo);
        if (action == null) {
            throw DomainException.badRequest("action 必填（dispatch/on_site/complete/reschedule/urge）",
                    Map.of("field", "action"));
        }
        if (version < 1) {
            throw DomainException.badRequest("version 必填且 ≥1（取自工单详情 version）", Map.of("field", "version"));
        }
        assertTransitionCapability(actor, action);
        assertTransitionScope(ticketNo, actor);
        String fingerprint = transitionFingerprint(ticketNo, action, payload, version);
        Optional<IdempotencyStore.Entry> replay = checkIdempotency(idempotencyKey, fingerprint);
        if (replay.isPresent()) {
            log.info("[ticket] transition 幂等重放 ticketNo={} action={} traceId={}", ticketNo, action.value(), traceId);
            return FetchResult.ok(castResult(replay.get().result(), TicketTransitionResult.class));
        }

        LoadedRecord loaded = loadForWrite(ticketNo, traceId);
        TicketRecord record = loaded.record();
        if (record.version != version) {
            throw DomainException.conflict("工单版本已变化，请重新获取详情后重试", versionConflictDetails(record, version));
        }
        TicketTransitions.Rule rule = TicketTransitions.rule(action);
        if (!rule.allows(record.status)) {
            throw DomainException.conflict("当前状态不支持该操作",
                    invalidTransitionDetails(record.status, action));
        }
        validateActionPayload(action, payload);

        Instant now = clock.instant();
        // 先确认上一次回写（例如 dispatch 的 pending 在 30 秒后确认），再处理本次动作
        WritebackRefresh previousWriteback = refreshWriteback(record, loaded.erpTicket(), traceId);

        Map<String, Object> erpPayload = payload == null ? new LinkedHashMap<>() : new LinkedHashMap<>(payload);
        DispatchService.Assignment dispatchAssignment = null;
        if (action == TicketTransitionAction.DISPATCH) {
            dispatchAssignment = resolveDispatch(record, payload);
            erpPayload.put("team", dispatchAssignment.teamName());
            erpPayload.put("eta", dispatchAssignment.etaText());
            if (!blank(dispatchAssignment.assignee())) {
                erpPayload.put("assignee", dispatchAssignment.assignee());
            }
            if (!blank(dispatchAssignment.skillTag())) {
                erpPayload.put("skillTag", dispatchAssignment.skillTag());
            }
        }

        int erpNodeCountBefore = record.erpNodes.size();
        Ticket erpTicket;
        try {
            erpTicket = connector.transitionTicket(ticketNo, action, erpPayload, idempotencyKey, traceId);
        } catch (ConnectorException ex) {
            if (action == TicketTransitionAction.COMPLETE) {
                // P2-018：完工回写失败 → 事务内不提交完工（保持 ON_SITE），落人工待办 + 降级，不自动重试
                return completeWritebackFailed(record, ex, idempotencyKey, fingerprint, traceId);
            }
            recordPendingHuman("ticket." + action.value(), ticketNo, record.roomCode, ex);
            throw DomainException.writeFailed(ex.code(),
                    "工单" + TicketTransitions.actionText(action) + "未成功（" + ex.getMessage() + "），已转人工处理",
                    Map.of("capability", Capability.TICKET_TRANSITION.label(), "ticketNo", ticketNo,
                            "action", action.value()),
                    runtime.degradedFromFailure(ex, DegradedFallback.HUMAN));
        }

        record.applyFrom(erpTicket);
        applyActionEffects(record, action, erpPayload);
        List<TicketAuditNode> newNodes = collectAuditNodes(record, action, erpPayload, erpNodeCountBefore);
        record.version = record.version + 1;
        record.updatedAt = firstNonBlank(erpTicket.updatedAt(), TicketTimes.nowIso());

        // P2-018：dispatch / complete 写成功 → 进入 30 秒回写确认窗口
        ErpWriteback echoedWriteback;
        if (action == TicketTransitionAction.DISPATCH || action == TicketTransitionAction.COMPLETE) {
            ErpWritebackState.Kind kind = action == TicketTransitionAction.DISPATCH
                    ? ErpWritebackState.Kind.DISPATCH
                    : ErpWritebackState.Kind.COMPLETE;
            TicketStatus target = action == TicketTransitionAction.DISPATCH
                    ? TicketStatus.DISPATCHED
                    : TicketStatus.COMPLETED;
            String erpRef = record.erp == null ? null : record.erp.ref();
            record.writeback = ErpWritebackState.pending(kind, erpRef, target, now);
            echoedWriteback = record.writeback.toDto();
        } else {
            echoedWriteback = previousWriteback.changed() ? previousWriteback.writeback() : null;
        }
        repository.save(record);

        // P2-019：第二次（派单/出发）与第三次（完工）推送；失败只记 failed，不影响迁移结果
        if (action == TicketTransitionAction.DISPATCH) {
            pushService.pushDispatched(record);
        } else if (action == TicketTransitionAction.COMPLETE) {
            pushService.pushCompleted(record, asText(payload == null ? null : payload.get("resolution")));
        }

        DispatchPayload dispatchEcho = dispatchAssignment == null ? null : new DispatchPayload(
                dispatchAssignment.teamId(),
                dispatchAssignment.teamName(),
                dispatchAssignment.etaText(),
                blank(dispatchAssignment.skillTag()) ? null : dispatchAssignment.skillTag(),
                blank(dispatchAssignment.assignee()) ? null : dispatchAssignment.assignee());

        String auditEventId = auditEventId(record.ticketNo, record.version);
        TicketTransitionResult result = new TicketTransitionResult(
                record.ticketNo,
                record.status,
                record.statusText(),
                record.version,
                newNodes,
                auditEventId,
                record.mock,
                echoedWriteback,
                dispatchEcho);
        idempotencyStore.save(idempotencyKey, fingerprint, result);
        log.info("[ticket] transition ok ticketNo={} action={} status={} version={} writeback={} auditEventId={} traceId={}",
                ticketNo, action.value(), record.status.value(), record.version,
                echoedWriteback == null ? "none" : echoedWriteback.status().value(), auditEventId, traceId);
        publishTicketEvent(EventType.TICKET_TRANSITIONED, record, action.value());
        DegradedInfo degraded = previousWriteback.failed() && action != TicketTransitionAction.COMPLETE
                ? writebackFailedDegraded()
                : null;
        return new FetchResult<>(result, degraded);
    }

    /** 完工回写失败：不提交完工（保持 ON_SITE），200 + erpWriteback.failed + 人工待办 + degraded{human} */
    private FetchResult<TicketTransitionResult> completeWritebackFailed(TicketRecord record, ConnectorException ex,
            String idempotencyKey, String fingerprint, String traceId) {
        recordPendingHuman("ticket.complete", record.ticketNo, record.roomCode, ex);
        String message = "完工回写 ERP 失败（" + ex.code().value() + "），未提交完工（保持"
                + TicketTransitions.statusText(record.status) + "），已转人工处理（不自动重写）";
        record.writeback = ErpWritebackState.failed(ErpWritebackState.Kind.COMPLETE,
                record.erp == null ? null : record.erp.ref(), message, clock.instant());
        repository.save(record);

        TicketTransitionResult result = new TicketTransitionResult(
                record.ticketNo,
                record.status,
                record.statusText(),
                record.version,
                List.of(),
                auditEventId(record.ticketNo, record.version),
                record.mock,
                record.writeback.toDto(),
                null);
        idempotencyStore.save(idempotencyKey, fingerprint, result);
        log.warn("[ticket] 完工回写失败，未提交完工，已转人工 ticketNo={} code={} traceId={}",
                record.ticketNo, ex.code().value(), traceId);
        return new FetchResult<>(result, runtime.degraded(message, DegradedFallback.HUMAN, false, ex.code(), null));
    }

    // ==================================================================
    // 回访评价
    // ==================================================================

    public TicketFeedbackResult feedback(String ticketNo, TicketFeedbackRequest request, String idempotencyKey,
            String traceId) {
        return feedback(ticketNo, request, idempotencyKey, traceId, null);
    }

    /**
     * 回访评价（带演示主体，P3-028 数据范围）：owner 只能评价**本户自有单**，
     * 跨房/历史种子单 → 403 {@code E-2002}（staff 不受本户限制）。
     */
    public TicketFeedbackResult feedback(String ticketNo, TicketFeedbackRequest request, String idempotencyKey,
            String traceId, Actor actor) {
        requireTicketNo(ticketNo);
        if (request == null) {
            throw DomainException.badRequest("请求体不能为空", Map.of("field", "body"));
        }
        if (request.score() < 1 || request.score() > 5) {
            throw DomainException.badRequest("score 必须为 1—5 的整数", Map.of("field", "score", "score", request.score()));
        }
        assertTransitionScope(ticketNo, actor);
        String fingerprint = feedbackFingerprint(ticketNo, request);
        Optional<IdempotencyStore.Entry> replay = checkIdempotency(idempotencyKey, fingerprint);
        if (replay.isPresent()) {
            log.info("[ticket] feedback 幂等重放 ticketNo={} traceId={}", ticketNo, traceId);
            return castResult(replay.get().result(), TicketFeedbackResult.class);
        }

        TicketRecord record = loadForWrite(ticketNo, traceId).record();
        if (!TicketTransitions.canFeedback(record.status)) {
            throw DomainException.conflict("仅已完工（COMPLETED）工单可回访评价",
                    feedbackDetails(record.status));
        }
        try {
            connector.submitFeedback(ticketNo, request, idempotencyKey, traceId);
        } catch (ConnectorException ex) {
            recordPendingHuman("ticket.feedback", ticketNo, record.roomCode, ex);
            throw DomainException.writeFailed(ex.code(),
                    "回访评价未成功（" + ex.getMessage() + "），已转人工记录",
                    Map.of("capability", Capability.TICKET_FEEDBACK.label(), "ticketNo", ticketNo),
                    runtime.degradedFromFailure(ex, DegradedFallback.HUMAN));
        }

        String reviewedAt = TicketTimes.nowIso();
        boolean supervisorAlert = request.score() <= 2;
        List<String> tags = request.tags() == null ? List.of() : List.copyOf(request.tags());
        record.status = TicketStatus.REVIEWED;
        record.reviewedAt = reviewedAt;
        record.feedbackScore = request.score();
        record.feedbackTags = tags;
        record.feedbackNote = request.note();
        record.version = record.version + 1;
        record.updatedAt = reviewedAt;
        record.appendLocalNode(new TicketAuditNode(
                TicketStatus.REVIEWED,
                TicketTransitions.statusText(TicketStatus.REVIEWED),
                reviewedAt,
                "业主",
                supervisorAlert ? "满意度较低（" + request.score() + " 分），已触发主管提醒" : "回访完成，感谢反馈"));
        repository.save(record);

        // v0.3.1：回访结果回显 + 落库（详情 Ticket.feedback 刷新不丢）
        TicketFeedbackSummary summary = record.feedbackSummary();
        TicketFeedbackResult result = new TicketFeedbackResult(
                ticketNo,
                TicketStatus.REVIEWED,
                TicketTransitions.statusText(TicketStatus.REVIEWED),
                request.score(),
                tags,
                supervisorAlert,
                reviewedAt,
                record.mock,
                summary);
        idempotencyStore.save(idempotencyKey, fingerprint, result);
        log.info("[ticket] feedback ok ticketNo={} score={} supervisorAlert={} traceId={}",
                ticketNo, request.score(), supervisorAlert, traceId);
        publishTicketEvent(EventType.TICKET_FEEDBACK, record, String.valueOf(request.score()));
        return result;
    }

    // ==================================================================
    // 转人工摘要（平台内生成）
    // ==================================================================

    public FetchResult<HandoffSummary> handoff(String ticketNo, String traceId) {
        requireTicketNo(ticketNo);
        FetchResult<Ticket> fetched = get(ticketNo, traceId);
        Ticket ticket = fetched.data();
        String ownerNameMasked = "";
        DegradedInfo degraded = fetched.degraded();
        try {
            RoomInfo room = connector.queryRoom(ticket.roomCode(), traceId);
            ownerNameMasked = room.ownerNameMasked() == null ? "" : room.ownerNameMasked();
        } catch (ConnectorException ex) {
            if (ex.code() == ErrorCode.E_3001) {
                ownerNameMasked = "";
            } else if (degraded == null) {
                degraded = runtime.degradedFromFailure(ex, DegradedFallback.PARTIAL);
            }
        }
        return new FetchResult<>(handoffSummaryFactory.build(ticket, ownerNameMasked), degraded);
    }

    // ==================================================================
    // 业主核验会话（供 OwnerController 记录、建单前置复用）
    // ==================================================================

    public void recordOwnerVerified(String roomCode) {
        if (roomCode != null && !roomCode.isBlank()) {
            verifiedRooms.put(roomCode.trim(), Instant.now());
        }
    }

    public boolean isSessionVerified(String roomCode) {
        if (roomCode == null || roomCode.isBlank()) {
            return false;
        }
        Instant verifiedAt = verifiedRooms.get(roomCode.trim());
        if (verifiedAt == null) {
            return false;
        }
        if (Instant.now().isAfter(verifiedAt.plusMillis(properties.getOwnerVerificationTtlMs()))) {
            verifiedRooms.remove(roomCode.trim());
            return false;
        }
        return true;
    }

    /** 人工待办快照（写失败/回写未确认降级落库；待办页消费） */
    public List<PendingHumanTask> pendingHumanTasks() {
        return List.copyOf(pendingHumanTasks);
    }

    // ==================================================================
    // 内部实现
    // ==================================================================

    private void validateCreateRequest(TicketCreateRequest request) {
        if (request == null) {
            throw DomainException.badRequest("请求体不能为空", Map.of("field", "body"));
        }
        Map<String, Object> missing = new LinkedHashMap<>();
        List<String> fields = new ArrayList<>();
        if (blank(request.roomCode())) {
            fields.add("roomCode");
        }
        if (request.category() == null) {
            fields.add("category");
        }
        if (request.urgency() == null) {
            fields.add("urgency");
        }
        if (blank(request.location())) {
            fields.add("location");
        }
        if (request.downstairs() == null) {
            fields.add("downstairs");
        }
        if (blank(request.duration())) {
            fields.add("duration");
        }
        if (blank(request.slot())) {
            fields.add("slot");
        }
        if (!fields.isEmpty()) {
            missing.put("missing", fields);
            throw DomainException.badRequest("报修要素缺失：" + String.join("、", fields) + "（五问 + 预约时段）", missing);
        }
        if (request.contactTail4() != null && !request.contactTail4().isBlank()
                && !request.contactTail4().trim().matches("\\d{4}")) {
            throw DomainException.badRequest("contactTail4 必须为 4 位数字", Map.of("field", "contactTail4"));
        }
    }

    private RoomInfo queryRoomForCreate(String roomCode, String traceId) {
        try {
            return connector.queryRoom(roomCode, traceId);
        } catch (ConnectorException ex) {
            if (ex.code() == ErrorCode.E_3001) {
                throw DomainException.notFound(ErrorCode.E_3001, "房号不存在：" + roomCode, Map.of("roomCode", roomCode));
            }
            recordPendingHuman("ticket.create", "", roomCode, ex);
            throw DomainException.writeFailed(ex.code(),
                    "房档查询失败（" + ex.getMessage() + "），建单已转人工待办",
                    Map.of("capability", Capability.ROOM_QUERY.label(), "roomCode", roomCode),
                    runtime.degradedFromFailure(ex, DegradedFallback.HUMAN));
        }
    }

    private void assertOwnerVerified(TicketCreateRequest request, String roomCode, String traceId) {
        // 管家代报（steward）不需要业主核验；业主/家属/租户按核验结论
        if (request.reporterType() == ReporterType.STEWARD) {
            return;
        }
        String tail = request.contactTail4();
        if (tail != null && !tail.isBlank()) {
            OwnerVerifyResult verify;
            try {
                verify = connector.verifyOwner(new OwnerVerifyRequest(roomCode, tail.trim()), traceId);
            } catch (ConnectorException ex) {
                if (ex.code() == ErrorCode.E_3001) {
                    throw DomainException.notFound(ErrorCode.E_3001, "房号不存在：" + roomCode,
                            Map.of("roomCode", roomCode));
                }
                throw DomainException.upstream(ex.code(), "业主核验暂不可用（" + ex.getMessage() + "），请稍后重试或转人工",
                        Map.of("capability", Capability.OWNER_VERIFY.label()),
                        runtime.degradedFromFailure(ex, DegradedFallback.HUMAN), ex.retryable());
            }
            if (verify == null || verify.locked() || !verify.verified()) {
                Integer attemptsLeft = verify == null ? null : verify.attemptsLeft();
                throw DomainException.unverified("核验未通过，请核对手机号后四位或联系人工客服",
                        Map.of("attemptsLeft", attemptsLeft == null ? 0 : attemptsLeft),
                        runtime.degraded("业主核验未通过（不提示错因）", DegradedFallback.HUMAN, false,
                                ErrorCode.E_2001, null));
            }
            recordOwnerVerified(roomCode);
            return;
        }
        if (isSessionVerified(roomCode)) {
            return;
        }
        if (properties.isEnforceOwnerVerification()) {
            throw DomainException.unverified("请先完成业主核验（手机号后四位）后再提交报修",
                    Map.of("roomCode", roomCode, "hint", "verify_first_or_pass_contactTail4"),
                    runtime.degraded("未完成业主核验", DegradedFallback.HUMAN, false, ErrorCode.E_2001, null));
        }
        log.warn("[ticket] 建单未带核验因子且未会话核验（enforce=false 放行）room={} traceId={}", roomCode, traceId);
    }

    private void ensureNoDuplicate(String roomCode, TicketCreateRequest request) {
        Instant since = Instant.now().minus(DUPLICATE_WINDOW);
        for (TicketRecord record : repository.findAll()) {
            if (!Objects.equals(roomCode, record.roomCode)) {
                continue;
            }
            if (request.category() != null && record.category != request.category()) {
                continue;
            }
            Instant created = TicketTimes.parseInstant(record.createdAt);
            if (created != null && created.isAfter(since)) {
                log.info("[ticket] 同房 24h 重复建单 room={} existing={}", roomCode, record.ticketNo);
                throw DomainException.duplicateNeedConfirm(roomCode, record.ticketNo);
            }
        }
    }

    /** 写前置读取：ERP 权威状态优先，ERP 不可用时用平台快照（真正写失败仍会 503 转人工） */
    private LoadedRecord loadForWrite(String ticketNo, String traceId) {
        try {
            Ticket erpTicket = connector.getTicket(ticketNo, traceId);
            TicketRecord record = repository.find(ticketNo).orElseGet(() -> newRecord(ticketNo));
            int version = record.version;
            record.applyFrom(erpTicket);
            record.version = version;
            repository.save(record);
            return new LoadedRecord(record, erpTicket);
        } catch (ConnectorException ex) {
            if (ex.code() == ErrorCode.E_3001) {
                throw DomainException.notFound(ErrorCode.E_3001, "工单不存在：" + ticketNo, Map.of("ticketNo", ticketNo));
            }
            Optional<TicketRecord> local = repository.find(ticketNo);
            if (local.isPresent()) {
                return new LoadedRecord(local.get(), null);
            }
            throw DomainException.upstream(ex.code(), "工单查询失败：" + ex.getMessage(),
                    Map.of("capability", Capability.TICKET_STATUS.label()),
                    runtime.degradedFromFailure(ex, DegradedFallback.HUMAN), ex.retryable());
        }
    }

    private void validateActionPayload(TicketTransitionAction action, Map<String, Object> payload) {
        Map<String, Object> source = payload == null ? Map.of() : payload;
        switch (action) {
            case DISPATCH -> {
                // P2-017：未带 teamName/teamId → 服务端按技能/楼栋/队列自动派单（不报错）；
                // 带了则由 resolveDispatch 校验班组是否存在（非法 → 400 E-1003）
            }
            case COMPLETE -> {
                if (blank(asText(source.get("resolution")))) {
                    throw DomainException.badRequest(
                            "完工必须提供 resolution（结案说明，业主可见）", Map.of("field", "resolution"));
                }
            }
            case RESCHEDULE -> {
                if (blank(asText(source.get("slot")))) {
                    throw DomainException.badRequest("改约必须提供 slot（新的上门时段）", Map.of("field", "slot"));
                }
            }
            default -> {
                // on_site / urge 的 payload 均为可选
            }
        }
    }

    /** P2-017 派单解析：自动派单或校验人工指定班组；返回派单回显（班组/ETA/技能/接单人） */
    private DispatchService.Assignment resolveDispatch(TicketRecord record, Map<String, Object> payload) {
        Map<String, Object> source = payload == null ? Map.of() : payload;
        String teamName = firstNonBlank(asText(source.get("teamName")), asText(source.get("team")));
        String teamId = asText(source.get("teamId"));
        String key = blank(teamId) ? teamName : teamId;
        if (blank(key)) {
            return dispatchService.autoAssign(record.category, record.urgency, record.roomCode);
        }
        String eta = asText(source.get("eta"));
        String assignee = asText(source.get("assignee"));
        String skillTag = asText(source.get("skillTag"));
        return dispatchService.assignTo(key, record.category, record.urgency, record.roomCode,
                blank(eta) ? null : eta,
                blank(assignee) ? null : assignee,
                blank(skillTag) ? null : skillTag);
    }

    private void applyActionEffects(TicketRecord record, TicketTransitionAction action, Map<String, Object> payload) {
        Map<String, Object> source = payload == null ? Map.of() : payload;
        switch (action) {
            case DISPATCH -> {
                String team = asText(source.get("team"));
                if (!blank(team)) {
                    record.team = team;
                }
                String eta = asText(source.get("eta"));
                if (!blank(eta)) {
                    record.eta = eta;
                }
            }
            case ON_SITE -> {
                String arrivedAt = asText(source.get("arrivedAt"));
                if (!blank(arrivedAt)) {
                    record.updatedAt = arrivedAt;
                }
            }
            case COMPLETE -> {
                String finishedAt = asText(source.get("finishedAt"));
                if (!blank(finishedAt)) {
                    record.updatedAt = finishedAt;
                }
            }
            case RESCHEDULE -> {
                String slot = asText(source.get("slot"));
                if (!blank(slot)) {
                    record.slot = slot;
                }
                String eta = asText(source.get("eta"));
                if (!blank(eta)) {
                    record.eta = eta;
                }
            }
            case URGE -> record.urgeCount = record.urgeCount + 1;
        }
    }

    private List<TicketAuditNode> collectAuditNodes(TicketRecord record, TicketTransitionAction action,
            Map<String, Object> payload, int erpNodeCountBefore) {
        if (record.erpNodes.size() > erpNodeCountBefore) {
            // ERP 已生成节点（派单/上门/完工）：取本次新增的最后一个（契约：一次迁移一个节点）
            List<TicketAuditNode> added = record.erpNodes.subList(erpNodeCountBefore, record.erpNodes.size());
            TicketAuditNode last = added.get(added.size() - 1);
            String text = blank(last.statusText()) ? TicketTransitions.statusText(last.status()) : last.statusText();
            return List.of(new TicketAuditNode(last.status(), text, last.at(), last.who(), last.ownerText()));
        }
        // ERP 未出节点（改约/催办）：平台侧补充审计节点
        TicketAuditNode synthesized = synthesizeNode(record, action, payload);
        record.appendLocalNode(synthesized);
        return List.of(synthesized);
    }

    private TicketAuditNode synthesizeNode(TicketRecord record, TicketTransitionAction action,
            Map<String, Object> payload) {
        Map<String, Object> source = payload == null ? Map.of() : payload;
        String at = TicketTimes.nowIso();
        String ownerText = switch (action) {
            case DISPATCH -> blank(record.team) ? "已派单" : record.team + " · " + (blank(record.eta) ? "已派单" : record.eta + "上门");
            case ON_SITE -> "工程师已上门处理";
            case COMPLETE -> {
                String resolution = firstNonBlank(asText(source.get("resolution")), asText(source.get("note")));
                yield blank(resolution) ? "已完工，等待回访" : "已完工：" + resolution;
            }
            case RESCHEDULE -> "已改约至 " + (blank(record.slot) ? "新时段" : record.slot);
            case URGE -> {
                String reason = asText(source.get("reason"));
                yield blank(reason) ? "已催办" : "已催办：" + reason;
            }
        };
        String who = switch (action) {
            case DISPATCH -> blank(record.team) ? "客服中心" : record.team;
            case ON_SITE, COMPLETE -> blank(record.team) ? "维修工程师" : record.team;
            case RESCHEDULE, URGE -> "客服中心";
        };
        return new TicketAuditNode(record.status, record.statusText(), at, who, ownerText);
    }

    private TicketListItem overlayListItem(TicketListItem item) {
        Optional<TicketRecord> found = repository.find(item.ticketNo());
        if (found.isEmpty()) {
            return item;
        }
        TicketRecord record = found.get();
        // ERP 仍为 COMPLETED 而平台已回访 → 覆盖为 REVIEWED（回访结论为平台侧语义）
        TicketStatus status = record.status == TicketStatus.REVIEWED && item.status() == TicketStatus.COMPLETED
                ? TicketStatus.REVIEWED
                : item.status();
        String team = blank(item.team()) ? record.team : item.team();
        String eta = blank(item.eta()) ? record.eta : item.eta();
        return new TicketListItem(
                item.ticketNo(),
                item.roomCode(),
                item.category(),
                item.urgency(),
                status,
                TicketTransitions.statusText(status),
                team,
                eta,
                record.version,
                item.aiCreated(),
                item.mock(),
                item.createdAt(),
                item.updatedAt());
    }

    /** v0.3.1 列表筛选：status/team/category + updatedAt 闭区间 */
    private boolean matchesFilter(TicketListItem item, TicketStatus status, String team, TicketCategory category,
            Instant from, Instant to) {
        if (status != null && item.status() != status) {
            return false;
        }
        if (team != null && !team.equals(item.team())) {
            return false;
        }
        if (category != null && item.category() != category) {
            return false;
        }
        if (from == null && to == null) {
            return true;
        }
        Instant updatedAt = TicketTimes.parseInstant(item.updatedAt());
        if (updatedAt == null) {
            return false;
        }
        return (from == null || !updatedAt.isBefore(from)) && (to == null || !updatedAt.isAfter(to));
    }

    /** v0.3.1 排序（默认 updatedAt_desc；稳定 tie-break = ticketNo） */
    private void sortItems(List<TicketListItem> items, TicketListSort sort) {
        Comparator<TicketListItem> comparator = switch (sort) {
            case UPDATED_AT_DESC -> Comparator.comparing(TicketListItem::updatedAt,
                    Comparator.nullsLast(Comparator.reverseOrder()));
            case CREATED_AT_ASC -> Comparator.comparing(TicketListItem::createdAt,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            case CREATED_AT_DESC -> Comparator.comparing(TicketListItem::createdAt,
                    Comparator.nullsLast(Comparator.reverseOrder()));
        };
        items.sort(comparator.thenComparing(TicketListItem::ticketNo,
                Comparator.nullsLast(Comparator.naturalOrder())));
    }

    private TicketCategory parseCategory(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return TicketCategory.fromValue(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw DomainException.badRequest(
                    "category 非法：" + raw + "（可选 plumbing / electrical / elevator / door_window / public_facility / other）",
                    Map.of("field", "category", "value", raw,
                            "allowed", List.of("plumbing", "electrical", "elevator", "door_window",
                                    "public_facility", "other")));
        }
    }

    private TicketListSort parseSort(String raw) {
        if (raw == null || raw.isBlank()) {
            return TicketListSort.UPDATED_AT_DESC;
        }
        try {
            return TicketListSort.fromValue(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw DomainException.badRequest(
                    "sort 非法：" + raw + "（可选 updatedAt_desc / createdAt_asc / createdAt_desc）",
                    Map.of("field", "sort", "value", raw,
                            "allowed", List.of("updatedAt_desc", "createdAt_asc", "createdAt_desc")));
        }
    }

    private Instant parseDateParam(String field, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (RuntimeException ignored) {
            // 继续尝试纯 UTC（Z）形式
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException ex) {
            throw DomainException.badRequest(
                    field + " 必须为 ISO 8601 date-time（如 2026-11-01T00:00:00+08:00）",
                    Map.of("field", field, "value", raw));
        }
    }

    // ------------------------------------------------------------------
    // P2-018 ERP 回写状态机（pending → confirmed / failed）
    // ------------------------------------------------------------------

    /**
     * 刷新回写状态：pending 在 30 秒后按「ERP 权威状态是否等于目标状态」确认；
     * 5 分钟仍未确认 → failed + 人工待办（不自动重写）。
     */
    private WritebackRefresh refreshWriteback(TicketRecord record, Ticket erpTicket, String traceId) {
        ErpWritebackState state = record.writeback;
        if (state == null) {
            return new WritebackRefresh(null, false, false);
        }
        if (state.status != ErpWritebackStatus.PENDING) {
            return new WritebackRefresh(state.toDto(), false,
                    state.status == ErpWritebackStatus.FAILED);
        }
        Instant now = clock.instant();
        if (!state.confirmationDue(now)) {
            return new WritebackRefresh(state.toDto(), false, false);
        }
        boolean confirmed = erpTicket != null && state.targetStatus != null
                && erpTicket.status() == state.targetStatus;
        if (confirmed) {
            state.status = ErpWritebackStatus.CONFIRMED;
            state.confirmedAt = now;
            state.confirmAttempts++;
            state.message = ErpWritebackState.CONFIRMED_MESSAGE + "（"
                    + TicketTransitions.statusText(state.targetStatus) + "）";
            log.info("[ticket] ERP 回写已确认 ticketNo={} kind={} traceId={}", record.ticketNo, state.kind, traceId);
            return new WritebackRefresh(state.toDto(), true, false);
        }
        if (state.humanAlarmDue(now)) {
            state.status = ErpWritebackStatus.FAILED;
            state.confirmAttempts++;
            state.message = "ERP 回写 5 分钟内未确认，已转人工核对（不自动重写）";
            recordPendingHuman("ticket." + state.kind.name().toLowerCase() + ".writeback", record.ticketNo,
                    record.roomCode, state.message, ErrorCode.E_4001.value());
            log.warn("[ticket] ERP 回写 5 分钟未确认，已转人工 ticketNo={} kind={} traceId={}",
                    record.ticketNo, state.kind, traceId);
            return new WritebackRefresh(state.toDto(), true, true);
        }
        state.confirmAttempts++;
        return new WritebackRefresh(state.toDto(), false, false);
    }

    private DegradedInfo writebackFailedDegraded() {
        return runtime.degraded("ERP 回写 5 分钟内未确认，已转人工核对（不自动重写）",
                DegradedFallback.HUMAN, false, null, null);
    }

    private TicketListResult paginate(List<TicketListItem> items, int page, int pageSize) {
        int from = Math.min((page - 1) * pageSize, items.size());
        int to = Math.min(from + pageSize, items.size());
        return new TicketListResult(List.copyOf(items.subList(from, to)), page, pageSize, items.size());
    }

    private TicketRecord newRecord(String ticketNo) {
        TicketRecord record = new TicketRecord();
        record.ticketNo = ticketNo;
        record.version = 1;
        return record;
    }

    private String auditEventId(String ticketNo, int version) {
        String suffix = ticketNo != null && ticketNo.length() >= 4 ? ticketNo.substring(ticketNo.length() - 4) : "0000";
        return "AUD-" + TicketTimes.compactDate() + "-" + suffix + "-" + String.format("%03d", version);
    }

    private void recordPendingHuman(String kind, String ticketNo, String roomCode, ConnectorException ex) {
        pendingHumanTasks.add(PendingHumanTask.of(kind, ticketNo, roomCode, ex.getMessage(), ex.code().value()));
        log.warn("[ticket] 写操作失败已落人工待办 kind={} ticketNo={} code={} msg={}",
                kind, ticketNo, ex.code().value(), ex.getMessage());
    }

    private void recordPendingHuman(String kind, String ticketNo, String roomCode, String reason, String errorCode) {
        pendingHumanTasks.add(PendingHumanTask.of(kind, ticketNo, roomCode, reason, errorCode));
        log.warn("[ticket] 已落人工待办 kind={} ticketNo={} code={}", kind, ticketNo, errorCode);
    }

    private Optional<IdempotencyStore.Entry> checkIdempotency(String key, String fingerprint) {
        Optional<IdempotencyStore.Entry> entry = idempotencyStore.find(key);
        if (entry.isEmpty()) {
            return Optional.empty();
        }
        if (!entry.get().fingerprint().equals(fingerprint)) {
            throw DomainException.idempotencyConflict(key);
        }
        return entry;
    }

    private Map<String, Object> versionConflictDetails(TicketRecord record, int expectedVersion) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", "version_conflict");
        details.put("currentVersion", record.version);
        details.put("expectedVersion", expectedVersion);
        return details;
    }

    private Map<String, Object> invalidTransitionDetails(TicketStatus current, TicketTransitionAction action) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", "invalid_transition");
        details.put("currentStatus", current == null ? "" : current.value());
        details.put("action", action.value());
        details.put("allowed", TicketTransitions.allowedActionValues(current));
        return details;
    }

    private Map<String, Object> feedbackDetails(TicketStatus current) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", "invalid_transition");
        details.put("currentStatus", current == null ? "" : current.value());
        details.put("allowed", List.of("feedback"));
        details.put("expectedStatus", TicketStatus.COMPLETED.value());
        return details;
    }

    private String createFingerprint(TicketCreateRequest request) {
        return String.join("|", "ticket.create",
                nz(request.roomCode()),
                request.category() == null ? "" : request.category().value(),
                request.urgency() == null ? "" : request.urgency().value(),
                nz(request.location()),
                request.downstairs() == null ? "" : request.downstairs().value(),
                nz(request.duration()),
                nz(request.slot()),
                nz(request.note()),
                request.reporterType() == null ? "" : request.reporterType().value(),
                nz(request.contactTail4()));
    }

    private String transitionFingerprint(String ticketNo, TicketTransitionAction action,
            Map<String, Object> payload, int version) {
        return String.join("|", "ticket.transition", ticketNo, action.value(), String.valueOf(version),
                canonicalPayload(payload));
    }

    private String feedbackFingerprint(String ticketNo, TicketFeedbackRequest request) {
        List<String> tags = request.tags() == null ? new ArrayList<>() : new ArrayList<>(request.tags());
        tags.sort(Comparator.naturalOrder());
        return String.join("|", "ticket.feedback", ticketNo, String.valueOf(request.score()),
                String.join(",", tags), nz(request.note()));
    }

    /** payload 规范化（键排序）用于幂等指纹，避免 Map 顺序不同被判成不同载荷 */
    private String canonicalPayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return "{}";
        }
        List<String> keys = new ArrayList<>(payload.keySet());
        keys.sort(Comparator.naturalOrder());
        StringBuilder builder = new StringBuilder("{");
        for (String key : keys) {
            if (builder.length() > 1) {
                builder.append(',');
            }
            builder.append(key).append('=').append(asText(payload.get(key)));
        }
        return builder.append('}').toString();
    }

    private void requireTicketNo(String ticketNo) {
        if (blank(ticketNo)) {
            throw DomainException.badRequest("ticketNo 必填", Map.of("field", "ticketNo"));
        }
    }

    // ------------------------------------------------------------------
    // P3-028/029/043 数据范围与 owner 动作级能力（服务端强制，前端隐藏入口不是安全边界）
    // ------------------------------------------------------------------

    /**
     * 建单归属回填（P3-028/043）：
     * <ul>
     *   <li>owner → 本人 actorId（{@code owner-3-2-1802}）；</li>
     *   <li>steward/agent/supervisor（staff 代报）→ 该房演示业主 {@code owner-<roomCode>}
     *       （含非演示账号覆盖的房档池房号；房号非法则留空，不编造）；</li>
     *   <li>未鉴权（security 关闭的离线演示/测试）→ 只按 6 个演示账号登记表回填，其它房号留空。</li>
     * </ul>
     */
    private String resolveOwnerId(Actor actor, String roomCode) {
        if (OwnerScope.isOwner(actor)) {
            return actor.actorId() == null ? "" : actor.actorId();
        }
        if (OwnerScope.isStaffProxy(actor)) {
            return OwnerScope.ownerActorIdForRoom(roomCode).orElse("");
        }
        return OwnerScope.demoOwnerActorId(roomCode).orElse("");
    }

    /** 建单能力（契约 §7.2）：owner/steward/agent/supervisor 可建单；it/admin 403 E-2001（服务层兜底） */
    private void assertCreateCapability(Actor actor) {
        if (actor == null) {
            // security 关闭的离线演示/测试模式（无 ActorContext）：与端点级口径一致，不做能力拦截
            return;
        }
        if (!PermissionMatrix.allows(actor.role(), PermissionMatrix.TICKET_CREATE)) {
            throw DomainException.forbiddenCapability(PermissionMatrix.TICKET_CREATE,
                    actor.role() == null ? "" : actor.role().value());
        }
    }

    /**
     * 建单数据范围（P3-043 修正）：owner 只能为本户房号建单（跨房 → 403 E-2002 out_of_scope）；
     * staff 代报（steward/agent/supervisor）不受房号限制（项目范围）；其它角色由能力校验拦截。
     */
    private void assertCreateScope(Actor actor, String roomCode) {
        if (!OwnerScope.isOwner(actor)) {
            return;
        }
        String ownerRoom = OwnerScope.ownerRoom(actor).orElse(null);
        String normalized = OwnerScope.normalizeRoomCode(roomCode);
        if (ownerRoom == null || !ownerRoom.equals(normalized)) {
            throw DomainException.outOfScope("ticket", "", normalized);
        }
    }

    /**
     * 读数据范围（详情/推送记录，P3-043 修正）：owner 只能读**本户**工单——
     * {@code ownerId} 归属等于本人 <b>或</b> {@code roomCode} 等于本人房号；
     * staff 不受本户限制。平台无记录时（历史/ERP 种子单无 ownerId）按 ERP 权威房号判定，
     * 顺带把已取到的工单返回给调用方复用（避免同一请求重复查询 ERP）。
     *
     * @return ERP 侧预取的工单（仅 owner 且平台无记录时非空）
     */
    private Optional<Ticket> assertReadScope(String ticketNo, Actor actor, String traceId) {
        if (!OwnerScope.isOwner(actor)) {
            return Optional.empty();
        }
        String ownerRoom = OwnerScope.ownerRoom(actor).orElse(null);
        TicketRecord local = repository.find(ticketNo).orElse(null);
        if (local != null) {
            if (inOwnerReadScope(local, null, actor.actorId(), ownerRoom)) {
                return Optional.empty();
            }
            throw DomainException.outOfScope("ticket", ticketNo, local.roomCode);
        }
        // 平台无记录（历史 ERP 种子单）：按 ERP 房号判定本户范围，判定前不返回任何工单内容
        try {
            Ticket erpTicket = connector.getTicket(ticketNo, traceId);
            if (inOwnerReadScope(null, erpTicket.roomCode(), actor.actorId(), ownerRoom)) {
                return Optional.of(erpTicket);
            }
            throw DomainException.outOfScope("ticket", ticketNo, erpTicket.roomCode());
        } catch (ConnectorException ex) {
            if (ex.code() == ErrorCode.E_3001) {
                throw DomainException.notFound(ErrorCode.E_3001, "工单不存在：" + ticketNo,
                        Map.of("ticketNo", ticketNo));
            }
            // ERP 不可用且平台无快照：范围无法证明 → 不越权放行（不暴露他人房号/工单是否存在）
            throw DomainException.outOfScope("ticket", ticketNo, null);
        }
    }

    /** 写数据范围（transition/feedback）：owner 只能操作本户**自有单**（ownerId 归属，P3-043 写入逻辑不变） */
    private void assertTransitionScope(String ticketNo, Actor actor) {
        if (!OwnerScope.isOwner(actor)) {
            return;
        }
        TicketRecord record = repository.find(ticketNo).orElse(null);
        if (record == null || !isOwnedBy(record, actor.actorId())) {
            throw DomainException.outOfScope("ticket", ticketNo, record == null ? null : record.roomCode);
        }
    }

    /** 动作级能力（契约 §7.2）：owner reschedule/urge 自有单；staff/其它动作需 ticket.assign */
    private void assertTransitionCapability(Actor actor, TicketTransitionAction action) {
        if (actor == null) {
            // security 关闭的离线演示/测试模式（无 ActorContext）：与既有端点口径一致，不做能力拦截
            return;
        }
        String requiredCapability = PermissionMatrix.ticketTransitionCapability(action, OwnerScope.isOwner(actor));
        if (!PermissionMatrix.allows(actor.role(), requiredCapability)) {
            throw DomainException.forbiddenCapability(requiredCapability,
                    actor.role() == null ? "" : actor.role().value());
        }
    }

    /**
     * 列表数据范围（P3-043 修正）：owner 强制本户（ownerId 归属 或 房号=本户房号）；
     * staff 返回 null = 不限。
     */
    private OwnerReadScope ownerReadScope(Actor actor) {
        if (!OwnerScope.isOwner(actor)) {
            return null;
        }
        // 本户房号无法解析（非 owner-<roomCode> 演示账号）时用不可命中的占位，避免"解析失败=看到全部"
        Optional<String> room = OwnerScope.ownerRoom(actor);
        String actorId = room.isPresent() && actor.actorId() != null ? actor.actorId() : "\u0000unbound-owner";
        return new OwnerReadScope(actorId, room.orElse(null));
    }

    /** 列表项是否在 owner 数据范围内（scope 为空 = staff/未鉴权，恒在范围内） */
    private boolean inOwnerScope(TicketListItem item, OwnerReadScope scope) {
        if (scope == null) {
            return true;
        }
        if (item == null) {
            return false;
        }
        Optional<TicketRecord> record = repository.find(item.ticketNo());
        if (record.isPresent() && isOwnedBy(record.get(), scope.actorId())) {
            return true;
        }
        return roomInScope(item.roomCode(), scope.roomCode());
    }

    private static boolean inOwnerReadScope(TicketRecord record, String erpRoomCode, String actorId,
            String ownerRoom) {
        if (isOwnedBy(record, actorId)) {
            return true;
        }
        String roomCode = record != null && !blank(record.roomCode) ? record.roomCode : erpRoomCode;
        return roomInScope(roomCode, ownerRoom);
    }

    private static boolean roomInScope(String roomCode, String ownerRoom) {
        return !blank(ownerRoom) && ownerRoom.equals(OwnerScope.normalizeRoomCode(roomCode));
    }

    private static boolean isOwnedBy(TicketRecord record, String actorId) {
        return record != null && !blank(record.ownerId) && record.ownerId.equals(actorId);
    }

    @SuppressWarnings("unchecked")
    private <T> T castResult(Object result, Class<T> type) {
        if (!type.isInstance(result)) {
            throw DomainException.internal("幂等结果类型不匹配：" + type.getSimpleName());
        }
        return (T) result;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    // ------------------------------------------------------------------
    // P5 W19：项目归属 / outbox 事件（P5-007 / P5-019）
    // ------------------------------------------------------------------

    /**
     * 工单归属项目（P5 W19 项目隔离）：平台记录 {@code projectId} 优先，缺失（ERP 历史种子单）按演示默认项目
     * {@code P-YQY} 归属——列表项 {@code TicketListItem} 无 projectId 字段（不改契约），故项目过滤在本服务侧
     * 按工单号取归属后执行。
     */
    public String projectIdOf(String ticketNo) {
        if (ticketNo == null || ticketNo.isBlank()) {
            return ProjectService.DEFAULT_PROJECT_ID;
        }
        return repository.find(ticketNo.trim())
                .map(record -> ProjectService.projectOf(record.projectId))
                .orElse(ProjectService.DEFAULT_PROJECT_ID);
    }

    /**
     * 发 outbox 事件（P5-019）：payload 只放脱敏业务主键/枚举（不含手机号/姓名/位置原文）。
     *
     * <p>未注入 {@link EventStore}（单测直接 new 本服务）时只记 debug 日志，不引入强依赖。
     */
    private void publishTicketEvent(EventType type, TicketRecord record, String detail) {
        if (eventStore == null) {
            log.debug("[ticket] 事件存储未注入，跳过 {} ticketNo={}", type.value(), record.ticketNo);
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ticketNo", record.ticketNo);
        payload.put("roomCode", record.roomCode);
        payload.put("status", record.status == null ? null : record.status.value());
        if (detail != null && !detail.isBlank()) {
            payload.put(type == EventType.TICKET_TRANSITIONED ? "action" : "detail", detail);
        }
        eventStore.publish(type, ProjectService.projectOf(record.projectId), payload);
    }

    private static String nz(String value) {
        return value == null ? "" : value.trim();
    }

    private static String firstNonBlank(String candidate, String fallback) {
        return blank(candidate) ? fallback : candidate;
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String asText(Object value) {
        if (value == null) {
            return "";
        }
        return String.valueOf(value);
    }

    /** 供测试/验收读取当前记录（不影响业务语义） */
    public Optional<TicketRecord> findRecord(String ticketNo) {
        return repository.find(ticketNo);
    }

    /** 演示/测试重置（生产实现不调用） */
    public void resetForTest() {
        repository.clear();
        idempotencyStore.clear();
        verifiedRooms.clear();
        pendingHumanTasks.clear();
        pushService.resetForTest();
        clock = Clock.system(TicketTimes.BUSINESS_ZONE);
    }

    /** 供 Controller 判定报修位置是否漏到楼下（后续话术/优先级用） */
    public static boolean affectsDownstairs(IssueScope scope) {
        return scope == IssueScope.DOWNSTAIRS;
    }

    /** 写前置读取结果：平台记录 + ERP 权威快照（ERP 不可用时快照为 null） */
    private record LoadedRecord(TicketRecord record, Ticket erpTicket) {
    }

    /**
     * owner 列表读范围（P3-043）：{@code actorId} 匹配 {@code TicketRecord.ownerId} 归属，
     * 或 {@code roomCode} 匹配工单房号（含无平台归属的历史/ERP 种子单）。
     */
    private record OwnerReadScope(String actorId, String roomCode) {
    }

    /** 回写刷新结果（{@code failed} = 已进人工待办，读/迁移响应需标 degraded） */
    private record WritebackRefresh(ErpWriteback writeback, boolean changed, boolean failed) {
    }
}
