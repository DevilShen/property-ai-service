package com.property.ai.domain.gray;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.property.ai.audit.AuditService;
import com.property.ai.bff.dto.Actor;
import com.property.ai.bff.dto.AuditAction;
import com.property.ai.bff.dto.AuditResourceType;
import com.property.ai.bff.dto.AuditResult;
import com.property.ai.bff.dto.BillInfo;
import com.property.ai.bff.dto.CapabilityCode;
import com.property.ai.bff.dto.ErrorCode;
import com.property.ai.bff.dto.EventType;
import com.property.ai.bff.dto.GrayCompareItem;
import com.property.ai.bff.dto.GrayCompareResult;
import com.property.ai.bff.dto.GrayPolicy;
import com.property.ai.bff.dto.GrayPolicyCreateRequest;
import com.property.ai.bff.dto.GrayPolicyUpdateRequest;
import com.property.ai.bff.dto.GrayScope;
import com.property.ai.bff.dto.RoomInfo;
import com.property.ai.bff.dto.TicketListItem;
import com.property.ai.connector.ConnectorException;
import com.property.ai.connector.ConnectorRegistry;
import com.property.ai.connector.ConnectorRouter;
import com.property.ai.connector.ConnectorTimes;
import com.property.ai.connector.ErpConnector;
import com.property.ai.domain.event.EventStore;
import com.property.ai.domain.project.ProjectService;
import com.property.ai.domain.ticket.DomainException;
import com.property.ai.security.ActorContext;

/**
 * 灰度与双跑比对服务（P5-013 / task-62，契约 v1.3.0 {@code /gray/policies*} + {@code /gray/compare}，
 * 架构 §24.4「灰度发布 + 双跑比对 + 回滚」）。
 *
 * <h2>策略口径</h2>
 * <ul>
 *   <li>{@code scope=connector}：{@code targetId} 必须是已注册连接器（未注册 → 404 {@code E-3001}）；
 *       {@code scope=capability}：{@code targetId} 必须是 18 项 {@link CapabilityCode}（未匹配 → 400 {@code E-1001}）；</li>
 *   <li>{@code percent} 0—100（新版本流量比例；0 = 全量旧版，100 = 全量新版）；越界 → 400 {@code E-1001}；</li>
 *   <li>同一 {@code scope}+{@code targetId} 仅允许一条策略（重复 → 409 {@code E-3002} {@code duplicate_policy}）；</li>
 *   <li>{@code enabled=false} 即暂停（等价全量回旧版）；写操作落审计 {@code gray.policy.create}/{@code gray.policy.update}
 *       （资源类型 {@code gray_policy}）并发 {@code gray.policy.updated} 事件；</li>
 *   <li>存储：进程内（P5 W22 迁 PG/配置中心）；本服务<b>不写业务主数据、不改流量分配引擎</b>（P5 W21 接入时按本策略生效）。</li>
 * </ul>
 *
 * <h2>双跑比对口径（{@code GET /gray/compare}）</h2>
 * 只读并行采样：旧连接器（默认 {@code yonyou-nc-mock}）与新连接器（灰度中的连接器，如 {@code kingdee-eas}），
 * 逐能力（{@code room.query} / {@code bill.query} / {@code ticket.status}）按<b>规范化投影</b>逐序号比对：
 * <ul>
 *   <li>{@code room.query}：结构字段 {bindState, 是否有车位, 面积}——两套 ERP 房号编码体系不同
 *       （NC {@code 3-2-1802} / 金蝶 {@code 1-1-01} / 自研 {@code 3-1-1802}），<b>不按房号 join</b>，避免因编码差异误报；</li>
 *   <li>{@code bill.query}：<b>可对账口径</b> {period, total, paid, outstanding, status, 公摊电费归一额}——
 *       金蝶 水费+电费 / 自研 fee_* 字段经连接器归一为契约费项，故费项级也可比（合计恒等式见 mock README §2.3）；</li>
 *   <li>{@code ticket.status}：契约状态码（金蝶小写状态 / 自研 {@code wo_state} 均已映射 7 节点）。</li>
 * </ul>
 * {@code diffRate = diffCount / checked}（checked = 两侧样本数较小值）；差异只作发布决策证据，
 * <b>不得对外声称「新旧连接器等价」</b>；比对只读，不写业务主数据、不改流量比例。
 * {@code from}/{@code to} 必填（发布决策时间窗）：按样本 {@code dataAsOf} 过滤窗口内样本；
 * 窗口内无样本时回退当前快照并记录日志（不伪造时间序列）。
 *
 * <p><b>P5 W20 三连接器（task-65 / P5-013）</b>：{@code connectorId} 缺省 = 全部已接入 mock 测试替身且
 * 处于灰度/发布态的连接器（当前 = 金蝶 + 自研），结果按 {@code items[].oldConnector/newConnector} 区分
 * （契约既有字段，不改形状）；支持逗号分隔多值与 {@code all/*}。
 */
