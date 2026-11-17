package com.property.ai.bff.api.room;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.property.ai.bff.api.ApiResponse;
import com.property.ai.bff.api.ticket.ApiSupport;
import com.property.ai.bff.dto.BindState;
import com.property.ai.bff.dto.DataFreshness;
import com.property.ai.bff.dto.DegradedFallback;
import com.property.ai.bff.dto.ErrorCode;
import com.property.ai.bff.dto.RoomInfo;
import com.property.ai.bff.dto.RoomListResult;
import com.property.ai.bff.dto.RoomListSort;
import com.property.ai.connector.Capability;
import com.property.ai.connector.ConnectorException;
import com.property.ai.connector.ConnectorRuntime;
import com.property.ai.connector.ErpConnector;
import com.property.ai.connector.ErpErrorMapper;
import com.property.ai.connector.FreshnessSupport;
import com.property.ai.connector.ReadCache;
import com.property.ai.domain.project.ProjectService;
import com.property.ai.domain.ticket.DomainException;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 房档只读（CAP-01 room.query）。
 *
 * <p>两条路径（契约 v0.3.2）：
 * <ul>
 *   <li>{@code GET /api/v1/rooms/{roomCode}}（W5 形状不变）：只读、不落库（设计 §24.5），不返回他人关系人明细；
 *       房号无法归一 → 400 E-1003；不存在 → 404 E-3001；ERP 超时/错误 → 503 + {@code degraded}
 *       （有 TTL 内缓存时按 {@code fallback=cache} 返回 200 + degraded + {@code freshness.dataAsOf}）；</li>
 *   <li>{@code GET /api/v1/rooms}（v0.3.2 新增，P2-023）：分页列表 + {@code building}/{@code bindState} 过滤 +
 *       {@code sort}，命中展示缓存时条目带 {@code freshness}（source=cache / ttlSeconds / hit=true）。</li>
 * </ul>
 *
 * <p>缓存边界（架构 §24.5）：只缓存 ERP 只读结果短时快照（TTL {@code property.erp.cache-ttl-ms}），
 * 不落库、不写 ERP 主数据；降级发生时同步落「降级台账」（P2-025）。
 */
@RestController
@RequestMapping("/api/v1/rooms")
public class RoomController {

    /** 列表缓存键前缀（含全部查询参数 + 项目作用域，避免不同过滤条件/项目串味） */
    private static final String LIST_CACHE_PREFIX = "rooms:list:";
    /**
     * 项目作用域全量快照回源的 ERP 单页大小（P5 W19 修复 task-64）：
     * 项目过滤在平台侧执行，必须先取回「过滤条件命中的全量条目」才能得到真实 total。ERP 单页上限 500。
     */
    private static final int SNAPSHOT_PAGE_SIZE = 500;
    /** 全量快照回源页数上限（防 ERP 分页无上限拖垮请求；20 × 500 = 10000 条，P5 演示样本 200 户足够） */
    private static final int SNAPSHOT_MAX_PAGES = 20;

    private final ErpConnector connector;
    private final ConnectorRuntime runtime;
    /** 项目隔离（P5-007）：?projectId= 作用域解析 + 快照式项目过滤 + 跨项目 403 */
    private final ProjectService projectService;

    public RoomController(ErpConnector connector, ConnectorRuntime runtime, ProjectService projectService) {
        this.connector = connector;
        this.runtime = runtime;
        this.projectService = projectService;
    }

    // ------------------------------------------------------------------
    // 单条（W5）
    // ------------------------------------------------------------------

