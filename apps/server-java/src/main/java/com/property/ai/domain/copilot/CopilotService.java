package com.property.ai.domain.copilot;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.property.ai.bff.dto.Actor;
import com.property.ai.bff.dto.ActorRole;
import com.property.ai.bff.dto.Complaint;
import com.property.ai.bff.dto.ComplaintListResult;
import com.property.ai.bff.dto.CopilotDigest;
import com.property.ai.bff.dto.CopilotPortrait;
import com.property.ai.bff.dto.CopilotPortraitHistory;
import com.property.ai.bff.dto.CopilotReport;
import com.property.ai.bff.dto.CopilotReportBreakdown;
import com.property.ai.bff.dto.CopilotScript;
import com.property.ai.bff.dto.CopilotScriptRequest;
import com.property.ai.bff.dto.CopilotSummary;
import com.property.ai.bff.dto.CopilotSummaryRequest;
import com.property.ai.bff.dto.CopilotWorkspaceListResult;
import com.property.ai.bff.dto.ErrorCode;
import com.property.ai.bff.dto.KnowledgeRef;
import com.property.ai.bff.dto.KnowledgeSearchHit;
import com.property.ai.bff.dto.KnowledgeSearchResult;
import com.property.ai.bff.dto.Ticket;
import com.property.ai.bff.dto.TicketAuditNode;
import com.property.ai.bff.dto.TicketCategory;
import com.property.ai.bff.dto.TicketStatus;
import com.property.ai.bff.dto.TicketUrgency;
import com.property.ai.domain.complaint.ComplaintService;
import com.property.ai.domain.desk.DeskQueueService;
import com.property.ai.domain.knowledge.KnowledgeService;
import com.property.ai.domain.knowledge.KnowledgeStatsService;
import com.property.ai.domain.ticket.DomainException;
import com.property.ai.domain.ticket.TicketRecord;
import com.property.ai.domain.ticket.TicketRepository;
import com.property.ai.domain.ticket.TicketService;
import com.property.ai.domain.ticket.TicketTimes;

/**
 * Copilot 8 工作区领域服务（P4-011，契约 v1.2.0 {@code /copilot/*} 7 个 operation）。
 *
 * <h2>边界（AGENTS.md §2 / 契约 §1.5 / README §7.4）</h2>
 * <ul>
 *   <li><b>不调模型</b>：Java 只做「平台侧规则组装」，所有结果 {@code mock:true} 标注演示编排；
 *       模型调用归 Python 编排层（本仓库 P4 未接）；</li>
 *   <li><b>不编造</b>：摘要取工单字段/节点/消息原文（脱敏后），画像取房档标签与计数字段，
 *       日报/报表数字全部来自服务端聚合（工单仓储 + mock 账单样本 + 投诉仓储 + 坐席 SLA）；</li>
 *   <li><b>脱敏</b>：出入参不含手机号/身份证号/欠费金额明文；消息入参先打码再压摘要；</li>
 *   <li><b>不做群内自动回复</b>：群服务只有「人工确认后群发公告」一个写操作（{@link CopilotBroadcastService}）。</li>
 * </ul>
 *
 * <h2>数据来源</h2>
 * <ul>
 *   <li>工单：{@link TicketService}（平台仓储优先，缺失再读 ERP 连接器）+ 历史样本（{@link CopilotDataRepository}）；</li>
 *   <li>知识引用：{@link KnowledgeService#search}（只回 published + 生效期；无命中返回空 {@code cites}，不编造出处）；</li>
 *   <li>画像历史：房档/账单计数（{@link CopilotDataRepository}）+ 投诉计数（{@link ComplaintService} 只读聚合）；</li>
 *   <li>首响均值：{@link DeskQueueService#sla}（与坐席工作台同口径，避免两套数字）。</li>
 * </ul>
 */
@Service
public class CopilotService {

    private static final Logger log = LoggerFactory.getLogger(CopilotService.class);

    /** 摘要行数上限（契约：≤5 行） */
    private static final int MAX_SUMMARY_LINES = 5;
    /** 待办条数上限（契约：≤3 条） */
    private static final int MAX_TODOS = 3;
    /** 知识引用条数（摘要最多 2 条，避免刷屏） */
    private static final int MAX_CITES = 2;
    /** 单行摘要最大字符数（超出截断，防止整段原文外泄） */
    private static final int LINE_MAX_CHARS = 60;

