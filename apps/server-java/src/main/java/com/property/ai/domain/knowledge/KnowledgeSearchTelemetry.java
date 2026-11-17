package com.property.ai.domain.knowledge;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.springframework.stereotype.Component;

import com.property.ai.bff.dto.KnowledgeGap;
import com.property.ai.domain.ticket.TicketTimes;

/**
 * 知识检索采纳/拒答遥测（P4-006，采纳率口径来源）。
 *
 * <h2>为什么需要它</h2>
 * 契约 {@code KnowledgeStats.adoptionRate = adopted / (adopted + rejected)}；P4 阶段没有真实会话/工单
 * 引用链路（前端未接、AI 编排层未接入），因此 <b>Java 侧用「检索引用」估算</b>，并把口径写死在这里：
 * <ul>
 *   <li><b>adopted（被采纳）</b>：一次 {@code GET /knowledge/search} 返回的<b>每个命中项</b>计 1 次引用
 *       （命中项带 {@code cite} 四要素，可直接作为回答依据）——即「引用知识 ID 且未被驳回」的演示近似；</li>
 *   <li><b>rejected（被驳回/拒答）</b>：一次检索<b>无任何命中</b>计 1 次拒答（AI 必须拒答转人工，
 *       不得编造）——即「答案不可用」的演示近似；未把 badcase 归因样本计入，避免把数据资产当会话量；</li>
 *   <li><b>gaps（缺口 TopN）</b>：窗口内拒答检索按<b>检索分词主题</b>聚类，{@code asked=unanswered=被问次数}
 *       （演示估算：命中检索不做主题聚类，故 asked 只统计缺口主题的被问次数）；</li>
 *   <li>分母为 0 → {@code adoptionRate=0}（契约「无引用记 0，不编造」）。</li>
 * </ul>
 *
 * <p><b>存储</b>：进程内环形保留最近 {@value #MAX_ATTEMPTS} 次检索（重启清零，demo 口径）；
 * 接真实会话后由 P4-019/P5 换成持久化聚合，接口/{@code KnowledgeStats} 形状不变。
 */
@Component
public class KnowledgeSearchTelemetry {

    /** 进程内保留的检索次数上限（超出淘汰最旧） */
    public static final int MAX_ATTEMPTS = 500;
    /** 缺口 TopN 上限 */
    public static final int GAP_TOP_N = 5;

    private final Deque<Attempt> attempts = new ConcurrentLinkedDeque<>();

    private Clock clock = Clock.system(TicketTimes.BUSINESS_ZONE);

    /** 记一次检索结果（命中条数 0 = 拒答）。 */
    public void recordSearch(String query, int hitCount) {
        int hits = Math.max(0, hitCount);
        attempts.addFirst(new Attempt(clock.instant(), KnowledgeSupport.normalize(query),
                KnowledgeSupport.topic(query), hits));
        while (attempts.size() > MAX_ATTEMPTS) {
            attempts.pollLast();
        }
    }

    /** 统计窗口汇总（{@code from}/{@code to} 为业务时区日期，含端点）。 */
    public Summary summary(LocalDate from, LocalDate to) {
        ZoneId zone = TicketTimes.BUSINESS_ZONE;
        Instant fromInstant = from.atStartOfDay(zone).toInstant();
        Instant toInstant = to.plusDays(1).atStartOfDay(zone).toInstant();
        int adopted = 0;
        int rejected = 0;
        Map<String, Integer> gapCounts = new LinkedHashMap<>();
        for (Attempt attempt : attempts) {
            Instant at = attempt.at();
            if (at.isBefore(fromInstant) || !at.isBefore(toInstant)) {
                continue;
            }
            if (attempt.hits() > 0) {
                adopted += attempt.hits();
            } else {
                rejected++;
                gapCounts.merge(attempt.topic(), 1, Integer::sum);
            }
        }
        long denominator = (long) adopted + rejected;
        double adoptionRate = denominator == 0 ? 0.0 : round2((double) adopted / denominator);
        List<KnowledgeGap> allGaps = new ArrayList<>();
        gapCounts.forEach((topic, asked) -> allGaps.add(new KnowledgeGap(topic, asked, asked)));
        allGaps.sort(Comparator.comparingInt(KnowledgeGap::unanswered).reversed()
                .thenComparing(KnowledgeGap::topic));
        List<KnowledgeGap> gaps = allGaps.size() > GAP_TOP_N
                ? List.copyOf(allGaps.subList(0, GAP_TOP_N))
                : List.copyOf(allGaps);
        return new Summary(adopted, rejected, adoptionRate, gaps, rejected == 0 && adopted == 0);
    }

    /** 当前保留的检索次数（诊断/测试用）。 */
    public int size() {
        return attempts.size();
    }

    /** 演示/测试重置。 */
    public void resetForTest() {
        attempts.clear();
        clock = Clock.system(TicketTimes.BUSINESS_ZONE);
    }

    /** 测试注入固定时钟。 */
    void setClockForTest(Clock clock) {
        this.clock = clock == null ? Clock.system(TicketTimes.BUSINESS_ZONE) : clock;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /**
     * 检索统计窗口汇总。
     *
     * @param noData 窗口内无检索（前端可显示「暂无引用数据」，不展示编造数值）
     */
    public record Summary(int adopted, int rejected, double adoptionRate, List<KnowledgeGap> gaps,
            boolean noData) {
    }

    /** 单次检索记录（{@code topic} = 分词主题，用于缺口聚类）。 */
    private record Attempt(Instant at, String query, String topic, int hits) {
    }
}