@Service
public class GrayService {

    private static final Logger log = LoggerFactory.getLogger(GrayService.class);

    /** 双跑对比样本上限（单能力单侧） */
    public static final int COMPARE_SAMPLE_LIMIT = 20;
    /** 旧连接器（对比基线）：P2 起的能力网关主连接器 */
    public static final String BASELINE_CONNECTOR_ID = ConnectorRegistry.NC_MOCK;
    /** 默认账期（账单双跑样本取当前账期） */
    private static final String SEED_CREATED_AT = "2026-11-16T10:00:00+08:00";

    private final ConnectorRegistry registry;
    private final ConnectorRouter router;
    private final AuditService auditService;
    /** 事件存储（可选注入：P5-019 outbox；单测不构造时为 null，只记日志） */
    private EventStore eventStore;

    private final ConcurrentMap<String, GrayPolicy> policies = new ConcurrentHashMap<>();
    /**
     * 策略的项目作用域（P5 W22 / P5-023）：{@code policyId → 可见项目集合}。
     * 契约 {@code GrayPolicy} DTO 无 projectId 字段（不改契约），归属在服务端维护（P5 迁 PG 后落 project_id 列）。
     */
    private final ConcurrentMap<String, Set<String>> policyProjects = new ConcurrentHashMap<>();
    /**
     * 策略变更事件的项目归属：种子策略保持演示默认项目 {@code P-YQY}（与 P5 W19/W20 事件证据一致）；
     * 显式按项目创建的策略发到该项目（事件按项目隔离，不以平台视图覆盖项目归属）。
     */
    private final ConcurrentMap<String, String> policyEventProjects = new ConcurrentHashMap<>();
    private final AtomicInteger sequence = new AtomicInteger();
    private final AtomicReference<String> sequenceDay = new AtomicReference<>("");

    public GrayService(ConnectorRegistry registry, ConnectorRouter router, AuditService auditService) {
        this.registry = registry;
        this.router = router;
        this.auditService = auditService;
        seed();
    }

    /** 事件存储注入（可选：保持既有单测构造不破；Spring 环境下自动注入） */
    @Autowired(required = false)
    public void setEventStore(EventStore eventStore) {
        this.eventStore = eventStore;
    }

    // ==================================================================
    // 策略查询 / 创建 / 更新
    // ==================================================================

    /** 全量策略（全量小集合，≤50，不分页；顺序 = 创建顺序 = policyId 日期+序号升序） */
    public List<GrayPolicy> list() {
        return list(null);
    }

    /**
     * 策略列表（P5 W22 / P5-023 项目作用域重载）：{@code scopeProjectId == null} = 平台级全量；
     * 非空 = 只返回该项目可见策略（演示归属见 {@link #seed()}；新建策略按创建时的项目作用域归属）。
     */
    public List<GrayPolicy> list(String scopeProjectId) {
        List<GrayPolicy> rows = new ArrayList<>();
        for (GrayPolicy policy : policies.values()) {
            if (inProjectScope(policy.policyId(), scopeProjectId)) {
                rows.add(policy);
            }
        }
        // 进程内存储是 ConcurrentHashMap（无序）：按 policyId 升序稳定输出（GRAY-yyyymmdd-nnnn 单调递增 = 创建顺序），
        // 避免客户端/契约测试拿到哈希序（同一份种子每次重启顺序不同）。
        rows.sort(java.util.Comparator.comparing(GrayPolicy::policyId));
        return List.copyOf(rows);
    }

    /** 按 ID 查策略（不存在 → 404 E-3001） */
    public GrayPolicy get(String policyId) {
        return require(policyId);
    }

    /** 按 ID 查策略（P5 W22 / P5-023 项目作用域）：不在请求项目作用域内 → 403 cross_project。 */
    public GrayPolicy get(String policyId, String scopeProjectId) {
        GrayPolicy policy = require(policyId);
        assertProjectScope(policy.policyId(), scopeProjectId);
        return policy;
    }