    private static final Pattern ROOM_CODE_PATTERN = Pattern.compile("^\\d{1,2}-\\d{1,2}-\\d{3,4}$");
    private static final Pattern MOBILE_PATTERN = Pattern.compile("(1[3-9]\\d)\\d{4}(\\d{4})");
    private static final Pattern ID_CARD_PATTERN = Pattern.compile("(\\d{6})\\d{8}(\\d{3}[\\dXx])");

    /**
     * 跨域只读聚合主体（投诉计数/日期窗口统计）：Copilot 管理视图的用户可能是 it/admin
     * （无 {@code complaint.read} 能力），而聚合只回计数/日期，不返回投诉正文。
     * <b>不是</b>用户身份冒用：不写审计、不下发任何他人隐私字段。
     */
    private static final Actor AGGREGATE_READ_ACTOR =
            new Actor("system-aggregate", ActorRole.SUPERVISOR, "Copilot 聚合查询", null, true);

    /** 工单类别 → 知识检索词（只用于 cite，不参与答复生成） */
    private static final Map<TicketCategory, String> CATEGORY_QUERY = Map.of(
            TicketCategory.PLUMBING, "漏水 报修",
            TicketCategory.ELECTRICAL, "紧急报修",
            TicketCategory.ELEVATOR, "电梯",
            TicketCategory.DOOR_WINDOW, "报修流程",
            TicketCategory.PUBLIC_FACILITY, "公共设施报修",
            TicketCategory.OTHER, "报修流程");

    /** 工单类别中文名（摘要/报表展示；不手写契约 code） */
    private static final Map<TicketCategory, String> CATEGORY_LABEL = Map.of(
            TicketCategory.PLUMBING, "给排水",
            TicketCategory.ELECTRICAL, "电气",
            TicketCategory.ELEVATOR, "电梯",
            TicketCategory.DOOR_WINDOW, "门窗",
            TicketCategory.PUBLIC_FACILITY, "公共设施",
            TicketCategory.OTHER, "其他");

    /** 主题识别（消息压摘要 + 噪声/费用等红线提示用） */
    private record Theme(String label, String query) {
    }

    private static final List<ThemeRule> THEME_RULES = List.of(
            new ThemeRule(new Theme("装修/噪声", "装修 噪声"), List.of("噪声", "噪音", "装修", "施工")),
            new ThemeRule(new Theme("漏水/报修", "漏水 报修"), List.of("漏水", "渗水", "返水", "堵塞", "滴水")),
            new ThemeRule(new Theme("费用/缴费", "公摊电费"),
                    List.of("费用", "物业费", "公摊", "缴费", "滞纳金", "欠费")),
            new ThemeRule(new Theme("投诉", "投诉渠道"), List.of("投诉", "态度")),
            new ThemeRule(new Theme("宠物", "宠物"), List.of("宠物", "狗", "猫")),
            new ThemeRule(new Theme("车位", "车位费"), List.of("车位", "停车")),
            new ThemeRule(new Theme("访客/放行", "访客与放行"), List.of("访客", "快递", "放行")),
            new ThemeRule(new Theme("电梯", "电梯"), List.of("电梯", "困人")));;

    private final CopilotWorkspaceCatalog workspaceCatalog;
    private final CopilotScriptComposer scriptComposer;
    private final CopilotDataRepository dataRepository;
    private final TicketService ticketService;
    private final TicketRepository ticketRepository;
    private final KnowledgeService knowledgeService;
    private final KnowledgeStatsService knowledgeStatsService;
    private final ComplaintService complaintService;
    private final DeskQueueService deskQueueService;

    private Clock clock = Clock.system(TicketTimes.BUSINESS_ZONE);

