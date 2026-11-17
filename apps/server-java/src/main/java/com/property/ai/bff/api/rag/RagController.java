package com.property.ai.bff.api.rag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.property.ai.audit.RagQueryAuditLog;
import com.property.ai.bff.api.ApiResponse;
import com.property.ai.bff.api.ticket.ApiSupport;
import com.property.ai.bff.dto.Actor;
import com.property.ai.bff.dto.DegradedFallback;
import com.property.ai.bff.dto.DegradedInfo;
import com.property.ai.bff.dto.ErrorCode;
import com.property.ai.bff.dto.KnowledgeItemStatus;
import com.property.ai.bff.dto.KnowledgeSearchResult;
import com.property.ai.bff.dto.RagHealth;
import com.property.ai.bff.dto.RagMode;
import com.property.ai.bff.dto.RagQueryRequest;
import com.property.ai.bff.dto.RagQueryResult;
import com.property.ai.bff.dto.RagReindexResult;
import com.property.ai.domain.knowledge.KnowledgeService;
import com.property.ai.domain.project.ProjectService;
import com.property.ai.domain.ticket.DomainException;
import com.property.ai.domain.ticket.TicketTimes;
import com.property.ai.security.ActorContext;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 知识库 RAG REST API（P6 W30 / task-80；契约 v1.4.0 <b>3 个 operation</b>）。
 *
 * <pre>
 * POST /api/v1/rag/query                        200 知识问答（混合检索 + 生成；拒答 200；编排层不可用 → 规则检索降级）
 * GET  /api/v1/rag/health                       200 编排健康与模型信息（仅 it/admin；不可用 → status=degraded）
 * POST /api/v1/knowledge/{knowledgeId}/reindex  200 重建知识索引（仅 it/admin；审计 knowledge.reindex）
 * </pre>
 *
 * <h2>链路与边界（AGENTS.md §1 / NEVER #2）</h2>
 * 生成/检索/重排全部在 Python 编排层（{@code services/ai-orchestrator} {@code /internal/rag/*}），
 * Java BFF 只做「出站 HTTP + 契约映射 + 降级 + 项目隔离 + 审计」：<b>Java 侧不接模型 SDK、不拼 Prompt</b>。
 *
 * <h2>降级阶梯（2xx，不编造）</h2>
 * <ul>
 *   <li>编排层不可用/超时/5xx/端点未就绪 → {@code KnowledgeService.search} 取 top5 映射为 sources，
 *       {@code mode=rules} + {@code degraded{reason=rag_unavailable…}}；无命中 → {@code answerable=false} +
 *       {@code no_evidence}（拒答转人工）；</li>
 *   <li>编排层返回 {@code answerable=false}（含拒答/越权/引用失败）→ 原样 200 + {@code refusalReason}，
 *       前端转人工；<b>不改成错误码</b>（契约：拒答不是错误）；</li>
 *   <li>重建索引写操作不可用 → 503 {@code E-5001} + {@code degraded}（写不假成功）。</li>
 * </ul>
 *
 * <h2>权限与项目隔离</h2>
 * 能力码由 {@code SecurityFilter} + {@link com.property.ai.security.PermissionMatrix} 统一执行
 * （{@code rag.query} 六角色 / {@code rag.health} 与 {@code knowledge.reindex} 仅 it/admin）。
 * {@code projectId} <b>由服务端强制</b>（body 传参不是安全边界）：owner/staff 跨项目 → 403
 * {@code E-2002} {@code cross_project}；缺省 = 当前 actor 项目；it/admin 缺省 = 平台级。
 *
 * <h2>审计（不落正文）</h2>
 * 问答为只读语义（契约 v1.4.0「不落写审计」）→ 元数据落 {@link RagQueryAuditLog}
 * （traceId/projectId/queryHash/answerable/cites/mode/latencyMs，无问题与答案正文）；
 * 重建索引为写操作 → 由统一 {@code AuditFilter} 落 {@code knowledge.reindex}（可经 {@code /audit/events} 查询）。
 */
@RestController
public class RagController {

    private static final Logger log = LoggerFactory.getLogger(RagController.class);

    /** 契约 RagQueryRequest.question 1—500 字符 */
    static final int MAX_QUESTION_LENGTH = 500;
    /** 契约 RagQueryRequest.topK 1—10，缺省 5 */
    static final int MIN_TOP_K = 1;
    static final int MAX_TOP_K = 10;
    static final int DEFAULT_TOP_K = 5;
    /** 规则检索降级只取 top5（与契约 topK 缺省一致） */
    static final int RULES_FALLBACK_TOP_K = 5;
    /** 规则检索域名 {@code q} 上限 200 字符（KnowledgeSupport.MAX_QUERY_LENGTH） */
    static final int KNOWLEDGE_QUERY_MAX = 200;

    private static final String RESOURCE_TYPE = "rag";

    private final RagClient ragClient;
    private final RagProperties ragProperties;
    private final KnowledgeService knowledgeService;
    private final ProjectService projectService;
    private final RagQueryAuditLog auditLog;

    public RagController(RagClient ragClient, RagProperties ragProperties, KnowledgeService knowledgeService,
            ProjectService projectService, RagQueryAuditLog auditLog) {
        this.ragClient = ragClient;
        this.ragProperties = ragProperties;
        this.knowledgeService = knowledgeService;
        this.projectService = projectService;
        this.auditLog = auditLog;
    }

    // ==================================================================
    // POST /api/v1/rag/query
    // ==================================================================

    /** 知识问答（契约 {@code ragQuery}）：生成答案 + 引用；编排层不可用 → 规则检索降级。 */
    @PostMapping("/api/v1/rag/query")
    public ResponseEntity<ApiResponse<RagQueryResult>> query(
            @RequestBody(required = false) RagQueryRequest requestBody, HttpServletRequest request) {
        long startedAt = System.nanoTime();
        String traceId = ApiSupport.traceId(request);
        Actor actor = ActorContext.actorOrNull();
        String question = requireQuestion(requestBody);
        int topK = requireTopK(requestBody);
        // 项目隔离：owner/staff 跨项目 → 403 E-2002 cross_project（服务端强制）；it/admin 缺省 = 平台级（null）
        String scopeProjectId = projectService.resolveScope(
                requestBody == null ? null : requestBody.projectId(), RESOURCE_TYPE, traceId);

        RagQueryResult result;
        String orchestratorTraceId = null;
        RagClientException failure = null;
        try {
            RagClient.InternalAnswer internal = ragClient.answer(question, scopeProjectId,
                    requestBody.category(), topK, traceId);
            orchestratorTraceId = internal.traceId();
            result = RagMapper.fromInternal(internal, traceId, viewsOf(scopeProjectId));
        } catch (RagClientException exception) {
            failure = exception;
            log.warn("[rag] 问答降级规则检索 kind={} traceId={} projectId={}", exception.kind(), traceId,
                    scopeProjectId);
            result = rulesFallback(question, scopeProjectId, actor, traceId, exception);
        }

        long latencyMs = (System.nanoTime() - startedAt) / 1_000_000L;
        recordAudit(actor, scopeProjectId, traceId, orchestratorTraceId, question, result, latencyMs, failure);
        log.info("[rag] query mode={} answerable={} cites={} latencyMs={} traceId={} projectId={}",
                result.mode(), result.answerable(), result.sources().size(), latencyMs, traceId, scopeProjectId);

        ApiResponse<RagQueryResult> envelope = result.degraded() == null
                ? ApiResponse.ok(result, traceId)
                : ApiResponse.okDegraded(result, result.degraded(), traceId);
        return ResponseEntity.ok(envelope);
    }

    private String requireQuestion(RagQueryRequest requestBody) {
        if (requestBody == null) {
            throw DomainException.badRequest("请求体必填：{\"question\":\"…\",\"projectId\":\"P-YQY\",\"topK\":5}",
                    Map.of("field", "body", "required", true));
        }
        String question = requestBody.question() == null ? "" : requestBody.question().trim();
        if (question.isEmpty()) {
            throw DomainException.badRequest("question 必填（1—" + MAX_QUESTION_LENGTH + " 字符）",
                    Map.of("field", "question", "required", true, "maximum", MAX_QUESTION_LENGTH));
        }
        if (question.length() > MAX_QUESTION_LENGTH) {
            throw DomainException.badRequest("question 长度不能超过 " + MAX_QUESTION_LENGTH + " 字符",
                    Map.of("field", "question", "length", question.length(), "maximum", MAX_QUESTION_LENGTH));
        }
        return question;
    }

    private int requireTopK(RagQueryRequest requestBody) {
        Integer topK = requestBody == null ? null : requestBody.topK();
        if (topK == null) {
            return DEFAULT_TOP_K;
        }
        if (topK < MIN_TOP_K || topK > MAX_TOP_K) {
            throw DomainException.badRequest("topK 必须在 " + MIN_TOP_K + "—" + MAX_TOP_K + " 之间",
                    Map.of("field", "topK", "value", topK, "minimum", MIN_TOP_K, "maximum", MAX_TOP_K));
        }
        return topK;
    }

    /** 规则检索降级（不生成答案，只回原文片段；无命中 → 拒答转人工）。 */
    private RagQueryResult rulesFallback(String question, String scopeProjectId, Actor actor, String traceId,
            RagClientException failure) {
        // 知识域 q 上限 200 字符（RAG question 可达 500）→ 规则检索按前 200 字截断，避免 400 打断降级路径
        String fallbackQuery = question.length() > KNOWLEDGE_QUERY_MAX
                ? question.substring(0, KNOWLEDGE_QUERY_MAX)
                : question;
        KnowledgeSearchResult search;
        try {
            search = knowledgeService.search(fallbackQuery, RULES_FALLBACK_TOP_K, actor, scopeProjectId);
        } catch (DomainException exception) {
            // 规则检索本身失败（不应发生）：按无命中降级，仍返回 2xx 拒答，不编造
            log.warn("[rag] 规则检索降级失败 code={} traceId={}", exception.code().value(), traceId);
            search = new KnowledgeSearchResult(List.of(), fallbackQuery);
        }
        return RagMapper.rulesFallback(search, viewsOf(scopeProjectId), traceId, failure);
    }

    /** 本项目可见知识视图（{@code knowledgeId → View}；用于来源卡片 version/owner/category/snippet 补齐）。 */
    private Map<String, KnowledgeService.View> viewsOf(String scopeProjectId) {
        try {
            return RagMapper.indexViews(knowledgeService.views(scopeProjectId));
        } catch (RuntimeException exception) {
            log.warn("[rag] 知识视图加载失败（来源卡片按编排层字段输出） err={}",
                    exception.getClass().getSimpleName());
            return Map.of();
        }
    }

    private void recordAudit(Actor actor, String projectId, String traceId, String orchestratorTraceId,
            String question, RagQueryResult result, long latencyMs, RagClientException failure) {
        try {
            auditLog.record(new RagQueryAuditLog.Entry(
                    null,
                    actor == null ? "anonymous" : actor.actorId(),
                    actor == null ? null : actor.role(),
                    projectId,
                    traceId,
                    orchestratorTraceId,
                    queryHash(question),
                    result.answerable(),
                    result.sources() == null ? 0 : result.sources().size(),
                    result.mode() == null ? RagMode.RULES : result.mode(),
                    latencyMs,
                    result.degraded() == null ? null : result.degraded().reason()));
        } catch (RuntimeException exception) {
            // 观测日志失败不得影响业务响应（且绝不落问题原文）
            log.warn("[rag] 问答观测审计写入失败 err={} traceId={}", exception.getClass().getSimpleName(), traceId);
        }
        if (failure != null) {
            log.info("[rag] 问答降级明细 kind={} status={} traceId={}", failure.kind(), failure.upstreamStatus(),
                    traceId);
        }
    }

    /** 问题 hash（SHA-256 前 12 位十六进制，与编排层 query_hash 同口径；日志/审计不落原文）。 */
    static String queryHash(String question) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((question == null ? "" : question).getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(12);
            for (int index = 0; index < 6; index++) {
                builder.append(String.format("%02x", hash[index]));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            return "";
        }
    }

    // ==================================================================
    // GET /api/v1/rag/health
    // ==================================================================

    /**
     * RAG 编排健康与模型信息（契约 {@code ragHealth}，仅 it/admin）。
     *
     * <p>聚合编排层 {@code GET /internal/rag/health} + Java 侧配置（enabled/timeout/token 是否配置）；
     * 编排层不可用 → <b>仍 200</b> + {@code status=degraded}（components 全部 down），不泄露 token 与内网地址。
     */
    @GetMapping("/api/v1/rag/health")
    public ResponseEntity<ApiResponse<RagHealth>> health(HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        RagHealth health;
        try {
            health = RagMapper.healthReachable(ragClient.health(traceId), ragProperties);
        } catch (RagClientException exception) {
            log.warn("[rag] health 降级 kind={} traceId={}", exception.kind(), traceId);
            health = RagMapper.healthUnreachable(exception, ragProperties);
        }
        if (!health.degraded()) {
            return ResponseEntity.ok(ApiResponse.ok(health, traceId));
        }
        DegradedInfo degraded = new DegradedInfo(
                "rag_degraded：RAG 链路存在降级组件（status=" + health.status() + "，详见 components）",
                DegradedFallback.NONE, true, ErrorCode.E_5001, null);
        return ResponseEntity.ok(ApiResponse.okDegraded(health, degraded, traceId));
    }

    // ==================================================================
    // POST /api/v1/knowledge/{knowledgeId}/reindex
    // ==================================================================

    /**
     * 重建知识索引（契约 {@code knowledgeReindex}，仅 it/admin，写操作 + 审计 {@code knowledge.reindex}）。
     *
     * <p>前置校验顺序（先存在、后状态，避免用 409 暴露 draft/rejected 知识的存在性口径混乱）：
     * 知识不存在 → 404 {@code E-3001}；非 {@code published} → 409 {@code E-3002}
     * （{@code details.reason=invalid_transition}）；编排层不可用 → 503 {@code E-5001} + {@code degraded}。
     */
    @PostMapping("/api/v1/knowledge/{knowledgeId}/reindex")
    public ResponseEntity<ApiResponse<RagReindexResult>> reindex(@PathVariable String knowledgeId,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        String normalizedId = knowledgeId == null ? "" : knowledgeId.trim();
        if (normalizedId.isEmpty()) {
            throw DomainException.badRequest("knowledgeId 必填", Map.of("field", "knowledgeId", "required", true));
        }
        KnowledgeService.View view = knowledgeService.view(normalizedId)
                .orElseThrow(() -> DomainException.notFound(ErrorCode.E_3001,
                        "知识不存在或不可见：" + normalizedId, Map.of("knowledgeId", normalizedId)));
        if (view.status() != KnowledgeItemStatus.PUBLISHED) {
            throw DomainException.conflict("仅 published 知识允许重建索引（当前 status="
                    + view.status().value() + "）", Map.of(
                            "reason", "invalid_transition",
                            "knowledgeId", view.knowledgeId(),
                            "status", view.status().value()));
        }
        try {
            RagClient.InternalReindex internal = ragClient.reindex(view.knowledgeId(), traceId);
            RagReindexResult result = new RagReindexResult(
                    internal.knowledgeId() == null ? view.knowledgeId() : internal.knowledgeId(),
                    internal.chunks(),
                    internal.indexedAt() == null ? TicketTimes.nowIso() : internal.indexedAt());
            log.info("[rag] reindex ok knowledgeId={} chunks={} traceId={}", result.knowledgeId(), result.chunks(),
                    traceId);
            return ResponseEntity.ok(ApiResponse.ok(result, traceId));
        } catch (RagClientException exception) {
            log.warn("[rag] reindex 不可用 kind={} knowledgeId={} traceId={}", exception.kind(),
                    view.knowledgeId(), traceId);
            DegradedInfo degraded = new DegradedInfo(
                    "rag_unavailable：" + exception.kind().label() + "（" + exception.getMessage()
                            + "），索引未重建，请稍后重试或联系运维检查编排层",
                    DegradedFallback.HUMAN, exception.retryable(), ErrorCode.E_5001, null);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("reason", "rag_unavailable");
            details.put("knowledgeId", view.knowledgeId());
            details.put("kind", exception.kind().name().toLowerCase(java.util.Locale.ROOT));
            throw DomainException.upstream(ErrorCode.E_5001,
                    "RAG 编排层不可用，索引未重建（已降级记录）", details, degraded, exception.retryable());
        }
    }
}