    /**
     * 创建灰度策略（201）：{@code scope}+{@code targetId} 唯一，{@code percent} 0—100。
     *
     * <p><b>P5 W20 回滚语义（task-65 / P5-013）</b>：同一 {@code scope}+{@code targetId} 已存在策略时，
     * 若本次请求体是**回滚意图**（{@code percent==0} 或 {@code enabled==false}），不返回 409，
     * 而是把既有策略就地回滚（{@code percent→0 / enabled→false}），审计记 {@code gray.policy.update}，
     * 事件仍发 {@code gray.policy.updated}；响应仍是 POST 的 201（契约 {@code GrayPolicy} 形状不变）。
     * 非回滚意图（{@code percent>0 && enabled==true}）的重复创建仍 409 {@code E-3002 duplicate_policy}。
     *
     * @throws DomainException 400 E-1001（参数非法）/ 404 E-3001（连接器未注册）/
     *                         409 E-3002（重复策略 {@code duplicate_policy}，且非回滚意图）
     */
    public GrayPolicy create(GrayPolicyCreateRequest request, String traceId, String ip) {
        return create(request, traceId, ip, null);
    }

    /**
     * 创建灰度策略（P5 W22 / P5-023 项目作用域重载）：显式 {@code projectId} → 策略归属该项目
     * （{@code gray.policy.updated} 事件也发到该项目）；省略 → 平台级演示默认项目 {@code P-YQY}。
     *
     * <p>唯一约束 {@code scope+targetId} 仍为全局（P5 W19 契约口径不变）：同目标不允许在两项目各建一条，
     * 项目级灰度比例覆盖（配置继承 集团→区域→项目）待 P6 迁移真实配置中心后细化。
     */
    public GrayPolicy create(GrayPolicyCreateRequest request, String traceId, String ip, String scopeProjectId) {
        if (request == null) {
            throw DomainException.badRequest("请求体不能为空（scope/targetId/percent 必填）",
                    Map.of("field", "body"));
        }
        GrayScope scope = request.scope();
        if (scope == null) {
            throw DomainException.badRequest("scope 必填（connector|capability）", Map.of("field", "scope"));
        }
        String targetId = validateTarget(scope, request.targetId());
        int percent = validatePercent(request.percent());
        boolean enabled = request.enabled();
        Optional<GrayPolicy> duplicate = policies.values().stream()
                .filter(item -> item.scope() == scope && item.targetId().equals(targetId))
                .findFirst();
        if (duplicate.isPresent()) {
            GrayPolicy existing = duplicate.get();
            if (isRollbackIntent(percent, enabled)) {
                // 回滚语义：就地回滚既有策略（审计 gray.policy.update，不新增策略、不破 scope+target 唯一约束）
                GrayPolicy rolledBack = new GrayPolicy(existing.policyId(), existing.scope(), existing.targetId(),
                        percent, enabled, existing.createdAt(), ConnectorTimes.nowIso());
                policies.put(rolledBack.policyId(), rolledBack);
                audit(AuditAction.GRAY_POLICY_UPDATE, rolledBack.policyId(), traceId, ip);
                publishEvent(rolledBack);
                log.info("[gray] rollback(POST) policyId={} scope={} targetId={} percent={} enabled={} traceId={} actorId={}",
                        rolledBack.policyId(), scope.value(), targetId, percent, enabled, traceId, actorId());
                return rolledBack;
            }
            throw DomainException.conflict("同一 scope+targetId 已存在灰度策略（唯一约束）",
                    Map.of("reason", "duplicate_policy", "scope", scope.value(), "targetId", targetId,
                            "policyId", existing.policyId()));
        }
        String now = ConnectorTimes.nowIso();
        GrayPolicy policy = new GrayPolicy(nextPolicyId(now), scope, targetId, percent, enabled, now, now);
        policies.put(policy.policyId(), policy);
        String effectiveProject = scopeProjectId == null || scopeProjectId.isBlank()
                ? ProjectService.DEFAULT_PROJECT_ID
                : scopeProjectId;
        policyProjects.put(policy.policyId(), Set.of(effectiveProject));
        policyEventProjects.put(policy.policyId(), effectiveProject);
        audit(AuditAction.GRAY_POLICY_CREATE, policy.policyId(), traceId, ip);
        publishEvent(policy);
        log.info("[gray] create policyId={} scope={} targetId={} percent={} enabled={} traceId={} actorId={}",
                policy.policyId(), scope.value(), targetId, percent, enabled, traceId, actorId());
        return policy;
    }

