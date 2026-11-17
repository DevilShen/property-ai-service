package com.property.ai.connector;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.property.ai.bff.dto.CapabilityCode;
import com.property.ai.bff.dto.CapabilityState;
import com.property.ai.bff.dto.CapabilityStatus;
import com.property.ai.bff.dto.ConnectorHealth;
import com.property.ai.bff.dto.ConnectorSummary;
import com.property.ai.bff.dto.ProbeLatestResult;
import com.property.ai.bff.dto.ProbeListResult;
import com.property.ai.bff.dto.ProbeReport;
import com.property.ai.bff.dto.ProbeSummary;
import com.property.ai.domain.ticket.DomainException;

/**
 * 能力探测服务（P2-025，契约 v0.3.2 {@code /api/v1/connectors/{id}/probe*}）。
 *
 * <p>口径（架构 §4.3/§6）：
 * <ul>
 *   <li>探测 = 读 ERP 能力元数据（{@code services/mock-erp} {@code /api/v1/meta/capabilities}）
 *       → 归一为 18 项 {@link CapabilityState}（顺序 CAP-01…CAP-18）→ 落探测日报（内存，保留最近 7 天/最近 50 次）；</li>
 *   <li>{@code summary.drift} = 与上一次探测相比状态变化的能力数（声明与探测不符时以探测为准）；</li>
 *   <li>探测失败（ERP 超时/5xx/返回不足 18 项）→ 抛 {@code ConnectorException}（503），
 *       <b>不更新能力矩阵、不写日报</b>，{@code /probe/latest} 仍返回上一次结果（契约 503 语义）；</li>
 *   <li>只有注册表里 {@code probeable=true} 的连接器可探测（P2 W7 仅用友 NC mock）；
 *       其余返回 503 {@code E-5001}，不把注册声明说成已验证。</li>
 * </ul>
 *
 * <p>探测场景（{@code all_supported|degraded}）由 {@code property.erp.probe-scenario} 决定：
 * 默认 {@code all_supported}；{@code degraded} 是 mock-erp 人为构造的三态演示场景
 * （<b>不代表真实 NC 能力差异</b>，仅用于入口显隐/降级话术/台账联调）。
 */
@Service
public class ProbeService {

    private static final Logger log = LoggerFactory.getLogger(ProbeService.class);

    /** 日报保留时长（契约：近 7 日历史） */
    private static final Duration RETENTION = Duration.ofDays(7);
    /** 每个连接器最多保留的日报条数（内存保护） */
    private static final int MAX_REPORTS_PER_CONNECTOR = 50;
    /** {@code /probe/latest} 返回的历史条数上限（契约 maxItems=7） */
    private static final int HISTORY_LIMIT = 7;

    private final ConnectorRegistry registry;
    private final ConnectorRouter router;
    private final ConnectorProperties properties;
    private final ConnectorRuntime runtime;

    /** connectorId → 探测日报（新→旧） */
    private final Map<String, List<ProbeReport>> reports = new ConcurrentHashMap<>();
    private final AtomicInteger sequence = new AtomicInteger();

    public ProbeService(ConnectorRegistry registry, ConnectorRouter router, ConnectorProperties properties,
            ConnectorRuntime runtime) {
        this.registry = registry;
        this.router = router;
        this.properties = properties;
        this.runtime = runtime;
    }

    // ------------------------------------------------------------------
    // 探测 / 查询
    // ------------------------------------------------------------------

    /**
     * 触发一次能力探测并落日报。
     *
     * @throws DomainException   连接器未注册（404 E-3001）
     * @throws ConnectorException 未接入（503 E-5001）/探测失败（503 E-400x/E-5002）
     */
    public ProbeReport probe(String connectorId, String traceId) {
        ConnectorDefinition definition = requireDefinition(connectorId);
        if (!definition.probeable()) {
            throw new ConnectorException(com.property.ai.bff.dto.ErrorCode.E_5001,
                    "连接器未接入（status=" + definition.status().value() + "），无法探测能力矩阵",
                    false, Map.of("connectorId", definition.connectorId(), "status", definition.status().value(),
                            "probeable", false));
        }
        // 按 connectorId 路由（P5 W19 多连接器）：未实现 SPI 的连接器不可探测（503 E-5001，不冒充已验证）
        ErpConnector connector = router.find(definition.connectorId())
                .orElseThrow(() -> new ConnectorException(com.property.ai.bff.dto.ErrorCode.E_5001,
                        "连接器未接入实现（connectorId=" + definition.connectorId() + "），无法探测能力矩阵",
                        false, Map.of("connectorId", definition.connectorId(), "probeable", false)));
        String startedAt = ConnectorTimes.nowIso();
        List<CapabilityState> raw = connector.probeCapabilities(properties.getProbeScenario(), traceId);
        List<CapabilityState> states = normalize(definition, raw);
        ProbeSummary counts = countStates(states);
        int drift = latestReport(definition.connectorId())
                .map(previous -> drift(previous.states(), states))
                .orElse(0);
        ProbeReport report = new ProbeReport(
                String.format("PROBE-%s-%s-%03d", definition.shortCode(), ConnectorTimes.todayCompact(),
                        sequence.incrementAndGet()),
                definition.connectorId(), startedAt, ConnectorTimes.nowIso(), states,
                new ProbeSummary(counts.supported(), counts.degraded(), counts.unsupported(), drift));
        store(definition.connectorId(), report);
        log.info("[probe] {} report={} supported/degraded/unsupported/drift={}/{}/{}/{}", definition.connectorId(),
                report.probeId(), report.summary().supported(), report.summary().degraded(),
                report.summary().unsupported(), report.summary().drift());
        return report;
    }

