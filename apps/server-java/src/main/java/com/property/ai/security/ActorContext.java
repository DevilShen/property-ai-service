package com.property.ai.security;

import java.time.Instant;
import java.util.Optional;

import com.property.ai.bff.dto.Actor;

/**
 * 请求级主体上下文（ThreadLocal，P2 W8）。
 *
 * <p>由 {@link SecurityFilter} 校验 Bearer token 后写入，请求结束（{@code finally}）清理，
 * 避免线程池复用导致主体串味。Controller 层通过 {@link #current()} 读取当前演示主体
 * （登录态 {@code GET /api/v1/auth/me}、登出撤销 jti）；<b>不要</b>在业务代码里手写角色判断，
 * 能力矩阵统一由 {@link PermissionMatrix} 判定。
 *
 * <p><b>demo 口径</b>：这是演示身份上下文，不是真实鉴权会话；不得据此宣称生产级鉴权。
 */
public final class ActorContext {

    private static final ThreadLocal<Principal> CURRENT = new ThreadLocal<>();

    private ActorContext() {
    }

    /** 写入当前请求主体；{@code null} 视为清理。 */
    public static void set(Principal principal) {
        if (principal == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(principal);
        }
    }

    /** 当前请求主体（未鉴权/公开端点为空）。 */
    public static Optional<Principal> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** 当前演示主体；未鉴权返回 {@code null}。 */
    public static Actor actorOrNull() {
        Principal principal = CURRENT.get();
        return principal == null ? null : principal.actor();
    }

    /** 请求结束清理（{@code finally} 必调，防线程复用串味）。 */
    public static void clear() {
        CURRENT.remove();
    }

    /**
     * 请求级主体：契约 {@code Actor} + token 元信息（jti 用于登出撤销、expiresAt 仅诊断）。
     */
    public record Principal(Actor actor, String jti, Instant expiresAt) {
    }
}