    @GetMapping("/{roomCode}")
    public ResponseEntity<ApiResponse<RoomInfo>> query(@PathVariable String roomCode,
            @RequestParam(name = "projectId", required = false) String projectId,
            HttpServletRequest request) {
        String normalized = ApiSupport.requireRoomCode(roomCode);
        String traceId = ApiSupport.traceId(request);
        // P5 W19 项目隔离：缺省 = 当前 actor 项目；跨项目 → 403 E-2002 cross_project（服务端强制）
        String scopeProjectId = projectService.resolveScope(projectId, "room", traceId);
        String cacheKey = "room:" + normalized;
        try {
            RoomInfo info = connector.queryRoom(normalized, traceId);
            projectService.requireResourceProject(scopeProjectId, info.projectId(), "room", normalized, traceId);
            runtime.cache().put(cacheKey, info);
            return ResponseEntity.ok(ApiResponse.ok(info, traceId));
        } catch (ConnectorException ex) {
            if (ex.code() == ErrorCode.E_3001) {
                throw DomainException.notFound(ErrorCode.E_3001, "房号不存在：" + normalized,
                        Map.of("roomCode", normalized));
            }
            if (ErpErrorMapper.retryable(ex.code())) {
                Optional<ReadCache.Entry> cached = runtime.cache().get(cacheKey);
                if (cached.isPresent() && cached.get().value() instanceof RoomInfo info) {
                    projectService.requireResourceProject(scopeProjectId, info.projectId(), "room", normalized,
                            traceId);
                    DataFreshness freshness = FreshnessSupport.fromEntry(runtime.cache(), cached.get());
                    return ResponseEntity.ok(ApiResponse.okDegraded(
                            FreshnessSupport.room(info, freshness),
                            runtime.recordDegrade(Capability.ROOM_QUERY, DegradedFallback.CACHE, ex,
                                    freshness.dataAsOf()),
                            traceId));
                }
            }
            return ResponseEntity.status(ApiSupport.statusFor(ex.code()))
                    .body(ApiResponse.error(ex.code(), "房档查询失败：" + ex.getMessage(), traceId,
                            ex.details(), runtime.recordDegrade(Capability.ROOM_QUERY, DegradedFallback.HUMAN, ex),
                            ex.retryable()));
        }
    }

    // ------------------------------------------------------------------
    // 分页列表（v0.3.2 / P2-023）
    // ------------------------------------------------------------------

