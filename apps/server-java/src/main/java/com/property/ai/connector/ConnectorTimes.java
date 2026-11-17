package com.property.ai.connector;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 连接器/只读查询时间口径（与 {@code recon.ReconTimes} 同源）：统一 Asia/Shanghai（+08:00）+ ISO 8601。
 *
 * <p>只读缓存新鲜度（{@code DataFreshness.dataAsOf}）、探测日报、降级台账都用本类生成时间，
 * 避免同一响应里出现两种时区口径。
 */
public final class ConnectorTimes {

    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final DateTimeFormatter COMPACT_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private ConnectorTimes() {
    }

    /** 当前时间（+08:00，秒级精度） */
    public static String nowIso() {
        return OffsetDateTime.now(BUSINESS_ZONE).withNano(0).format(TIMESTAMP);
    }

    /** 当前账期（YYYY-MM）；账单列表模式默认值 */
    public static String currentPeriod() {
        return YearMonth.now(BUSINESS_ZONE).format(PERIOD);
    }

    /** 日期紧凑格式（yyyyMMdd），用于 probeId / trialRunId */
    public static String todayCompact() {
        return OffsetDateTime.now(BUSINESS_ZONE).format(COMPACT_DATE);
    }

    /**
     * 解析 ISO 8601 时间（带偏移量；纯本地时间按 +08:00 处理）。
     *
     * @return 解析失败返回 {@code null}（由调用方转 400 E-1001）
     */
    public static OffsetDateTime parseIsoOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        try {
            return OffsetDateTime.parse(text);
        } catch (RuntimeException ignored) {
            try {
                return LocalDateTime.parse(text).atZone(BUSINESS_ZONE).toOffsetDateTime();
            } catch (RuntimeException alsoIgnored) {
                return null;
            }
        }
    }

    /** 偏移时间 → ISO 8601 字符串（+08:00） */
    public static String iso(OffsetDateTime time) {
        return time == null ? null : time.withNano(0).format(TIMESTAMP);
    }
}
