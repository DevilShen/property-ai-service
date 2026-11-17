package com.property.ai.domain.mapping;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.property.ai.bff.dto.ErrorCode;
import com.property.ai.bff.dto.MappingField;
import com.property.ai.bff.dto.MappingTrialReport;
import com.property.ai.bff.dto.MappingVersion;
import com.property.ai.bff.dto.MappingVersionStatus;
import com.property.ai.connector.ConnectorRegistry;
import com.property.ai.connector.ConnectorRouter;
import com.property.ai.connector.ConnectorTimes;
import com.property.ai.connector.ErpConnector;
import com.property.ai.domain.project.ProjectService;
import com.property.ai.domain.ticket.DomainException;

/**
 * 映射版本化服务（P2-024，契约 v0.3.2 {@code /api/v1/mappings*}）。
 *
 * <h2>状态机（架构 §5.2）</h2>
 * <pre>
 * draft ──trial(passed)──▶ trial_passed ──publish──▶ published ──rollback──▶ rolled_back
 *   ▲                                                    │                         │
 *   └──────────── (新版本另起 draft，历史版本保留 ≥2 个可回滚) ◀──────────────────────┘
 * </pre>
 * <ul>
 *   <li>试跑只读样本、不改业务主数据；通过（diffRate ≤ 0.02）后版本可推进 {@code trial_passed}；</li>
 *   <li>发布前置必须是 {@code trial_passed}，否则 409 {@code E-3002}（details 给 allowed=[trial]）；</li>
 *   <li>回滚把当前 {@code published} 版本置 {@code rolled_back}，目标历史版本重新生效并 {@code published}；
 *       {@code toVersion} 不存在 → 404 {@code E-3001}，无可用回滚版本 → 409 {@code E-3002}；</li>
 *   <li>保留 ≥2 个可回滚版本（初始种子含上一个已发布版本）。</li>
 * </ul>
 *
 * <p>存储：P2 为进程内仓储（单实例，重启回种子），P5 起迁 PG 并保留本类接口；
 * 配置类动作不写业务主数据（架构 §24.5），可选 {@code Idempotency-Key} 由 BFF 层按可重放处理。
 */
@Service
public class MappingService {

    private static final Logger log = LoggerFactory.getLogger(MappingService.class);

    /** 试跑默认样本数（契约：默认 200） */
    public static final int DEFAULT_TRIAL_SAMPLE_SIZE = 200;
    /** 试跑样本数上限（契约：1—1000） */
    public static final int MAX_TRIAL_SAMPLE_SIZE = 1000;
    /** 默认操作人（P2 无登录态；P2-029 登录后取真实操作人） */
    public static final String DEFAULT_OPERATOR = "演示管理员";

    private static final String SEED_UPDATED_AT = "2026-11-15T09:10:00+08:00";

    /** 默认连接器 ID（P2—P4 单连接器语义：种子映射均绑定用友 NC mock） */
    private static final String DEFAULT_CONNECTOR_ID = "yonyou-nc-mock";

    private final ConnectorRouter router;
    /** 连接器注册表（校验 connectorId 合法性；离线单测模式为 null，跳过多连接器校验） */
    private final ConnectorRegistry registry;
    /** 单连接器离线模式（P2 兼容构造）：非空时全部映射都路由到该连接器，不经注册表路由 */
    private final ErpConnector singleConnector;
    private final MappingTrialEvaluator evaluator;
    private final Map<String, MappingRecord> records = new LinkedHashMap<>();
    private final AtomicInteger trialSequence = new AtomicInteger();

    /**
     * 多连接器构造（P5 W19 / P5-006）：映射仓储按 {@code connectorId} 隔离，样本/字段字典按映射绑定连接器路由。
     */
    @Autowired
    public MappingService(ConnectorRouter router, ConnectorRegistry registry, MappingTrialEvaluator evaluator) {
        this(router, registry, evaluator, null);
    }

    /** 内部构造：{@code singleConnector} 非空 = 单连接器离线模式（P2 兼容） */
    private MappingService(ConnectorRouter router, ConnectorRegistry registry, MappingTrialEvaluator evaluator,
            ErpConnector singleConnector) {
        this.router = router;
        this.registry = registry;
        this.evaluator = evaluator;
        this.singleConnector = singleConnector;
        seed();
    }

