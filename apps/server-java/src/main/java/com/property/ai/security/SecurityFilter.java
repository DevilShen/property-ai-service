package com.property.ai.security;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.cors.CorsUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.property.ai.audit.AuditIp;
import com.property.ai.audit.AuditService;
import com.property.ai.bff.api.ApiResponse;
import com.property.ai.bff.api.ticket.ApiSupport;
import com.property.ai.bff.dto.Actor;
import com.property.ai.bff.dto.AuditAction;
import com.property.ai.bff.dto.AuditResourceType;
import com.property.ai.bff.dto.AuditResult;
import com.property.ai.bff.dto.ErrorCode;
import com.property.ai.security.PermissionMatrix.EndpointRule;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 演示鉴权过滤器（P2-030，契约 v0.3.3 §8）：Bearer JWT 校验 + 角色 × 能力矩阵。
 *
 * <h2>行为（401/403 复用既有 {@code E-2001}，不新造错误码）</h2>
 * <ul>
 *   <li><b>公开端点（演示豁免）</b>：{@code GET /healthz}、{@code GET /api/v1/meta/screens}、
 *       {@code GET /api/v1/screens/{screenKey}}、{@code POST /api/v1/auth/login} —— 不鉴权；</li>
 *   <li><b>401</b>（{@code E-2001} + {@code details.reason}）：无 token → {@code missing_token}；
 *       伪造/签名不符/格式非法 → {@code invalid_token}；过期或已登出 → {@code expired_token}；</li>
 *   <li><b>403</b>（{@code E-2001} + {@code details{requiredCapability, role, reason=capability_denied}}）：
 *       角色 × 能力矩阵不通过（{@link PermissionMatrix}），并落 {@code access.deny} 审计（result=denied）；</li>
 *   <li>校验通过后写 {@link ActorContext}（ThreadLocal），请求结束 {@code finally} 清理。</li>
 * </ul>
 *
 * <p><b>demo 口径</b>：这是演示身份 + 本地演示 JWT，不是真实 SSO/微信/短信鉴权；无速率限制、
 * 无 refresh token、无密钥轮换（见任务报告遗留风险）。日志不落 token 明文/真实手机号。
 */
