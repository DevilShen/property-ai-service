package com.property.ai.domain.desk;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.property.ai.bff.api.ticket.ApiSupport;
import com.property.ai.bff.dto.Actor;
import com.property.ai.bff.dto.ActorRole;
import com.property.ai.bff.dto.DeskQaAgentStat;
import com.property.ai.bff.dto.DeskQaSample;
import com.property.ai.bff.dto.DeskQaSampleListResult;
import com.property.ai.bff.dto.DeskQaSampleStatus;
import com.property.ai.bff.dto.DeskQaScoreRequest;
import com.property.ai.bff.dto.DeskQaStats;
import com.property.ai.bff.dto.ErrorCode;
import com.property.ai.domain.ticket.DomainException;
import com.property.ai.domain.ticket.TicketTimes;

/**
 * 坐席质检（P4-013，契约 v1.2.0 {@code /desk/qa/*}、shared {@code DEFAULT_QA_SCORE_RANGE = 0—100}）。
 *
 * <h2>抽样口径（规则抽样，不随机、不编造会话）</h2>
 * <ul>
 *   <li>样本来源 = 坐席会话仓储中<b>已有接管坐席</b>的会话（在务或已释放；等待中会话无坐席 → 不进样本）；</li>
 *   <li>排序 = 超首响 SLA 优先（契约「SLA 超时/投诉升级/低分回访优先」的 SLA 部分），其次按进入队列时间升序；</li>
 *   <li>{@code sampleId = QA-<会话进入队列日期>-<会话序号 4 位>}，进程重启后稳定（无随机 ID）；</li>
 *   <li>{@code pending} 样本不返回 {@code score}（DTO {@code @JsonInclude(NON_NULL)}）。</li>
 * </ul>
 *
 * <h2>评分口径</h2>
 * <ul>
 *   <li>{@code score} 必须是 0—100 整数（越界 → 400 {@code E-1001}）；评分后 {@code status=scored}；</li>
 *   <li>重复评分 → 409 {@code E-3002}（{@code details.reason=already_scored}），不覆盖首次结论；</li>
 *   <li>agent 只能看/评自己的抽样（越权 → 403 {@code E-2001} {@code not_sample_owner}）；
 *       supervisor / it / admin 可评分本项目样本（端点级能力 {@code desk.qa.score}）；</li>
 *   <li>写操作审计 {@code desk.qa.score} 由 {@code AuditFilter} 统一落库（{@code resourceType=conversation}）。</li>
 * </ul>
 *
 * <p><b>demo 口径</b>：质检结论存进程内（重启清空），不是生产质检台账；P5 迁 PostgreSQL。
 */
@Service
public class QaService {

    private static final Logger log = LoggerFactory.getLogger(QaService.class);
    private static final DateTimeFormatter COMPACT_DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 评分区间（shared {@code DEFAULT_QA_SCORE_RANGE}，契约 §7.4） */
    public static final int MIN_SCORE = 0;
    public static final int MAX_SCORE = 100;

    private final DeskConversationRepository repository;
    /** 评分结论（sampleId → 结论；进程内 demo 存储） */
    private final ConcurrentMap<String, ScoreRecord> scores = new ConcurrentHashMap<>();

    public QaService(DeskConversationRepository repository) {
        this.repository = repository;
    }

    // ==================================================================
    // 抽样列表
    // ==================================================================

    /** 质检抽样列表（分页 + 状态过滤；agent 只看自己的样本）。 */
    public DeskQaSampleListResult samples(String statusParam, int page, int pageSize, Actor actor) {
        ApiSupport.requirePageParams(page, pageSize);
        DeskQaSampleStatus statusFilter = parseStatus(statusParam);
        List<DeskQaSample> all = new ArrayList<>();
        for (Candidate candidate : candidates()) {
            DeskQaSample sample = toSample(candidate);
            if (statusFilter != null && sample.status() != statusFilter) {
                continue;
            }
            all.add(sample);
        }
        List<DeskQaSample> scoped = applyAgentScope(all, actor);
        int total = scoped.size();
        int offset = (page - 1) * pageSize;
        List<DeskQaSample> items;
        if (offset >= total) {
            items = List.of();
        } else {
            items = List.copyOf(scoped.subList(offset, Math.min(total, offset + pageSize)));
        }
        return new DeskQaSampleListResult(items, page, pageSize, total);
    }