    /** 最近一次探测 + 近 7 日历史（无则 latest=null、history=[]，不造数） */
    public ProbeLatestResult latest(String connectorId) {
        ConnectorDefinition definition = requireDefinition(connectorId);
        List<ProbeReport> retained = retained(definition.connectorId());
        ProbeReport latest = retained.isEmpty() ? null : retained.get(0);
        List<ProbeReport> history = retained.stream()
                .limit(HISTORY_LIMIT)
                .sorted(Comparator.comparing(ProbeReport::startedAt).thenComparing(ProbeReport::probeId))
                .toList();
        return new ProbeLatestResult(latest, history);
    }

    /** 探测日报分页（按 startedAt 倒序） */
    public ProbeListResult list(String connectorId, int page, int pageSize) {
        ConnectorDefinition definition = requireDefinition(connectorId);
        List<ProbeReport> retained = retained(definition.connectorId());
        int total = retained.size();
        int from = Math.min(Math.max(0, (page - 1) * pageSize), total);
        int to = Math.min(from + pageSize, total);
        return new ProbeListResult(List.copyOf(retained.subList(from, to)), page, pageSize, total);
    }

    // ------------------------------------------------------------------
    // 能力矩阵 / 连接器摘要（能力事实源）
    // ------------------------------------------------------------------

    /** 连接器详情里的 18 项能力：优先最近一次探测，未探测返回注册声明（标注未探测） */
    public List<CapabilityState> capabilityStates(String connectorId) {
        ConnectorDefinition definition = requireDefinition(connectorId);
        return latestReport(definition.connectorId())
                .map(ProbeReport::states)
                .orElseGet(definition::declaredCapabilityStates);
    }

    /** 连接器摘要：能力计数取最近一次探测，未探测取注册声明；健康度取连接器运行时 */
    public ConnectorSummary summary(ConnectorDefinition definition) {
        Optional<ProbeReport> latest = latestReport(definition.connectorId());
        int supported = latest.map(report -> report.summary().supported())
                .orElseGet(() -> definition.declaredCount(CapabilityStatus.SUPPORTED));
        int degraded = latest.map(report -> report.summary().degraded())
                .orElseGet(() -> definition.declaredCount(CapabilityStatus.DEGRADED));
        int unsupported = latest.map(report -> report.summary().unsupported())
                .orElseGet(() -> definition.declaredCount(CapabilityStatus.UNSUPPORTED));
        String lastProbeAt = latest.map(ProbeReport::finishedAt).orElse(null);
        return new ConnectorSummary(definition.connectorId(), definition.name(), definition.vendor(),
                definition.vendorVersion(), definition.status(), healthOf(definition), definition.mock(),
                CapabilityCode.values().length, supported, degraded, unsupported, lastProbeAt,
                definition.mappingVersion(), lastProbeAt == null ? definition.updatedAt() : lastProbeAt);
    }

    /** 注册表全量摘要（{@code GET /api/v1/connectors}） */
    public List<ConnectorSummary> summaries() {
        List<ConnectorSummary> rows = new ArrayList<>();
        for (ConnectorDefinition definition : registry.all()) {
            rows.add(summary(definition));
        }
        return List.copyOf(rows);
    }

    /** 最近一次探测（无/超出保留期返回 empty） */
    public Optional<ProbeReport> latestReport(String connectorId) {
        List<ProbeReport> retained = retained(connectorId);
        return retained.isEmpty() ? Optional.empty() : Optional.of(retained.get(0));
    }

