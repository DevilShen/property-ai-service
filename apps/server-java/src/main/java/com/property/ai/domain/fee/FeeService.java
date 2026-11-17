package com.property.ai.domain.fee;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.property.ai.bff.dto.ArrearsBucket;
import com.property.ai.bff.dto.ArrearsItem;
import com.property.ai.bff.dto.ArrearsListResult;
import com.property.ai.bff.dto.ArrearsSummary;
import com.property.ai.bff.dto.BillExplanation;
import com.property.ai.bff.dto.DataFreshness;
import com.property.ai.bff.dto.DegradedFallback;
import com.property.ai.bff.dto.DegradedInfo;
import com.property.ai.bff.dto.ErrorCode;
import com.property.ai.connector.Capability;
import com.property.ai.connector.ConnectorException;
import com.property.ai.connector.ConnectorRuntime;
import com.property.ai.connector.ErpErrorMapper;
import com.property.ai.connector.FreshnessSupport;
import com.property.ai.connector.ReadCache;
import com.property.ai.domain.ticket.DomainException;

/**
 * 费用只读领域服务（P3 W10，P3-005/006）。
 *
 * <p><b>欠费台账</b>（{@code GET /api/v1/fees/arrears}）：账期清单 → 各账期 ERP 账单 + 房档 →
 * 本地分层聚合（{@link FeeAggregator}）→ 分页 + 分层统计。金额一律 ERP 口径（汇总求和不算新金额）。
 *
 * <p><b>账单解释</b>（{@code GET /api/v1/fees/explain}）：ERP 账单（金额事实）+ ERP 费项字典（公式口径）+
 * 知识库 mock（出处）→ {@link FeeExplainService} 组装，可追溯性校验失败即拒答（{@code E-6002}）。
 *
 * <p><b>缓存与降级（与 P2 只读端点同口径）</b>：
 * <ul>
 *   <li>列表/解释均 cache-first（TTL {@code property.erp.cache-ttl-ms}，默认 300s），
 *       命中即返回缓存快照（这两个契约 schema 无 freshness 字段，缓存命中不额外标注）；</li>
 *   <li>ERP 超时/上游错误（E-4001/E-4002/E-4003）→ 有陈旧快照时返回 2xx + {@code degraded{fallback=cache,
 *       dataAsOf}}（不误导为实时）；无快照 → 向上抛 {@link ConnectorException}，BFF 返回 503 + degraded；</li>
 *   <li>缓存只承载 ERP 只读结果短时快照，不落库、不写 ERP 主数据（架构 §12.1）。</li>
 * </ul>
 */
@Service
public class FeeService {

    private static final String CACHE_PREFIX = "fee:";
    /** 缓存键里空值占位（避免 null 拼接歧义） */
    private static final String DASH = "-";

    private final FeeErpClient erpClient;
    private final FeeDictionary dictionary;
    private final FeeExplainService explainService;
    private final ReadCache cache;
    private final ConnectorRuntime runtime;

    public FeeService(FeeErpClient erpClient, FeeDictionary dictionary, FeeExplainService explainService,
            ReadCache cache, ConnectorRuntime runtime) {
        this.erpClient = erpClient;
        this.dictionary = dictionary;
        this.explainService = explainService;
        this.cache = cache;
        this.runtime = runtime;
    }

    /** 只读结果 + 可选降级标记（降级不是错误：2xx + degraded）。 */
    public record FeeResult<T>(T value, DegradedInfo degraded) {

        public static <T> FeeResult<T> direct(T value) {
            return new FeeResult<>(value, null);
        }
    }

    // ------------------------------------------------------------------
    // 欠费台账
    // ------------------------------------------------------------------

    /**
     * 欠费台账（分层 + 分页 + 分层统计）。
     *
     * @param building 楼栋筛选（如「1栋」；空 = 不限；steward 片区范围由数据范围叠加，P3 W10 为单项目）
     * @param bucket   逾期分层筛选（{@code null} = 全部分层）
     * @param scope    数据范围（owner 仅本户）
     */
    public FeeResult<ArrearsListResult> arrears(String building, ArrearsBucket bucket, int page, int pageSize,
            FeeScope scope, String traceId) {
        String buildingKey = FeeAggregator.normalizeBuilding(building);
        String key = CACHE_PREFIX + "arrears:" + scope.cacheKey() + ":" + (buildingKey == null ? DASH : buildingKey)
                + ":" + (bucket == null ? DASH : bucket.value()) + ":" + page + ":" + pageSize;
        Optional<ReadCache.Entry> cached = cache.get(key);
        if (cached.isPresent() && cached.get().value() instanceof ArrearsListResult snapshot) {
            return FeeResult.direct(snapshot);
        }
        try {
            ArrearsListResult result = loadArrears(buildingKey, bucket, page, pageSize, scope, traceId);
            cache.put(key, result);
            return FeeResult.direct(result);
        } catch (ConnectorException ex) {
            if (ErpErrorMapper.retryable(ex.code())) {
                Optional<ReadCache.Entry> stale = cache.getStale(key);
                if (stale.isPresent() && stale.get().value() instanceof ArrearsListResult snapshot) {
                    DataFreshness freshness = FreshnessSupport.fromEntry(cache, stale.get());
                    DegradedInfo degraded = runtime.recordDegrade(Capability.BILL_QUERY, DegradedFallback.CACHE,
                            ex, freshness.dataAsOf());
                    return new FeeResult<>(snapshot, degraded);
                }
            }
            throw ex;
        }
    }