    /**
     * 更新灰度策略（PATCH）：{@code percent}/{@code enabled} 至少一项；{@code enabled=false} 即暂停/回滚。
     *
     * @throws DomainException 400 E-1001（空 body / percent 越界）/ 404 E-3001（策略不存在）
     */
    public GrayPolicy update(String policyId, GrayPolicyUpdateRequest request, String traceId, String ip) {
        return update(policyId, request, traceId, ip, null);
    }

    /** 更新灰度策略（P5 W22 / P5-023 项目作用域重载）：策略不在请求项目作用域内 → 403 cross_project。 */
    public GrayPolicy update(String policyId, GrayPolicyUpdateRequest request, String traceId, String ip,
            String scopeProjectId) {
        GrayPolicy current = require(policyId);
        assertProjectScope(current.policyId(), scopeProjectId);
        if (request == null || (request.percent() == null && request.enabled() == null)) {
            throw DomainException.badRequest("至少提供一项：percent（0—100）或 enabled",
                    Map.of("field", "body", "allowed", List.of("percent", "enabled")));
        }
        int percent = request.percent() == null ? current.percent() : validatePercent(request.percent());
        boolean enabled = request.enabled() == null ? current.enabled() : request.enabled();
        GrayPolicy updated = new GrayPolicy(current.policyId(), current.scope(), current.targetId(), percent, enabled,
                current.createdAt(), ConnectorTimes.nowIso());
        policies.put(updated.policyId(), updated);
        audit(AuditAction.GRAY_POLICY_UPDATE, updated.policyId(), traceId, ip);
        publishEvent(updated);
        log.info("[gray] update policyId={} percent={} enabled={} rollback={} traceId={} actorId={}",
                updated.policyId(), percent, enabled, isRollbackIntent(percent, enabled), traceId, actorId());
        return updated;
    }

    /** 回滚意图：流量归零（percent=0）或策略禁用（enabled=false） */
    private static boolean isRollbackIntent(int percent, boolean enabled) {
        return percent == 0 || !enabled;
    }

    /** 当前操作者（结构化日志字段；无上下文返回 anonymous） */
    private static String actorId() {
        Actor actor = ActorContext.actorOrNull();
        return actor == null ? "anonymous" : actor.actorId();
    }

    // ==================================================================
    // 双跑比对（GET /gray/compare）
    // ==================================================================

    /**
     * 双跑比对：旧连接器（NC）vs 新连接器（灰度中连接器），逐能力输出 checked/matched/diffCount/diffRate。
     *
     * <p><b>P5 W20 三连接器口径（task-65 / P5-013）</b>：
     * <ul>
     *   <li>{@code connectorId} 缺省 / {@code all} / {@code *} → <b>全部已接入 mock 测试替身且处于灰度/发布态</b>
     *       的连接器（当前 = 金蝶 {@code kingdee-eas} + 自研 {@code self-built-erp}），逐连接器 × 逐能力输出
     *       {@code GrayCompareItem}（{@code oldConnector}/{@code newConnector} 已是契约既有字段，不改形状）；</li>
     *   <li>{@code connectorId} 支持逗号分隔多值（如 {@code kingdee-eas,self-built-erp}），顺序按请求顺序去重；</li>
     *   <li>显式指定但未接入实现/未注册/非灰度态 → 404 {@code E-3001} / 400 {@code E-1001}（不静默回退）。</li>
     * </ul>
     *
     * @param from        时间窗起点（必填，按样本 dataAsOf 过滤）
     * @param to          时间窗终点（必填）
     * @param connectorId 新连接器（省略/全部 = 所有已接入 mock 的灰度连接器）
     * @throws DomainException 400 E-1001（from/to 缺失或非法 / connectorId 非灰度连接器）/
     *                         404 E-3001（连接器未注册）
     */
    public GrayCompareResult compare(String from, String to, String connectorId, String traceId) {
        return compare(from, to, connectorId, traceId, null);
    }