    // ==================================================================
    // 评分
    // ==================================================================

    /** 质检评分（写操作）：0—100 整数；重复评分 409；agent 只能评自己的样本。 */
    public DeskQaSample score(String sampleId, DeskQaScoreRequest request, Actor actor, String traceId,
            String clientIp) {
        if (sampleId == null || sampleId.isBlank()) {
            throw DomainException.badRequest("sampleId 必填", Map.of("field", "sampleId", "required", true));
        }
        if (request == null) {
            throw DomainException.badRequest("请求体不能为空（score 0—100 必填）",
                    Map.of("field", "score", "required", true, "min", MIN_SCORE, "max", MAX_SCORE));
        }
        int score = request.score();
        if (score < MIN_SCORE || score > MAX_SCORE) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("field", "score");
            details.put("value", score);
            details.put("min", MIN_SCORE);
            details.put("max", MAX_SCORE);
            details.put("reason", "out_of_range");
            throw DomainException.badRequest("score 必须在 0—100 之间", details);
        }
        Candidate candidate = candidates().stream()
                .filter(item -> item.sampleId().equals(sampleId.trim()))
                .findFirst()
                .orElseThrow(() -> DomainException.notFound(ErrorCode.E_3001, "质检抽样不存在：" + sampleId,
                        Map.of("sampleId", sampleId)));
        assertAgentScope(candidate.conversation(), actor);
        ScoreRecord existing = scores.get(candidate.sampleId());
        if (existing != null) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("reason", "already_scored");
            details.put("sampleId", candidate.sampleId());
            details.put("score", existing.score());
            details.put("scoredBy", existing.scoredBy());
            throw DomainException.conflict("该抽样已评分，不能重复评分", details);
        }
        Instant now = repository.clock().instant();
        String scoredBy = actor == null || actor.actorId() == null || actor.actorId().isBlank()
                ? "unknown"
                : actor.actorId();
        scores.put(candidate.sampleId(), new ScoreRecord((double) score, scoredBy, TicketTimes.iso(now),
                request.note()));
        log.info("[desk] qa score sampleId={} score={} scoredBy={} traceId={}", candidate.sampleId(), score,
                scoredBy, traceId);
        return toSample(candidate);
    }

    // ==================================================================
    // 统计
    // ==================================================================

    /** 质检统计（管理视图：supervisor/it/admin；端点级能力 {@code desk.qa.stats}）。 */
    public DeskQaStats stats(Actor actor) {
        List<Candidate> candidates = candidates();
        int scored = 0;
        double scoreSum = 0.0;
        Map<String, int[]> agentBuckets = new LinkedHashMap<>();
        Map<String, Double> agentScoreSum = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            String agentId = candidate.conversation().agentId() == null ? "" : candidate.conversation().agentId();
            agentBuckets.computeIfAbsent(agentId, key -> new int[2])[0]++;
            agentScoreSum.putIfAbsent(agentId, 0.0);
            ScoreRecord record = scores.get(candidate.sampleId());
            if (record != null) {
                scored++;
                scoreSum += record.score();
                agentBuckets.get(agentId)[1]++;
                agentScoreSum.put(agentId, agentScoreSum.get(agentId) + record.score());
            }
        }
        List<DeskQaAgentStat> byAgent = new ArrayList<>();
        agentBuckets.forEach((agentId, bucket) -> {
            double avg = bucket[1] == 0 ? 0.0 : round1(agentScoreSum.get(agentId) / bucket[1]);
            byAgent.add(new DeskQaAgentStat(agentId, bucket[0], avg));
        });
        byAgent.sort(Comparator.comparing(DeskQaAgentStat::agentId));
        double avgScore = scored == 0 ? 0.0 : round1(scoreSum / scored);
        log.debug("[desk] qa stats samples={} scored={} actor={}", candidates.size(), scored,
                actor == null ? "anonymous" : actor.actorId());
        return new DeskQaStats(candidates.size(), scored, avgScore, List.copyOf(byAgent));
    }

    // ==================================================================
    // 内部
    // ==================================================================

    private record ScoreRecord(double score, String scoredBy, String scoredAt, String note) {
    }

    private record Candidate(String sampleId, DeskConversation conversation) {
    }

    /** 抽样候选：已有接管坐席的会话；超 SLA 优先，其次进入队列时间升序。 */
    private List<Candidate> candidates() {
        Instant now = repository.clock().instant();
        List<Candidate> rows = new ArrayList<>();
        List<DeskConversation> conversations = repository.all();
        for (int index = 0; index < conversations.size(); index++) {
            DeskConversation conversation = conversations.get(index);
            if (conversation.agentId() == null || conversation.agentId().isBlank()) {
                continue;
            }
            rows.add(new Candidate(sampleIdOf(conversation, index), conversation));
        }
        rows.sort(Comparator
                .comparing((Candidate candidate) -> candidate.conversation().slaBreached(now) ? 0 : 1)
                .thenComparing(candidate -> candidate.conversation().enqueuedAt())
                .thenComparing(Candidate::sampleId));
        return List.copyOf(rows);
    }

    private static String sampleIdOf(DeskConversation conversation, int index) {
        ZoneId zone = TicketTimes.BUSINESS_ZONE;
        String day = COMPACT_DAY.format(conversation.enqueuedAt().atZone(zone));
        return "QA-" + day + "-" + String.format("%04d", index + 1);
    }

    private DeskQaSample toSample(Candidate candidate) {
        DeskConversation conversation = candidate.conversation();
        ScoreRecord record = scores.get(candidate.sampleId());
        return new DeskQaSample(
                candidate.sampleId(),
                conversation.conversationId(),
                conversation.agentId(),
                record == null ? null : record.score(),
                record == null ? DeskQaSampleStatus.PENDING : DeskQaSampleStatus.SCORED);
    }

    /** agent 只看/评自己的抽样（supervisor/it/admin 不限）。 */
    private static List<DeskQaSample> applyAgentScope(List<DeskQaSample> samples, Actor actor) {
        if (actor == null || actor.role() != ActorRole.AGENT) {
            return samples;
        }
        String actorId = actor.actorId() == null ? "" : actor.actorId();
        List<DeskQaSample> scoped = new ArrayList<>();
        for (DeskQaSample sample : samples) {
            if (actorId.equals(sample.agentId())) {
                scoped.add(sample);
            }
        }
        return scoped;
    }

    private static void assertAgentScope(DeskConversation conversation, Actor actor) {
        if (actor == null || actor.role() != ActorRole.AGENT) {
            return;
        }
        String actorId = actor.actorId() == null ? "" : actor.actorId();
        if (!actorId.equals(conversation.agentId())) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("reason", "not_sample_owner");
            details.put("conversationId", conversation.conversationId());
            details.put("agentId", conversation.agentId() == null ? "" : conversation.agentId());
            throw DomainException.forbidden("只能评分自己的质检抽样", details);
        }
    }

    private static DeskQaSampleStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return DeskQaSampleStatus.fromValue(raw.trim().toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            List<String> allowed = new ArrayList<>();
            for (DeskQaSampleStatus status : DeskQaSampleStatus.values()) {
                allowed.add(status.value());
            }
            throw DomainException.badRequest("status 参数不在契约枚举内：" + raw,
                    Map.of("field", "status", "value", raw, "allowed", allowed));
        }
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    /** 测试/诊断用：已评分数量。 */
    public int scoredCount() {
        return scores.size();
    }

    /** 测试用：清空评分结论。 */
    void resetForTest() {
        scores.clear();
    }

    /** 测试用：按样本 ID 取评分结论（诊断口径）。 */
    Optional<Double> scoreOf(String sampleId) {
        ScoreRecord record = scores.get(sampleId);
        return record == null ? Optional.empty() : Optional.of(record.score());
    }
}