    public CopilotService(CopilotWorkspaceCatalog workspaceCatalog, CopilotScriptComposer scriptComposer,
            CopilotDataRepository dataRepository, TicketService ticketService, TicketRepository ticketRepository,
            KnowledgeService knowledgeService, KnowledgeStatsService knowledgeStatsService,
            ComplaintService complaintService, DeskQueueService deskQueueService) {
        this.workspaceCatalog = workspaceCatalog;
        this.scriptComposer = scriptComposer;
        this.dataRepository = dataRepository;
        this.ticketService = ticketService;
        this.ticketRepository = ticketRepository;
        this.knowledgeService = knowledgeService;
        this.knowledgeStatsService = knowledgeStatsService;
        this.complaintService = complaintService;
        this.deskQueueService = deskQueueService;
    }

    /** 测试注入固定时钟（日报/报表日期窗口断言用；生产不调用）。 */
    void setClockForTest(Clock clock) {
        this.clock = clock == null ? Clock.system(TicketTimes.BUSINESS_ZONE) : clock;
    }

    // ==================================================================
    // 工作区元数据
    // ==================================================================

    /** 8 个工作区（固定顺序 + 可用状态；不分页）。 */
    public CopilotWorkspaceListResult workspaces() {
        return workspaceCatalog.list();
    }

    // ==================================================================
    // 摘要
    // ==================================================================

    /**
     * 工单/会话摘要：{@code ticketNo} 与 {@code messages} 二选一。
     *
     * <ul>
     *   <li>{@code ticketNo}：读平台仓储（优先）或 ERP 工单，按字段/节点规则压 5 行 + 3 条待办；</li>
     *   <li>{@code messages}：先脱敏（手机号/证件号打码）再压摘要，不调用模型；</li>
     *   <li>两者都缺 → 400 {@code E-1001}；{@code ticketNo} 不存在 → 404 {@code E-3001}（由 TicketService 抛出）。</li>
     * </ul>
     */
    public CopilotSummary summary(CopilotSummaryRequest request, Actor actor, String traceId) {
        String ticketNo = request == null || request.ticketNo() == null ? "" : request.ticketNo().trim();
        List<String> messages = request == null || request.messages() == null
                ? List.of()
                : request.messages();
        if (ticketNo.isEmpty() && messages.isEmpty()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("field", "ticketNo|messages");
            details.put("reason", "missing_input");
            details.put("hint", "ticketNo 与 messages 二选一（都给时以 ticketNo 为准）");
            throw DomainException.badRequest("ticketNo 与 messages 必须提供其一", details);
        }
        if (!ticketNo.isEmpty()) {
            return summaryFromTicket(ticketNo, actor, traceId);
        }
        return summaryFromMessages(messages, actor);
    }

    /** 工单摘要（平台仓储优先 → ERP 兜底；ERP 不可用由 TicketService 走平台快照/降级口径）。 */
    private CopilotSummary summaryFromTicket(String ticketNo, Actor actor, String traceId) {
        Ticket ticket = ticketService.findRecord(ticketNo)
                .map(TicketRecord::toTicket)
                .orElseGet(() -> ticketService.get(ticketNo, traceId, actor).data());
        List<String> summary = new ArrayList<>();
        String symptom = blankTo(ticket.symptom(), "业主反馈问题（原文缺失）");
        summary.add(cut(ticket.roomCode() + " · " + categoryLabel(ticket.category()) + "：" + symptom));
        String statusLine = "当前状态：" + blankTo(ticket.statusText(), statusText(ticket.status()));
        if (!blank(ticket.team())) {
            statusLine += "；班组 " + ticket.team();
        }
        if (!blank(ticket.eta())) {
            statusLine += "；" + ticket.eta();
        }
        summary.add(cut(statusLine));
        List<TicketAuditNode> nodes = ticket.nodes() == null ? List.of() : ticket.nodes();
        if (!nodes.isEmpty()) {
            TicketAuditNode last = nodes.get(nodes.size() - 1);
            summary.add(cut("时间轴 " + nodes.size() + " 个节点，最新：" + blankTo(last.statusText(), "节点更新")
                    + (last.at() == null ? "" : "（" + last.at() + "）")));
        }
        String urgencyLine = "紧急度：" + urgencyLabel(ticket.urgency());
        if (!blank(ticket.createdAt())) {
            urgencyLine += "；建单 " + ticket.createdAt();
        }
        if (ticket.aiCreated()) {
            urgencyLine += "；AI 建单";
        }
        summary.add(cut(urgencyLine));

        List<String> todos = new ArrayList<>();
        boolean open = ticket.status() != TicketStatus.COMPLETED && ticket.status() != TicketStatus.REVIEWED
                && ticket.status() != TicketStatus.ARCHIVED;
        if (open) {
            addTodo(todos, "按首响 SLA 在 30 分钟内联系业主并留痕（不承诺时限外结果）");
        }
        if (ticket.urgency() == TicketUrgency.EMERGENCY || ticket.urgency() == TicketUrgency.URGENT) {
            addTodo(todos, "加急/紧急工单：确认上门时间并在系统登记，超时同步主管");
        }
        if (ticket.status() == TicketStatus.DISPATCHED || ticket.status() == TicketStatus.ON_SITE) {
            addTodo(todos, "完工后 24 小时内回访并记录回访结论");
        }
        if (ticket.aiCreated()) {
            addTodo(todos, "核对 AI 建单要素（房号/时间/问题描述）是否与业主表述一致");
        }
        if (todos.isEmpty()) {
            addTodo(todos, "向业主确认处理结果并登记回访记录");
        }
        List<KnowledgeRef> cites = cites(categoryQuery(ticket.category()), actor);
        if (cites.isEmpty()) {
            addTodo(todos, "知识库暂未命中可引用条目：转人工确认口径后再答复（不得编造）");
        }
        log.info("[copilot] summary ticketNo={} lines={} cites={} actor={} traceId={}", ticketNo, summary.size(),
                cites.size(), actorId(actor), traceId);
        return new CopilotSummary(trimTo(summary, MAX_SUMMARY_LINES), trimTo(todos, MAX_TODOS), cites, true);
    }