public class SecurityFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SecurityFilter.class);

    /** 契约 §8：请求头 {@code Authorization: Bearer <token>} */
    public static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "bearer ";

    private static final String REASON_MISSING_TOKEN = "missing_token";
    private static final String REASON_INVALID_TOKEN = "invalid_token";
    private static final String REASON_EXPIRED_TOKEN = "expired_token";
    private static final String REASON_CAPABILITY_DENIED = "capability_denied";

    private final SecurityProperties properties;
    private final JwtService jwtService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SecurityFilter(SecurityProperties properties, JwtService jwtService, AuditService auditService) {
        this.properties = properties;
        this.jwtService = jwtService;
        this.auditService = auditService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!properties.isEnabled() || CorsUtils.isPreFlightRequest(request)) {
            // property.security.enabled=false：测试/离线演示的显式逃生阀（默认 true，生产必须 true）
            chain.doFilter(request, response);
            return;
        }
        String method = request.getMethod();
        String path = PermissionMatrix.normalizePath(request.getRequestURI());
        Optional<EndpointRule> rule = PermissionMatrix.ruleFor(method, path);
        if (rule.map(EndpointRule::publicEndpoint).orElse(false)) {
            chain.doFilter(request, response);
            return;
        }

        String token = extractBearerToken(request);
        if (token == null) {
            writeUnauthorized(response, request, REASON_MISSING_TOKEN);
            return;
        }

        JwtService.JwtPayload payload;
        try {
            payload = jwtService.verify(token);
        } catch (JwtService.JwtException ex) {
            String reason = ex.failure() == JwtService.JwtFailure.EXPIRED
                    ? REASON_EXPIRED_TOKEN
                    : REASON_INVALID_TOKEN;
            log.info("[auth] token 校验失败 reason={} failure={} path={} traceId={}",
                    reason, ex.failure(), path, ApiSupport.traceId(request));
            writeUnauthorized(response, request, reason);
            return;
        }

        boolean logout = isLogout(method, path);
        if (jwtService.isRevoked(payload.jti())) {
            if (!logout) {
                // 已登出的 token：契约 §8「过期/已注销 → expired_token」；登出端点本身幂等（重复登出 200）
                writeUnauthorized(response, request, REASON_EXPIRED_TOKEN);
                return;
            }
        }

        if (rule.isPresent()) {
            EndpointRule endpoint = rule.get();
            String requiredCapability = endpoint.requiredCapability();
            if (!PermissionMatrix.allows(payload.role(), requiredCapability)) {
                auditCapabilityDenied(request, path, payload, endpoint, requiredCapability);
                writeForbidden(response, request, payload.role(), requiredCapability, endpoint, path);
                return;
            }
        }

        Actor actor = payload.toActor();
        ActorContext.set(new ActorContext.Principal(actor, payload.jti(), payload.expiresAt()));
        try {
            chain.doFilter(request, response);
        } finally {
            ActorContext.clear();
        }
    }

    // ------------------------------------------------------------------
    // 401 / 403 输出（统一信封）
    // ------------------------------------------------------------------

    private void writeUnauthorized(HttpServletResponse response, HttpServletRequest request, String reason)
            throws IOException {
        String traceId = ApiSupport.traceId(request);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", reason);
        ApiResponse<Object> body = ApiResponse.error(ErrorCode.E_2001,
                "未登录或登录已过期，请重新登录", traceId, details, null, false);
        response.setHeader("WWW-Authenticate", "Bearer realm=\"property-ai-demo\"");
        writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, body);
    }

    private void writeForbidden(HttpServletResponse response, HttpServletRequest request,
            com.property.ai.bff.dto.ActorRole role, String requiredCapability, EndpointRule endpoint, String path)
            throws IOException {
        String traceId = ApiSupport.traceId(request);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("requiredCapability", requiredCapability == null ? "" : requiredCapability);
        details.put("role", role == null ? "" : role.value());
        details.put("reason", REASON_CAPABILITY_DENIED);
        ApiResponse<Object> body = ApiResponse.error(ErrorCode.E_2001,
                "当前角色无该操作权限", traceId, details, null, false);
        log.info("[auth] 能力拒绝 role={} requiredCapability={} path={} traceId={}",
                role, requiredCapability, path, traceId);
        writeJson(response, HttpServletResponse.SC_FORBIDDEN, body);
    }

    private void writeJson(HttpServletResponse response, int status, ApiResponse<?> body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(body));
        response.getWriter().flush();
    }

    // ------------------------------------------------------------------
    // 审计：403 拒绝（契约 §8「越权拒绝落 access.deny」）
    // ------------------------------------------------------------------

    private void auditCapabilityDenied(HttpServletRequest request, String path, JwtService.JwtPayload payload,
            EndpointRule endpoint, String requiredCapability) {
        try {
            AuditResourceType resourceType = endpoint.resourceType() == null
                    ? AuditResourceType.AUTH
                    : endpoint.resourceType();
            String resourceId = Optional.ofNullable(endpoint.extractResourceId(path)).orElse("");
            auditService.record(new AuditService.AuditRecord(
                    payload.actorId(),
                    payload.role(),
                    AuditAction.ACCESS_DENY,
                    resourceType,
                    resourceId,
                    ApiSupport.traceId(request),
                    AuditResult.DENIED,
                    AuditIp.sanitize(request.getRemoteAddr()),
                    endpoint.maskedFields()));
        } catch (RuntimeException ex) {
            // 审计失败不得影响拒绝响应（只记 warn，不落 token/请求体）
            log.warn("[auth] access.deny 审计写入失败 path={} err={}", path, ex.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 解析 {@code Authorization: Bearer <token>}；缺失/非 Bearer 返回 {@code null}。 */
    private static String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader(AUTHORIZATION_HEADER);
        if (header == null || header.isBlank()) {
            return null;
        }
        String value = header.trim();
        if (value.length() <= BEARER_PREFIX.length()
                || !value.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        String token = value.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    private static boolean isLogout(String method, String path) {
        return "POST".equalsIgnoreCase(method) && "/api/v1/auth/logout".equals(path);
    }
}
