package com.property.ai.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;

import com.property.ai.bff.dto.ActorRole;

/**
 * 6 个演示账号登记表（P2 W8 demo，契约 v0.3.3 / README §2.1 / shared {@code DEMO_ACCOUNTS}）。
 *
 * <p><b>demo 口径（AGENTS.md NEVER #11）</b>：这些是演示账号 + 固定口令，不是真实身份体系
 * （不接 SSO/微信/短信，不含真实手机号/工号），不得把 demo 登录说成真实鉴权/生产账号。
 *
 * <p>顺序与 shared {@code DEMO_ACCOUNTS}、OpenAPI {@code ActorRole} 严格一致：
 * owner → steward → agent → supervisor → it → admin（it/admin 为平台级，{@code projectId} 缺省）。
 */
public final class DemoAccounts {

    /** 演示统一口令（shared {@code DEMO_PASSWORD}）；不是真实口令策略/多因子 */
    public static final String DEMO_PASSWORD = "demo123";

    /** 演示 token 有效期（秒），与 shared/OpenAPI {@code AUTH_TOKEN_TTL_SECONDS} 同值 */
    public static final long AUTH_TOKEN_TTL_SECONDS = 7200L;

    /** 6 个演示账号（与 shared {@code DEMO_ACCOUNTS} 逐字一致；不得含真实手机号/工号） */
    public static final List<DemoAccount> ACCOUNTS = List.of(
            new DemoAccount("owner-3-2-1802", "owner-3-2-1802", ActorRole.OWNER, "王女士", "P-YQY"),
            new DemoAccount("steward-01", "steward-01", ActorRole.STEWARD, "李管家", "P-YQY"),
            new DemoAccount("agent-01", "agent-01", ActorRole.AGENT, "陈坐席", "P-YQY"),
            new DemoAccount("supervisor-01", "supervisor-01", ActorRole.SUPERVISOR, "赵主管", "P-YQY"),
            new DemoAccount("it-01", "it-01", ActorRole.IT, "IT·孙", null),
            new DemoAccount("admin-01", "admin-01", ActorRole.ADMIN, "管理员·周", null));

    private DemoAccounts() {
    }

    /** 按登录账号查演示账号（精确匹配；不存在返回 {@link Optional#empty()}）。 */
    public static Optional<DemoAccount> find(String account) {
        if (account == null || account.isBlank()) {
            return Optional.empty();
        }
        String normalized = account.trim();
        return ACCOUNTS.stream().filter(item -> item.account().equals(normalized)).findFirst();
    }

    /**
     * 演示口令校验（定长常量时间比较，避免时序侧信道）。
     *
     * <p>失败原因不区分「账号不存在 / 口令错误」（防枚举，契约 401 {@code invalid_credentials}）。
     */
    public static boolean passwordMatches(String provided) {
        String candidate = provided == null ? "" : provided;
        boolean matched = MessageDigest.isEqual(
                DEMO_PASSWORD.getBytes(StandardCharsets.UTF_8),
                candidate.getBytes(StandardCharsets.UTF_8));
        // 即使长度不同也已按字节比较；再补一次定长比较，避免长度差异带来的额外分支成本
        return matched & candidate.length() == DEMO_PASSWORD.length();
    }
}