    /** 消息摘要（脱敏 + 规则压缩；不调用模型）。 */
    private CopilotSummary summaryFromMessages(List<String> messages, Actor actor) {
        List<String> masked = new ArrayList<>();
        boolean maskedAny = false;
        for (String message : messages) {
            String raw = message == null ? "" : message.trim();
            if (raw.isEmpty()) {
                continue;
            }
            String safe = maskContent(raw);
            maskedAny = maskedAny || !safe.equals(raw);
            masked.add(safe);
        }
        if (masked.isEmpty()) {
            throw DomainException.badRequest("messages 至少包含 1 条非空消息",
                    Map.of("field", "messages", "reason", "empty_messages"));
        }
        String first = masked.get(0);
        String last = masked.get(masked.size() - 1);
        List<Theme> themes = themes(String.join(" ", masked));

        List<String> summary = new ArrayList<>();
        summary.add(cut("业主反映：" + first));
        if (masked.size() > 1) {
            summary.add(cut("最新进展：" + last));
        }
        if (!themes.isEmpty()) {
            List<String> labels = new ArrayList<>();
            for (Theme theme : themes) {
                labels.add(theme.label());
            }
            summary.add(cut("识别主题：" + String.join(" / ", labels)));
        }
        summary.add("共 " + masked.size() + " 条会话消息（规则压摘要，未接模型）");
        if (maskedAny) {
            summary.add("消息中的手机号/证件号已打码");
        }

        List<String> todos = new ArrayList<>();
        addTodo(todos, "核实房号/时间/诉求细节并在会话或工单留痕");
        List<KnowledgeRef> cites = new ArrayList<>();
        if (!themes.isEmpty()) {
            cites.addAll(cites(themes.get(0).query(), actor));
        }
        if (cites.isEmpty()) {
            addTodo(todos, "知识库未命中：转人工确认口径后答复（不得编造答案）");
        } else {
            addTodo(todos, "引用知识回复前核对条目版本与生效期");
        }
        if (!themes.isEmpty() && "费用/缴费".equals(themes.get(0).label())) {
            addTodo(todos, "涉及费用：先核对账单明细口径，不承诺减免/免滞纳金");
        }
        log.info("[copilot] summary messages={} themes={} cites={} actor={}", masked.size(), themes.size(),
                cites.size(), actorId(actor));
        return new CopilotSummary(trimTo(summary, MAX_SUMMARY_LINES), trimTo(todos, MAX_TODOS),
                List.copyOf(cites), true);
    }

    // ==================================================================
    // 话术
    // ==================================================================