    /**
     * 双跑比对（P5 W22 / P5-023 项目作用域重载）：比对是<b>连接器级只读采样</b>，项目参数只做访问作用域校验
     * （跨项目已在 Controller 由 {@code ProjectService#resolveScope} 拒绝），不按项目过滤样本
     * （连接器是项目共用基础设施；项目级流量比例待 P6 配置继承）。
     */
    public GrayCompareResult compare(String from, String to, String connectorId, String traceId,
            String scopeProjectId) {
        OffsetDateTime fromTime = requireTime(from, "from");
        OffsetDateTime toTime = requireTime(to, "to");
        if (fromTime.isAfter(toTime)) {
            throw DomainException.badRequest("from 不能晚于 to", Map.of("field", "from", "value", from, "to", to));
        }
        List<String> targets = resolveCompareTargets(connectorId);
        List<GrayCompareItem> items = new ArrayList<>();
        ErpConnector baseline = router.resolve(BASELINE_CONNECTOR_ID);
        for (String target : targets) {
            ErpConnector candidate = router.find(target).orElseThrow(() -> DomainException.notFound(
                    ErrorCode.E_3001, "连接器未接入实现：" + target, Map.of("connectorId", target)));
            items.add(compareCapability(CapabilityCode.ROOM_QUERY, baseline, candidate, fromTime, toTime, traceId));
            items.add(compareCapability(CapabilityCode.BILL_QUERY, baseline, candidate, fromTime, toTime, traceId));
            items.add(compareCapability(CapabilityCode.TICKET_STATUS, baseline, candidate, fromTime, toTime, traceId));
        }
        GrayCompareResult result = new GrayCompareResult(List.copyOf(items), ConnectorTimes.nowIso());
        log.info("[gray] compare baseline={} targets={} window={}~{} items={} projectId={} traceId={} actorId={}",
                BASELINE_CONNECTOR_ID, targets, from, to, items.size(), scopeProjectId, traceId, actorId());
        return result;
    }

    // ==================================================================
    // 内部：比对实现
    // ==================================================================

    /**
     * 对比目标解析（P5 W20）：
     * 显式 {@code connectorId}（支持逗号分隔 / {@code all} / {@code *}）→ 校验基线/注册/灰度态；
     * 缺省 → 全部「已接入 mock（有 SPI 实现）且处于灰度/发布态」的连接器（不含基线 NC）。
     */
    private List<String> resolveCompareTargets(String connectorId) {
        if (connectorId == null || connectorId.isBlank() || "all".equalsIgnoreCase(connectorId.trim())
                || "*".equals(connectorId.trim())) {
            return defaultCompareTargets();
        }
        List<String> requested = new ArrayList<>();
        for (String raw : connectorId.split(",")) {
            String normalized = raw == null ? "" : raw.trim();
            if (normalized.isBlank() || requested.contains(normalized)) {
                continue;
            }
            validateCompareTarget(normalized);
            requested.add(normalized);
        }
        if (requested.isEmpty()) {
            return defaultCompareTargets();
        }
        return List.copyOf(requested);
    }

    /** 缺省目标：全部已接入 mock 测试替身（SPI 实现存在）且处于 GRAY/PUBLISHED 的非基线连接器 */
    private List<String> defaultCompareTargets() {
        List<String> targets = new ArrayList<>();
        for (var definition : registry.all()) {
            if (BASELINE_CONNECTOR_ID.equals(definition.connectorId())) {
                continue;
            }
            boolean grayOrPublished = definition.status() == com.property.ai.bff.dto.ConnectorStatus.GRAY
                    || definition.status() == com.property.ai.bff.dto.ConnectorStatus.PUBLISHED;
            if (!grayOrPublished) {
                continue;
            }
            if (router.find(definition.connectorId()).isEmpty()) {
                // 仅注册声明、未接入实现的连接器不参与双跑（无法并行采样）
                continue;
            }
            targets.add(definition.connectorId());
        }
        return List.copyOf(targets);
    }

    /** 显式对比目标校验：未注册 404；基线 400 not_gray；非灰度/发布态 400 */
    private void validateCompareTarget(String connectorId) {
        var definition = registry.find(connectorId).orElseThrow(() -> DomainException.notFound(
                ErrorCode.E_3001, "连接器未注册：" + connectorId, Map.of("connectorId", connectorId)));
        if (BASELINE_CONNECTOR_ID.equals(definition.connectorId())) {
            throw DomainException.badRequest("connectorId 需指向灰度中的连接器（基线连接器不能作为对比目标）",
                    Map.of("field", "connectorId", "value", connectorId, "reason", "not_gray"));
        }
        if (definition.status() != com.property.ai.bff.dto.ConnectorStatus.GRAY
                && definition.status() != com.property.ai.bff.dto.ConnectorStatus.PUBLISHED) {
            throw DomainException.badRequest("connectorId 未处于灰度/发布态，不能参与双跑",
                    Map.of("field", "connectorId", "value", connectorId, "status",
                            definition.status().value()));
        }
        if (router.find(definition.connectorId()).isEmpty()) {
            throw DomainException.notFound(ErrorCode.E_3001, "连接器未接入实现：" + connectorId,
                    Map.of("connectorId", connectorId));
        }
    }

