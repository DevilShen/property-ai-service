package com.property.ai.security;

import java.util.Optional;

import org.springframework.stereotype.Service;

import com.property.ai.bff.dto.LoginResult;
import com.property.ai.domain.ticket.TicketTimes;

/**
 * 演示登录服务（P2-029，契约 v0.3.3 {@code POST /api/v1/auth/login}）。
 *
 * <p><b>demo 口径</b>：演示账号 + 固定口令 {@code demo123} + 本地演示 JWT（HS256）；
 * 不是真实 SSO/微信/短信鉴权，不含真实手机号/工号。生产替换为真实身份体系（P5 范围）。
 *
 * <p>防枚举：账号不存在与口令错误都返回 401 {@code E-2001} + {@code details.reason=invalid_credentials}，
 * 响应文案完全一致（由 {@code AuthController} 统一构造）；本类只负责「查账号 → 校验口令 → 签发 token」。
 */
@Service
public class AuthService {

    private final JwtService jwtService;

    public AuthService(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    /** 按演示账号查找（不存在返回空，不区分"账号不存在"与"口令错误"对外语义）。 */
    public Optional<DemoAccount> find(String account) {
        return DemoAccounts.find(account);
    }

    /** 演示口令校验（定长比较；错误统一由调用方返回 invalid_credentials）。 */
    public boolean passwordMatches(String password) {
        return DemoAccounts.passwordMatches(password);
    }

    /** 签发演示 token + 主体（{@code mock:true} 恒真，tokenType 固定 Bearer）。 */
    public LoginResult login(DemoAccount account) {
        JwtService.IssuedToken issued = jwtService.issue(account);
        return new LoginResult(
                issued.token(),
                "Bearer",
                TicketTimes.iso(issued.expiresAt()),
                account.toActor(),
                true);
    }

    /** 演示登出：当前 token 的 jti 进本地撤销表（重复登出幂等）。 */
    public void logout(String jti) {
        jwtService.revoke(jti);
    }

    /** 撤销状态（登出幂等判断用）。 */
    public boolean isRevoked(String jti) {
        return jwtService.isRevoked(jti);
    }
}
