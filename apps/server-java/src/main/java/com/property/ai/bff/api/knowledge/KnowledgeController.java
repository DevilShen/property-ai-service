package com.property.ai.bff.api.knowledge;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.property.ai.bff.api.ApiResponse;
import com.property.ai.bff.api.ticket.ApiSupport;
import com.property.ai.bff.dto.Actor;
import com.property.ai.bff.dto.KnowledgeChange;
import com.property.ai.bff.dto.KnowledgeChangeRequest;
import com.property.ai.bff.dto.KnowledgeDetail;
import com.property.ai.bff.dto.KnowledgeListResult;
import com.property.ai.bff.dto.KnowledgeReviewRequest;
import com.property.ai.bff.dto.KnowledgeSearchResult;
import com.property.ai.bff.dto.KnowledgeStats;
import com.property.ai.bff.dto.KnowledgeVersionListResult;
import com.property.ai.domain.knowledge.KnowledgeService;
import com.property.ai.domain.knowledge.KnowledgeStatsService;
import com.property.ai.domain.project.ProjectService;
import com.property.ai.security.ActorContext;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 知识服务 REST API（P4-005/006，契约 v1.2.0 {@code /api/v1/knowledge*} 7 个 operation）。
 *
 * <pre>
 * GET  /api/v1/knowledge                         200 列表（status/category/owner/effectiveFrom/effectiveTo/分页）
 * GET  /api/v1/knowledge/search?q=&amp;limit=        200 检索（只回 published 且在生效期内；无命中 items: []）
 * GET  /api/v1/knowledge/stats?from=&amp;to=         200 采纳率/缺口 TopN/过期数/责任人分布（staff/it/admin）
 * GET  /api/v1/knowledge/{knowledgeId}           200 详情（含 versions[]；owner 只读 published）
 * GET  /api/v1/knowledge/{knowledgeId}/versions  200 版本历史（按提交时间升序）
 * POST /api/v1/knowledge/{knowledgeId}/change    201 提交变更（pending_review，不直接生效）
 * POST /api/v1/knowledge/{knowledgeId}/review    200 审核（approved/rejected；**仅 it/admin**）
 * </pre>
 *
 * <h2>项目隔离（P5 W22 / P5-023，契约 v1.3.0 §7.6）</h2>
 * 7 个 operation 全部接受可选 {@code ?projectId=}：缺省 = 当前 actor 项目
 * （{@code owner/steward/agent/supervisor} 恒为本项目；{@code it/admin} 平台级缺省 = 全部项目）；
 * 非本项目 → 403 {@code E-2002} + {@code details{reason:cross_project}}（服务端强制，前端隐藏入口不是边界）。
 * 知识 DTO 无 {@code projectId} 字段（不改契约），项目归属见 {@code domain/knowledge/KnowledgeProjectScope}。
 * <b>契约补口 P5-024</b>：{@code ?projectId=} 为向后兼容的可选 query 参数，v1.3.0 OpenAPI 尚未声明，
 * 已登记 {@code _dev/reports/P5-W22-QA素材.md} §待补契约清单（P5 W24 / v1.3.1 契约增量收口）。
 *
 * <h2>分层与鉴权</h2>
 * <ul>
 *   <li>角色 × 能力（{@code knowledge.read/change/review/stats}）由 {@code SecurityFilter} +
 *       {@code PermissionMatrix} 统一执行，Controller 不写角色判断；404/409/400 的业务口径在
 *       {@code domain/knowledge/*Service}；owner 的数据范围（只读 published+生效期）由
 *       {@link KnowledgeService} 强制；</li>
 *   <li>写操作审计（{@code knowledge.change}/{@code knowledge.review}）由统一 {@code AuditFilter}
 *       按 {@code PermissionMatrix} 登记的动作落库（{@code resourceType=knowledge}、
 *       {@code resourceId=knowledgeId}、{@code maskedFields} 只记字段名），Controller 不重复写。</li>
 * </ul>
 *
 * <h2>路由顺序（契约 §1.5 明确要求）</h2>
 * {@code /knowledge/search} 与 {@code /knowledge/stats} 是<b>字面量段</b>，
 * Spring {@code PathPattern} 匹配优先级天然是「字面量 &gt; 变量」，因此不会把 {@code search} 当成
 * {@code knowledgeId}；本类仍按字面量在前声明以保持可读性，并已在契约测试中覆盖
 * （{@code GET /knowledge/search}、{@code GET /knowledge/stats} 均命中各自 handler）。
 */
@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeController {

    private final KnowledgeService knowledgeService;
    private final KnowledgeStatsService knowledgeStatsService;
    /** 项目隔离（P5 W22 / P5-023）：?projectId= 作用域解析 + 跨项目 403 cross_project */
    private final ProjectService projectService;

    public KnowledgeController(KnowledgeService knowledgeService, KnowledgeStatsService knowledgeStatsService,
            ProjectService projectService) {
        this.knowledgeService = knowledgeService;
        this.knowledgeStatsService = knowledgeStatsService;
        this.projectService = projectService;
    }

    /** 知识列表（契约 {@code knowledgeList}）：筛选 + 分页；owner 恒只回 published+生效期。 */
    @GetMapping
    public ResponseEntity<ApiResponse<KnowledgeListResult>> list(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "category", required = false) String category,
            @RequestParam(name = "owner", required = false) String owner,
            @RequestParam(name = "effectiveFrom", required = false) String effectiveFrom,
            @RequestParam(name = "effectiveTo", required = false) String effectiveTo,
            @RequestParam(name = "projectId", required = false) String projectId,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "20") int pageSize,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        // P5 W22 项目隔离：缺省 = 当前 actor 项目（it/admin 平台级缺省 = 全部）；跨项目 → 403 cross_project
        String scopeProjectId = projectService.resolveScope(projectId, "knowledge", traceId);
        KnowledgeListResult result = knowledgeService.list(status, category, owner, effectiveFrom, effectiveTo,
                page, pageSize, ActorContext.actorOrNull(), scopeProjectId);
        return ResponseEntity.ok(ApiResponse.ok(result, traceId));
    }

    /**
     * 知识检索（契约 {@code knowledgeSearch}）：**只返回 published 且在生效期内**的条目，带 {@code cite}。
     * 无命中返回 200 + {@code items: []}（AI 必须拒答转人工，不得编造）；{@code q} 缺失/超长 → 400 E-1001。
     */
    @GetMapping("/search")
    public ResponseEntity<ApiResponse<KnowledgeSearchResult>> search(
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "limit", defaultValue = "10") int limit,
            @RequestParam(name = "projectId", required = false) String projectId,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        String scopeProjectId = projectService.resolveScope(projectId, "knowledge", traceId);
        KnowledgeSearchResult result = knowledgeService.search(q, limit, ActorContext.actorOrNull(), scopeProjectId);
        return ResponseEntity.ok(ApiResponse.ok(result, traceId));
    }

    /** 知识统计（契约 {@code knowledgeStats}）：管理视图，仅 staff/it/admin（owner → 403）。 */
    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<KnowledgeStats>> stats(
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "projectId", required = false) String projectId,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        String scopeProjectId = projectService.resolveScope(projectId, "knowledge", traceId);
        KnowledgeStats stats = knowledgeStatsService.stats(from, to, scopeProjectId);
        return ResponseEntity.ok(ApiResponse.ok(stats, traceId));
    }

    /** 知识详情（契约 {@code knowledgeGet}）：含 {@code versions[]}；owner 读未生效/过期 → 404。 */
    @GetMapping("/{knowledgeId}")
    public ResponseEntity<ApiResponse<KnowledgeDetail>> get(@PathVariable String knowledgeId,
            @RequestParam(name = "projectId", required = false) String projectId,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        String scopeProjectId = projectService.resolveScope(projectId, "knowledge", traceId);
        KnowledgeDetail detail = knowledgeService.detail(knowledgeId, ActorContext.actorOrNull(), scopeProjectId);
        return ResponseEntity.ok(ApiResponse.ok(detail, traceId));
    }

    /** 版本历史（契约 {@code knowledgeVersionList}）：按提交时间升序、不分页。 */
    @GetMapping("/{knowledgeId}/versions")
    public ResponseEntity<ApiResponse<KnowledgeVersionListResult>> versions(@PathVariable String knowledgeId,
            @RequestParam(name = "projectId", required = false) String projectId,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        String scopeProjectId = projectService.resolveScope(projectId, "knowledge", traceId);
        KnowledgeVersionListResult result = knowledgeService.versions(knowledgeId, ActorContext.actorOrNull(),
                scopeProjectId);
        return ResponseEntity.ok(ApiResponse.ok(result, traceId));
    }

    /** 提交知识变更（契约 {@code knowledgeChange}，201）：进入 {@code pending_review}，不直接生效。 */
    @PostMapping("/{knowledgeId}/change")
    public ResponseEntity<ApiResponse<KnowledgeChange>> change(@PathVariable String knowledgeId,
            @RequestBody(required = false) KnowledgeChangeRequest requestBody,
            @RequestParam(name = "projectId", required = false) String projectId,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        Actor actor = ActorContext.actorOrNull();
        String scopeProjectId = projectService.resolveScope(projectId, "knowledge", traceId);
        KnowledgeChange change = knowledgeService.submitChange(knowledgeId, requestBody, actor, traceId,
                request.getRemoteAddr(), scopeProjectId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(change, traceId));
    }

    /** 审核知识变更（契约 {@code knowledgeReview}，仅 it/admin；通过后生成新版本并生效）。 */
    @PostMapping("/{knowledgeId}/review")
    public ResponseEntity<ApiResponse<KnowledgeChange>> review(@PathVariable String knowledgeId,
            @RequestBody(required = false) KnowledgeReviewRequest requestBody,
            @RequestParam(name = "projectId", required = false) String projectId,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        Actor actor = ActorContext.actorOrNull();
        String scopeProjectId = projectService.resolveScope(projectId, "knowledge", traceId);
        KnowledgeChange change = knowledgeService.reviewChange(knowledgeId, requestBody, actor, traceId,
                request.getRemoteAddr(), scopeProjectId);
        return ResponseEntity.ok(ApiResponse.ok(change, traceId));
    }
}