    /** 单能力比对（按规范化投影逐序号比对；两侧样本数取较小值） */
    private GrayCompareItem compareCapability(CapabilityCode capability, ErpConnector baseline, ErpConnector candidate,
            OffsetDateTime from, OffsetDateTime to, String traceId) {
        List<Map<String, Object>> oldRows = project(capability, baseline, from, to, traceId);
        List<Map<String, Object>> newRows = project(capability, candidate, from, to, traceId);
        int checked = Math.min(oldRows.size(), newRows.size());
        int matched = 0;
        for (int index = 0; index < checked; index++) {
            if (oldRows.get(index).equals(newRows.get(index))) {
                matched++;
            }
        }
        int diffCount = checked - matched;
        BigDecimal diffRate = checked == 0
                ? BigDecimal.ZERO.setScale(3)
                : BigDecimal.valueOf(diffCount).divide(BigDecimal.valueOf(checked), 3, RoundingMode.HALF_UP);
        return new GrayCompareItem(capability, baseline.connectorId(), candidate.connectorId(), checked, matched,
                diffCount, diffRate);
    }

    /** 按能力取样本 → 规范化投影（窗口内过滤；窗口内无样本回退当前快照并记日志） */
    private List<Map<String, Object>> project(CapabilityCode capability, ErpConnector connector, OffsetDateTime from,
            OffsetDateTime to, String traceId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            switch (capability) {
                case ROOM_QUERY -> {
                    for (RoomInfo room : connector.sampleRooms(COMPARE_SAMPLE_LIMIT, traceId)) {
                        if (inWindow(room.dataAsOf(), from, to)) {
                            rows.add(projectRoom(room));
                        }
                    }
                }
                case BILL_QUERY -> {
                    for (BillInfo bill : connector.sampleBills(ConnectorTimes.currentPeriod(), COMPARE_SAMPLE_LIMIT,
                            traceId)) {
                        if (inWindow(bill.dataAsOf(), from, to)) {
                            rows.add(projectBill(bill));
                        }
                    }
                }
                case TICKET_STATUS -> {
                    for (TicketListItem ticket : connector.sampleTickets(COMPARE_SAMPLE_LIMIT, traceId)) {
                        if (inWindow(ticket.updatedAt(), from, to)) {
                            rows.add(projectTicket(ticket));
                        }
                    }
                }
                default -> {
                    // 未纳入双跑的能力（写能力/无样本能力）：不造数
                }
            }
        } catch (ConnectorException ex) {
            // 连接器不可用：该侧样本为空 → checked=0（diffRate 0），不伪造比对结果
            log.warn("[gray] 采样失败 connectorId={} capability={} code={}", connector.connectorId(),
                    capability.value(), ex.code().value());
            return List.of();
        }
        if (rows.isEmpty()) {
            log.info("[gray] 时间窗内无样本，回退当前快照 connectorId={} capability={} window={}~{}",
                    connector.connectorId(), capability.value(), from, to);
            return fallbackSnapshot(capability, connector, traceId);
        }
        return rows;
    }

    /** 窗口内无样本时的当前快照（只作当前时点对比，不伪造时间序列） */
    private List<Map<String, Object>> fallbackSnapshot(CapabilityCode capability, ErpConnector connector,
            String traceId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            switch (capability) {
                case ROOM_QUERY -> connector.sampleRooms(COMPARE_SAMPLE_LIMIT, traceId)
                        .forEach(room -> rows.add(projectRoom(room)));
                case BILL_QUERY -> connector.sampleBills(ConnectorTimes.currentPeriod(), COMPARE_SAMPLE_LIMIT, traceId)
                        .forEach(bill -> rows.add(projectBill(bill)));
                case TICKET_STATUS -> connector.sampleTickets(COMPARE_SAMPLE_LIMIT, traceId)
                        .forEach(ticket -> rows.add(projectTicket(ticket)));
                default -> {
                }
            }
        } catch (ConnectorException ex) {
            log.warn("[gray] 快照回退失败 connectorId={} capability={} code={}", connector.connectorId(),
                    capability.value(), ex.code().value());
            return List.of();
        }
        return rows;
    }

    /** 房档规范化投影：只比结构字段（房号编码体系不同，不按房号 join） */
    private static Map<String, Object> projectRoom(RoomInfo room) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("bindState", room.bindState() == null ? null : room.bindState().value());
        row.put("hasParking", room.parkingNo() != null && !room.parkingNo().isBlank());
        row.put("areaM2", normalizeAmount(room.areaM2()));
        return row;
    }

    /** 账单规范化投影：可对账口径 + 公摊电费归一额（金蝶水+电 ↔ NC PUBLIC_ENERGY） */
    private static Map<String, Object> projectBill(BillInfo bill) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("period", bill.period());
        row.put("total", normalizeAmount(bill.total()));
        row.put("paid", normalizeAmount(bill.paid()));
        row.put("outstanding", normalizeAmount(bill.outstanding()));
        row.put("status", bill.status() == null ? null : bill.status().value());
        row.put("publicEnergy", normalizeAmount(publicEnergyOf(bill)));
        return row;
    }

    private static BigDecimal publicEnergyOf(BillInfo bill) {
        if (bill.items() == null) {
            return null;
        }
        return bill.items().stream()
                .filter(item -> "PUBLIC_ENERGY".equals(item.itemCode()))
                .map(item -> item.amount() == null ? BigDecimal.ZERO : item.amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** 工单规范化投影：契约状态码（金蝶小写状态已映射 7 节点） */
    private static Map<String, Object> projectTicket(TicketListItem ticket) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("status", ticket.status() == null ? null : ticket.status().value());
        return row;
    }

    private static boolean inWindow(String dataAsOf, OffsetDateTime from, OffsetDateTime to) {
        OffsetDateTime at = ConnectorTimes.parseIsoOrNull(dataAsOf);
        if (at == null) {
            return false;
        }
        return !at.isBefore(from) && !at.isAfter(to);
    }

    private static BigDecimal normalizeAmount(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros();
    }

    // ==================================================================
    // 内部：项目作用域（P5 W22 / P5-023）
    // ==================================================================

    /** 是否在项目作用域内（{@code scopeProjectId == null}/空 = 平台级，全部可见；未知策略按默认项目归属） */
    private boolean inProjectScope(String policyId, String scopeProjectId) {
        if (scopeProjectId == null || scopeProjectId.isBlank()) {
            return true;
        }
        Set<String> projects = policyProjects.getOrDefault(policyId, Set.of(ProjectService.DEFAULT_PROJECT_ID));
        return projects.contains(scopeProjectId);
    }

    /** 项目作用域校验：策略不在请求项目作用域内 → 403 {@code E-2002 cross_project} */
    private void assertProjectScope(String policyId, String scopeProjectId) {
        if (inProjectScope(policyId, scopeProjectId)) {
            return;
        }
        throw DomainException.crossProject("gray_policy", policyId, scopeProjectId);
    }

    // ==================================================================
    // 内部：校验 / 审计 / 事件
    // ==================================================================

    private GrayPolicy require(String policyId) {
        if (policyId == null || policyId.isBlank()) {
            throw DomainException.badRequest("policyId 不能为空", Map.of("field", "policyId"));
        }
        GrayPolicy policy = policies.get(policyId.trim());
        if (policy == null) {
            throw DomainException.notFound(ErrorCode.E_3001, "灰度策略不存在：" + policyId,
                    Map.of("policyId", policyId));
        }
        return policy;
    }

    /** targetId 校验：connector → 已注册连接器（404）；capability → 18 项能力码（400） */
    private String validateTarget(GrayScope scope, String targetId) {
        if (targetId == null || targetId.isBlank()) {
            throw DomainException.badRequest("targetId 必填（连接器 ID 或 CapabilityCode）",
                    Map.of("field", "targetId", "scope", scope.value()));
        }
        String normalized = targetId.trim();
        if (scope == GrayScope.CONNECTOR) {
            registry.find(normalized).orElseThrow(() -> DomainException.notFound(ErrorCode.E_3001,
                    "连接器未注册：" + normalized, Map.of("connectorId", normalized)));
            return normalized;
        }
        try {
            CapabilityCode.fromValue(normalized);
            return normalized;
        } catch (IllegalArgumentException ex) {
            throw DomainException.badRequest("targetId 不是 18 项标准能力码（如 bill.query）",
                    Map.of("field", "targetId", "value", normalized, "scope", scope.value()));
        }
    }

    private static int validatePercent(Integer percent) {
        if (percent == null) {
            throw DomainException.badRequest("percent 必填（0—100）", Map.of("field", "percent"));
        }
        if (percent < 0 || percent > 100) {
            throw DomainException.badRequest("percent 必须在 0—100 之间",
                    Map.of("field", "percent", "value", percent, "minimum", 0, "maximum", 100));
        }
        return percent;
    }

    private static OffsetDateTime requireTime(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw DomainException.badRequest(field + " 必填（ISO 8601 时间，如 2026-11-16T00:00:00+08:00）",
                    Map.of("field", field));
        }
        OffsetDateTime parsed = ConnectorTimes.parseIsoOrNull(raw);
        if (parsed == null) {
            throw DomainException.badRequest(field + " 必须为 ISO 8601 时间",
                    Map.of("field", field, "value", raw));
        }
        return parsed;
    }

    private String nextPolicyId(String now) {
        OffsetDateTime at = ConnectorTimes.parseIsoOrNull(now);
        String day = at == null
                ? ConnectorTimes.todayCompact()
                : String.format("%04d%02d%02d", at.getYear(), at.getMonthValue(), at.getDayOfMonth());
        synchronized (sequence) {
            if (!day.equals(sequenceDay.get())) {
                sequenceDay.set(day);
                sequence.set(0);
            }
            return "GRAY-" + day + "-" + String.format("%04d", sequence.incrementAndGet());
        }
    }

    /** 灰度写操作审计（gray.policy.create / gray.policy.update，资源类型 gray_policy） */
    private void audit(AuditAction action, String policyId, String traceId, String ip) {
        Actor actor = ActorContext.actorOrNull();
        try {
            auditService.record(new AuditService.AuditRecord(
                    actor == null ? "anonymous" : actor.actorId(),
                    actor == null ? com.property.ai.bff.dto.ActorRole.IT : actor.role(),
                    action,
                    AuditResourceType.GRAY_POLICY,
                    policyId,
                    traceId,
                    AuditResult.SUCCESS,
                    ip == null ? null : com.property.ai.audit.AuditIp.sanitize(ip),
                    List.of()));
        } catch (RuntimeException ex) {
            log.warn("[gray] 审计写入失败 action={} policyId={} err={}", action.value(), policyId,
                    ex.getClass().getSimpleName());
        }
    }

    /** outbox 事件（gray.policy.updated）；事件存储未注入时只记日志（单测/离线模式） */
    private void publishEvent(GrayPolicy policy) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("policyId", policy.policyId());
        payload.put("scope", policy.scope().value());
        payload.put("targetId", policy.targetId());
        payload.put("percent", policy.percent());
        payload.put("enabled", policy.enabled());
        if (eventStore == null) {
            log.debug("[gray] 事件存储未注入，跳过 gray.policy.updated event policyId={}", policy.policyId());
            return;
        }
        eventStore.publish(EventType.GRAY_POLICY_UPDATED,
                policyEventProjects.getOrDefault(policy.policyId(), ProjectService.DEFAULT_PROJECT_ID), payload);
    }

    // ==================================================================
    // 种子（与契约示例一致：connector kingdee-eas 10% / capability bill.query 50% 暂停）
    // ==================================================================

    private void seed() {
        GrayPolicy connectorPolicy = new GrayPolicy("GRAY-20261116-0001", GrayScope.CONNECTOR,
                ConnectorRegistry.KINGDEE, 10, true, SEED_CREATED_AT, SEED_CREATED_AT);
        GrayPolicy capabilityPolicy = new GrayPolicy("GRAY-20261116-0002", GrayScope.CAPABILITY,
                CapabilityCode.BILL_QUERY.value(), 50, false, "2026-11-16T10:10:00+08:00",
                "2026-11-16T10:30:00+08:00");
        policies.put(connectorPolicy.policyId(), connectorPolicy);
        policies.put(capabilityPolicy.policyId(), capabilityPolicy);
        // P5 W22 / P5-023 演示项目归属：金蝶连接器灰度 = P-JZY（金蝶项目）；能力级 bill.query = P-YQY + P-LVY
        policyProjects.put(connectorPolicy.policyId(), Set.of("P-JZY"));
        policyProjects.put(capabilityPolicy.policyId(), Set.of("P-YQY", "P-LVY"));
        // 种子策略事件仍按演示默认项目 P-YQY（与 P5 W19/W20 事件证据一致；按项目建策略时事件才随之项目）
        policyEventProjects.put(connectorPolicy.policyId(), ProjectService.DEFAULT_PROJECT_ID);
        policyEventProjects.put(capabilityPolicy.policyId(), ProjectService.DEFAULT_PROJECT_ID);
        sequenceDay.set("20261116");
        sequence.set(2);
    }

    /** 重置为种子（仅测试/演示重置用；生产策略是配置数据，不允许运行时清空） */
    public synchronized void resetToSeed() {
        policies.clear();
        policyProjects.clear();
        policyEventProjects.clear();
        sequence.set(0);
        sequenceDay.set("");
        seed();
    }
}
