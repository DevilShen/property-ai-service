package com.property.ai.audit;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.springframework.stereotype.Component;

import com.property.ai.bff.dto.ActorRole;
import com.property.ai.bff.dto.RagMode;
import com.property.ai.domain.ticket.TicketTimes;

/**
 * RAG 问答观测审计（P6 W30 / task-80）：记录 {@code POST /api/v1/rag/query} 的<b>元数据</b>，不落正文。
 *
 * <h2>为什么不写进 {@link AuditService}</h2>
 * 契约 v1.4.0 {@code ragQuery} 明确「<b>只读语义：不写业务主数据、不落写审计</b>」，且 {@code AuditAction}
 * 枚举（v1.4.0 仅末尾追加 {@code knowledge.reindex}）<b>没有</b> {@code rag.query} 取值；{@code bff/dto}
 * 为冻结契约产物（本任务禁止改动）。因此问答观测用独立的内存审计日志承载（同属 {@code audit} 包），
 * 字段口径与任务卡一致：{@code traceId / projectId / queryHash / answerable / cites / mode / latencyMs}，
 * 外加 actor/时间/降级原因；<b>问题原文、答案正文、snippet 一律不落</b>（AGENTS.md NEVER #8）。
 *
 * <h2>与其它审计的关系</h2>
 * <ul>
 *   <li>{@code knowledge.reindex}（写操作）仍走统一 {@code AuditFilter} + {@link AuditService}
 *       （{@code AuditEvent.action=knowledge.reindex}），可经 {@code GET /api/v1/audit/events} 查询；</li>
 *   <li>本日志是 RAG 问答的<b>内部观测/缺口分析</b>数据源（queryHash 可聚合热点问题而不暴露原文），
 *       进程内保留最近 {@value #MAX_ENTRIES} 条（demo 口径，P6 迁日志/ES 后持久化）。</li>
 * </ul>
 */
@Component
public class RagQueryAuditLog {

    /** 内存上限（进程内 demo 口径；超出淘汰最旧） */
    public static final int MAX_ENTRIES = 500;

    private final Deque<Entry> entries = new ConcurrentLinkedDeque<>();
    private Clock clock = Clock.system(TicketTimes.BUSINESS_ZONE);

    /** 落一条问答审计（元数据，不含问题/答案正文）。 */
    public Entry record(Entry entry) {
        if (entry == null) {
            return null;
        }
        Entry stored = entry.at() != null ? entry : entry.withAt(TicketTimes.iso(clock.instant()));
        entries.addFirst(stored);
        while (entries.size() > MAX_ENTRIES) {
            entries.pollLast();
        }
        return stored;
    }

    /** 最近若干条（新 → 旧；{@code limit <= 0} 返回全部快照）。 */
    public List<Entry> recent(int limit) {
        List<Entry> snapshot = new ArrayList<>();
        for (Entry entry : entries) {
            if (limit > 0 && snapshot.size() >= limit) {
                break;
            }
            snapshot.add(entry);
        }
        return List.copyOf(snapshot);
    }

    /** 按 traceId 过滤（回溯用）。 */
    public List<Entry> byTraceId(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return List.of();
        }
        List<Entry> matched = new ArrayList<>();
        for (Entry entry : entries) {
            if (traceId.equals(entry.traceId())) {
                matched.add(entry);
            }
        }
        return List.copyOf(matched);
    }

    /** 当前条数（诊断/测试用）。 */
    public int size() {
        return entries.size();
    }

    /** 清空（测试隔离用；生产不调用）。 */
    public void clear() {
        entries.clear();
    }

    /** 测试可注入固定时钟。 */
    void setClockForTest(Clock clock) {
        this.clock = clock == null ? Clock.system(TicketTimes.BUSINESS_ZONE) : clock;
    }

    /**
     * 一条问答审计（<b>只含元数据</b>）。
     *
     * @param at               记录时间（业务时区 ISO 8601；缺省由服务填充）
     * @param actorId          操作人（演示账号 ID）
     * @param actorRole        演示角色
     * @param projectId        生效项目作用域（{@code null} = 平台级）
     * @param traceId          BFF 链路 traceId（与响应信封同源）
     * @param orchestratorTraceId 编排层返回的 traceId（诊断用，可能为 {@code rag-answer-<hash>}）
     * @param queryHash        问题 hash（SHA-256 前 12 位，与编排层 query_hash 同口径，不落原文）
     * @param answerable       是否可答（false = 拒答转人工）
     * @param cites            来源卡片数（{@code sources.length}，不落 snippet）
     * @param mode             链路模式（generative / retrieval_only / rules）
     * @param latencyMs        BFF 侧端到端耗时（毫秒）
     * @param degradedReason   降级原因（人话；无降级为 null，不含 token/地址/正文）
     */
    public record Entry(String at, String actorId, ActorRole actorRole, String projectId, String traceId,
            String orchestratorTraceId, String queryHash, boolean answerable, int cites, RagMode mode, long latencyMs,
            String degradedReason) {

        /** 补记录时间（不可变 record 的复制构造）。 */
        public Entry withAt(String at) {
            return new Entry(at, actorId, actorRole, projectId, traceId, orchestratorTraceId, queryHash, answerable,
                    cites, mode, latencyMs, degradedReason);
        }
    }
}
