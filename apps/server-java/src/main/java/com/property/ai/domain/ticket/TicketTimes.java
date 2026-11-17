package com.property.ai.domain.ticket;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 时间口径工具：业务时区 Asia/Shanghai，ISO 8601（{@code 2026-11-14T15:06:00+08:00}），契约 §6。
 *
 * <p>P2 第 1 周统一用字符串存储与下发（ERP 也是字符串）；P5 落 PG 后改为 {@code timestamptz} + 序列化统一。
 */
public final class TicketTimes {

    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private static final DateTimeFormatter ISO_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    /** Instant → 业务时区 ISO 8601（{@code 2026-11-15T16:30:00+08:00}） */
    public static String iso(Instant instant) {
        if (instant == null) {
            return null;
        }
        return ISO_FORMATTER.format(instant.atZone(BUSINESS_ZONE));
    }
    private static final DateTimeFormatter COMPACT_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private TicketTimes() {
    }

    public static String nowIso() {
        return ISO_FORMATTER.format(OffsetDateTime.now(BUSINESS_ZONE));
    }

    public static String compactDate() {
        return COMPACT_DATE.format(OffsetDateTime.now(BUSINESS_ZONE));
    }

    /** 解析 ISO 时间（失败返回 null，不抛异常——用于历史数据的宽松比较） */
    public static Instant parseInstant(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(iso).toInstant();
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