    /**
     * 房档分页列表（只读）：{@code building}/{@code bindState} 可组合过滤，{@code sort} 默认 {@code roomCode_asc}。
     *
     * <p>缓存策略：TTL 内命中直接返回缓存快照（items 带 {@code freshness{source=cache,hit=true}}）；
     * 未命中回源 ERP 并写回缓存（items 带定向 freshness，hit=false）；ERP 失败且有 TTL 内缓存 →
     * 200 + {@code degraded{fallback=cache}} + 缓存 freshness；无缓存 → 503 + {@code degraded{fallback=human}}。
     *
     * <p><b>P5 W19 修复（task-64）</b>：项目作用域下先按 {@code building}/{@code bindState}/{@code sort}
     * 取全量快照（逐页回源），在**全量**上按项目过滤 → 计数 → 再按 {@code page/pageSize} 切片，
     * 因此 {@code total} 是「本项目命中总数」，不再等于本页条数；缓存键含项目作用域，不跨项目串味。
     */
    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam(name = "building", required = false) String building,
            @RequestParam(name = "bindState", required = false) String bindState,
            @RequestParam(name = "projectId", required = false) String projectId,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "20") int pageSize,
            @RequestParam(name = "sort", required = false) String sort,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        ApiSupport.requirePageParams(page, pageSize);
        // P5 W19 项目隔离：缺省 = 当前 actor 项目；跨项目 → 403 E-2002 cross_project（服务端强制）
        String scopeProjectId = projectService.resolveScope(projectId, "room", traceId);
        BindState bindStateValue = parseBindState(bindState);
        RoomListSort sortValue = parseSort(sort);
        String buildingFilter = building == null || building.isBlank() ? null : building.trim();
        String cacheKey = LIST_CACHE_PREFIX + (buildingFilter == null ? "" : buildingFilter) + ':'
                + (bindStateValue == null ? "" : bindStateValue.value()) + ':' + page + ':' + pageSize + ':'
                + sortValue.value() + ':' + (scopeProjectId == null ? "" : scopeProjectId);

        ReadCache cache = runtime.cache();
        Optional<ReadCache.Entry> cached = cache.get(cacheKey);
        if (cached.isPresent() && cached.get().value() instanceof RoomListSnapshot snapshot) {
            return ResponseEntity.ok(ApiResponse.ok(
                    FreshnessSupport.roomListCached(snapshot.result(), FreshnessSupport.fromEntry(cache, cached.get())),
                    traceId));
        }
        try {
            RoomListResult result = scopedPage(buildingFilter, bindStateValue, sortValue, page, pageSize,
                    scopeProjectId, traceId);
            cache.put(cacheKey, new RoomListSnapshot(result));
            return ResponseEntity.ok(ApiResponse.ok(FreshnessSupport.roomListDirect(result), traceId));
        } catch (ConnectorException ex) {
            if (ErpErrorMapper.retryable(ex.code())) {
                Optional<ReadCache.Entry> fallback = cache.getStale(cacheKey);
                if (fallback.isPresent() && fallback.get().value() instanceof RoomListSnapshot snapshot) {
                    DataFreshness freshness = FreshnessSupport.fromEntry(cache, fallback.get());
                    return ResponseEntity.ok(ApiResponse.okDegraded(
                            FreshnessSupport.roomListCached(snapshot.result(), freshness),
                            runtime.recordDegrade(Capability.ROOM_QUERY, DegradedFallback.CACHE, ex,
                                    freshness.dataAsOf()),
                            traceId));
                }
            }
            return ResponseEntity.status(ApiSupport.statusFor(ex.code()))
                    .body(ApiResponse.error(ex.code(), "房档列表查询失败：" + ex.getMessage(), traceId,
                            ex.details(), runtime.recordDegrade(Capability.ROOM_QUERY, DegradedFallback.HUMAN, ex),
                            ex.retryable()));
        }
    }

    // ------------------------------------------------------------------
    // 项目作用域分页（P5 W19 修复 task-64：全量过滤 → 计数 → 切片）
    // ------------------------------------------------------------------

    /**
     * 项目作用域分页：平台级（{@code scopeProjectId == null}）直接下推 ERP 分页（ERP {@code total} 即可信总数）；
     * 项目级先取「过滤条件命中的全量快照」，在全量上按项目过滤后计数并切片。
     */
    private RoomListResult scopedPage(String building, BindState bindState, RoomListSort sort, int page, int pageSize,
            String scopeProjectId, String traceId) {
        if (scopeProjectId == null) {
            return connector.listRooms(building, bindState, page, pageSize, sort, traceId);
        }
        List<RoomInfo> allRooms = loadAllRooms(building, bindState, sort, traceId);
        ProjectService.ScopedPage<RoomInfo> scoped = projectService.paginateByProject(allRooms, scopeProjectId,
                RoomInfo::projectId, page, pageSize);
        return new RoomListResult(scoped.items(), scoped.page(), scoped.pageSize(), scoped.total());
    }

    /** 逐页回源取「building/bindState/sort 命中的全量房档」（项目过滤在平台侧，必须先拿全量才能算 total） */
    private List<RoomInfo> loadAllRooms(String building, BindState bindState, RoomListSort sort, String traceId) {
        List<RoomInfo> all = new ArrayList<>();
        for (int pageNo = 1; pageNo <= SNAPSHOT_MAX_PAGES; pageNo++) {
            RoomListResult chunk = connector.listRooms(building, bindState, pageNo, SNAPSHOT_PAGE_SIZE, sort, traceId);
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
    private record RoomListSnapshot(RoomListResult result) {
    }

    // ------------------------------------------------------------------
    // 参数解析（非法 → 400 E-1001，统一错误信封）
    // ------------------------------------------------------------------

    private static BindState parseBindState(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return BindState.fromValue(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw DomainException.badRequest("bindState 取值非法（bound|unbound|pending）",
                    Map.of("field", "bindState", "value", raw));
        }
    }

    private static RoomListSort parseSort(String raw) {
        if (raw == null || raw.isBlank()) {
            return RoomListSort.ROOM_CODE_ASC;
        }
        try {
            return RoomListSort.fromValue(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw DomainException.badRequest("sort 取值非法（roomCode_asc|updatedAt_desc）",
                    Map.of("field", "sort", "value", raw));
        }
    }
}