    /** 清空探测日报（仅测试/演示重置用；P5 迁库后由保留策略替代） */
    public void clearReports() {
        reports.clear();
        sequence.set(0);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private ConnectorDefinition requireDefinition(String connectorId) {
        return registry.find(connectorId).orElseThrow(() -> DomainException.notFound(
                com.property.ai.bff.dto.ErrorCode.E_3001, "连接器未注册：" + connectorId,
                Map.of("connectorId", connectorId == null ? "" : connectorId)));
    }

    /**
     * 健康度：按 connectorId 取连接器自身的健康快照（P5 W19 多连接器）。
     *
     * <p>默认连接器（NC）由 {@code ConnectorRuntime} 周期探测驱动；金蝶连接器无独立轮询，
     * 取最近一次出站调用结果（未调用 = unknown）。未接入实现 → unknown（不造数）。
     */
    private ConnectorHealth healthOf(ConnectorDefinition definition) {
        if (!definition.probeable()) {
            return ConnectorHealth.UNKNOWN;
        }
        Optional<com.property.ai.connector.ConnectorHealth> snapshot = router.find(definition.connectorId())
                .map(ErpConnector::health);
        if (snapshot.isEmpty()) {
            return ConnectorHealth.UNKNOWN;
        }
        return switch (snapshot.get().state()) {
            case UP -> ConnectorHealth.HEALTHY;
            case DOWN -> ConnectorHealth.ERROR;
            case UNKNOWN -> ConnectorHealth.UNKNOWN;
        };
    }

    /** 归一：18 项齐全 + 固定顺序 + 缺失项补目录默认值；条数不足 → E-5002（映射失败） */
    private List<CapabilityState> normalize(ConnectorDefinition definition, List<CapabilityState> raw) {
        if (raw == null || raw.isEmpty()) {
            throw ErpErrorMapper.mapping(Capability.ROOM_QUERY, "能力探测无返回（连接器未声明能力）");
        }
        Map<CapabilityCode, CapabilityState> byCode = new EnumMap<>(CapabilityCode.class);
        for (CapabilityState state : raw) {
            if (state != null && state.code() != null) {
                byCode.put(state.code(), state);
            }
        }
        if (byCode.size() != CapabilityCode.values().length) {
            throw ErpErrorMapper.mapping(Capability.ROOM_QUERY,
                    "能力探测返回条数应为 18，实际 " + byCode.size() + "（探测结果不完整，不更新矩阵）");
        }
        String checkedAt = ConnectorTimes.nowIso();
        List<CapabilityState> ordered = new ArrayList<>();
        for (CapabilityCode code : CapabilityCode.values()) {
            CapabilityState state = byCode.get(code);
            CapabilityCatalog.Entry entry = CapabilityCatalog.entry(code);
            if (state.state() == null) {
                throw ErpErrorMapper.mapping(Capability.ROOM_QUERY, "能力探测缺少三态：" + code.value());
            }
            String fallback = state.fallback() == null && state.state() != CapabilityStatus.SUPPORTED
                    ? entry.fallback()
                    : state.fallback();
            ordered.add(new CapabilityState(
                    state.capabilityCode() == null ? entry.designCode() : state.capabilityCode(),
                    code,
                    state.name() == null ? entry.name() : state.name(),
                    state.level() == null ? entry.level() : state.level(),
                    state.state(),
                    state.checkedAt() == null ? checkedAt : state.checkedAt(),
                    state.evidence(),
                    fallback));
        }
        log.debug("[probe] {} 能力矩阵归一完成（18 项）", definition.connectorId());
        return List.copyOf(ordered);
    }

    private static ProbeSummary countStates(List<CapabilityState> states) {
        int supported = 0;
        int degraded = 0;
        int unsupported = 0;
        for (CapabilityState state : states) {
            switch (state.state()) {
                case SUPPORTED -> supported++;
                case DEGRADED -> degraded++;
                case UNSUPPORTED -> unsupported++;
            }
        }
        return new ProbeSummary(supported, degraded, unsupported, 0);
    }

    private static int drift(List<CapabilityState> previous, List<CapabilityState> current) {
        Map<CapabilityCode, CapabilityStatus> before = new EnumMap<>(CapabilityCode.class);
        if (previous != null) {
            for (CapabilityState state : previous) {
                if (state != null && state.code() != null && state.state() != null) {
                    before.put(state.code(), state.state());
                }
            }
        }
        int changed = 0;
        for (CapabilityState state : current) {
            CapabilityStatus old = before.get(state.code());
            if (old != null && old != state.state()) {
                changed++;
            }
        }
        return changed;
    }

    private void store(String connectorId, ProbeReport report) {
        List<ProbeReport> list = reports.computeIfAbsent(connectorId, key -> new CopyOnWriteArrayList<>());
        list.add(0, report);
        OffsetDateTime threshold = OffsetDateTime.now(ConnectorTimes.BUSINESS_ZONE).minus(RETENTION);
        list.removeIf(item -> {
            OffsetDateTime startedAt = ConnectorTimes.parseIsoOrNull(item.startedAt());
            return startedAt != null && startedAt.isBefore(threshold);
        });
        while (list.size() > MAX_REPORTS_PER_CONNECTOR) {
            list.remove(list.size() - 1);
        }
    }

    /** 保留期内的日报（新→旧） */
    private List<ProbeReport> retained(String connectorId) {
        List<ProbeReport> list = reports.get(connectorId);
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        OffsetDateTime threshold = OffsetDateTime.now(ConnectorTimes.BUSINESS_ZONE).minus(RETENTION);
        List<ProbeReport> rows = new ArrayList<>();
        for (ProbeReport report : list) {
            OffsetDateTime startedAt = ConnectorTimes.parseIsoOrNull(report.startedAt());
            if (startedAt == null || !startedAt.isBefore(threshold)) {
                rows.add(report);
            }
        }
        return List.copyOf(rows);
    }
}
