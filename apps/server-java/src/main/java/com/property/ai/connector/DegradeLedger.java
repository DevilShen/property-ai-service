package com.property.ai.connector;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

import com.property.ai.bff.dto.CapabilityCode;
import com.property.ai.bff.dto.DegradeLedgerItem;
import com.property.ai.bff.dto.DegradeLedgerResult;
import com.property.ai.bff.dto.DegradedFallback;

/**
 * 降级台账（P2-025，契约 v0.3.2 {@code GET /api/v1/degrade/ledger}）。
 *
 * <p>口径（架构 §8 降级阶梯）：一条台账 = 「连接器 × 能力 × 替代路径」的命中聚合；
 * {@code count/firstHitAt/lastHitAt} 累计，{@code reason} 取最近一次命中的人话原因（含错误码）。
 * {@code fallback} 与信封 {@code DegradedInfo.fallback} 同码（cache/human/partial/alt_connector/mock/none）。
 *
 * <p>边界：台账是<b>平台内可解释性记录</b>，只存「降级事实」，不落业务主数据、不含房号/手机号；
 * P2 为进程内存储（单实例），P5 起迁 Redis/PG 并保留本类接口。
 */
@Component
public class DegradeLedger {

    /** 聚合主键：连接器 × 能力 × 替代路径 */
    private record Key(String connectorId, CapabilityCode capability, DegradedFallback fallback) {
    }

    private static final class Entry {
        private final String id;
        private final Key key;
        private final OffsetDateTime firstHitAt;
        private volatile OffsetDateTime lastHitAt;
        private volatile String reason;
        private volatile int count;

        private Entry(String id, Key key, OffsetDateTime firstHitAt, OffsetDateTime lastHitAt, String reason,
                int count) {
            this.id = id;
            this.key = key;
            this.firstHitAt = firstHitAt;
            this.lastHitAt = lastHitAt;
            this.reason = reason;
            this.count = count;
        }
    }

    private final Map<Key, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicInteger sequence = new AtomicInteger();

    /**
     * 记录一次降级命中（连接器 fallback 发生时调用；线程安全、幂等聚合）。
     *
     * @param connectorId 连接器 ID（如 yonyou-nc-mock）
     * @param capability  能力码（契约 18 项）
     * @param fallback    替代路径（cache/human/partial/alt_connector/mock）
     * @param reason      人话原因（含错误码，不含敏感信息）
     */
    public synchronized void record(String connectorId, CapabilityCode capability, DegradedFallback fallback,
            String reason) {
        if (capability == null) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now(ConnectorTimes.BUSINESS_ZONE).withNano(0);
        DegradedFallback effective = fallback == null ? DegradedFallback.HUMAN : fallback;
        Key key = new Key(connectorId == null || connectorId.isBlank() ? "unknown" : connectorId.trim(), capability,
                effective);
        Entry entry = entries.get(key);
        if (entry == null) {
            entries.put(key, new Entry(
                    String.format("DEGRADE-%s-%04d", shortCode(key.connectorId()), sequence.incrementAndGet()),
                    key, now, now, reason, 1));
            return;
        }
        entry.count++;
        entry.lastHitAt = now;
        if (reason != null && !reason.isBlank()) {
            entry.reason = reason;
        }
    }

    /**
     * 台账分页查询（按 {@code lastHitAt} 倒序）。
     *
     * @param connectorId 连接器过滤（null/blank = 全部）
     * @param from        起始时间（含，按 lastHitAt；null = 不限）
     * @param to          结束时间（含，按 lastHitAt；null = 不限）
     */
    public DegradeLedgerResult query(String connectorId, OffsetDateTime from, OffsetDateTime to, int page,
            int pageSize) {
        String connectorFilter = connectorId == null || connectorId.isBlank() ? null : connectorId.trim();
        List<DegradeLedgerItem> matched = new ArrayList<>();
        for (Entry entry : entries.values()) {
            if (connectorFilter != null && !connectorFilter.equals(entry.key.connectorId())) {
                continue;
            }
            if (from != null && entry.lastHitAt.isBefore(from)) {
                continue;
            }
            if (to != null && entry.lastHitAt.isAfter(to)) {
                continue;
            }
            matched.add(new DegradeLedgerItem(entry.id, entry.key.connectorId(), entry.key.capability(), entry.reason,
                    entry.key.fallback(), entry.count, ConnectorTimes.iso(entry.firstHitAt),
                    ConnectorTimes.iso(entry.lastHitAt)));
        }
        matched.sort(Comparator.comparing(DegradeLedgerItem::lastHitAt).reversed()
                .thenComparing(DegradeLedgerItem::id));
        int total = matched.size();
        int fromIndex = Math.min(Math.max(0, (page - 1) * pageSize), total);
        int toIndex = Math.min(fromIndex + pageSize, total);
        return new DegradeLedgerResult(List.copyOf(matched.subList(fromIndex, toIndex)), page, pageSize, total);
    }

    /** 当前聚合条目数（监控/测试用） */
    public int size() {
        return entries.size();
    }

    /** 清空台账（仅测试/演示重置用） */
    public synchronized void clear() {
        entries.clear();
    }

    /** 台账 id 的短码：用友 NC → NC，其他取连接器 ID 首段大写 */
    private static String shortCode(String connectorId) {
        if (connectorId == null || connectorId.isBlank()) {
            return "ERP";
        }
        if (connectorId.startsWith("yonyou")) {
            return "NC";
        }
        String first = connectorId.split("-")[0];
        return first.isEmpty() ? "ERP" : first.toUpperCase(java.util.Locale.ROOT);
    }
}
