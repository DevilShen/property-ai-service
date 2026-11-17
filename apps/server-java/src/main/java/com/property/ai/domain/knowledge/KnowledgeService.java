package com.property.ai.domain.knowledge;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.property.ai.bff.api.ticket.ApiSupport;
import com.property.ai.bff.dto.Actor;
import com.property.ai.bff.dto.ActorRole;
import com.property.ai.bff.dto.ErrorCode;
import com.property.ai.bff.dto.KnowledgeCategory;
import com.property.ai.bff.dto.KnowledgeChange;
import com.property.ai.bff.dto.KnowledgeChangeRequest;
import com.property.ai.bff.dto.KnowledgeDetail;
import com.property.ai.bff.dto.KnowledgeItem;
import com.property.ai.bff.dto.KnowledgeItemStatus;
import com.property.ai.bff.dto.KnowledgeListResult;
import com.property.ai.bff.dto.KnowledgeReviewRequest;
import com.property.ai.bff.dto.KnowledgeSearchHit;
import com.property.ai.bff.dto.KnowledgeSearchResult;
import com.property.ai.bff.dto.KnowledgeSource;
import com.property.ai.bff.dto.KnowledgeVersion;
import com.property.ai.bff.dto.KnowledgeVersionListResult;
import com.property.ai.domain.ticket.DomainException;
import com.property.ai.domain.ticket.TicketTimes;

/**
 * 知识读取/检索服务（P4-005，契约 v1.2.0 {@code /api/v1/knowledge*}）。
 *
 * <h2>读取口径（AGENTS.md §1「不编造知识」+ 契约 §7.4）</h2>
 * <ul>
 *   <li><b>list/detail/versions</b>：staff（steward/agent/supervisor）与 it/admin 可读全部状态
 *       （管理视图含 draft/pending_review/rejected）；<b>owner 只读 published 且在生效期内</b>，
 *       其余状态一律 404 {@code E-3001}（不区分「不存在」与「不可见」，避免探测未生效内容）；</li>
 *   <li><b>search</b>：只返回 {@code status=published} <b>且</b> {@code effectiveFrom ≤ today ≤ effectiveTo}
 *       的条目——过期（expired）/草稿/待审/驳回一律不返回（检索不分叉）；
 *       过期详情对 staff 仍可读并标注 {@code expired}（降级不删数据），但不进检索；</li>
 *   <li><b>无命中</b>：返回 200 + {@code items: []}（不是错误）：AI 必须拒答转人工，不得编造答案；</li>
 *   <li><b>引用四要素</b>：{@code knowledgeId}/{@code title}/{@code version}/{@code owner} + 可直接展示的
 *       {@code cite}（{@code K-018 V1.2 · 装修管理规定 §3.1}）；前端不得自行拼接；</li>
 *   <li><b>覆盖版本</b>：变更审核通过后，list/detail/versions/search 优先读
 *       {@link KnowledgeChangeService} 的内存覆盖版本（不写回 mock 文件）。</li>
 * </ul>
 *
 * <p><b>数据范围（P5 W22 / P5-023 多项目隔离）</b>：知识契约 DTO 没有 {@code projectId} 字段（不改契约），
 * 项目归属由 {@link KnowledgeProjectScope} 在服务端维护：缺省 = {@code Actor.projectId} 项目，
 * 平台级（it/admin）缺省 = 全部项目；跨项目 → 403 {@code E-2002} {@code cross_project}；
 * list/detail/versions/search/change/review 全部按作用域过滤或校验（AI 引用不得跨项目串数据）。
 */