    /** 3 版话术 + 禁区扫描（只生成不外发；{@code scenario} 缺失 → 400）。 */
    public CopilotScript script(CopilotScriptRequest request, Actor actor) {
        if (request == null || request.scenario() == null || request.scenario().isBlank()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("field", "scenario");
            details.put("required", true);
            details.put("hint", "如「装修噪声安抚」「催缴首响」「投诉安抚」「上门预约」");
            throw DomainException.badRequest("scenario 必填", details);
        }
        CopilotScript script = scriptComposer.compose(request);
        log.info("[copilot] script scenario={} versions={} actor={}", request.scenario(),
                script.versions().size(), actorId(actor));
        return script;
    }

    // ==================================================================
    // 画像
    // ==================================================================

    /**
     * 业主画像（脱敏）：掩码姓名 + 运营标签 + 服务历史计数 + 沟通禁忌。
     *
     * <p><b>不含</b>手机号/身份证/欠费金额明文；房号不存在 → 404；房号缺失/格式非法 → 400。
     */
    public CopilotPortrait portrait(String roomCodeParam, Actor actor) {
        String roomCode = normalizeRoomCode(roomCodeParam);
        CopilotDataRepository.Household household = dataRepository.household(roomCode)
                .orElseThrow(() -> DomainException.notFound(ErrorCode.E_3001, "房号不存在：" + roomCode,
                        Map.of("roomCode", roomCode)));
        List<String> tags = new ArrayList<>(household.tags());
        CopilotPortraitHistory history = new CopilotPortraitHistory(
                ticketCountOfRoom(roomCode), dataRepository.billCount(roomCode), complaintCountOfRoom(roomCode));
        List<String> risks = risksOf(household, history);
        log.info("[copilot] portrait roomCode={} tags={} historyTickets={} complaints={} actor={}", roomCode,
                tags.size(), history.tickets(), history.complaints(), actorId(actor));
        return new CopilotPortrait(roomCode, household.ownerNameMasked(), List.copyOf(tags), history,
                List.copyOf(risks), true);
    }

    /** 画像风险/沟通禁忌（只由标签/绑定状态/计数派生，不引入金额与联系方式）。 */
    private static List<String> risksOf(CopilotDataRepository.Household household,
            CopilotPortraitHistory history) {
        List<String> risks = new ArrayList<>();
        for (String tag : household.tags()) {
            if (tag.contains("欠费") || tag.contains("催缴")) {
                addRisk(risks, "费用敏感：沟通避免当众/群内提及欠费明细");
                break;
            }
        }
        if (household.overdueDays() > 0) {
            addRisk(risks, "存在逾期账期：不承诺减免，按账单口径答复");
        }
        if ("unbound".equalsIgnoreCase(household.bindState())) {
            addRisk(risks, "联系方式未绑定：需人工核验身份后再办理敏感业务");
        }
        if (history.complaints() > 0) {
            addRisk(risks, "历史投诉记录：沟通需留痕并优先首响");
        }
        for (String tag : household.tags()) {
            if (tag.contains("沟通敏感") || tag.contains("夜间")) {
                addRisk(risks, "夜间沟通敏感：非紧急事项勿夜间触达");
            }
        }
        return risks;
    }

    private int ticketCountOfRoom(String roomCode) {
        Set<String> ticketNos = new LinkedHashSet<>(dataRepository.ticketNosByRoom()
                .getOrDefault(roomCode, List.of()));
        int count = 0;
        for (TicketRecord record : ticketRepository.findAll()) {
            if (roomCode.equals(record.roomCode) && !ticketNos.contains(record.ticketNo)) {
                count++;
            }
        }
        return ticketNos.size() + count;
    }

    private int complaintCountOfRoom(String roomCode) {
        try {
            ComplaintListResult result = complaintService.list(null, null, roomCode, null, 1, 100,
                    AGGREGATE_READ_ACTOR);
            return (int) result.total();
        } catch (RuntimeException ex) {
            log.debug("[copilot] 投诉计数降级为 0 roomCode={} err={}", roomCode, ex.getClass().getSimpleName());
            return 0;
        }
    }

    // ==================================================================
    // 日报 / 报表
    // ==================================================================

