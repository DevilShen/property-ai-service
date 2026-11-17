package com.property.ai.audit;

import java.io.IOException;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.property.ai.bff.api.ticket.ApiSupport;
import com.property.ai.bff.dto.Actor;
import com.property.ai.bff.dto.ActorRole;
import com.property.ai.bff.dto.AuditResult;
import com.property.ai.security.ActorContext;
import com.property.ai.security.PermissionMatrix;
import com.property.ai.security.PermissionMatrix.EndpointRule;
import com.property.ai.security.SecurityProperties;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 写操作审计过滤器（P2-031，契约 v0.3.3 §8）。
 *
 * <p>为什么放在统一 Filter 而不是散落到各 Controller：P2-030/031 的写审计口径（动作/资源类型/
 * 脱敏字段/结果判定）必须与端点策略同源，统一由 {@link PermissionMatrix} 的
 * {@link EndpointRule#auditAction()} 声明；这样新增端点只需在矩阵登记，避免 Controller 各写一套
 * 「落审计」逻辑（也避免跨 write_scope 改动 W5—W7 已有 Controller）。
 *
 * <h2>记录范围（契约 §8「写操作必须落审计」）</h2>
 * {@code ticket.create} / {@code ticket.transition} / {@code ticket.feedback} /
 * {@code mapping.publish} / {@code mapping.rollback} / {@code connector.probe} / {@code recon.run} /
 * {@code owner.verify}；登录/登出由 {@code AuthController} 显式落审计（需要成功/失败与账号口径），
 * 403 拒绝由 {@link com.property.ai.security.SecurityFilter} 落 {@code access.deny}。
 *
 * <h2>字段来源</h2>
 * actor/角色取 {@link ActorContext}（鉴权过滤器已写入）；result 由 HTTP 状态 + 响应 {@code ok} 判定
 * （2xx 且 ok≠false → success；401/403 → denied；其余 → failed）；resourceId 优先取响应 data 里的
 * 业务主键（ticketNo/runId/connectorId/id/roomCode），缺失时取路径动态段；不记录请求/响应原文。
 */
public class AuditFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AuditFilter.class);

    /**
     * 响应 data 里可作为 resourceId 的业务主键（按优先级）。
     *
     * <p>P4 W16 追加 {@code conversationId}：坐席接管/质检评分的契约资源类型是 {@code conversation}，
     * resourceId 取会话 ID（既有响应不含该字段，故不改变 W5—P4 W15 端点行为）。
     */
    private static final String[] RESOURCE_ID_FIELDS =
            { "ticketNo", "runId", "connectorId", "probeId", "conversationId", "id", "roomCode", "eventId" };

    private final SecurityProperties properties;
    private final AuditService auditService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AuditFilter(SecurityProperties properties, AuditService auditService) {
        this.properties = properties;
        this.auditService = auditService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!properties.isEnabled()) {
            chain.doFilter(request, response);
            return;
        }
        String method = request.getMethod();
        String path = PermissionMatrix.normalizePath(request.getRequestURI());
        Optional<EndpointRule> rule = PermissionMatrix.ruleFor(method, path);
        if (rule.isEmpty() || rule.get().auditAction() == null) {
            chain.doFilter(request, response);
            return;
        }
        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(request, wrapper);
        } finally {
            try {
                recordAudit(request, path, rule.get(), wrapper);
            } catch (RuntimeException ex) {
                // 审计失败不得影响业务响应（只记 warn，不落请求/响应原文、不落 token）
                log.warn("[audit] 写审计落库失败 path={} err={}", path, ex.getClass().getSimpleName());
            }
            try {
                wrapper.copyBodyToResponse();
            } catch (IOException ex) {
                log.warn("[audit] 响应回写失败 path={} err={}", path, ex.getClass().getSimpleName());
            }
        }
    }

    private void recordAudit(HttpServletRequest request, String path, EndpointRule rule,
            ContentCachingResponseWrapper wrapper) {
        JsonNode root = parseBody(wrapper.getContentAsByteArray());
        AuditResult result = classify(wrapper.getStatus(), root);
        String resourceId = resolveResourceId(root, rule, path);
        ActorContext.Principal principal = ActorContext.current().orElse(null);
        Actor actor = principal == null ? null : principal.actor();
        String actorId = actor == null ? "anonymous" : actor.actorId();
        // 审计 schema 的 actorRole 必填且枚举只有 6 个角色（无 anonymous）；
        // 理论上写审计一定带主体（鉴权过滤器已放行），无主体时占位 owner 仅防御性兜底。
        ActorRole actorRole = actor == null ? ActorRole.OWNER : actor.role();
        auditService.record(new AuditService.AuditRecord(
                actorId,
                actorRole,
                rule.auditAction(),
                rule.resourceType(),
                resourceId,
                ApiSupport.traceId(request),
                result,
                AuditIp.sanitize(request.getRemoteAddr()),
                rule.maskedFields()));
    }

    private JsonNode parseBody(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            return objectMapper.readTree(body);
        } catch (IOException ex) {
            return null;
        }
    }

    private static AuditResult classify(int status, JsonNode root) {
        boolean bodyOk = root == null || root.path("ok").asBoolean(true);
        if (status >= 200 && status < 300 && bodyOk) {
            return AuditResult.SUCCESS;
        }
        if (status == HttpServletResponse.SC_UNAUTHORIZED || status == HttpServletResponse.SC_FORBIDDEN) {
            return AuditResult.DENIED;
        }
        return AuditResult.FAILED;
    }

    private static String resolveResourceId(JsonNode root, EndpointRule rule, String path) {
        if (root != null) {
            JsonNode data = root.path("data");
            for (String field : RESOURCE_ID_FIELDS) {
                JsonNode value = data.path(field);
                if (value.isTextual() && !value.asText().isBlank()) {
                    return value.asText();
                }
            }
        }
        String fromPath = rule.extractResourceId(path);
        return fromPath == null ? "" : fromPath;
    }
}