@Service
public class KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);

    private static final List<String> STATUS_VALUES = List.of(
            KnowledgeItemStatus.PUBLISHED.value(),
            KnowledgeItemStatus.EXPIRED.value(),
            KnowledgeItemStatus.DRAFT.value(),
            KnowledgeItemStatus.PENDING_REVIEW.value(),
            KnowledgeItemStatus.REJECTED.value());

    private static final List<String> CATEGORY_VALUES = List.of(
            KnowledgeCategory.FEE.value(), KnowledgeCategory.UTILITY.value(), KnowledgeCategory.PARKING.value(),
            KnowledgeCategory.RENOVATION.value(), KnowledgeCategory.PET.value(), KnowledgeCategory.VISITOR.value(),
            KnowledgeCategory.NOISE.value(), KnowledgeCategory.NOTICE.value(), KnowledgeCategory.REPAIR.value(),
            KnowledgeCategory.COMPLAINT.value(), KnowledgeCategory.OTHER.value());

    /** 单命中的最低分词覆盖率（低于该值不算命中，避免「只有一个字眼重合」的假命中） */
    private static final double MIN_COVERAGE = 0.34;

    private final KnowledgeRepository repository;
    private final KnowledgeChangeService changeService;
    private final KnowledgeSearchTelemetry telemetry;

    private Clock clock = Clock.system(TicketTimes.BUSINESS_ZONE);

    public KnowledgeService(KnowledgeRepository repository, KnowledgeChangeService changeService,
            KnowledgeSearchTelemetry telemetry) {
        this.repository = repository;
        this.changeService = changeService;
        this.telemetry = telemetry;
    }

    /** 测试注入固定时钟（生效期/过期判定断言用；生产不调用） */
    void setClockForTest(Clock clock) {
        this.clock = clock == null ? Clock.system(TicketTimes.BUSINESS_ZONE) : clock;
    }

    /** 今天的业务时区日期（生效期判定基准）。 */
    LocalDate today() {
        return LocalDate.now(clock.withZone(TicketTimes.BUSINESS_ZONE));
    }

    // ==================================================================
    // 视图（仓库基线 + 已生效覆盖版本）
    // ==================================================================

    /**
     * 知识视图（合并口径）：仓库基线条目 + 变更审核通过的覆盖版本。
     *
     * <p>包内可见即可：{@link KnowledgeStatsService} 复用同一份合并视图，保证「列表/检索/统计」口径一致。
     */
    public record View(
            String knowledgeId,
            String title,
            String content,
            String version,
            KnowledgeItemStatus status,
            String owner,
            String effectiveFrom,
            String effectiveTo,
            KnowledgeSource source,
            KnowledgeCategory category,
            boolean badcase,
            List<String> refs,
            List<String> keywords,
            String updatedAt,
            String changeId) {

        /** 契约 {@code KnowledgeItem}（列表项：不含正文差异外的额外字段）。 */
        KnowledgeItem toItem() {
            return new KnowledgeItem(knowledgeId, title, content, version, status, owner, effectiveFrom,
                    effectiveTo, source, category, badcase, refs);
        }
    }

    /**
     * 项目作用域内的知识视图（P5 W22 / P5-023）：{@code scopeProjectId == null} = 平台级全量；
     * 非空 = 只保留该项目可见条目（见 {@link KnowledgeProjectScope}）。
     */
    public List<View> views(String scopeProjectId) {
        List<View> all = views();
        if (scopeProjectId == null || scopeProjectId.isBlank()) {
            return all;
        }
        List<View> scoped = new ArrayList<>(all.size());
        for (View view : all) {
            if (KnowledgeProjectScope.visibleTo(view.knowledgeId(), scopeProjectId)) {
                scoped.add(view);
            }
        }
        return List.copyOf(scoped);
    }

    /** 全部知识视图（合并覆盖版本；不排序，调用方按需排序）。 */
    public List<View> views() {
        List<KnowledgeRepository.Entry> entries = repository.all();
        List<View> views = new ArrayList<>(entries.size());
        for (KnowledgeRepository.Entry entry : entries) {
            Optional<KnowledgeChangeService.Override> override = changeService.overrideFor(entry.knowledgeId());
            views.add(override.map(value -> new View(
                    entry.knowledgeId(),
                    value.title() == null ? entry.title() : value.title(),
                    value.content(),
                    value.version(),
                    KnowledgeItemStatus.PUBLISHED,
                    entry.owner(),
                    value.effectiveFrom(),
                    value.effectiveTo(),
                    entry.source(),
                    entry.category(),
                    entry.badcase(),
                    entry.refs(),
                    entry.keywords(),
                    value.reviewedAt() == null ? entry.updatedAt() : value.reviewedAt(),
                    value.changeId())).orElseGet(() -> new View(
                    entry.knowledgeId(), entry.title(), entry.content(), entry.version(), entry.status(),
                    entry.owner(), entry.effectiveFrom(), entry.effectiveTo(), entry.source(), entry.category(),
                    entry.badcase(), entry.refs(), entry.keywords(), entry.updatedAt(), null)));
        }
        return List.copyOf(views);
    }

    /** 按 ID 取视图（大小写不敏感；不区分状态）。 */
    public Optional<View> view(String knowledgeId) {
        if (knowledgeId == null || knowledgeId.isBlank()) {
            return Optional.empty();
        }
        String normalized = knowledgeId.trim();
        return views().stream().filter(view -> normalized.equalsIgnoreCase(view.knowledgeId())).findFirst();
    }

    // ==================================================================
    // 列表（筛选 + 分页）
    // ==================================================================

    /**
     * 知识列表（{@code GET /api/v1/knowledge}）：状态/分类/责任人/生效期过滤 + 分页。
     *
     * @param effectiveFromParam 生效期起点：条目的 {@code effectiveFrom >= 该日}
     * @param effectiveToParam   生效期终点：条目的 {@code effectiveFrom <= 该日}（契约口径，按起点比较）
     */
    public KnowledgeListResult list(String statusParam, String categoryParam, String ownerParam,
            String effectiveFromParam, String effectiveToParam, int page, int pageSize, Actor actor) {
        return list(statusParam, categoryParam, ownerParam, effectiveFromParam, effectiveToParam, page, pageSize,
                actor, KnowledgeProjectScope.scopeOf(actor));
    }

    /**
     * 知识列表（P5 W22 / P5-023 项目作用域重载）。
     *
     * @param scopeProjectId 生效项目作用域（{@code null} = 平台级全量；受限角色 = {@code Actor.projectId}；
     *                       Controller 已用 {@code ProjectService#resolveScope} 做跨项目 403 校验）
     */
    public KnowledgeListResult list(String statusParam, String categoryParam, String ownerParam,
            String effectiveFromParam, String effectiveToParam, int page, int pageSize, Actor actor,
            String scopeProjectId) {
        ApiSupport.requirePageParams(page, pageSize);
        KnowledgeItemStatus status = parseStatus(statusParam);
        KnowledgeCategory category = parseCategory(categoryParam);
        LocalDate fromFilter = optionalDate("effectiveFrom", effectiveFromParam);
        LocalDate toFilter = optionalDate("effectiveTo", effectiveToParam);
        String ownerFilter = ownerParam == null || ownerParam.isBlank() ? null : ownerParam.trim();
        boolean ownerScope = isOwnerScoped(actor);
        LocalDate today = today();

        List<View> filtered = new ArrayList<>();
        if (!(ownerScope && status != null && status != KnowledgeItemStatus.PUBLISHED)) {
            for (View view : sortedViews(scopeProjectId)) {
                if (ownerScope && !searchable(view, today)) {
                    continue;
                }
                if (status != null && view.status() != status) {
                    continue;
                }
                if (category != null && view.category() != category) {
                    continue;
                }
                if (ownerFilter != null && (view.owner() == null || !ownerFilter.equals(view.owner()))) {
                    continue;
                }
                LocalDate itemFrom = KnowledgeSupport.dateOrNull(view.effectiveFrom());
                if (fromFilter != null && (itemFrom == null || itemFrom.isBefore(fromFilter))) {
                    continue;
                }
                if (toFilter != null && (itemFrom == null || itemFrom.isAfter(toFilter))) {
                    continue;
                }
                filtered.add(view);
            }
        }
        int total = filtered.size();
        int offset = (page - 1) * pageSize;
        List<KnowledgeItem> items;
        if (offset >= total) {
            items = List.of();
        } else {
            int end = Math.min(total, offset + pageSize);
            items = filtered.subList(offset, end).stream().map(View::toItem).toList();
        }
        return new KnowledgeListResult(items, page, pageSize, total);
    }

    // ==================================================================
    // 详情 / 版本历史
    // ==================================================================

    /** 知识详情（含 {@code versions[]}）：owner 不可见未生效/过期条目（404，不区分不可见与不存在）。 */
    public KnowledgeDetail detail(String knowledgeId, Actor actor) {
        return detail(knowledgeId, actor, KnowledgeProjectScope.scopeOf(actor));
    }

    /**
     * 知识详情（P5 W22 / P5-023 项目作用域重载）：条目不在生效作用域内 → 403 {@code E-2002}
     * {@code cross_project}（平台级 it/admin 显式带 {@code ?projectId=} 时也会被拒，避免"选错项目读到别项目知识"）。
     */
    public KnowledgeDetail detail(String knowledgeId, Actor actor, String scopeProjectId) {
        View view = requireVisible(knowledgeId, actor, scopeProjectId);
        return new KnowledgeDetail(view.knowledgeId(), view.title(), view.content(), view.version(),
                view.status(), view.owner(), view.effectiveFrom(), view.effectiveTo(), view.source(),
                view.category(), view.badcase(), view.refs(), versions(view));
    }

    /** 版本历史（按提交时间升序；基线版本在前，其后为已批准变更版本）。 */
    public KnowledgeVersionListResult versions(String knowledgeId, Actor actor) {
        return versions(knowledgeId, actor, KnowledgeProjectScope.scopeOf(actor));
    }

    /** 版本历史（P5 W22 / P5-023 项目作用域重载；跨项目 → 403 {@code cross_project}）。 */
    public KnowledgeVersionListResult versions(String knowledgeId, Actor actor, String scopeProjectId) {
        View view = requireVisible(knowledgeId, actor, scopeProjectId);
        List<KnowledgeVersion> versions = versions(view);
        return new KnowledgeVersionListResult(view.knowledgeId(), versions, versions.size());
    }

    private List<KnowledgeVersion> versions(View view) {
        List<KnowledgeVersion> versions = new ArrayList<>();
        // 基线版本：取**仓库基线**（不是覆盖版本），mock 数据没有「提交人/提交时间」字段，
        // 用责任人与数据时点（updatedAt）如实回填；随后追加已批准变更版本（按提交时间升序）。
        KnowledgeRepository.Entry base = repository.find(view.knowledgeId()).orElse(null);
        if (base != null) {
            versions.add(new KnowledgeVersion(base.knowledgeId(), base.version(), base.status(),
                    base.effectiveFrom(), base.effectiveTo(), base.owner(), base.updatedAt(), null, null));
        }
        versions.addAll(changeService.approvedVersions(view.knowledgeId()));
        return List.copyOf(versions);
    }

    // ==================================================================
    // 检索（只回 published + 生效期内）
    // ==================================================================

    /**
     * 知识检索（{@code GET /api/v1/knowledge/search?q=}）。
     *
     * <p>只返回 {@code published} 且在生效期内的条目；无命中返回空 {@code items}（200，不编造）。
     * 每次检索记入 {@link KnowledgeSearchTelemetry}，供 {@code /knowledge/stats} 采纳率/缺口口径使用。
     */
    public KnowledgeSearchResult search(String query, int limit, Actor actor) {
        return search(query, limit, actor, KnowledgeProjectScope.scopeOf(actor));
    }

    /**
     * 知识检索（P5 W22 / P5-023 项目作用域重载）：只在本项目可见条目内检索
     * （跨项目知识不得进入 AI 答案/引用，AGENTS.md「不编造、不串项目数据」）。
     */
    public KnowledgeSearchResult search(String query, int limit, Actor actor, String scopeProjectId) {
        String rawQuery = query == null ? "" : query.trim();
        if (rawQuery.isEmpty()) {
            throw DomainException.badRequest("检索词 q 必填（≤200 字符）",
                    Map.of("field", "q", "required", true, "maximum", KnowledgeSupport.MAX_QUERY_LENGTH));
        }
        if (rawQuery.length() > KnowledgeSupport.MAX_QUERY_LENGTH) {
            throw DomainException.badRequest("检索词 q 长度不能超过 " + KnowledgeSupport.MAX_QUERY_LENGTH + " 字符",
                    Map.of("field", "q", "length", rawQuery.length(),
                            "maximum", KnowledgeSupport.MAX_QUERY_LENGTH));
        }
        int effectiveLimit = limit <= 0 ? 10 : limit;
        if (effectiveLimit > KnowledgeSupport.MAX_SEARCH_LIMIT) {
            throw DomainException.badRequest("limit 不能超过 " + KnowledgeSupport.MAX_SEARCH_LIMIT,
                    Map.of("field", "limit", "value", limit, "maximum", KnowledgeSupport.MAX_SEARCH_LIMIT));
        }
        LocalDate today = today();
        List<Scored> scored = new ArrayList<>();
        for (View view : views(scopeProjectId)) {
            if (!searchable(view, today)) {
                continue;
            }
            double score = score(view, rawQuery);
            if (score > 0) {
                scored.add(new Scored(view, score));
            }
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed()
                .thenComparing(item -> item.view().knowledgeId()));
        List<KnowledgeSearchHit> hits = new ArrayList<>();
        for (Scored item : scored) {
            if (hits.size() >= effectiveLimit) {
                break;
            }
            View view = item.view();
            hits.add(new KnowledgeSearchHit(view.knowledgeId(), view.title(), view.version(), view.owner(),
                    view.effectiveFrom(), view.effectiveTo(), item.score(),
                    KnowledgeSupport.cite(view.knowledgeId(), view.version(), view.source())));
        }
        telemetry.recordSearch(rawQuery, hits.size());
        log.debug("[knowledge] search q={} hits={} actor={}", rawQuery, hits.size(),
                actor == null ? "anonymous" : actor.actorId());
        return new KnowledgeSearchResult(List.copyOf(hits), rawQuery);
    }

    // ==================================================================
    // 变更 / 审核（委派 KnowledgeChangeService，Controller 只依赖本服务）
    // ==================================================================

    public KnowledgeChange submitChange(String knowledgeId, KnowledgeChangeRequest request, Actor actor,
            String traceId, String clientIp) {
        return submitChange(knowledgeId, request, actor, traceId, clientIp, KnowledgeProjectScope.scopeOf(actor));
    }

    /** 提交知识变更（P5 W22 / P5-023）：条目不在生效项目作用域内 → 403 {@code cross_project}。 */
    public KnowledgeChange submitChange(String knowledgeId, KnowledgeChangeRequest request, Actor actor,
            String traceId, String clientIp, String scopeProjectId) {
        requireVisible(knowledgeId, actor, scopeProjectId);
        return changeService.submit(knowledgeId, request, actor, traceId, clientIp);
    }

    public KnowledgeChange reviewChange(String knowledgeId, KnowledgeReviewRequest request, Actor actor,
            String traceId, String clientIp) {
        return reviewChange(knowledgeId, request, actor, traceId, clientIp, KnowledgeProjectScope.scopeOf(actor));
    }

    /** 审核知识变更（P5 W22 / P5-023）：条目不在生效项目作用域内 → 403 {@code cross_project}。 */
    public KnowledgeChange reviewChange(String knowledgeId, KnowledgeReviewRequest request, Actor actor,
            String traceId, String clientIp, String scopeProjectId) {
        requireVisible(knowledgeId, actor, scopeProjectId);
        return changeService.review(knowledgeId, request, actor, traceId, clientIp);
    }

    // ==================================================================
    // 内部：可见性 / 生效期 / 评分
    // ==================================================================

    /** 是否 owner 数据范围（owner 只读 published + 生效期内）。 */
    static boolean isOwnerScoped(Actor actor) {
        return actor != null && actor.role() == ActorRole.OWNER;
    }

    /** 检索可见性（**唯一**口径）：published 且生效期覆盖今天。 */
    boolean searchable(View view, LocalDate today) {
        if (view.status() != KnowledgeItemStatus.PUBLISHED) {
            return false;
        }
        LocalDate from = KnowledgeSupport.dateOrNull(view.effectiveFrom());
        LocalDate to = KnowledgeSupport.dateOrNull(view.effectiveTo());
        if (from != null && today.isBefore(from)) {
            return false;
        }
        return to == null || !today.isAfter(to);
    }

    private View requireVisible(String knowledgeId, Actor actor) {
        return requireVisible(knowledgeId, actor, null);
    }

    /**
     * 详情/变更/审核的可见性校验（P5 W22 / P5-023）：
     * <ol>
     *   <li>条目不存在 → 404 {@code E-3001}（不区分不可见与不存在，避免探测）；</li>
     *   <li>条目不在生效项目作用域内 → 403 {@code E-2002} {@code cross_project}
     *       （details{resourceType=knowledge, resourceId, projectId}）；</li>
     *   <li>owner 视角的未生效/过期条目 → 404 {@code E-3001}。</li>
     * </ol>
     */
    private View requireVisible(String knowledgeId, Actor actor, String scopeProjectId) {
        View view = view(knowledgeId).orElseThrow(() -> DomainException.notFound(ErrorCode.E_3001,
                "知识不存在：" + (knowledgeId == null ? "" : knowledgeId),
                Map.of("knowledgeId", knowledgeId == null ? "" : knowledgeId)));
        if (!KnowledgeProjectScope.visibleTo(view.knowledgeId(), scopeProjectId)) {
            throw DomainException.crossProject("knowledge", view.knowledgeId(), scopeProjectId);
        }
        if (isOwnerScoped(actor) && !searchable(view, today())) {
            // 非管理视图不暴露未生效/过期内容：与「不存在」同响应，避免探测
            throw DomainException.notFound(ErrorCode.E_3001,
                    "知识不存在：" + view.knowledgeId(), Map.of("knowledgeId", view.knowledgeId()));
        }
        return view;
    }

    private List<View> sortedViews() {
        return sortedViews(null);
    }

    /** 项目作用域内的排序视图（knowledgeId 升序；分页前全量排序，保证翻页稳定） */
    private List<View> sortedViews(String scopeProjectId) {
        List<View> views = new ArrayList<>(views(scopeProjectId));
        views.sort(Comparator.comparing(View::knowledgeId));
        return views;
    }

    /**
     * 词面相关度（0—1，服务端给出；前端不参与排序）。
     *
     * <p>规则：整句命中标题 → 1.0；否则按分词片段的**字段权重**（标题 1.0 / 关键词 0.93 / 正文 0.8 /
     * 出处 0.6）与**覆盖率**加权：{@code score = 0.55 × 最佳字段权重 + 0.45 × 覆盖率}；
     * 覆盖率低于 {@value #MIN_COVERAGE} 不算命中（避免单字假命中）。<b>不做语义猜测、不补全答案</b>。
     */
    double score(View view, String query) {
        String normalized = KnowledgeSupport.normalize(query);
        String title = KnowledgeSupport.normalize(view.title());
        String content = KnowledgeSupport.normalize(view.content());
        if (normalized.isEmpty()) {
            return 0.0;
        }
        if (!title.isEmpty() && title.contains(normalized)) {
            return 1.0;
        }
        List<String> tokens = KnowledgeSupport.tokens(query);
        if (tokens.isEmpty()) {
            return 0.0;
        }
        double weightSum = 0.0;
        int matched = 0;
        for (String token : tokens) {
            double weight = weightOf(token, title, view.keywords(), content, view.source());
            if (weight > 0) {
                matched++;
                weightSum += weight;
            }
        }
        if (matched == 0) {
            return 0.0;
        }
        double coverage = (double) matched / tokens.size();
        if (coverage < MIN_COVERAGE) {
            return 0.0;
        }
        double averageWeight = weightSum / tokens.size();
        double score = 0.55 * averageWeight + 0.45 * coverage;
        return Math.round(Math.min(1.0, score) * 100.0) / 100.0;
    }

    private static double weightOf(String token, String title, List<String> keywords, String content,
            KnowledgeSource source) {
        if (!title.isEmpty() && title.contains(token)) {
            return 1.0;
        }
        if (keywords != null) {
            for (String keyword : keywords) {
                String normalizedKeyword = KnowledgeSupport.normalize(keyword);
                if (normalizedKeyword.length() < 2) {
                    continue;
                }
                if (normalizedKeyword.contains(token) || token.contains(normalizedKeyword)) {
                    return 0.93;
                }
            }
        }
        if (!content.isEmpty() && content.contains(token)) {
            return 0.8;
        }
        if (source != null && source.doc() != null
                && KnowledgeSupport.normalize(source.doc()).contains(token)) {
            return 0.6;
        }
        return 0.0;
    }

    private KnowledgeItemStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim().toLowerCase(Locale.ROOT);
        for (KnowledgeItemStatus status : KnowledgeItemStatus.values()) {
            if (status.value().equals(text)) {
                return status;
            }
        }
        throw DomainException.badRequest("status 参数不在契约枚举内：" + raw,
                Map.of("field", "status", "value", raw, "allowed", STATUS_VALUES));
    }

    private KnowledgeCategory parseCategory(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim().toLowerCase(Locale.ROOT);
        for (KnowledgeCategory category : KnowledgeCategory.values()) {
            if (category.value().equals(text)) {
                return category;
            }
        }
        throw DomainException.badRequest("category 参数不在契约枚举内：" + raw,
                Map.of("field", "category", "value", raw, "allowed", CATEGORY_VALUES));
    }

    private LocalDate optionalDate(String field, String raw) {
        return raw == null || raw.isBlank() ? null : KnowledgeSupport.requireDate(field, raw);
    }

    /** 检索命中 + 分数（内部排序用）。 */
    private record Scored(View view, double score) {
    }

    /** 合并视图总数（诊断/测试用，不对外暴露端点）。 */
    public int size() {
        return views().size();
    }

    /** 仓库源文件路径（报告/日志用；未找到为空串）。 */
    public String sourcePath() {
        return repository.sourcePath() == null ? "" : repository.sourcePath().toString();
    }
}
