package com.property.ai.connector;

import com.property.ai.bff.dto.BillInfo;
import com.property.ai.bff.dto.BillListResult;
import com.property.ai.bff.dto.BillSource;
import com.property.ai.bff.dto.DataFreshness;
import com.property.ai.bff.dto.RoomInfo;
import com.property.ai.bff.dto.RoomListResult;
import com.property.ai.bff.dto.RoomSource;

/**
 * 只读数据新鲜度（契约 v0.3.2 {@code DataFreshness}）构造与回填。
 *
 * <p>口径（契约 §1.2 + README §2「新鲜度」）：
 * <ul>
 *   <li>命中展示缓存 / {@code source=cache} / 降级 {@code degraded.fallback=cache} 时 freshness <b>必带</b>；</li>
 *   <li>直读 ERP：freshness 可选（本实现一律带上，{@code hit=false}、{@code ttlSeconds=0}、source=条目来源），
 *       便于前端统一展示「数据时间」；</li>
 *   <li>缓存条目：{@code hit=true}、{@code source=cache}、{@code dataAsOf}=缓存写入时间、
 *       {@code ttlSeconds}=剩余 TTL（秒），同时把条目 {@code source} 改写成 {@code cache}（与 freshness.source 同口径）。</li>
 * </ul>
 *
 * <p>缓存只承载 ERP 只读结果的短时快照，不落库、不写 ERP 主数据（架构 §24.5）。
 */
public final class FreshnessSupport {

    /** freshness.source 的缓存取值（与 RoomSource.CACHE / BillSource.CACHE 同码） */
    public static final String SOURCE_CACHE = "cache";

    private FreshnessSupport() {
    }

    /** 直读 ERP 的新鲜度（hit=false、ttlSeconds=0） */
    public static DataFreshness direct(String source, String dataAsOf) {
        String normalized = source == null || source.isBlank() ? "erp" : source;
        return new DataFreshness(normalized, dataAsOf, 0, false);
    }

    /** 缓存命中的新鲜度（hit=true、source=cache） */
    public static DataFreshness cached(String dataAsOf, int ttlSeconds) {
        return new DataFreshness(SOURCE_CACHE, dataAsOf, Math.max(0, ttlSeconds), true);
    }

    /** 直读房档：source 取 ERP 返回的来源（mock/erp） */
    public static RoomInfo roomDirect(RoomInfo base) {
        if (base == null) {
            return null;
        }
        return room(base, direct(base.source() == null ? null : base.source().value(), base.dataAsOf()));
    }

    /** 回填房档 freshness；命中缓存时同时把条目 source 改写成 {@code cache} */
    public static RoomInfo room(RoomInfo base, DataFreshness freshness) {
        if (base == null) {
            return null;
        }
        RoomSource source = freshness != null && freshness.hit() ? RoomSource.CACHE : base.source();
        return new RoomInfo(base.householdId(), base.projectId(), base.roomCode(), base.building(), base.unit(),
                base.room(), base.areaM2(), base.layout(), base.ownerNameMasked(), base.bindState(),
                base.parkingNo(), base.accountStatus(), base.overdueDays(), base.arrearsAmount(),
                base.relations(), base.tags(), source, base.mock(), base.dataAsOf(), base.updatedAt(),
                freshness);
    }

    /** 直读账单：source 取 ERP 返回的来源（mock/erp） */
    public static BillInfo billDirect(BillInfo base) {
        if (base == null) {
            return null;
        }
        return bill(base, direct(base.source() == null ? null : base.source().value(), base.dataAsOf()));
    }

    /** 回填账单 freshness；命中缓存时同时把条目 source 改写成 {@code cache} */
    public static BillInfo bill(BillInfo base, DataFreshness freshness) {
        if (base == null) {
            return null;
        }
        BillSource source = freshness != null && freshness.hit() ? BillSource.CACHE : base.source();
        // P3 W10（契约 v1.1.0）：回填 freshness 时保留 BillInfo 末尾新增的可选字段
        // （overdueDays/arrearsDays/explainRef 原样透传，null 不序列化；W5—W9 形状不破坏）。
        return new BillInfo(base.period(), base.total(), base.due(), base.items(), base.prevTotal(), base.diff(),
                base.cite(), base.diffReason(), base.billNo(), base.householdId(), base.projectId(),
                base.roomCode(), base.paid(), base.outstanding(), base.lateFee(), base.status(), base.dueDate(),
                base.currency(), source, base.dataAsOf(), base.mock(), freshness, base.overdueDays(),
                base.arrearsDays(), base.explainRef());
    }

    /** 缓存条目 → 条目级新鲜度（dataAsOf=缓存写入时间，ttlSeconds=剩余 TTL） */
    public static DataFreshness fromEntry(ReadCache cache, ReadCache.Entry entry) {
        return cached(cache.storedAtIso(entry), cache.remainingTtlSeconds(entry));
    }

    /** 房档列表：直读带 freshness（hit=false，source=条目来源） */
    public static RoomListResult roomListDirect(RoomListResult result) {
        if (result == null) {
            return null;
        }
        return new RoomListResult(result.items().stream().map(FreshnessSupport::roomDirect).toList(),
                result.page(), result.pageSize(), result.total());
    }

    /** 房档列表：缓存命中带 freshness（hit=true，source=cache）并把条目 source 同步改写为 cache */
    public static RoomListResult roomListCached(RoomListResult snapshot, DataFreshness freshness) {
        if (snapshot == null) {
            return null;
        }
        return new RoomListResult(
                snapshot.items().stream().map(item -> room(item, freshness)).toList(),
                snapshot.page(), snapshot.pageSize(), snapshot.total());
    }

    /** 账单列表：直读带 freshness（hit=false，source=条目来源） */
    public static BillListResult billListDirect(BillListResult result) {
        if (result == null) {
            return null;
        }
        return new BillListResult(result.items().stream().map(FreshnessSupport::billDirect).toList(),
                result.page(), result.pageSize(), result.total());
    }

    /** 账单列表：缓存命中带 freshness（hit=true，source=cache）并把条目 source 同步改写为 cache */
    public static BillListResult billListCached(BillListResult snapshot, DataFreshness freshness) {
        if (snapshot == null) {
            return null;
        }
        return new BillListResult(
                snapshot.items().stream().map(item -> bill(item, freshness)).toList(),
                snapshot.page(), snapshot.pageSize(), snapshot.total());
    }
}