    /**
     * 日报/摘要（{@code date} 省略 = 今天）：条目化摘要 + 当日指标。
     *
     * <p>数字全部来自服务端聚合；无数据日期返回空 {@code summary} 与 0 值（200，不是错误）。
     */
    public CopilotDigest digest(String dateParam) {
        LocalDate today = LocalDate.now(clock.withZone(TicketTimes.BUSINESS_ZONE));
        LocalDate date = parseDate("date", dateParam, today);
        String day = date.toString();

        List<TicketRow> rows = ticketRows();
        int tickets = 0;
        int aiResolved = 0;
        for (TicketRow row : rows) {
            if (!dayMatches(row.createdAt(), day)) {
                continue;
            }
            tickets++;
            if (row.aiCreated() && "completed".equalsIgnoreCase(row.status())) {
                aiResolved++;
            }
        }
        int complaints = complaintCountOnDate(day);
        int requests = tickets + complaints;
        double avgFirstResponseMinutes = Math.round(
                deskQueueService.sla(day, day).avgFirstResponseSeconds() / 60.0 * 10.0) / 10.0;

        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("requests", (double) requests);
        metrics.put("aiResolved", (double) aiResolved);
        metrics.put("tickets", (double) tickets);
        metrics.put("complaints", (double) complaints);
        metrics.put("avgFirstResponseMinutes", avgFirstResponseMinutes);

        List<String> summary = new ArrayList<>();
        if (requests > 0) {
            addLine(summary, "诉求 " + requests + " 件（工单 " + tickets + " / 投诉 " + complaints + "）");
            if (aiResolved > 0) {
                addLine(summary, "AI 自助建单 " + aiResolved + " 单已完工");
            }
            if (avgFirstResponseMinutes > 0) {
                addLine(summary, "首响均值 " + avgFirstResponseMinutes + " 分钟");
            }
            if (tickets > 0) {
                int automationRate = (int) Math.round((double) aiResolved / tickets * 100);
                addLine(summary, "当日工单 AI 建单占比 " + automationRate + "%");
            }
        }
        log.info("[copilot] digest date={} requests={} tickets={} complaints={}", day, requests, tickets,
                complaints);
        return new CopilotDigest(day, trimTo(summary, MAX_SUMMARY_LINES), metrics, true);
    }

    /**
     * Copilot 报表（{@code from}/{@code to} 省略 = 近 7 天）：周期指标 + 按类别分组明细。
     *
     * <p>口径与看板一致：诉求量 = 工单 + 投诉；自助解决率 = AI 建单且已完工 / 诉求量；
     * 知识采纳率取自 {@link KnowledgeStatsService}（检索命中 vs 拒答），分母 0 记 0。
     */
    public CopilotReport report(String fromParam, String toParam) {
        LocalDate today = LocalDate.now(clock.withZone(TicketTimes.BUSINESS_ZONE));
        LocalDate to = parseDate("to", toParam, today);
        LocalDate from = parseDate("from", fromParam, to.minusDays(6));
        if (from.isAfter(to)) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("field", "from");
            details.put("reason", "invalid_range");
            details.put("from", from.toString());
            details.put("to", to.toString());
            throw DomainException.badRequest("from 不能晚于 to", details);
        }
        List<TicketRow> rows = ticketRows();
        int tickets = 0;
        int aiResolved = 0;
        Map<String, Integer> byCategory = new LinkedHashMap<>();
        for (TicketRow row : rows) {
            LocalDate created = createdDate(row.createdAt());
            if (created == null || created.isBefore(from) || created.isAfter(to)) {
                continue;
            }
            tickets++;
            String category = row.category() == null ? "other" : row.category();
            byCategory.merge(category, 1, Integer::sum);
            if (row.aiCreated() && "completed".equalsIgnoreCase(row.status())) {
                aiResolved++;
            }
        }
        int complaints = complaintCountBetween(from, to);
        int requests = tickets + complaints;
        double selfServiceRate = requests == 0 ? 0.0 : round4((double) aiResolved / requests);
        double knowledgeAdoptionRate = 0.0;
        try {
            knowledgeAdoptionRate = round4(
                    knowledgeStatsService.stats(from.toString(), to.toString()).adoptionRate());
        } catch (RuntimeException ex) {
            log.debug("[copilot] 知识采纳率降级为 0 err={}", ex.getClass().getSimpleName());
        }
        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("requests", (double) requests);
        metrics.put("tickets", (double) tickets);
        metrics.put("complaints", (double) complaints);
        metrics.put("selfServiceRate", selfServiceRate);
        metrics.put("knowledgeAdoptionRate", knowledgeAdoptionRate);

