package com.property.ai.domain.knowledge;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Service;

import com.property.ai.bff.dto.KnowledgeItemStatus;
import com.property.ai.bff.dto.KnowledgeOwnerStat;
import com.property.ai.bff.dto.KnowledgeStats;
import com.property.ai.domain.ticket.DomainException;

/**
 * 知识采纳与缺口统计（P4-006，契约 v1.2.0 {@code GET /knowledge/stats}、{@code KnowledgeStats}）。
 *
 * <h2>口径（逐条对齐契约 §1.5 / §7.4，全部可复算）</h2>
 * <ul>
 *   <li>{@code total} / {@code byStatus}：合并视图（仓库基线 + 已批准覆盖版本）的条目数与五状态分布
 *       （缺失状态记 0）；</li>
 *   <li>{@code expiredSoon}：状态 published 且 {@code effectiveTo} 落在 [今天, 今天+30 天] 的条目数
 *       （{@code effectiveTo} 为空 = 长期有效，不计）；</li>
 *   <li>{@code adopted}/{@code rejected}/{@code adoptionRate}：见
 *       {@link KnowledgeSearchTelemetry} —— 检索命中引用计 adopted、无命中拒答计 rejected，
 *       {@code adoptionRate = adopted/(adopted+rejected)}，分母 0 记 0（不编造）；默认统计窗口 = 近 30 天；</li>
 *   <li>{@code gaps}：窗口内拒答检索按分词主题聚类，TopN=5；</li>
 *   <li>{@code owners}：责任人分布（total/published/expired），按 total 倒序、责任人升序，
 *       用于「过期/待审知识追到人」。</li>
 * </ul>
 *
 * <p><b>权限</b>：统计是管理视图，仅 staff/it/admin（owner → 403 {@code E-2001}
 * {@code knowledge.stats}），端点规则登记在 {@code PermissionMatrix}。
 */
@Service
public class KnowledgeStatsService {

    /** 30 天内到期 = expiredSoon 的窗口（契约写死 30 天） */
    static final int EXPIRING_WINDOW_DAYS = 30;
    /** 默认统计窗口（省略 from/to = 近 30 天） */
    static final int DEFAULT_WINDOW_DAYS = 30;

    private final KnowledgeService knowledgeService;
    private final KnowledgeSearchTelemetry telemetry;

    public KnowledgeStatsService(KnowledgeService knowledgeService, KnowledgeSearchTelemetry telemetry) {
        this.knowledgeService = knowledgeService;
        this.telemetry = telemetry;
    }

    /**
     * 知识统计。
     *
     * @param fromParam 统计起点（省略 = {@code to - 30 天}）
     * @param toParam   统计终点（省略 = 今天）
     */
    public KnowledgeStats stats(String fromParam, String toParam) {
        return stats(fromParam, toParam, null);
    }

    /**
     * 知识统计（P5 W22 / P5-023 项目作用域重载）：{@code total}/{@code byStatus}/{@code owners}/
     * {@code expiredSoon} 只统计作用域内可见条目（{@code scopeProjectId == null} = 平台级全量）。
     *
     * <p>检索采纳率/缺口（{@code adopted}/{@code rejected}/{@code gaps}）来自
     * {@link KnowledgeSearchTelemetry} 的进程内检索计数，暂不按项目切分（P5 迁 PG 后随检索日志落
     * {@code project_id}）；已在交付报告列遗留项，不在本任务伪造分项目采纳率。
     */
    public KnowledgeStats stats(String fromParam, String toParam, String scopeProjectId) {
        LocalDate today = knowledgeService.today();
        LocalDate to = toParam == null || toParam.isBlank() ? today : KnowledgeSupport.requireDate("to", toParam);
        LocalDate from = fromParam == null || fromParam.isBlank()
                ? to.minusDays(DEFAULT_WINDOW_DAYS)
                : KnowledgeSupport.requireDate("from", fromParam);
        if (from.isAfter(to)) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("field", "from");
            details.put("reason", "invalid_range");
            details.put("from", from.toString());
            details.put("to", to.toString());
            throw DomainException.badRequest("from 不能晚于 to", details);
        }

        List<KnowledgeService.View> views = knowledgeService.views(scopeProjectId);
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        for (KnowledgeItemStatus status : KnowledgeItemStatus.values()) {
            byStatus.put(status.value(), 0);
        }
        for (KnowledgeService.View view : views) {
            if (view.status() != null) {
                byStatus.merge(view.status().value(), 1, Integer::sum);
            }
        }

        LocalDate expiringUntil = today.plusDays(EXPIRING_WINDOW_DAYS);
        int expiredSoon = 0;
        Map<String, int[]> ownerBuckets = new TreeMap<>();
        for (KnowledgeService.View view : views) {
            if (view.status() == KnowledgeItemStatus.PUBLISHED) {
                LocalDate effectiveTo = KnowledgeSupport.dateOrNull(view.effectiveTo());
                if (effectiveTo != null && !effectiveTo.isBefore(today) && !effectiveTo.isAfter(expiringUntil)) {
                    expiredSoon++;
                }
            }
            if (view.owner() != null && !view.owner().isBlank()) {
                int[] bucket = ownerBuckets.computeIfAbsent(view.owner(), key -> new int[3]);
                bucket[0]++;
                if (view.status() == KnowledgeItemStatus.PUBLISHED) {
                    bucket[1]++;
                } else if (view.status() == KnowledgeItemStatus.EXPIRED) {
                    bucket[2]++;
                }
            }
        }
        List<KnowledgeOwnerStat> owners = new ArrayList<>();
        ownerBuckets.forEach((owner, bucket) -> owners.add(
                new KnowledgeOwnerStat(owner, bucket[0], bucket[1], bucket[2])));
        owners.sort(Comparator.comparingInt(KnowledgeOwnerStat::total).reversed()
                .thenComparing(KnowledgeOwnerStat::owner));

        KnowledgeSearchTelemetry.Summary summary = telemetry.summary(from, to);
        return new KnowledgeStats(views.size(), byStatus, summary.adoptionRate(), summary.adopted(),
                summary.rejected(), summary.gaps(), expiredSoon, List.copyOf(owners));
    }
}