    private ArrearsListResult loadArrears(String building, ArrearsBucket bucket, int page, int pageSize,
            FeeScope scope, String traceId) {
        List<FeeErpClient.ErpBill> bills = new ArrayList<>();
        for (String period : erpClient.periods(traceId)) {
            bills.addAll(erpClient.billsOfPeriod(period, traceId));
        }
        Map<String, FeeErpClient.ErpRoom> rooms = roomsByCode(traceId);
        List<ArrearsItem> scopeRows = FeeAggregator.toArrearsItems(bills, rooms, scope, building, null);
        List<ArrearsItem> filtered = bucket == null
                ? scopeRows
                : scopeRows.stream().filter(item -> item.bucket() == bucket).toList();
        ArrearsSummary summary = FeeAggregator.summarize(scopeRows, filtered);
        FeeAggregator.PageSlice<ArrearsItem> slice = FeeAggregator.paginate(filtered, page, pageSize);
        return new ArrearsListResult(slice.items(), page, pageSize, slice.total(), summary);
    }

    // ------------------------------------------------------------------
    // 账单解释
    // ------------------------------------------------------------------

    /**
     * 账单解释（公式 / 分摊明细 / 与上期差异 / 知识引用）。
     *
     * <p>房号/账期不存在 → 404 {@code E-3001}；知识或计费口径缺失 → 404 {@code E-6002}（拒答）。
     */
    public FeeResult<BillExplanation> explain(String roomCode, String period, String itemCode, FeeScope scope,
            String traceId) {
        String key = CACHE_PREFIX + "explain:" + scope.cacheKey() + ":" + roomCode + ":" + period + ":"
                + (itemCode == null || itemCode.isBlank() ? DASH : itemCode.trim().toUpperCase(java.util.Locale.ROOT));
        Optional<ReadCache.Entry> cached = cache.get(key);
        if (cached.isPresent() && cached.get().value() instanceof BillExplanation snapshot) {
            return FeeResult.direct(snapshot);
        }
        try {
            BillExplanation explanation = loadExplanation(roomCode, period, itemCode, traceId);
            cache.put(key, explanation);
            return FeeResult.direct(explanation);
        } catch (ConnectorException ex) {
            if (ErpErrorMapper.retryable(ex.code())) {
                Optional<ReadCache.Entry> stale = cache.getStale(key);
                if (stale.isPresent() && stale.get().value() instanceof BillExplanation snapshot) {
                    DataFreshness freshness = FreshnessSupport.fromEntry(cache, stale.get());
                    DegradedInfo degraded = runtime.recordDegrade(Capability.BILL_QUERY, DegradedFallback.CACHE,
                            ex, freshness.dataAsOf());
                    return new FeeResult<>(snapshot, degraded);
                }
            }
            throw ex;
        }
    }

    private BillExplanation loadExplanation(String roomCode, String period, String itemCode, String traceId) {
        FeeErpClient.ErpBill bill;
        try {
            bill = erpClient.bill(roomCode, period, traceId);
        } catch (ConnectorException ex) {
            if (ex.code() == ErrorCode.E_3001) {
                throw DomainException.notFound(ErrorCode.E_3001,
                        "房号或该账期账单不存在：" + roomCode + " / " + period,
                        Map.of("roomCode", roomCode, "period", period));
            }
            throw ex;
        }
        FeeErpClient.ErpRoom room;
        try {
            room = erpClient.room(roomCode, traceId);
        } catch (ConnectorException ex) {
            if (ex.code() == ErrorCode.E_3001) {
                throw DomainException.notFound(ErrorCode.E_3001, "房号不存在：" + roomCode,
                        Map.of("roomCode", roomCode));
            }
            throw ex;
        }
        List<FeeErpClient.FeeItemDef> dictionaryItems = dictionary.all(traceId);
        return explainService.explain(bill, room, itemCode, dictionaryItems, traceId);
    }

    /** 房号 → 房档（台账业主掩码/楼栋；单次请求只取一次）。 */
    private Map<String, FeeErpClient.ErpRoom> roomsByCode(String traceId) {
        Map<String, FeeErpClient.ErpRoom> map = new LinkedHashMap<>();
        for (FeeErpClient.ErpRoom room : erpClient.rooms(traceId)) {
            if (room.roomCode() != null) {
                map.put(room.roomCode(), room);
            }
        }
        return map;
    }
}
