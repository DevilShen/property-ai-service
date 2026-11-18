package com.property.ai.domain.knowledge;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.property.ai.bff.dto.Actor;
import com.property.ai.bff.dto.KnowledgeChange;
import com.property.ai.bff.dto.KnowledgeChangeRequest;
import com.property.ai.bff.dto.KnowledgeChangeStatus;
import com.property.ai.bff.dto.KnowledgeItemStatus;
import com.property.ai.bff.dto.KnowledgeReviewRequest;
import com.property.ai.bff.dto.KnowledgeVersion;
import com.property.ai.bff.dto.ReviewDecision;
import com.property.ai.bff.dto.EventType;
import com.property.ai.domain.event.EventStore;
import com.property.ai.domain.project.ProjectService;
import com.property.ai.domain.ticket.DomainException;
import com.property.ai.domain.ticket.TicketTimes;
import com.property.ai.security.PermissionMatrix;

/**
 * 知识变更审核卡点（P4-005，契约 v1.2.0 {@code POST /knowledge/{id}/change}、
 * {@code POST /knowledge/{id}/review}）。
 *
 * <h2>状态机（单向，不留后门）</h2>
 * <pre>
 * 提交（knowledge.change，staff/it/admin）→ pending_review（**不生效**）
 *   → 审核通过（knowledge.review，仅 it/admin）→ approved：生成新版本并**立即生效**
 *   → 审核驳回（同上，note 必填）          → rejected：变更单关闭，不产生版本
 * 终态（approved/rejected）不可再审 → 409 E-3002（details.reason=invalid_transition）
 * 提交人不得自审（双人复核）        → 403 E-2001（details.reason=self_review_forbidden）
 * 同一知识同时只允许一个待审变更单   → 409 E-3002（details.reason=change_pending）
 * </pre>
 *
 * <h2>生效口径（重要）</h2>
 * 批准后写入<b>进程内覆盖版本</b>（{@link #overrideFor(String)}），
 * <b>不写回</b> {@code mock/data/knowledge.json}（AGENTS.md §5）；
 * {@link KnowledgeService} 的 list/detail/versions/search 读取时优先使用覆盖版本，
 * 因此「审核通过 → 检索/详情立即读新版本」，重启后覆盖丢失（demo 口径，P5 落库时不变接口）。
 *
 * <h2>审计</h2>
 * 写操作审计由统一 {@code AuditFilter} 按 {@code PermissionMatrix} 登记的动作落库：
 * {@code knowledge.change} / {@code knowledge.review}，{@code resourceType=knowledge}、
 * {@code resourceId=knowledgeId}、{@code maskedFields} 只记字段名；本类不重复写审计，避免双写。
 */