    /** P2 兼容构造（单连接器离线单测；既有 {@code new MappingService(connector, evaluator)} 调用不破） */
    public MappingService(ErpConnector connector, MappingTrialEvaluator evaluator) {
        this(connector == null ? null : new ConnectorRouter(connector), (ConnectorRegistry) null, evaluator,
                connector);
    }

    // ==================================================================
    // 查询
    // ==================================================================

    /**
     * 映射版本列表（全量小集合，不分页；fieldCount 需要字段字典，会按需读 ERP）。
     *
     * <p>P5 W19 多连接器（v1.3.0）：{@code connectorId} 省略 = 全部连接器；显式指定则只返回该连接器映射
     * （多连接器映射仓储彼此隔离，互不污染）。非法/未注册 connectorId → 400 {@code E-1001}（契约）。
     */
    public synchronized List<MappingVersion> list(String connectorId, String traceId) {
        return list(connectorId, traceId, null);
    }

    /**
     * 映射版本列表（P5 W22 / P5-023 项目作用域重载）。
     *
     * @param scopeProjectId 项目作用域（{@code null} = 平台级全量；非空 = 只返回该项目可见映射，
     *                       见 {@link #projectIdsOf(MappingRecord)} 的演示归属口径）
     */
    public synchronized List<MappingVersion> list(String connectorId, String traceId, String scopeProjectId) {
        String normalized = normalizeConnectorFilter(connectorId);
        List<MappingVersion> rows = new ArrayList<>();
        for (MappingRecord record : records.values()) {
            if (normalized != null && !normalized.equals(record.connectorId)) {
                continue;
            }
            if (!inProjectScope(record, scopeProjectId)) {
                continue;
            }
            fields(record, traceId);
            rows.add(toVersion(record, false));
        }
        return List.copyOf(rows);
    }

    /** P2 兼容重载（全部连接器；既有调用不破） */
    public List<MappingVersion> list(String traceId) {
        return list(null, traceId, null);
    }

    /** 映射版本详情（含 fields[]） */
    public synchronized MappingVersion get(String mappingId, String traceId) {
        return get(mappingId, traceId, null);
    }

    /** 映射版本详情（P5 W22 / P5-023 项目作用域重载）：映射不在请求项目作用域内 → 403 cross_project。 */
    public synchronized MappingVersion get(String mappingId, String traceId, String scopeProjectId) {
        MappingRecord record = require(mappingId);
        assertProjectScope(record, scopeProjectId);
        fields(record, traceId);
        return toVersion(record, true);
    }

    // ==================================================================
    // 试跑 / 发布 / 回滚
    // ==================================================================

    /**
     * 映射试跑：默认 200 样本，逐字段比对（完整性/类型/必填/金额恒等式）并给出通过结论。
     *
     * <p>通过后：{@code draft|rolled_back → trial_passed}；已 {@code published} 的版本试跑不影响其状态
     * （避免线上版本被试跑降级）。试跑失败不改状态（不能拿未验证的映射上线）。
     */
    public synchronized MappingTrialReport trial(String mappingId, Integer sampleSize, String traceId) {
        return trial(mappingId, sampleSize, traceId, null);
    }

    /**
     * 映射试跑（P5 W22 / P5-023 项目作用域重载）：显式项目作用域与映射归属不一致 → 403 {@code cross_project}
     * （试跑只能验证本项目可见的映射；平台级省略 projectId 行为不变）。
     */
    public synchronized MappingTrialReport trial(String mappingId, Integer sampleSize, String traceId,
            String scopeProjectId) {
        MappingRecord record = require(mappingId);
        assertProjectScope(record, scopeProjectId);
        int size = sampleSize == null ? DEFAULT_TRIAL_SAMPLE_SIZE : sampleSize;
        if (size < 1 || size > MAX_TRIAL_SAMPLE_SIZE) {
            throw DomainException.badRequest("sampleSize 必须在 1—" + MAX_TRIAL_SAMPLE_SIZE + " 之间",
                    Map.of("field", "sampleSize", "sampleSize", size, "maximum", MAX_TRIAL_SAMPLE_SIZE));
        }
        List<MappingField> fields = fields(record, traceId);
        String startedAt = ConnectorTimes.nowIso();
        List<Map<String, Object>> samples = samples(record, size, traceId);
        MappingTrialEvaluator.TrialResult result = evaluator.evaluate(record.sampleKind, fields, samples);
        String finishedAt = ConnectorTimes.nowIso();
        String runId = String.format("TRIAL-%s-%04d", ConnectorTimes.todayCompact(), trialSequence.incrementAndGet());
        if (result.passed()) {
            markTrialPassed(record, finishedAt);
        }
        log.info("[mapping] trial {} runId={} checked={} diffCount={} diffRate={} passed={}", mappingId, runId,
                result.checked(), result.diffCount(), result.diffRate(), result.passed());
        return new MappingTrialReport(runId, mappingId, result.checked(), result.matched(), result.diffCount(),
                result.diffRate(), evaluator.threshold(), result.passed(), result.items(), startedAt, finishedAt);
    }

