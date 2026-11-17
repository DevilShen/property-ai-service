package com.property.ai.domain.fee;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Component;

import com.property.ai.connector.ReadCache;

/**
 * 费项字典读取（P3 W10，{@code /fees/explain} 的公式/分摊口径事实源）。
 *
 * <p>数据来自 ERP 私有协议 {@code /api/v1/meta/fee-items}（≥8 费项：编码/名称/单位/公式/周期/可欠费/知识出处），
 * 与 P2 只读缓存口径一致：<b>cache-first</b>，TTL 取 {@code property.erp.cache-ttl-ms}（默认 300s）。
 * 字典是费用规则元数据（非账单金额），只用于解释文案，不参与金额计算。
 *
 * <p><b>P3-032 口径变更生效点</b>：审核通过的费项口径变更由 {@link FeeCatalogChangeService} 调
 * {@link #applyFormulaOverride(String, String)} 写入本类内存覆盖表；{@link #all(String)}/{@link #find}
 * 返回时按 {@code itemCode} 应用覆盖后的公式（解释文案与 {@code /meta/fee-items} 读取同一事实源即时生效）。
 * 覆盖表只存**已审核通过**的变更（进程内，重启回到 ERP 原口径；P5 迁库后按变更单重放）。
 */
@Component
public class FeeDictionary {

    /** 字典缓存键（字典随 ERP 元数据更新，短 TTL 足够） */
    public static final String CACHE_KEY = "fee:dict:fee-items";

    private final FeeErpClient erpClient;
    private final ReadCache cache;
    /** 已审核通过的口径覆盖：itemCode → 新公式（P3-032；空 = 未发生口径变更） */
    private final ConcurrentMap<String, String> formulaOverrides = new ConcurrentHashMap<>();

    public FeeDictionary(FeeErpClient erpClient, ReadCache cache) {
        this.erpClient = erpClient;
        this.cache = cache;
    }

    /** 全部费项（cache-first；ERP 失败时若有陈旧快照则回落，避免解释整体不可用）。 */
    public List<FeeErpClient.FeeItemDef> all(String traceId) {
        Optional<ReadCache.Entry> cached = cache.get(CACHE_KEY);
        if (cached.isPresent() && cached.get().value() instanceof List<?> snapshot) {
            return withOverrides(asItems(snapshot));
        }
        try {
            List<FeeErpClient.FeeItemDef> fetched = erpClient.feeItems(traceId);
            cache.put(CACHE_KEY, fetched);
            return withOverrides(fetched);
        } catch (RuntimeException ex) {
            Optional<ReadCache.Entry> stale = cache.getStale(CACHE_KEY);
            if (stale.isPresent() && stale.get().value() instanceof List<?> snapshot) {
                return withOverrides(asItems(snapshot));
            }
            throw ex;
        }
    }

    /** ID → 费项定义（未匹配返回空，由解释层决定拒答）。 */
    public Optional<FeeErpClient.FeeItemDef> find(String itemCode, String traceId) {
        if (itemCode == null || itemCode.isBlank()) {
            return Optional.empty();
        }
        String normalized = itemCode.trim().toUpperCase(java.util.Locale.ROOT);
        return all(traceId).stream().filter(item -> normalized.equals(item.itemCode())).findFirst();
    }

    /** 字典编码列表（非法 itemCode → 400 E-1001 的 details.allowed 用）。 */
    public List<String> codes(String traceId) {
        return all(traceId).stream().map(FeeErpClient.FeeItemDef::itemCode).toList();
    }

    /** 字典 ID → 定义（解释组装用，保持 ERP 顺序）。 */
    public Map<String, FeeErpClient.FeeItemDef> byCode(String traceId) {
        Map<String, FeeErpClient.FeeItemDef> map = new LinkedHashMap<>();
        for (FeeErpClient.FeeItemDef item : all(traceId)) {
            map.put(item.itemCode(), item);
        }
        return map;
    }

    // ------------------------------------------------------------------
    // P3-032 口径变更生效（仅审核通过的变更可写入；读取时按 itemCode 应用）
    // ------------------------------------------------------------------

    /**
     * 应用一条**已审核通过**的口径变更（公式覆盖）。
     *
     * <p>只改公式（{@code formula}）字段：编码/名称/单位/知识出处等仍以 ERP 字典为准（不伪造元数据）。
     * 覆盖按 {@code itemCode} 匹配、大小写不敏感；写后对 {@link #all}/{@link #find} 立即生效。
     */
    public void applyFormulaOverride(String itemCode, String formula) {
        String code = normalize(itemCode);
        if (code.isEmpty() || formula == null || formula.isBlank()) {
            return;
        }
        formulaOverrides.put(code, formula.trim());
    }

    /** 当前生效中的口径覆盖（只读快照；诊断/测试断言用） */
    public Map<String, String> formulaOverrides() {
        return Map.copyOf(formulaOverrides);
    }

    /** 清空口径覆盖（演示/测试重置用；生产不调用） */
    public void clearFormulaOverrides() {
        formulaOverrides.clear();
    }

    private List<FeeErpClient.FeeItemDef> withOverrides(List<FeeErpClient.FeeItemDef> items) {
        if (formulaOverrides.isEmpty() || items.isEmpty()) {
            return items;
        }
        List<FeeErpClient.FeeItemDef> result = new ArrayList<>(items.size());
        for (FeeErpClient.FeeItemDef item : items) {
            String override = item == null || item.itemCode() == null ? null : formulaOverrides.get(normalize(item.itemCode()));
            result.add(override == null ? item : withFormula(item, override));
        }
        return List.copyOf(result);
    }

    private static FeeErpClient.FeeItemDef withFormula(FeeErpClient.FeeItemDef item, String formula) {
        return new FeeErpClient.FeeItemDef(item.itemCode(), item.name(), item.unit(), formula, item.periodText(),
                item.arrearsAllowed(), item.knowledgeId(), item.note());
    }

    private static String normalize(String itemCode) {
        return itemCode == null ? "" : itemCode.trim().toUpperCase(Locale.ROOT);
    }

    private static List<FeeErpClient.FeeItemDef> asItems(List<?> snapshot) {
        return snapshot.stream()
                .filter(FeeErpClient.FeeItemDef.class::isInstance)
                .map(FeeErpClient.FeeItemDef.class::cast)
                .toList();
    }
}
