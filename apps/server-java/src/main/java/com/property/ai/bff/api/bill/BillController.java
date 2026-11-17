package com.property.ai.bff.api.bill;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.property.ai.bff.api.ApiResponse;
import com.property.ai.bff.api.ticket.ApiSupport;
import com.property.ai.bff.dto.BillInfo;
import com.property.ai.bff.dto.BillListResult;
import com.property.ai.bff.dto.BillListSort;
import com.property.ai.bff.dto.BillStatus;
import com.property.ai.bff.dto.DataFreshness;
import com.property.ai.bff.dto.DegradedFallback;
import com.property.ai.bff.dto.ErrorCode;
import com.property.ai.connector.Capability;
import com.property.ai.connector.ConnectorException;
import com.property.ai.connector.ConnectorRuntime;
import com.property.ai.connector.ConnectorTimes;
import com.property.ai.connector.ErpConnector;
import com.property.ai.connector.ErpErrorMapper;
import com.property.ai.connector.FreshnessSupport;
import com.property.ai.connector.ReadCache;
import com.property.ai.domain.project.ProjectService;
import com.property.ai.domain.ticket.DomainException;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 账单只读（CAP-03 bill.query）——契约 v0.3.2 双模式。
 *
 * <p><b>单账单模式</b>（提供 {@code roomCode}，W5 形状不变）：{@code period} 必填；
 * {@code total/due/prevTotal/diff/items[].amount} 全部以 ERP 返回为准（AI 不自算金额），
 * 差额解释必须带 {@code cite}（知识条目 + 版本）；该账期无账单 → 404 E-3001（前端展示空态，不造数）；
 * ERP 超时/失败 → 503 + {@code degraded}（有缓存时 200 + {@code fallback=cache} + {@code freshness}）。
 *
 * <p><b>列表模式</b>（省略 {@code roomCode}，v0.3.2 新增）：按 {@code period}（默认当前账期）/
 * {@code status}/{@code building} 过滤并分页，{@code sort} 默认 {@code updatedAt_desc}；账期无数据返回空列表
 * （不是 404）。命中展示缓存时条目带 {@code freshness{source=cache,ttlSeconds,hit}}。
 *
 * <p>缓存边界：只缓存 ERP 只读结果短时快照（TTL {@code property.erp.cache-ttl-ms}），不落库、不写主数据。
 */
@RestController
@RequestMapping("/api/v1/bills")
public class BillController {

    private static final Pattern PERIOD_PATTERN = Pattern.compile("^\\d{4}-\\d{2}$");
    /** 列表缓存键前缀（含全部查询参数 + 项目作用域，避免不同过滤条件/项目串味） */
    private static final String LIST_CACHE_PREFIX = "bills:list:";
    /**
     * 项目作用域全量快照回源的单页大小（P5 W19 修复 task-64）：
     * 项目过滤在平台侧执行，必须先取回「period/status/building 命中的全量账单」才能得到真实 total。
     */
    private static final int SNAPSHOT_PAGE_SIZE = 500;
    /** 全量快照回源页数上限（防分页无上限拖垮请求；20 × 500 = 10000 条，P5 演示样本 200 账单足够） */
    private static final int SNAPSHOT_MAX_PAGES = 20;

    private final ErpConnector connector;
    private final ConnectorRuntime runtime;
    /** 项目隔离（P5-007）：?projectId= 作用域解析 + 快照式项目过滤 + 跨项目 403 */
    private final ProjectService projectService;

    public BillController(ErpConnector connector, ConnectorRuntime runtime, ProjectService projectService) {
        this.connector = connector;
        this.runtime = runtime;
        this.projectService = projectService;
    }

