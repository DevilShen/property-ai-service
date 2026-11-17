package com.property.ai.bff.api.auth;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.property.ai.audit.AuditIp;
import com.property.ai.audit.AuditService;
import com.property.ai.bff.api.ApiResponse;
import com.property.ai.bff.api.ticket.ApiSupport;
import com.property.ai.bff.dto.Actor;
import com.property.ai.bff.dto.ActorRole;
import com.property.ai.bff.dto.AuditAction;
import com.property.ai.bff.dto.AuditResourceType;
import com.property.ai.bff.dto.AuditResult;
import com.property.ai.bff.dto.ErrorCode;
import com.property.ai.bff.dto.LoginRequest;
import com.property.ai.bff.dto.LoginResult;
import com.property.ai.bff.dto.LogoutResult;
import com.property.ai.domain.ticket.DomainException;
import com.property.ai.security.ActorContext;
import com.property.ai.security.AuthService;
import com.property.ai.security.DemoAccount;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 演示登录态 REST（P2-029，契约 v0.3.3）：
 *
 * <pre>
 * POST /api/v1/auth/login   公开（demo 演示豁免）→ LoginResult{token,tokenType,expiresAt,actor,mock}
 * GET  /api/v1/auth/me      需 Bearer → Actor
 * POST /api/v1/auth/logout  需 Bearer → LogoutResult{ok:true}（无状态 + 撤销 jti + 落 auth.logout 审计）
 * </pre>
 *
 * <p><b>demo 口径（AGENTS.md NEVER #11）</b>：6 个演示账号 + 固定口令 {@code demo123} + 本地演示 JWT，
 * 不是真实 SSO/微信/短信鉴权；响应里 {@code mock:true} 恒真，不得说成真实鉴权/生产账号。
 *
 * <p>安全口径：
 * <ul>
 *   <li>账号不存在与口令错误<b>返回完全一致</b>的 401 {@code E-2001} + {@code details.reason=invalid_credentials}（防枚举）；</li>
 *   <li>登录成功/失败均落审计（{@code auth.login}，失败 result=failed）；登出落 {@code auth.logout}；</li>
 *   <li>日志/审计不落 token 明文；ip 只落回环/内网演示值（{@link AuditIp}）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    /** 防枚举统一文案（账号不存在 / 口令错误 / 口令缺省 都用同一句） */
    private static final String INVALID_CREDENTIALS_MESSAGE = "账号或密码不正确";
    private static final String UNAUTHORIZED_MESSAGE = "未登录或登录已过期，请重新登录";

    private final AuthService authService;
    private final AuditService auditService;

    public AuthController(AuthService authService, AuditService auditService) {
        this.authService = authService;
        this.auditService = auditService;
    }

    /** 演示登录（公开端点）：成功 200 + 演示 JWT；账号/口令错误 401 invalid_credentials。 */
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResult>> login(
            @RequestBody(required = false) LoginRequest requestBody, HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        String account = requestBody == null ? null : requestBody.account();
        if (account == null || account.isBlank()) {
            throw DomainException.badRequest("请提供演示账号 account（见 README §2.1）",
                    Map.of("field", "account", "required", true));
        }
        DemoAccount demoAccount = authService.find(account).orElse(null);
        // 账号不存在也走一次定长口令比较，避免通过响应耗时区分账号是否存在（防枚举）
        boolean passwordOk = authService.passwordMatches(requestBody.password());
        if (demoAccount == null || !passwordOk) {
            auditLoginFailure(request, traceId, account.trim(), demoAccount);
            return unauthorizedResponse(traceId, "invalid_credentials", INVALID_CREDENTIALS_MESSAGE);
        }
        LoginResult result = authService.login(demoAccount);
        auditLoginSuccess(request, traceId, demoAccount);
        return ResponseEntity.ok(ApiResponse.ok(result, traceId));
    }

    /** 当前登录主体（需 Bearer；缺失/伪造/过期由 SecurityFilter 统一 401）。 */
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<Actor>> me(HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        Actor actor = ActorContext.actorOrNull();
        if (actor == null) {
            // 防御性：property.security.enabled=false（测试/离线演示）时没有鉴权上下文
            return unauthorizedResponse(traceId, "missing_token", UNAUTHORIZED_MESSAGE);
        }
        return ResponseEntity.ok(ApiResponse.ok(actor, traceId));
    }

    /** 演示登出（需 Bearer）：撤销当前 token 的 jti + 落审计；重复登出幂等 200。 */
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<LogoutResult>> logout(HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        ActorContext.Principal principal = ActorContext.current().orElse(null);
        if (principal == null) {
            return unauthorizedResponse(traceId, "missing_token", UNAUTHORIZED_MESSAGE);
        }
        authService.logout(principal.jti());
        try {
            auditService.record(new AuditService.AuditRecord(
                    principal.actor().actorId(),
                    principal.actor().role(),
                    AuditAction.AUTH_LOGOUT,
                    AuditResourceType.AUTH,
                    principal.actor().actorId(),
                    traceId,
                    AuditResult.SUCCESS,
                    AuditIp.sanitize(request.getRemoteAddr()),
                    null));
        } catch (RuntimeException ignored) {
            // 审计失败不影响登出（幂等 200）；只影响可追溯性，已在过滤器侧统一记 warn
        }
        return ResponseEntity.ok(ApiResponse.ok(new LogoutResult(true), traceId));
    }

    // ------------------------------------------------------------------
    // 401 输出 + 审计
    // ------------------------------------------------------------------

    private static <T> ResponseEntity<ApiResponse<T>> unauthorizedResponse(String traceId, String reason,
            String message) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", reason);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header("WWW-Authenticate", "Bearer realm=\"property-ai-demo\"")
                .body(ApiResponse.error(ErrorCode.E_2001, message, traceId, details, null, false));
    }

    private void auditLoginSuccess(HttpServletRequest request, String traceId, DemoAccount account) {
        auditService.record(new AuditService.AuditRecord(
                account.actorId(),
                account.role(),
                AuditAction.AUTH_LOGIN,
                AuditResourceType.AUTH,
                account.actorId(),
                traceId,
                AuditResult.SUCCESS,
                AuditIp.sanitize(request.getRemoteAddr()),
                null));
    }

    private void auditLoginFailure(HttpServletRequest request, String traceId, String account,
            DemoAccount knownAccount) {
        try {
            // 未知账号不泄漏是否存在：actorId 记 anonymous；actorRole 无 anonymous 枚举值（契约 6 角色），
            // 取最低权限 owner 作匿名占位（仅审计展示，P3 契约增补匿名角色后可替换）。
            String actorId = knownAccount == null ? "anonymous" : knownAccount.actorId();
            ActorRole actorRole = knownAccount == null ? ActorRole.OWNER : knownAccount.role();
            auditService.record(new AuditService.AuditRecord(
                    actorId,
                    actorRole,
                    AuditAction.AUTH_LOGIN,
                    AuditResourceType.AUTH,
                    knownAccount == null ? "anonymous" : knownAccount.actorId(),
                    traceId,
                    AuditResult.FAILED,
                    AuditIp.sanitize(request.getRemoteAddr()),
                    null));
        } catch (RuntimeException ignored) {
            // 审计失败不影响 401 响应
        }
    }
}
