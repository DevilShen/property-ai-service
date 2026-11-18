package com.property.ai.domain.knowledge.importer;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

import com.property.ai.bff.dto.KnowledgeImportJobStatus;
import com.property.ai.bff.dto.KnowledgeImportSourceType;
import com.property.ai.domain.ticket.TicketTimes;

/**
 * 导入任务仓储（task-86，内存 demo，<b>进程内不落库</b>；PG 表见设计 §5.4）。
 *
 * <p>存储口径（demo，不得说成生产级）：
 * <ul>
 *   <li>进程内 {@link ConcurrentMap}，重启清空；P5 迁 PostgreSQL（{@code knowledge_import_jobs}），
 *       接口不变；</li>
 *   <li>任务 ID {@code IMP-yyyyMMdd-NNNN}（业务时区每天从 0001 递增），稳定主键，可回溯；</li>
 *   <li>可选 {{@code Idempotency-Key}} 幂等登记（同键同载荷返回首次结果；同键不同载荷 409 E-3003）；</li>
 *   <li><b>原文不清理</b>：任务/文档记录不做自动删除（用户裁决，契约不设 retention 字段）；
 *       仅测试用 {@link #clearForTest()} 重置。</li>
 * </ul>
 */
@Component
public class KnowledgeImportRepository {

    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final ConcurrentMap<String, KnowledgeImportJobRecord> jobs = new ConcurrentHashMap<>();
    /** 幂等键 → 首次结果登记（创建：jobId；提交：KnowledgeCommitResult 快照） */
    private final ConcurrentMap<String, IdempotencyEntry> idempotency = new ConcurrentHashMap<>();
    private final AtomicInteger sequence = new AtomicInteger();
    private final AtomicReference<String> sequenceDay = new AtomicReference<>("");
    /** 导入生成的知识 ID 序列（{@code K-IMP-NNNN}）：进程内唯一，不与 mock 的 {@code K-0xx} 冲突 */
    private final AtomicInteger knowledgeSequence = new AtomicInteger();
    private Clock clock = Clock.system(TicketTimes.BUSINESS_ZONE);

    /**
     * 幂等登记条目。
     *
     * @param kind        操作类型（{@code create} / {@code commit}），用于区分同名键的不同语义
     * @param fingerprint 载荷指纹（SHA-256）
     * @param jobId       首次结果对应的任务 ID
     * @param result      首次结果快照（create 为 null，commit 为 {@code List<KnowledgeCommitItem>}）
     */
    public record IdempotencyEntry(String kind, String fingerprint, String jobId, Object result) {
    }

    /** 测试注入固定时钟（任务编号/时间断言用；生产不调用） */
    void setClockForTest(Clock clock) {
        this.clock = clock == null ? Clock.system(TicketTimes.BUSINESS_ZONE) : clock;
    }

    public String nextJobId() {
        Instant now = clock.instant();
        String day = DAY_FORMAT.format(now.atZone(TicketTimes.BUSINESS_ZONE));
        synchronized (sequence) {
            if (!day.equals(sequenceDay.get())) {
                sequenceDay.set(day);
                sequence.set(0);
            }
            return "IMP-" + day + "-" + String.format("%04d", sequence.incrementAndGet());
        }
    }

    public void save(KnowledgeImportJobRecord job) {
        jobs.put(job.jobId(), job);
    }

    /** 导入生成的知识 ID（{@code K-IMP-NNNN}，稳定主键，可回溯到导入任务；进程内唯一）。 */
    public String nextKnowledgeId() {
        return "K-IMP-" + String.format("%04d", knowledgeSequence.incrementAndGet());
    }

    public Optional<KnowledgeImportJobRecord> find(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(jobs.get(jobId.trim()));
    }

    /** 全部任务（按 createdAt 倒序、jobId 倒序；列表服务再做项目过滤/分页）。 */
    public List<KnowledgeImportJobRecord> all() {
        List<KnowledgeImportJobRecord> records = new ArrayList<>(jobs.values());
        records.sort(Comparator.comparing(KnowledgeImportJobRecord::createdAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(KnowledgeImportJobRecord::jobId, Comparator.reverseOrder()));
        return List.copyOf(records);
    }

    /** 某账号的在途任务数（并发上限口径：pending/parsing/committing/indexing）。 */
    public int inFlightCount(String actorId) {
        if (actorId == null || actorId.isBlank()) {
            return 0;
        }
        int count = 0;
        for (KnowledgeImportJobRecord job : jobs.values()) {
            if (actorId.equals(job.createdBy()) && job.inFlight()) {
                count++;
            }
        }
        return count;
    }

    /** 删除任务（仅用于「编排层不可用时不留半成品」回滚；任务一旦预览成功即长期保留）。 */
    public void remove(String jobId) {
        if (jobId != null) {
            jobs.remove(jobId);
        }
    }

    // ------------------------------------------------------------------
    // 幂等
    // ------------------------------------------------------------------

    public Optional<IdempotencyEntry> findIdempotency(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(idempotency.get(key.trim()));
    }

    public void saveIdempotency(String key, IdempotencyEntry entry) {
        if (key == null || key.isBlank() || entry == null) {
            return;
        }
        idempotency.put(key.trim(), entry);
    }

    /** 按项目取任务数（诊断/测试用）。 */
    public int countByProject(String projectId) {
        return (int) jobs.values().stream().filter(job -> projectId == null || projectId.equals(job.projectId()))
                .count();
    }

    /** 按状态取任务数（诊断/测试用）。 */
    public long countByStatus(KnowledgeImportJobStatus status) {
        return jobs.values().stream().filter(job -> status == null || job.status() == status).count();
    }

    /**
     * 测试专用：直接改状态（构造并发/超限场景；生产路径一律走
     * {@link KnowledgeImportJobRecord#transitionTo}）。
     */
    public void setStatusForTest(String jobId, KnowledgeImportJobStatus status) {
        find(jobId).ifPresent(job -> job.setStatusDirect(status, TicketTimes.nowIso()));
    }

    /** 演示/测试重置（生产实现不调用；<b>不代表生产可清理原文</b>）。 */
    public void clearForTest() {
        jobs.clear();
        idempotency.clear();
        sequence.set(0);
        sequenceDay.set("");
        knowledgeSequence.set(0);
        clock = Clock.system(TicketTimes.BUSINESS_ZONE);
    }

    /** 任务来源类型（诊断用；避免未使用告警）。 */
    public long countBySource(KnowledgeImportSourceType sourceType) {
        return jobs.values().stream().filter(job -> sourceType == null || job.sourceType() == sourceType).count();
    }
}
