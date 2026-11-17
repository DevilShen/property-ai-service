package com.property.ai.domain.complaint;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

import com.property.ai.bff.dto.ComplaintLevel;
import com.property.ai.domain.ticket.TicketTimes;

/**
 * 投诉首响 SLA 口径（P3-021，契约 v1.1.0 {@code Complaint.slaDueAt} / shared {@code COMPLAINT_SLA_MINUTES}）。
 *
 * <p><b>三档 SLA（与契约/ shared 逐值一致，勿按其他任务卡的简写猜）</b>：
 * <pre>
 * L1 一级：30 分钟（30）     —— 人身安全/群体/媒体/诉讼/政府转办，立即接管 + 30 分钟上报
 * L2 二级：24 小时（1440）   —— 同一问题 ≥2 次/费用争议 >1000 元/员工态度，24h 首复 + 48h 方案
 * L3 三级：2 小时（120）     —— 单次、事实清晰，2h 首响 + 48h 闭环
 * </pre>
 * 值来源：{@code docs/openapi/openapi.yaml} v1.1.0 {@code Complaint.slaDueAt} 描述、
 * {@code packages/shared/src/index.ts} {@code COMPLAINT_SLA_MINUTES}（L1=30 / L2=1440 / L3=120）。
 *
 * <p>边界：SLA 只在平台侧内存域计算（不写 ERP）；{@code firstResponseAt} 只在 {@code first_response}
 * 动作后回带，首响前 {@code slaDueAt} 已确定（从 {@code createdAt} 起算）。
 */
public final class ComplaintSla {

    private static final Map<ComplaintLevel, Integer> MINUTES = buildMinutes();

    private ComplaintSla() {
    }

    private static Map<ComplaintLevel, Integer> buildMinutes() {
        EnumMap<ComplaintLevel, Integer> minutes = new EnumMap<>(ComplaintLevel.class);
        minutes.put(ComplaintLevel.L1, 30);
        minutes.put(ComplaintLevel.L2, 1440);
        minutes.put(ComplaintLevel.L3, 120);
        return Collections.unmodifiableMap(minutes);
    }

    /** 首响 SLA 分钟数；{@code null} 按默认级别 L3 处理（契约：level 省略建议 L3）。 */
    public static int minutes(ComplaintLevel level) {
        ComplaintLevel effective = level == null ? ComplaintLevel.L3 : level;
        return MINUTES.get(effective);
    }

    /** 首响截止时间 = {@code createdAt} + SLA 分钟（业务时区口径由调用方格式化）。 */
    public static Instant dueAt(Instant createdAt, ComplaintLevel level) {
        if (createdAt == null) {
            return null;
        }
        return createdAt.plus(minutes(level), ChronoUnit.MINUTES);
    }

    /**
     * 是否首响超 SLA（契约 {@code ComplaintStats.slaBreachCount} 口径）：
     * <ul>
     *   <li>已首响：{@code firstResponseAt} 晚于 {@code slaDueAt} → 超时；</li>
     *   <li>未首响：当前时间已晚于 {@code slaDueAt} → 超时（不论是否已结案，契约原文「未响应且已超期」）。</li>
     * </ul>
     *
     * @param now 判定基准时间（服务层传注入时钟，测试可控）
     */
    public static boolean breached(String firstResponseAt, String slaDueAt, Instant now) {
        Instant due = TicketTimes.parseInstant(slaDueAt);
        if (due == null) {
            return false;
        }
        Instant respondedAt = TicketTimes.parseInstant(firstResponseAt);
        if (respondedAt != null) {
            return respondedAt.isAfter(due);
        }
        return now != null && now.isAfter(due);
    }

    /** 单条投诉首响分钟（已首响才算；未首响返回 {@code null}）。 */
    public static Double firstResponseMinutes(String createdAt, String firstResponseAt) {
        Instant created = TicketTimes.parseInstant(createdAt);
        Instant responded = TicketTimes.parseInstant(firstResponseAt);
        if (created == null || responded == null) {
            return null;
        }
        return Duration.between(created, responded).toSeconds() / 60.0;
    }

    /** 平均值保留 1 位小数（契约示例为整数，四舍五入到 1 位更稳妥；无数据 0）。 */
    public static double averageOf(double sum, int count) {
        if (count <= 0) {
            return 0.0;
        }
        double value = sum / count;
        return Math.round(value * 10.0) / 10.0;
    }
}
