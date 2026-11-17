package com.property.ai.recon;

import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** 对账时间口径：统一 Asia/Shanghai（+08:00）+ ISO 8601；账期 = {@code YYYY-MM}。 */
final class ReconTimes {

    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyy-MM");

    private ReconTimes() {
    }

    static String nowIso() {
        return OffsetDateTime.now(BUSINESS_ZONE).withNano(0).format(TIMESTAMP);
    }

    /** 当前账期（P2 第 1 周 mock-erp 本期 = 当月；可用 {@code property.recon.period} 覆盖） */
    static String currentPeriod() {
        return YearMonth.now(BUSINESS_ZONE).format(PERIOD);
    }

    /** 报告文件名里的日期（{@code yyyyMMdd}） */
    static String todayCompact() {
        return OffsetDateTime.now(BUSINESS_ZONE).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
    }

    /** 报告文件名里的日期（{@code yyyyMMdd} 之外的 ISO 日期，供 runId/报告命名） */
    static String today() {
        return OffsetDateTime.now(BUSINESS_ZONE).format(DateTimeFormatter.ISO_LOCAL_DATE);
    }
}