    /**
     * 账单查询：带 {@code roomCode} → W5 单账单；不带 → 分页列表。
     *
     * <p>返回类型按模式不同（契约 {@code oneOf: BillQueryResponse | BillListResponse}），
     * 统一信封字段（ok/data/traceId/degraded）不变。
     */
    @GetMapping
    public ResponseEntity<?> query(
            @RequestParam(name = "roomCode", required = false) String roomCode,
            @RequestParam(name = "period", required = false) String period,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "building", required = false) String building,
            @RequestParam(name = "sort", required = false) String sort,
            @RequestParam(name = "projectId", required = false) String projectId,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "20") int pageSize,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        // P5 W19 项目隔离：缺省 = 当前 actor 项目；跨项目 → 403 E-2002 cross_project（服务端强制）
        String scopeProjectId = projectService.resolveScope(projectId, "bill", traceId);
        if (roomCode != null && !roomCode.isBlank()) {
            return single(roomCode, period, traceId, scopeProjectId);
        }
        return list(period, status, building, sort, page, pageSize, traceId, scopeProjectId);
    }

    // ------------------------------------------------------------------
    // 单账单模式（W5 形状不变）
    // ------------------------------------------------------------------

    private ResponseEntity<ApiResponse<BillInfo>> single(String roomCode, String period, String traceId,
            String scopeProjectId) {
        if (period == null || period.isBlank()) {
            throw DomainException.badRequest("请提供 period（账期 YYYY-MM）", Map.of("field", "period"));
        }
        String normalizedRoom = ApiSupport.requireRoomCode(roomCode);
        String normalizedPeriod = period.trim();
        if (!PERIOD_PATTERN.matcher(normalizedPeriod).matches()) {
            throw DomainException.badRequest("period 必须为 YYYY-MM（如 2026-11）",
                    Map.of("field", "period", "value", normalizedPeriod));
        }
        String cacheKey = "bill:" + normalizedRoom + ":" + normalizedPeriod;
        try {
            BillInfo info = connector.queryBill(normalizedRoom, normalizedPeriod, traceId);
            projectService.requireResourceProject(scopeProjectId, info.projectId(), "bill",
                    normalizedRoom + "/" + normalizedPeriod, traceId);
            runtime.cache().put(cacheKey, info);
            return ResponseEntity.ok(ApiResponse.ok(info, traceId));
        } catch (ConnectorException ex) {
            if (ex.code() == ErrorCode.E_3001) {
                throw DomainException.notFound(ErrorCode.E_3001,
                        "房号或该账期账单不存在：" + normalizedRoom + " / " + normalizedPeriod,
                        Map.of("roomCode", normalizedRoom, "period", normalizedPeriod));
            }
            if (ErpErrorMapper.retryable(ex.code())) {
                Optional<ReadCache.Entry> cached = runtime.cache().get(cacheKey);
                if (cached.isPresent() && cached.get().value() instanceof BillInfo info) {
                    projectService.requireResourceProject(scopeProjectId, info.projectId(), "bill",
                            normalizedRoom + "/" + normalizedPeriod, traceId);
                    DataFreshness freshness = FreshnessSupport.fromEntry(runtime.cache(), cached.get());
                    return ResponseEntity.ok(ApiResponse.okDegraded(
                            FreshnessSupport.bill(info, freshness),
                            runtime.recordDegrade(Capability.BILL_QUERY, DegradedFallback.CACHE, ex,
                                    freshness.dataAsOf()),
                            traceId));
                }
            }
            return ResponseEntity.status(ApiSupport.statusFor(ex.code()))
                    .body(ApiResponse.error(ex.code(), "账单查询失败：" + ex.getMessage(), traceId,
                            ex.details(), runtime.recordDegrade(Capability.BILL_QUERY, DegradedFallback.HUMAN, ex),
                            ex.retryable()));
        }
    }

    // ------------------------------------------------------------------
    // 列表模式（v0.3.2 / P2-023）
    // ------------------------------------------------------------------

    /**
     * 账单分页列表（只读）：{@code period}/{@code status}/{@code building}/{@code sort} 可组合过滤。
     *
     * <p><b>P5 W19 修复（task-64）</b>：项目作用域下先取「period/status/building/sort 命中的全量账单」
     * （逐页回源），在**全量**上按项目过滤 → 计数 → 再按 {@code page/pageSize} 切片，
     * 因此 {@code total} 是「本项目命中总数」，不再等于本页条数；缓存键含项目作用域，不跨项目串味。
     */
    private ResponseEntity<ApiResponse<BillListResult>> list(String period, String status, String building,
            String sort, int page, int pageSize, String traceId, String scopeProjectId) {
        ApiSupport.requirePageParams(page, pageSize);
        String normalizedPeriod = resolvePeriod(period);
        BillStatus statusValue = parseStatus(status);
        BillListSort sortValue = parseSort(sort);
        String buildingFilter = building == null || building.isBlank() ? null : building.trim();
        String cacheKey = LIST_CACHE_PREFIX + normalizedPeriod + ':' + (statusValue == null ? "" : statusValue.value())
                + ':' + (buildingFilter == null ? "" : buildingFilter) + ':' + page + ':' + pageSize + ':'
                + sortValue.value() + ':' + (scopeProjectId == null ? "" : scopeProjectId);

        ReadCache cache = runtime.cache();
        Optional<ReadCache.Entry> cached = cache.get(cacheKey);
        if (cached.isPresent() && cached.get().value() instanceof BillListSnapshot snapshot) {
            return ResponseEntity.ok(ApiResponse.ok(
                    FreshnessSupport.billListCached(snapshot.result(), FreshnessSupport.fromEntry(cache, cached.get())),
                    traceId));
        }
        try {
            BillListResult result = scopedPage(normalizedPeriod, statusValue, buildingFilter, sortValue, page,
                    pageSize, scopeProjectId, traceId);
            cache.put(cacheKey, new BillListSnapshot(result));
            return ResponseEntity.ok(ApiResponse.ok(FreshnessSupport.billListDirect(result), traceId));
        } catch (ConnectorException ex) {
            if (ErpErrorMapper.retryable(ex.code())) {
                Optional<ReadCache.Entry> fallback = cache.getStale(cacheKey);
                if (fallback.isPresent() && fallback.get().value() instanceof BillListSnapshot snapshot) {
                    DataFreshness freshness = FreshnessSupport.fromEntry(cache, fallback.get());
                    return ResponseEntity.ok(ApiResponse.okDegraded(
                            FreshnessSupport.billListCached(snapshot.result(), freshness),
                            runtime.recordDegrade(Capability.BILL_QUERY, DegradedFallback.CACHE, ex,
                                    freshness.dataAsOf()),
                            traceId));
                }
            }
            return ResponseEntity.status(ApiSupport.statusFor(ex.code()))
                    .body(ApiResponse.error(ex.code(), "账单列表查询失败：" + ex.getMessage(), traceId,
                            ex.details(), runtime.recordDegrade(Capability.BILL_QUERY, DegradedFallback.HUMAN, ex),
                            ex.retryable()));
        }
    }

    // ------------------------------------------------------------------
    // 项目作用域分页（P5 W19 修复 task-64：全量过滤 → 计数 → 切片）
    // ------------------------------------------------------------------

    /**
     * 项目作用域分页：平台级（{@code scopeProjectId == null}）直接下推分页（连接器 {@code total} 即可信总数）；
     * 项目级先取「过滤条件命中的全量账单」，在全量上按项目过滤后计数并切片。
     */
    private BillListResult scopedPage(String period, BillStatus status, String building, BillListSort sort, int page,
            int pageSize, String scopeProjectId, String traceId) {
        if (scopeProjectId == null) {
            return connector.listBills(period, status, building, page, pageSize, sort, traceId);
        }
        List<BillInfo> allBills = loadAllBills(period, status, building, sort, traceId);
        ProjectService.ScopedPage<BillInfo> scoped = projectService.paginateByProject(allBills, scopeProjectId,
                BillInfo::projectId, page, pageSize);
        return new BillListResult(scoped.items(), scoped.page(), scoped.pageSize(), scoped.total());
    }

    /** 逐页回源取「period/status/building/sort 命中的全量账单」（项目过滤在平台侧，必须先拿全量才能算 total） */
    private List<BillInfo> loadAllBills(String period, BillStatus status, String building, BillListSort sort,
            String traceId) {
        List<BillInfo> all = new ArrayList<>();
        for (int pageNo = 1; pageNo <= SNAPSHOT_MAX_PAGES; pageNo++) {
            BillListResult chunk = connector.listBills(period, status, building, pageNo, SNAPSHOT_PAGE_SIZE, sort,
                    traceId);
            if (chunk.items().isEmpty()) {
                break;
            }
            all.addAll(chunk.items());
            if (all.size() >= chunk.total() || chunk.items().size() < SNAPSHOT_PAGE_SIZE) {
                break;
            }
        }
        return List.copyOf(all);
    }

    /** 列表缓存值：已按项目作用域过滤并切片的页结果（缓存键含项目作用域与分页参数，命中即同一页） */
    private record BillListSnapshot(BillListResult result) {
    }

    // ------------------------------------------------------------------
    // 参数解析（非法 → 400 E-1001，统一错误信封）
    // ------------------------------------------------------------------

    private static String resolvePeriod(String period) {
        if (period == null || period.isBlank()) {
            return ConnectorTimes.currentPeriod();
        }
        String normalized = period.trim();
        if (!PERIOD_PATTERN.matcher(normalized).matches()) {
            throw DomainException.badRequest("period 必须为 YYYY-MM（如 2026-11）",
                    Map.of("field", "period", "value", normalized));
        }
        return normalized;
    }

    private static BillStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return BillStatus.fromValue(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw DomainException.badRequest("status 取值非法（paid|unpaid|overdue）",
                    Map.of("field", "status", "value", raw));
        }
    }

    private static BillListSort parseSort(String raw) {
        if (raw == null || raw.isBlank()) {
            return BillListSort.UPDATED_AT_DESC;
        }
        try {
            return BillListSort.fromValue(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw DomainException.badRequest("sort 取值非法（total_desc|due_asc|updatedAt_desc）",
                    Map.of("field", "sort", "value", raw));
        }
    }
}