    /** 映射版本发布（P2 兼容重载：不指定连接器作用域 = 映射自身 connectorId） */
    public synchronized MappingVersion publish(String mappingId, String operator) {
        return publish(mappingId, null, operator, null);
    }

    /**
     * 映射版本发布（P5 W19 / P5-006 多连接器）：仅 {@code trial_passed} 可发布，否则 409 {@code E-3002}。
     *
     * <p>连接器作用域：{@code connectorId} 省略 = 映射自身 {@code connectorId}（v0.3.2 行为不变）；
     * 显式指定与映射绑定连接器不一致 → 403 {@code E-2002} + {@code details{reason=cross_connector}}
     * （映射不能跨连接器发布）。
     */
    public synchronized MappingVersion publish(String mappingId, String connectorId, String operator) {
        return publish(mappingId, connectorId, operator, null);
    }

    /**
     * 映射版本发布（P5 W22 / P5-023 项目作用域重载）：显式项目作用域与映射归属不一致 → 403 {@code cross_project}
     * （项目作用域先于连接器作用域校验：先判"是不是本项目的映射"，再判"是不是本连接器的映射"）。
     */
    public synchronized MappingVersion publish(String mappingId, String connectorId, String operator,
            String scopeProjectId) {
        MappingRecord record = require(mappingId);
        assertProjectScope(record, scopeProjectId);
        assertConnectorScope(record, connectorId);
        VersionEntry current = record.current();
        if (current.status != MappingVersionStatus.TRIAL_PASSED) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("mappingId", record.id);
            details.put("status", current.status.value());
            details.put("allowed", allowedActions(current.status));
            throw DomainException.conflict("当前版本未通过试跑，不能发布（需先 trial 通过）", details);
        }
        String now = ConnectorTimes.nowIso();
        current.status = MappingVersionStatus.PUBLISHED;
        current.publishedAt = now;
        current.updatedAt = now;
        current.operator = operator == null || operator.isBlank() ? DEFAULT_OPERATOR : operator;
        log.info("[mapping] publish {} version={} operator={}", record.id, current.version, current.operator);
        return toVersion(record, false);
    }

    /** 映射版本回滚（P2 兼容重载：不指定连接器作用域 = 映射自身 connectorId） */
    public synchronized MappingVersion rollback(String mappingId, String toVersion, String reason) {
        return rollback(mappingId, toVersion, reason, null);
    }

    /**
     * 映射版本回滚（P5 W19 / P5-006 多连接器）：{@code toVersion} 省略 = 上一个已发布版本。
     *
     * <p>连接器作用域口径同 {@link #publish(String, String, String)}：显式指定且与映射绑定连接器不一致
     * → 403 {@code E-2002} {@code cross_connector}。
     *
     * @throws DomainException 404 E-3001（目标版本不存在）/ 409 E-3002（当前未发布 / 无可用回滚版本）
     */
    public synchronized MappingVersion rollback(String mappingId, String toVersion, String reason,
            String connectorId) {
        return rollback(mappingId, toVersion, reason, connectorId, null);
    }

    /** 回滚（P5 W22 / P5-023 项目作用域重载）：映射不在请求项目作用域内 → 403 {@code cross_project}。 */
    public synchronized MappingVersion rollback(String mappingId, String toVersion, String reason,
            String connectorId, String scopeProjectId) {
        MappingRecord record = require(mappingId);
        assertProjectScope(record, scopeProjectId);
        assertConnectorScope(record, connectorId);
        VersionEntry current = record.current();
        if (current.status != MappingVersionStatus.PUBLISHED) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("mappingId", record.id);
            details.put("status", current.status.value());
            details.put("allowed", allowedActions(current.status));
            throw DomainException.conflict("当前版本未发布，无回滚对象", details);
        }
        int targetIndex = resolveRollbackTarget(record, toVersion);
        String now = ConnectorTimes.nowIso();
        current.status = MappingVersionStatus.ROLLED_BACK;
        current.updatedAt = now;
        VersionEntry target = record.versions.get(targetIndex);
        target.publishedAt = now;
        target.updatedAt = now;
        record.currentIndex = targetIndex;
        log.info("[mapping] rollback {} from={} to={} reason={}", record.id, current.version, target.version,
                reason == null ? "" : reason);
        return toVersion(record, false);
    }

    // ==================================================================
    // 内部：状态机
    // ==================================================================

    private int resolveRollbackTarget(MappingRecord record, String toVersion) {
        int currentIndex = record.currentIndex;
        if (toVersion != null && !toVersion.isBlank()) {
            String expected = toVersion.trim();
            for (int index = 0; index < record.versions.size(); index++) {
                VersionEntry entry = record.versions.get(index);
                if (index == currentIndex || !entry.version.equals(expected)) {
                    continue;
                }
                if (entry.status != MappingVersionStatus.PUBLISHED) {
                    throw DomainException.conflict("目标版本不是已发布版本，不能回滚",
                            Map.of("mappingId", record.id, "toVersion", expected, "status", entry.status.value()));
                }
                return index;
            }
            throw DomainException.notFound(ErrorCode.E_3001, "目标映射版本不存在：" + expected,
                    Map.of("mappingId", record.id, "toVersion", expected));
        }
        for (int index = currentIndex - 1; index >= 0; index--) {
            if (record.versions.get(index).status == MappingVersionStatus.PUBLISHED) {
                return index;
            }
        }
        throw DomainException.conflict("无可用回滚版本（历史里没有已发布版本）",
                Map.of("mappingId", record.id, "status", record.current().status.value(),
                        "allowed", List.of("publish")));
    }

    private static List<String> allowedActions(MappingVersionStatus status) {
        return switch (status) {
            case DRAFT -> List.of("trial");
            case TRIAL_PASSED -> List.of("publish", "trial");
            case PUBLISHED -> List.of("rollback", "trial");
            case ROLLED_BACK -> List.of("trial");
        };
    }

    private void markTrialPassed(MappingRecord record, String at) {
        VersionEntry current = record.current();
        if (current.status == MappingVersionStatus.DRAFT || current.status == MappingVersionStatus.ROLLED_BACK) {
            current.status = MappingVersionStatus.TRIAL_PASSED;
            current.updatedAt = at;
        }
    }

    // ==================================================================
    // 内部：项目作用域（P5 W22 / P5-023）
    // ==================================================================

    /**
     * 演示项目归属（P5 W22 / P5-023）：映射是<b>平台配置视图</b>（契约 §7.6 仅 it/admin 可读），
     * 但需要按项目切换看"本项目启用哪些映射"。演示口径（不新增种子、不改既有 5 条映射契约基线）：
     * <ul>
     *   <li>{@code MAP-NC-TICKET} / {@code MAP-NC-BILL}：P-YQY（主项目，用友 NC）；</li>
     *   <li>{@code MAP-NC-ROOM}：P-YQY + P-LVY（两项目同用 NC，房档映射共用）；</li>
     *   <li>{@code MAP-KD-BILL} / {@code MAP-KD-TICKET}：P-JZY（金蝶 EAS 项目）。</li>
     * </ul>
     */
    private static Set<String> projectIdsOf(MappingRecord record) {
        return switch (record.id) {
            case "MAP-KD-BILL", "MAP-KD-TICKET" -> Set.of("P-JZY");
            case "MAP-NC-ROOM" -> Set.of("P-YQY", "P-LVY");
            default -> Set.of(ProjectService.DEFAULT_PROJECT_ID);
        };
    }

    /** 是否在项目作用域内（{@code scopeProjectId == null}/空 = 平台级，全部可见） */
    private static boolean inProjectScope(MappingRecord record, String scopeProjectId) {
        if (scopeProjectId == null || scopeProjectId.isBlank()) {
            return true;
        }
        return projectIdsOf(record).contains(scopeProjectId);
    }

    /** 项目作用域校验：显式项目作用域与映射归属不一致 → 403 {@code E-2002 cross_project} */
    private static void assertProjectScope(MappingRecord record, String scopeProjectId) {
        if (scopeProjectId == null || scopeProjectId.isBlank() || inProjectScope(record, scopeProjectId)) {
            return;
        }
        throw DomainException.crossProject("mapping", record.id, scopeProjectId);
    }

    // ==================================================================
    // 内部：多连接器作用域
    // ==================================================================

    /** 映射绑定的连接器实现（显式 connectorId 路由；未接入实现/离线单测 → 404 E-3001 或默认连接器） */
    private ErpConnector connectorOf(MappingRecord record) {
        if (singleConnector != null) {
            return singleConnector;
        }
        if (router == null) {
            throw DomainException.notFound(ErrorCode.E_3001, "映射未绑定可用连接器：" + record.connectorId,
                    Map.of("connectorId", record.connectorId));
        }
        return router.resolve(record.connectorId);
    }

    /**
     * 连接器作用域校验（P5 W19 / v1.3.0）：显式 connectorId 必须等于映射绑定连接器，否则 403
     * {@code E-2002} {@code cross_connector}（省略 = 映射自身 connectorId，v0.3.2 行为不变）。
     */
    private static void assertConnectorScope(MappingRecord record, String connectorId) {
        if (connectorId == null || connectorId.isBlank()) {
            return;
        }
        String requested = connectorId.trim();
        if (!requested.equals(record.connectorId)) {
            throw DomainException.crossConnector(record.id, requested, record.connectorId);
        }
    }

    /**
     * 列表过滤用的 connectorId 校验：省略 → null（全部）；显式但未注册 → 400 {@code E-1001}
     * （契约 {@code GET /mappings?connectorId=} 描述：非法/未注册 → 400）。
     */
    private String normalizeConnectorFilter(String connectorId) {
        if (connectorId == null || connectorId.isBlank()) {
            return null;
        }
        String normalized = connectorId.trim();
        if (registry != null && registry.find(normalized).isEmpty()) {
            throw DomainException.badRequest("connectorId 未注册（如 yonyou-nc-mock / kingdee-eas）",
                    Map.of("field", "connectorId", "value", connectorId));
        }
        return normalized;
    }

    // ==================================================================
    // 内部：样本 / 字段字典 / DTO 组装
    // ==================================================================

    /** 试跑样本（映射后 DTO → 投影行）；按映射类别取**映射绑定连接器**的 ERP 样本，最多 sampleSize 条 */
    private List<Map<String, Object>> samples(MappingRecord record, int sampleSize, String traceId) {
        ErpConnector connector = connectorOf(record);
        List<Map<String, Object>> rows = new ArrayList<>();
        switch (record.sampleKind) {
            case "room" -> {
                for (var info : connector.sampleRooms(sampleSize, traceId)) {
                    rows.add(MappingSampleProjector.room(info));
                }
            }
            case "bill" -> {
                for (var info : connector.sampleBills(ConnectorTimes.currentPeriod(), sampleSize, traceId)) {
                    rows.add(MappingSampleProjector.bill(info));
                }
            }
            case "ticket" -> {
                for (var info : connector.sampleTickets(sampleSize, traceId)) {
                    rows.add(MappingSampleProjector.ticket(info));
                }
            }
            default -> {
                // 无样本来源的映射类别：返回空样本 → passed=false（不伪造通过）
            }
        }
        return rows;
    }

    /** 字段字典（按映射绑定连接器的字典类别合并，结果缓存到进程内；多连接器互不污染） */
    private List<MappingField> fields(MappingRecord record, String traceId) {
        if (record.fields != null) {
            return record.fields;
        }
        ErpConnector connector = connectorOf(record);
        List<MappingField> merged = new ArrayList<>();
        for (String category : record.dictionaryCategories) {
            merged.addAll(connector.fieldDictionary(category, traceId));
        }
        record.fields = List.copyOf(merged);
        return record.fields;
    }

    private MappingVersion toVersion(MappingRecord record, boolean includeFields) {
        VersionEntry current = record.current();
        int fieldCount = record.fields == null ? 0 : record.fields.size();
        return new MappingVersion(record.id, record.name, record.connectorId, current.version, current.status,
                fieldCount, includeFields ? record.fields : null, current.updatedAt,
                current.status == MappingVersionStatus.PUBLISHED ? current.publishedAt : null, current.operator);
    }

    /**
     * 重置为种子数据（仅测试/演示重置用；P5 迁 PG 后由数据迁移替代）。
     * 生产路径不调用：映射版本是配置数据，不允许运行时清空。
     */
    public synchronized void resetToSeed() {
        records.clear();
        trialSequence.set(0);
        seed();
    }

    private MappingRecord require(String mappingId) {        if (mappingId == null || mappingId.isBlank()) {
            throw DomainException.badRequest("mappingId 不能为空", Map.of("field", "mappingId"));
        }
        MappingRecord record = records.get(mappingId.trim());
        if (record == null) {
            throw DomainException.notFound(ErrorCode.E_3001, "映射版本不存在：" + mappingId,
                    Map.of("mappingId", mappingId));
        }
        return record;
    }

    // ==================================================================
    // 种子（与契约示例一致：MAP-NC-TICKET published / MAP-NC-BILL trial_passed）
    // P5 W19 多连接器：每个连接器独立的映射版本（按 connectorId 隔离，互不污染）
    // ==================================================================

    private void seed() {
        seedMapping("MAP-NC-TICKET", "用友 NC · 工单映射", DEFAULT_CONNECTOR_ID, "ticket", List.of("ticket"),
                "v1.8.2", MappingVersionStatus.PUBLISHED, "张敏", "2026-11-14T19:30:00+08:00", "v1.8.1");
        seedMapping("MAP-NC-BILL", "用友 NC · 账单映射", DEFAULT_CONNECTOR_ID, "bill", List.of("bill"),
                "v1.7.6", MappingVersionStatus.TRIAL_PASSED, "李工", null, "v1.7.5");
        seedMapping("MAP-NC-ROOM", "用友 NC · 房档映射", DEFAULT_CONNECTOR_ID, "room", List.of("room", "owner"),
                "v1.2.0", MappingVersionStatus.DRAFT, "张敏", null, null);
        // 金蝶 EAS（:4400 测试替身，只读）：账单映射已发布（多连接器映射中心第 2 家），工单映射试跑通过待发布
        seedMapping("MAP-KD-BILL", "金蝶 EAS · 账单映射", "kingdee-eas", "bill", List.of("bill"),
                "v2.1.0", MappingVersionStatus.PUBLISHED, "张敏", "2026-11-16T10:00:00+08:00", "v2.0.0");
        seedMapping("MAP-KD-TICKET", "金蝶 EAS · 工单映射", "kingdee-eas", "ticket", List.of("ticket"),
                "v2.1.0", MappingVersionStatus.TRIAL_PASSED, "李工", null, "v2.0.0");
    }

    private void seedMapping(String id, String name, String connectorId, String sampleKind,
            List<String> dictionaryCategories, String currentVersion, MappingVersionStatus currentStatus,
            String operator, String publishedAt, String previousPublishedVersion) {
        MappingRecord record = new MappingRecord(id, name, connectorId, sampleKind, dictionaryCategories);
        if (previousPublishedVersion != null) {
            record.versions.add(new VersionEntry(previousPublishedVersion, MappingVersionStatus.PUBLISHED,
                    "2026-11-01T10:00:00+08:00", "张敏", "2026-11-01T10:00:00+08:00"));
        }
        record.versions.add(new VersionEntry(currentVersion, currentStatus, publishedAt, operator,
                publishedAt == null ? SEED_UPDATED_AT : publishedAt));
        record.currentIndex = record.versions.size() - 1;
        records.put(id, record);
    }

    /** 单条映射记录（进程内状态；fields 懒加载缓存；connectorId 决定样本/字段字典来源） */
    private static final class MappingRecord {
        private final String id;
        private final String name;
        private final String connectorId;
        private final String sampleKind;
        private final List<String> dictionaryCategories;
        private final List<VersionEntry> versions = new ArrayList<>();
        private int currentIndex;
        private List<MappingField> fields;

        private MappingRecord(String id, String name, String connectorId, String sampleKind,
                List<String> dictionaryCategories) {
            this.id = id;
            this.name = name;
            this.connectorId = connectorId;
            this.sampleKind = sampleKind;
            this.dictionaryCategories = List.copyOf(dictionaryCategories);
        }

        private VersionEntry current() {
            return versions.get(currentIndex);
        }
    }

    /** 单个版本（状态机节点）；历史版本保留用于回滚（≥2 个可回滚版本） */
    private static final class VersionEntry {
        private final String version;
        private MappingVersionStatus status;
        private String publishedAt;
        private String operator;
        private String updatedAt;

        private VersionEntry(String version, MappingVersionStatus status, String publishedAt, String operator,
                String updatedAt) {
            this.version = version;
            this.status = status;
            this.publishedAt = publishedAt;
            this.operator = operator;
            this.updatedAt = updatedAt;
        }
    }
}