        List<CopilotReportBreakdown> breakdown = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : byCategory.entrySet()) {
            double ratio = tickets == 0 ? 0.0 : round4((double) entry.getValue() / tickets);
            breakdown.add(new CopilotReportBreakdown(categoryLabel(entry.getKey()), entry.getValue(), ratio));
        }
        breakdown.sort((left, right) -> Double.compare(right.value(), left.value()));
        String generatedAt = TicketTimes.iso(clock.instant());
        log.info("[copilot] report {}~{} requests={} tickets={} breakdown={}", from, to, requests, tickets,
                breakdown.size());
        return new CopilotReport(metrics, List.copyOf(breakdown), generatedAt, true);
    }

    // ==================================================================
    // 内部：工单行 / 日期 / 知识引用
    // ==================================================================

    /** 工单行（历史样本 + 平台实时记录的合并视图；平台记录覆盖同名工单状态）。 */
    private record TicketRow(String ticketNo, String roomCode, String category, String status,
            String createdAt, boolean aiCreated) {
    }

    private List<TicketRow> ticketRows() {
        Map<String, TicketRow> rows = new LinkedHashMap<>();
        for (CopilotDataRepository.SeedTicket ticket : dataRepository.tickets()) {
            rows.put(ticket.ticketNo(), new TicketRow(ticket.ticketNo(), ticket.roomCode(), ticket.category(),
                    ticket.status(), ticket.createdAt(), ticket.aiCreated()));
        }
        for (TicketRecord record : ticketRepository.findAll()) {
            rows.put(record.ticketNo, new TicketRow(record.ticketNo, record.roomCode,
                    record.category == null ? null : record.category.value(),
                    record.status == null ? null : record.status.value(), record.createdAt, record.aiCreated));
        }
        return List.copyOf(rows.values());
    }

    private int complaintCountOnDate(String day) {
        int count = 0;
        for (Complaint complaint : complaints()) {
            if (complaint.createdAt() != null && complaint.createdAt().startsWith(day)) {
                count++;
            }
        }
        return count;
    }

    private int complaintCountBetween(LocalDate from, LocalDate to) {
        int count = 0;
        for (Complaint complaint : complaints()) {
            LocalDate created = createdDate(complaint.createdAt());
            if (created != null && !created.isBefore(from) && !created.isAfter(to)) {
                count++;
            }
        }
        return count;
    }

    private List<Complaint> complaints() {
        try {
            ComplaintListResult result = complaintService.list(null, null, null, null, 1, 100,
                    AGGREGATE_READ_ACTOR);
            return result.items();
        } catch (RuntimeException ex) {
            log.debug("[copilot] 投诉聚合降级为空 err={}", ex.getClass().getSimpleName());
            return List.of();
        }
    }

    /** 知识引用（只回 published + 生效期；无命中返回空列表）。 */
    private List<KnowledgeRef> cites(String query, Actor actor) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        try {
            KnowledgeSearchResult result = knowledgeService.search(query, MAX_CITES, actor);
            List<KnowledgeRef> refs = new ArrayList<>(result.items().size());
            for (KnowledgeSearchHit hit : result.items()) {
                refs.add(new KnowledgeRef(hit.knowledgeId(), hit.title(), hit.version(), hit.owner()));
            }
            return List.copyOf(refs);
        } catch (DomainException ex) {
            log.debug("[copilot] 知识检索跳过 query={} code={}", query, ex.code().value());
            return List.of();
        }
    }

    // ==================================================================
    // 内部：标签 / 脱敏 / 日期
    // ==================================================================

    private static String categoryQuery(TicketCategory category) {
        return CATEGORY_QUERY.getOrDefault(category, "报修流程");
    }

    private static String categoryLabel(TicketCategory category) {
        return category == null ? "其他" : CATEGORY_LABEL.getOrDefault(category, "其他");
    }

    private static String categoryLabel(String categoryCode) {
        if (categoryCode == null || categoryCode.isBlank()) {
            return "其他";
        }
        for (Map.Entry<TicketCategory, String> entry : CATEGORY_LABEL.entrySet()) {
            if (entry.getKey().value().equalsIgnoreCase(categoryCode.trim())) {
                return entry.getValue();
            }
        }
        return "其他";
    }

    private static String urgencyLabel(TicketUrgency urgency) {
        if (urgency == null) {
            return "普通";
        }
        return switch (urgency) {
            case NORMAL -> "普通";
            case URGENT -> "加急";
            case EMERGENCY -> "紧急";
        };
    }

    private static String statusText(TicketStatus status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case CREATED -> "已创建";
            case ACCEPTED -> "已受理";
            case DISPATCHED -> "已派单";
            case ON_SITE -> "处理中";
            case COMPLETED -> "已完工";
            case REVIEWED -> "已回访";
            case ARCHIVED -> "已归档";
        };
    }

    private static String normalizeRoomCode(String raw) {
        String code = raw == null ? "" : raw.trim().replace('－', '-').replace('—', '-').replace(" ", "");
        if (code.isEmpty() || !ROOM_CODE_PATTERN.matcher(code).matches()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("field", "roomCode");
            details.put("required", true);
            details.put("value", raw == null ? "" : raw);
            details.put("format", "楼栋-单元-房间，如 1-1-101");
            throw DomainException.badRequest("roomCode 必填且格式为「楼栋-单元-房间」", details);
        }
        return code;
    }

    /** 内容脱敏：手机号/身份证号打码（与投诉域同口径；只用于摘要输入，不落日志）。 */
    static String maskContent(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        Matcher mobile = MOBILE_PATTERN.matcher(content);
        String masked = mobile.replaceAll("$1****$2");
        Matcher idCard = ID_CARD_PATTERN.matcher(masked);
        return idCard.replaceAll("$1********$2");
    }

    private static List<Theme> themes(String text) {
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
        List<Theme> themes = new ArrayList<>();
        for (ThemeRule rule : THEME_RULES) {
            for (String keyword : rule.keywords()) {
                if (normalized.contains(keyword.toLowerCase(Locale.ROOT))) {
                    themes.add(rule.theme());
                    break;
                }
            }
            if (themes.size() >= 3) {
                break;
            }
        }
        return themes;
    }

    private record ThemeRule(Theme theme, List<String> keywords) {
    }

    /** 行截断（单行不超过 {@value #LINE_MAX_CHARS} 字符）。 */
    private static String cut(String line) {
        if (line == null) {
            return "";
        }
        String text = line.trim().replaceAll("\\s+", " ");
        return text.length() <= LINE_MAX_CHARS ? text : text.substring(0, LINE_MAX_CHARS - 1) + "…";
    }

    private static void addLine(List<String> lines, String line) {
        if (lines.size() < MAX_SUMMARY_LINES) {
            lines.add(cut(line));
        }
    }

    private static void addTodo(List<String> todos, String todo) {
        if (todos.size() < MAX_TODOS && todo != null && !todos.contains(todo)) {
            todos.add(todo);
        }
    }

    private static void addRisk(List<String> risks, String risk) {
        if (risk != null && !risks.contains(risk)) {
            risks.add(risk);
        }
    }

    private static <T> List<T> trimTo(List<T> items, int max) {
        if (items.size() <= max) {
            return List.copyOf(items);
        }
        return List.copyOf(items.subList(0, max));
    }

    private static boolean dayMatches(String at, String day) {
        return at != null && at.startsWith(day);
    }

    private static LocalDate createdDate(String at) {
        if (at == null || at.length() < 10) {
            return null;
        }
        return parseDate("createdAt", at.substring(0, 10), null);
    }

    private static LocalDate parseDate(String field, String raw, LocalDate fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException ex) {
            if (fallback == null) {
                return null;
            }
            throw DomainException.badRequest(field + " 必须是 YYYY-MM-DD 日期",
                    Map.of("field", field, "value", raw, "format", "YYYY-MM-DD"));
        }
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String actorId(Actor actor) {
        return actor == null || actor.actorId() == null ? "anonymous" : actor.actorId();
    }

    private static double round4(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
