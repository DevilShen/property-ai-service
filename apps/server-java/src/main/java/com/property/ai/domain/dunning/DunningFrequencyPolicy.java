package com.property.ai.domain.dunning;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;

import com.property.ai.bff.dto.DunningFrequencyCheck;
import com.property.ai.domain.ticket.TicketTimes;

/**
 * 频次红线校验（P3-014，契约 {@code DunningFrequencyCheck} / 产品 FR-BILL-04）。
 *
 * <h2>口径</h2>
 * <ul>
 *   <li><b>频次</b>：单户（按 {@code roomCode} 聚合，<b>跨任务</b>）{@value #WINDOW_DAYS} 天滚动窗口内
 *       成功触达 ≤{@value #LIMIT} 次；{@code used} 只统计 mock 通道<b>已受理</b>的发送
 *       （失败尝试未触达业主，不计入，避免通道故障把额度烧掉）；</li>
 *   <li><b>时间窗</b>：{@code [now - 7d, now]}（左开右闭语义用 {@code isAfter(from) && !isAfter(now)} 表达，
 *       边界点算在窗口内更保守）；</li>
 *   <li><b>22:00—8:00 不外呼</b>：属<b>外呼</b>红线（契约原文「不外呼」）。P3 触达是异步消息
 *       （企微/短信/站内信），<b>无 AI 语音外呼</b>（AGENTS.md NEVER #7），因此消息渠道不受夜间窗口限制；
 *       外呼通道接入时必须以 {@link ChannelMode#VOICE_OUTBOUND} 调用本类（预留能力 + 单测覆盖），
 *       该模式下 22:00—8:00 {@code allowed=false}（{@code send} 409 {@code E-3003}）。</li>
 * </ul>
 *
 * <p>纯函数、无 IO、无 Spring 依赖，可直接单测（时钟由调用方传入 {@link Instant}）。
 */
public final class DunningFrequencyPolicy {

    /** 统计窗口天数（单户每周口径） */
    public static final int WINDOW_DAYS = DunningPolicy.FREQUENCY_WINDOW_DAYS;
    /** 窗口内允许上限 */
    public static final int LIMIT = DunningPolicy.FREQUENCY_LIMIT;
    /** 夜间窗口开始（含）：22:00 */
    public static final int NIGHT_START_HOUR = 22;
    /** 夜间窗口结束（不含）：08:00 */
    public static final int NIGHT_END_HOUR = 8;

    private DunningFrequencyPolicy() {
    }

    /** 触达渠道性质：P3 只有异步消息；{@link #VOICE_OUTBOUND} 为真实外呼预留（P6 门禁）。 */
    public enum ChannelMode {
        /** 异步消息（wecom/sms/inapp）：受频次窗口约束，不受夜间「不外呼」约束。 */
        MESSAGE,
        /** 真实语音外呼：受频次窗口 + 22:00—8:00 夜间红线双重约束。 */
        VOICE_OUTBOUND
    }

    /** 消息渠道校验（P3 全部发送走这里）。 */
    public static DunningFrequencyCheck check(List<Instant> sends, Instant now) {
        return check(sends, now, ChannelMode.MESSAGE);
    }

    /**
     * 频次红线校验。
     *
     * @param sends 该户成功触达时间（可含窗口外历史；本方法自行截窗）
     * @param now   业务「当前时间」
     * @param mode  渠道性质（消息 / 真实外呼）
     */
    public static DunningFrequencyCheck check(List<Instant> sends, Instant now, ChannelMode mode) {
        Instant reference = now == null ? Instant.now() : now;
        int used = countInWindow(sends, reference);
        boolean night = isNightWindow(reference);
        boolean allowed = used < LIMIT && !(mode == ChannelMode.VOICE_OUTBOUND && night);
        return new DunningFrequencyCheck(WINDOW_DAYS, used, LIMIT, allowed);
    }

    /** 真实外呼夜间红线：22:00—8:00 允许外呼？不区分频次（供外呼通道接入时单独判定）。 */
    public static boolean isNightWindow(Instant at) {
        if (at == null) {
            return false;
        }
        int hour = ZonedDateTime.ofInstant(at, TicketTimes.BUSINESS_ZONE).getHour();
        return hour >= NIGHT_START_HOUR || hour < NIGHT_END_HOUR;
    }

    /** 滚动窗口内成功触达次数（{@code [now-7d, now]}，含边界）。 */
    public static int countInWindow(List<Instant> sends, Instant now) {
        if (sends == null || sends.isEmpty()) {
            return 0;
        }
        Instant reference = now == null ? Instant.now() : now;
        Instant windowStart = reference.minus(Duration.ofDays(WINDOW_DAYS));
        int used = 0;
        for (Instant sentAt : sends) {
            if (sentAt == null) {
                continue;
            }
            if (!sentAt.isBefore(windowStart) && !sentAt.isAfter(reference)) {
                used++;
            }
        }
        return used;
    }
}