@Service
public class KnowledgeChangeService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeChangeService.class);

    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int MAX_CONTENT_LENGTH = 2000;
    private static final int MAX_TITLE_LENGTH = 100;
    private static final int MAX_REASON_LENGTH = 200;
    private static final int MAX_NOTE_LENGTH = 200;

    private final KnowledgeRepository repository;
    /** outbox 事件存储（P5-019；可选注入：保持既有单测构造不破，未注入时只记日志） */
    private EventStore eventStore;
    /**
     * 导入审核联动索引钩子（task-86）：由 {@code domain/knowledge/importer/KnowledgeImportService} 实现。
     * 用 {@link ObjectProvider} 延迟解析，避免「导入服务 → 变更服务 → 导入服务」循环依赖；
     * 未装配（单测/切片）时安全跳过，既有审核行为不变。
     */
    private ObjectProvider<KnowledgeIndexHook> indexHookProvider;

    /** 变更单（changeId → 记录） */
    private final ConcurrentMap<String, ChangeRecord> changes = new ConcurrentHashMap<>();
    /** 已生效覆盖版本（knowledgeId → 最近一次 approved 的新版本） */
    private final ConcurrentMap<String, Override> overrides = new ConcurrentHashMap<>();
    /**
     * 导入来源的待审变更单（changeId = knowledgeId，task-86）：审核结束时需要联动索引/状态，
     * 非导入知识不在集合内（行为完全不变）。
     */
    private final java.util.Set<String> importedChanges = ConcurrentHashMap.newKeySet();

    private final AtomicInteger sequence = new AtomicInteger();
    private final AtomicReference<String> sequenceDay = new AtomicReference<>("");

    private Clock clock = Clock.system(TicketTimes.BUSINESS_ZONE);

    /** 事件存储注入（可选：P5-019 outbox；单测不构造时为 null） */
    @Autowired(required = false)
    public void setEventStore(EventStore eventStore) {
        this.eventStore = eventStore;
    }

    /** 导入审核联动钩子注入（可选：task-86；单测/切片未装配时跳过联动） */
    @Autowired(required = false)
    public void setIndexHookProvider(ObjectProvider<KnowledgeIndexHook> indexHookProvider) {
        this.indexHookProvider = indexHookProvider;
    }

    public KnowledgeChangeService(KnowledgeRepository repository) {
        this.repository = repository;
    }

    /** 测试注入固定时钟（变更单号/审核时间断言用；生产不调用） */
    void setClockForTest(Clock clock) {
        this.clock = clock == null ? Clock.system(TicketTimes.BUSINESS_ZONE) : clock;
    }

    /** 演示/测试重置（生产实现不调用） */
    public void resetForTest() {
        changes.clear();
        overrides.clear();
        importedChanges.clear();
        sequence.set(0);
        sequenceDay.set("");
        clock = Clock.system(TicketTimes.BUSINESS_ZONE);
    }

    // ==================================================================
    // 导入来源知识：登记待审变更单（task-86，契约 v1.5.0 imports ④）
    // ==================================================================

    /**
     * 登记「导入来源知识」的待审变更单：{@code changeId = knowledgeId}。
     *
     * <h2>为什么 changeId 用 knowledgeId</h2>
     * 契约要求导入的待审知识走<b>既有</b> {@code POST /api/v1/knowledge/{knowledgeId}/review} 审核，而该端点
     * 必填 {@code changeId}；导入任务没有独立的「变更单查询端点」（{@code KnowledgeCommitResult} 只回
     * knowledgeId/version/status，DTO 已冻结）。因此导入知识的待审变更单以 knowledgeId 作为变更单号，
     * 审核人可直接用 {@code knowledgeId} 作为 {@code changeId} 审核，无需额外查询端点、不改契约。
     *
     * <p>审核通过后：生成/刷新覆盖版本（版本号沿用导入的 {@code V1.0}，不做次版本 +1——导入知识是首次发布），
     * 并由 {@link KnowledgeIndexHook} 触发 {@code /internal/rag/index} + {@code knowledge.index} 审计。
     */
    public void registerImportedPending(String knowledgeId, String submittedBy, String title, String content,
            String effectiveFrom, String effectiveTo, String reviewNote) {
        String kid = knowledgeId == null ? "" : knowledgeId.trim();
        if (kid.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        ChangeRecord record = new ChangeRecord(
                kid, kid, KnowledgeChangeStatus.PENDING_REVIEW,
                submittedBy == null || submittedBy.isBlank() ? "anonymous" : submittedBy,
                TicketTimes.iso(now), "import", title, content, effectiveFrom, effectiveTo,
                null, null, null, reviewNote);
        changes.put(kid, record);
        importedChanges.add(kid);
    }

    /** 是否为导入来源的待审变更单（诊断/测试用）。 */
    public boolean isImportedChange(String changeId) {
        return changeId != null && importedChanges.contains(changeId.trim());
    }

    // ==================================================================
    // 提交（pending_review，不生效）
    // ==================================================================

    /**
     * 提交知识变更（写操作，进入 {@code pending_review}）。
     *
     * @param knowledgeId 知识 ID（不存在 → 404 {@code E-3001}）
     * @param request     入参（content/effectiveFrom/effectiveTo/reason 必填；title 省略沿用原标题）
     * @param actor       当前演示主体（{@code null} = 离线演示/测试模式，不做能力拦截）
     */
    public KnowledgeChange submit(String knowledgeId, KnowledgeChangeRequest request, Actor actor,
            String traceId, String clientIp) {
        assertCapability(actor, PermissionMatrix.KNOWLEDGE_CHANGE);
        KnowledgeRepository.Entry base = requireKnowledge(knowledgeId);
        if (request == null) {
            throw DomainException.badRequest("请求体必填：KnowledgeChangeRequest（content/effectiveFrom/effectiveTo/reason）",
                    Map.of("body", "required"));
        }
        List<String> missing = new ArrayList<>();
        if (isBlank(request.content())) {
            missing.add("content");
        }
        if (isBlank(request.effectiveFrom())) {
            missing.add("effectiveFrom");
        }
        if (isBlank(request.effectiveTo())) {
            missing.add("effectiveTo");
        }
        if (isBlank(request.reason())) {
            missing.add("reason");
        }
        if (!missing.isEmpty()) {
            throw DomainException.badRequest("知识变更缺少必填字段：" + String.join(",", missing),
                    Map.of("missing", List.copyOf(missing), "required", List.of("content", "effectiveFrom",
                            "effectiveTo", "reason")));
        }
        String content = request.content().trim();
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw DomainException.badRequest("content 长度不能超过 " + MAX_CONTENT_LENGTH + " 字符",
                    Map.of("field", "content", "length", content.length(), "maximum", MAX_CONTENT_LENGTH));
        }
        String title = isBlank(request.title()) ? base.title() : request.title().trim();
        if (title != null && title.length() > MAX_TITLE_LENGTH) {
            throw DomainException.badRequest("title 长度不能超过 " + MAX_TITLE_LENGTH + " 字符",
                    Map.of("field", "title", "length", title.length(), "maximum", MAX_TITLE_LENGTH));
        }
        String reason = request.reason().trim();
        if (reason.length() > MAX_REASON_LENGTH) {
            throw DomainException.badRequest("reason 长度不能超过 " + MAX_REASON_LENGTH + " 字符",
                    Map.of("field", "reason", "length", reason.length(), "maximum", MAX_REASON_LENGTH));
        }
        var effectiveFrom = KnowledgeSupport.requireDate("effectiveFrom", request.effectiveFrom());
        var effectiveTo = KnowledgeSupport.requireDate("effectiveTo", request.effectiveTo());
        if (effectiveFrom.isAfter(effectiveTo)) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("field", "effectiveFrom");
            details.put("reason", "invalid_range");
            details.put("effectiveFrom", effectiveFrom.toString());
            details.put("effectiveTo", effectiveTo.toString());
            throw DomainException.badRequest("effectiveFrom 不能晚于 effectiveTo", details);
        }
        Optional<ChangeRecord> pending = pendingChange(base.knowledgeId());
        if (pending.isPresent()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("reason", "change_pending");
            details.put("knowledgeId", base.knowledgeId());
            details.put("changeId", pending.get().changeId());
            details.put("status", pending.get().status().value());
            throw DomainException.conflict("该知识已有待审核变更单（" + pending.get().changeId() + "），请等待审核完成",
                    details);
        }
        Instant now = clock.instant();
        ChangeRecord record = new ChangeRecord(
                nextChangeId(now), base.knowledgeId(), KnowledgeChangeStatus.PENDING_REVIEW,
                actorIdOf(actor), TicketTimes.iso(now), reason, title, content,
                effectiveFrom.toString(), effectiveTo.toString(),
                null, null, null, null);
        changes.put(record.changeId(), record);
        log.info("[knowledge] change submit ok changeId={} knowledgeId={} status=pending_review by={} traceId={}",
                record.changeId(), record.knowledgeId(), record.submittedBy(), traceId);
        return record.toDto();
    }

    // ==================================================================
    // 审核（仅 it/admin；通过才生版本并生效）
    // ==================================================================

    /**
     * 审核知识变更（{@code knowledge.review}，仅 it/admin，且不得自审）。
     */
    public KnowledgeChange review(String knowledgeId, KnowledgeReviewRequest request, Actor actor,
            String traceId, String clientIp) {
        assertCapability(actor, PermissionMatrix.KNOWLEDGE_REVIEW);
        String kid = knowledgeId == null ? "" : knowledgeId.trim();
        if (request == null) {
            throw DomainException.badRequest("请求体必填：KnowledgeReviewRequest（changeId/decision）",
                    Map.of("body", "required"));
        }
        String changeId = isBlank(request.changeId()) ? "" : request.changeId().trim();
        if (changeId.isEmpty()) {
            throw DomainException.badRequest("changeId 必填", Map.of("field", "changeId", "required", true));
        }
        if (request.decision() == null) {
            throw DomainException.badRequest("decision 必填（approved/rejected）",
                    Map.of("field", "decision", "required", true, "allowed",
                            List.of(ReviewDecision.APPROVED.value(), ReviewDecision.REJECTED.value())));
        }
        ChangeRecord change = changes.get(changeId);
        if (change == null) {
            throw DomainException.notFound(com.property.ai.bff.dto.ErrorCode.E_3001,
                    "知识变更单不存在：" + changeId, Map.of("changeId", changeId, "knowledgeId", kid));
        }
        KnowledgeRepository.Entry base = requireKnowledge(kid);
        if (!change.knowledgeId().equalsIgnoreCase(base.knowledgeId())) {
            throw invalidTransition(change, kid, "变更单不属于该知识");
        }
        if (isTerminal(change.status())) {
            throw invalidTransition(change, kid, "该变更单已处理（" + change.status().value() + "），不可重复审核");
        }
        String reviewer = actorIdOf(actor);
        if (change.submittedBy() != null && !change.submittedBy().isBlank() && change.submittedBy().equals(reviewer)) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("reason", "self_review_forbidden");
            details.put("changeId", change.changeId());
            details.put("knowledgeId", change.knowledgeId());
            details.put("requiredCapability", PermissionMatrix.KNOWLEDGE_REVIEW);
            throw DomainException.forbidden("提交人不得审核自己提交的知识变更（需他人复核）", details);
        }
        String note = isBlank(request.note()) ? "" : request.note().trim();
        if (request.decision() == ReviewDecision.REJECTED && note.isEmpty()) {
            throw DomainException.badRequest("驳回知识变更必须填写审核意见（note）",
                    Map.of("field", "note", "required", true));
        }
        if (note.length() > MAX_NOTE_LENGTH) {
            throw DomainException.badRequest("note 长度不能超过 " + MAX_NOTE_LENGTH + " 字符",
                    Map.of("field", "note", "length", note.length(), "maximum", MAX_NOTE_LENGTH));
        }
        Instant now = clock.instant();
        String reviewedAt = TicketTimes.iso(now);
        boolean approve = request.decision() == ReviewDecision.APPROVED;
        // task-86：导入来源知识是首次发布 → 版本沿用 V1.0（不做次版本 +1）；既有知识变更仍 +1
        boolean imported = importedChanges.contains(change.changeId());
        String newVersion = approve
                ? (imported ? base.version() : bumpVersion(currentVersion(base.knowledgeId(), base.version())))
                : null;
        ChangeRecord updated = change.reviewed(
                approve ? KnowledgeChangeStatus.APPROVED : KnowledgeChangeStatus.REJECTED,
                reviewer, reviewedAt, newVersion, note.isEmpty() ? null : note);
        changes.put(updated.changeId(), updated);
        if (approve) {
            // 卡点通过后**立即生效**：写入内存覆盖版本（不写 mock/data 文件），读接口优先使用
            overrides.put(base.knowledgeId(), new Override(
                    base.knowledgeId(), updated.title(), updated.content(), newVersion,
                    updated.effectiveFrom(), updated.effectiveTo(), updated.changeId(),
                    updated.submittedBy(), updated.submittedAt(), reviewer, reviewedAt,
                    note.isEmpty() ? null : note));
        }
        log.info("[knowledge] review ok changeId={} knowledgeId={} decision={} version={} reviewer={} imported={}"
                + " traceId={}", updated.changeId(), updated.knowledgeId(), updated.status().value(), newVersion,
                reviewer, imported, traceId);
        if (approve) {
            publishChangedEvent(updated, newVersion, actor);
        }
        if (imported) {
            notifyImportedReviewed(base.knowledgeId(), approve, traceId);
        }
        return updated.toDto();
    }

    /**
     * 导入来源知识审核结束：通知导入域联动（approved → 触发编排层索引；rejected → 置 rejected）。
     *
     * <p>失败只告警，<b>不改变审核结果</b>（审核已落库）：索引失败由导入域落 {@code knowledge.index}
     * 审计（result=failed）并保留知识 published，运营可手动重建索引。
     */
    private void notifyImportedReviewed(String knowledgeId, boolean approved, String traceId) {
        ObjectProvider<KnowledgeIndexHook> provider = this.indexHookProvider;
        if (provider == null) {
            log.debug("[knowledge] 导入联动钩子未装配，跳过 knowledgeId={}", knowledgeId);
            return;
        }
        KnowledgeIndexHook hook;
        try {
            hook = provider.getIfAvailable();
        } catch (RuntimeException ex) {
            log.warn("[knowledge] 导入联动钩子解析失败 knowledgeId={} err={}", knowledgeId,
                    ex.getClass().getSimpleName());
            return;
        }
        if (hook == null) {
            log.debug("[knowledge] 导入联动钩子不存在，跳过 knowledgeId={}", knowledgeId);
            return;
        }
        try {
            hook.onReviewed(knowledgeId, approved, traceId);
        } catch (RuntimeException ex) {
            log.warn("[knowledge] 导入联动失败（审核结果不回滚）knowledgeId={} approved={} err={}", knowledgeId,
                    approved, ex.getClass().getSimpleName());
        }
    }

    /**
     * 发 outbox 事件 knowledge.changed（P5-019，仅审核通过生效时）：payload 脱敏——知识 ID/版本/变更单号，
     * <b>不放知识正文</b>（事件总线只作变更通知，正文走知识读接口且受 {@code knowledge.read} 约束）。
     */
    private void publishChangedEvent(ChangeRecord change, String version, Actor actor) {
        if (eventStore == null) {
            log.debug("[knowledge] 事件存储未注入，跳过 knowledge.changed changeId={}", change.changeId());
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("knowledgeId", change.knowledgeId());
        payload.put("changeId", change.changeId());
        payload.put("version", version);
        eventStore.publish(EventType.KNOWLEDGE_CHANGED,
                ProjectService.projectOf(actor == null ? null : actor.projectId()), payload);
    }

    // ==================================================================
    // 查询（读接口用：覆盖版本 / 版本历史）
    // ==================================================================

    /** 某知识最近一次批准生效的覆盖版本（读接口优先使用；无则空）。 */
    public Optional<Override> overrideFor(String knowledgeId) {
        if (knowledgeId == null || knowledgeId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(overrides.get(knowledgeId.trim()));
    }

    /** 某知识已批准的变更版本（按提交时间升序，供 {@code versions[]}）。 */
    public List<KnowledgeVersion> approvedVersions(String knowledgeId) {
        if (knowledgeId == null || knowledgeId.isBlank()) {
            return List.of();
        }
        String kid = knowledgeId.trim();
        List<ChangeRecord> approved = new ArrayList<>();
        for (ChangeRecord change : changes.values()) {
            if (change.status() == KnowledgeChangeStatus.APPROVED && kid.equalsIgnoreCase(change.knowledgeId())) {
                approved.add(change);
            }
        }
        approved.sort(Comparator
                .comparing(ChangeRecord::submittedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ChangeRecord::changeId));
        List<KnowledgeVersion> versions = new ArrayList<>();
        for (ChangeRecord change : approved) {
            versions.add(new KnowledgeVersion(
                    change.knowledgeId(), change.version(), KnowledgeItemStatus.PUBLISHED,
                    change.effectiveFrom(), change.effectiveTo(), change.submittedBy(), change.submittedAt(),
                    change.changeId(), change.reviewNote()));
        }
        return List.copyOf(versions);
    }

    /** 按变更单号查（不存在返回空）。 */
    public Optional<ChangeRecord> find(String changeId) {
        return changeId == null || changeId.isBlank()
                ? Optional.empty()
                : Optional.ofNullable(changes.get(changeId.trim()));
    }

    /** 某知识的待审变更单（同一知识最多一条）。 */
    public Optional<ChangeRecord> pendingChange(String knowledgeId) {
        if (knowledgeId == null || knowledgeId.isBlank()) {
            return Optional.empty();
        }
        String kid = knowledgeId.trim();
        return changes.values().stream()
                .filter(change -> change.status() == KnowledgeChangeStatus.PENDING_REVIEW
                        && kid.equalsIgnoreCase(change.knowledgeId()))
                .findFirst();
    }

    /** 全部变更单（新 → 旧；诊断/测试用）。 */
    public List<ChangeRecord> all() {
        List<ChangeRecord> all = new ArrayList<>(changes.values());
        all.sort(Comparator.comparing(ChangeRecord::submittedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(ChangeRecord::changeId));
        return List.copyOf(all);
    }

    // ==================================================================
    // 内部
    // ==================================================================

    private KnowledgeRepository.Entry requireKnowledge(String knowledgeId) {
        String kid = knowledgeId == null ? "" : knowledgeId.trim();
        if (kid.isEmpty()) {
            throw DomainException.notFound(com.property.ai.bff.dto.ErrorCode.E_3001, "知识不存在",
                    Map.of("knowledgeId", ""));
        }
        return repository.find(kid).orElseThrow(() -> DomainException.notFound(
                com.property.ai.bff.dto.ErrorCode.E_3001, "知识不存在：" + kid, Map.of("knowledgeId", kid)));
    }

    private String currentVersion(String knowledgeId, String baseVersion) {
        return overrideFor(knowledgeId).map(Override::version).orElse(baseVersion);
    }

    /**
     * 版本号递增：{@code V1.0 → V1.1}、{@code V1.1 → V1.2}（次版本 +1，主版本不变）；
     * 不可解析的版本号按 {@code 原值.1} 兜底（防御，正常数据不会走到）。
     */
    static String bumpVersion(String current) {
        if (current == null || current.isBlank()) {
            return "V1.0";
        }
        String text = current.trim();
        if (text.matches("(?i)^v\\d+\\.\\d+$")) {
            int dot = text.indexOf('.');
            int major = Integer.parseInt(text.substring(1, dot));
            int minor = Integer.parseInt(text.substring(dot + 1));
            return "V" + major + "." + (minor + 1);
        }
        return text + ".1";
    }

    private DomainException invalidTransition(ChangeRecord change, String knowledgeId, String message) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", "invalid_transition");
        details.put("changeId", change.changeId());
        details.put("knowledgeId", change.knowledgeId());
        details.put("status", change.status().value());
        details.put("requestedKnowledgeId", knowledgeId);
        details.put("hint", "仅 pending_review 变更单可审核；approved/rejected 为终态，新调整请重新提交变更单");
        return DomainException.conflict(message, details);
    }

    private void assertCapability(Actor actor, String capability) {
        if (actor == null) {
            // security 关闭的离线演示/测试模式：与既有领域服务口径一致，不做能力拦截
            log.warn("[knowledge] 无演示主体（security 关闭），跳过能力校验 capability={}", capability);
            return;
        }
        if (!PermissionMatrix.allows(actor.role(), capability)) {
            throw DomainException.forbiddenCapability(capability,
                    actor.role() == null ? "" : actor.role().value());
        }
    }

    /** 终态判定（契约 KnowledgeChangeStatus 单向状态机：pending_review → approved | rejected）。 */
    private static boolean isTerminal(KnowledgeChangeStatus status) {
        return status == KnowledgeChangeStatus.APPROVED || status == KnowledgeChangeStatus.REJECTED;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String actorIdOf(Actor actor) {
        return actor == null || actor.actorId() == null ? "anonymous" : actor.actorId();
    }

    private String nextChangeId(Instant at) {
        String day = DAY_FORMAT.format(at.atZone(TicketTimes.BUSINESS_ZONE));
        synchronized (sequence) {
            if (!day.equals(sequenceDay.get())) {
                sequenceDay.set(day);
                sequence.set(0);
            }
            return "KC-" + day + "-" + String.format("%04d", sequence.incrementAndGet());
        }
    }

    /**
     * 已生效覆盖版本（进程内）：读接口优先使用，<b>不写回 mock 文件</b>。
     */
    public record Override(
            String knowledgeId,
            String title,
            String content,
            String version,
            String effectiveFrom,
            String effectiveTo,
            String changeId,
            String submittedBy,
            String submittedAt,
            String reviewedBy,
            String reviewedAt,
            String reviewNote) {
    }

    /**
     * 变更单内部记录（入参全量留存，便于审核通过时生成覆盖版本；不对外暴露正文以外的隐私字段）。
     *
     * @param version 审核通过后生成的新版本号（未通过为 {@code null}）
     */
    public record ChangeRecord(
            String changeId,
            String knowledgeId,
            KnowledgeChangeStatus status,
            String submittedBy,
            String submittedAt,
            String reason,
            String title,
            String content,
            String effectiveFrom,
            String effectiveTo,
            String version,
            String reviewedBy,
            String reviewedAt,
            String reviewNote) {

        ChangeRecord reviewed(KnowledgeChangeStatus target, String reviewer, String reviewedAt, String newVersion,
                String note) {
            return new ChangeRecord(changeId, knowledgeId, target, submittedBy, submittedAt, reason, title, content,
                    effectiveFrom, effectiveTo, newVersion, reviewer, reviewedAt, note);
        }

        /** 对外 DTO（契约 {@code KnowledgeChange}）：只回状态时间与变更原因，不回正文。 */
        public KnowledgeChange toDto() {
            return new KnowledgeChange(changeId, knowledgeId, status, submittedBy, submittedAt, reason,
                    reviewedBy, reviewedAt);
        }
    }
}
