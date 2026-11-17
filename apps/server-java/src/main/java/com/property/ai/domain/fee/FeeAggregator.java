package com.property.ai.domain.fee;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.property.ai.bff.dto.ArrearsBucket;
import com.property.ai.bff.dto.ArrearsBucketStat;
import com.property.ai.bff.dto.ArrearsItem;
import com.property.ai.bff.dto.ArrearsSummary;

/**
 * 欠费台账纯函数聚合（P3 W10，无 IO，可直接单测）。
 *
 * <p>口径（契约 v1.1.0 {@code ArrearsListResult} / {@code ArrearsSummary}）：
 * <ul>
 *   <li>台账行 = {@code outstanding > 0 && overdueDays > 0} 的 ERP 账单行（未逾期不入台账）；</li>
 *   <li>分层由 ERP 字段 {@code arrearsBucket} 给出，缺失时按 {@code overdueDays} 推导
 *       （D1 1—7 / D7 8—15 / D15 16—30 / D30 &gt;30），与 mock-erp 字典同口径；</li>
 *   <li>{@code summary.buckets} 顺序固定 {@code D1 → D7 → D15 → D30}，即使某层为 0 也返回；</li>
 *   <li>{@code rooms} 为<b>去重房号</b>数（一户多账期只算一户）；{@code amount} 为 ERP 未缴金额之和
 *       （汇总不重算单笔金额，架构 §12.1：AI 不自算金额）；</li>
 *   <li>排序：逾期天数降序 → 房号升序 → 账期降序（稳定分页）。</li>
 * </ul>
 */
public final class FeeAggregator {

    /** 分层固定顺序（契约：buckets 顺序不可变） */
    public static final List<ArrearsBucket> BUCKET_ORDER =
            List.of(ArrearsBucket.D1, ArrearsBucket.D7, ArrearsBucket.D15, ArrearsBucket.D30);

    private static final Comparator<ArrearsItem> ORDER = Comparator
            .comparingInt(ArrearsItem::overdueDays).reversed()
            .thenComparing(ArrearsItem::roomCode, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(ArrearsItem::period, Comparator.nullsLast(Comparator.reverseOrder()));

    private FeeAggregator() {
    }

    /** 逾期天数 → 分层（0/负数 = 未逾期，返回 {@code null}）。 */
    public static ArrearsBucket bucketOf(int overdueDays) {
        if (overdueDays <= 0) {
            return null;
        }
        if (overdueDays <= 7) {
            return ArrearsBucket.D1;
        }
        if (overdueDays <= 15) {
            return ArrearsBucket.D7;
        }
        if (overdueDays <= 30) {
            return ArrearsBucket.D15;
        }
        return ArrearsBucket.D30;
    }

    /** 解析 ERP 分层字段（未匹配回退按逾期天数推导）。 */
    public static ArrearsBucket resolveBucket(String raw, Integer overdueDays) {
        if (raw != null && !raw.isBlank()) {
            try {
                return ArrearsBucket.fromValue(raw.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // ERP 给未知 code：回退逾期天数推导（不新造枚举）
            }
        }
        return overdueDays == null ? null : bucketOf(overdueDays);
    }

    /**
     * ERP 账单 + 房档 → 欠费台账行（含数据范围/楼栋/分层过滤）。
     *
     * @param bills        某账期/多账期的 ERP 账单
     * @param roomsByCode  房号 → 房档（业主掩码、楼栋）
     * @param scope        数据范围（owner 仅本户；staff 不限）
     * @param building     楼栋筛选（如「1栋」；空 = 不限）
     * @param bucket       分层筛选（空 = 全部分层）
     */
    public static List<ArrearsItem> toArrearsItems(List<FeeErpClient.ErpBill> bills,
            Map<String, FeeErpClient.ErpRoom> roomsByCode, FeeScope scope, String building, ArrearsBucket bucket) {
        if (bills == null || bills.isEmpty()) {
            return List.of();
        }
        String buildingNo = normalizeBuilding(building);
        String ownerRoom = scope == null ? null : scope.boundRoom().orElse(null);
        List<ArrearsItem> rows = new ArrayList<>();
        for (FeeErpClient.ErpBill bill : bills) {
            if (bill.roomCode() == null || bill.period() == null) {
                continue;
            }
            if (ownerRoom != null && !ownerRoom.equals(bill.roomCode())) {
                continue;
            }
            int overdueDays = bill.overdueDays() == null ? 0 : bill.overdueDays();
            BigDecimal outstanding = bill.outstanding() == null ? BigDecimal.ZERO : bill.outstanding();
            if (overdueDays <= 0 || outstanding.signum() <= 0) {
                continue;
            }
            ArrearsBucket rowBucket = resolveBucket(bill.arrearsBucket(), overdueDays);
            if (rowBucket == null) {
                continue;
            }
            if (bucket != null && bucket != rowBucket) {
                continue;
            }
            if (buildingNo != null && !buildingNo.equals(buildingOf(bill.roomCode(), roomsByCode))) {
                continue;
            }
            FeeErpClient.ErpRoom room = roomsByCode.get(bill.roomCode());
            rows.add(new ArrearsItem(
                    bill.roomCode(),
                    room == null || room.ownerNameMasked() == null ? "**" : room.ownerNameMasked(),
                    bill.period(),
                    bill.total(),
                    outstanding,
                    overdueDays,
                    rowBucket,
                    bill.lateFee() == null ? BigDecimal.ZERO : bill.lateFee(),
                    null));
        }
        rows.sort(ORDER);
        return List.copyOf(rows);
    }

    /**
     * 分层统计（{@code buckets} 顺序固定 D1→D7→D15→D30）。
     *
     * @param rowsForBuckets 分层统计样本（当前数据范围 + 楼栋筛选，<b>不含</b> bucket 筛选）
     * @param rowsForTotals  合计样本（当前全部筛选条件，含 bucket）
     */
    public static ArrearsSummary summarize(List<ArrearsItem> rowsForBuckets, List<ArrearsItem> rowsForTotals) {
        List<ArrearsItem> bucketRows = rowsForBuckets == null ? List.of() : rowsForBuckets;
        List<ArrearsItem> totalRows = rowsForTotals == null ? List.of() : rowsForTotals;
        List<ArrearsBucketStat> buckets = new ArrayList<>();
        for (ArrearsBucket bucket : BUCKET_ORDER) {
            List<ArrearsItem> rows = bucketRows.stream().filter(item -> item.bucket() == bucket).toList();
            buckets.add(new ArrearsBucketStat(bucket, distinctRooms(rows), sumAmount(rows)));
        }
        return new ArrearsSummary(List.copyOf(buckets), distinctRooms(totalRows), sumAmount(totalRows));
    }

    /** 去重房号数（户数口径）。 */
    public static int distinctRooms(List<ArrearsItem> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        Set<String> rooms = new LinkedHashSet<>();
        for (ArrearsItem row : rows) {
            if (row.roomCode() != null) {
                rooms.add(row.roomCode());
            }
        }
        return rooms.size();
    }

    /** 未缴金额合计（ERP 口径求和，不重算单笔金额；两位小数上限，避免浮点尾巴）。 */
    public static BigDecimal sumAmount(List<ArrearsItem> rows) {
        BigDecimal sum = BigDecimal.ZERO;
        if (rows != null) {
            for (ArrearsItem row : rows) {
                if (row.outstanding() != null) {
                    sum = sum.add(row.outstanding());
                }
            }
        }
        return sum.scale() > 2 ? sum.setScale(2, java.math.RoundingMode.HALF_UP) : sum;
    }

    /** 分页（page ≥1 / pageSize 1—100 已由 Controller 校验）。 */
    public static PageSlice<ArrearsItem> paginate(List<ArrearsItem> rows, int page, int pageSize) {
        List<ArrearsItem> safeRows = rows == null ? List.of() : rows;
        int total = safeRows.size();
        int from = Math.min(Math.max(0, (page - 1) * pageSize), total);
        int to = Math.min(from + pageSize, total);
        return new PageSlice<>(List.copyOf(safeRows.subList(from, to)), total);
    }

    /** 分页切片（items + 过滤后总数）。 */
    public record PageSlice<T>(List<T> items, long total) {
    }

    /** 楼栋归一：`1栋` → `1`；空/空白返回 {@code null}。 */
    public static String normalizeBuilding(String building) {
        if (building == null || building.isBlank()) {
            return null;
        }
        String value = building.trim();
        if (value.endsWith("栋")) {
            value = value.substring(0, value.length() - 1);
        }
        return value.isBlank() ? null : value;
    }

    /** 账单房号 → 楼栋号（优先 ERP 房档 building，缺失时取 roomCode 首段）。 */
    private static String buildingOf(String roomCode, Map<String, FeeErpClient.ErpRoom> roomsByCode) {
        FeeErpClient.ErpRoom room = roomsByCode == null ? null : roomsByCode.get(roomCode);
        String raw = room == null ? null : room.building();
        if (raw != null && !raw.isBlank()) {
            return normalizeBuilding(raw);
        }
        if (roomCode == null) {
            return null;
        }
        int index = roomCode.indexOf('-');
        return index > 0 ? roomCode.substring(0, index) : roomCode;
    }
}
