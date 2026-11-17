package com.property.ai.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.property.ai.bff.dto.ActorRole;

/**
 * 演示 JWT 服务（P2 W8，契约 v0.3.3 §8）：HS256 + 本地演示密钥 + 进程内撤销表。
 *
 * <h2>实现选型（禁止新增 Maven 依赖）</h2>
 * 本仓库 Java 端只有 {@code spring-boot-starter-web/validation/actuator}，<b>没有</b>
 * spring-security / jjwt 依赖；按任务约束不新增 Maven 依赖，因此用 JDK 自带
 * {@link javax.crypto.Mac}（{@code HmacSHA256}）+ {@link java.util.Base64}（URL encoder, 无 padding）
 * + Jackson（已随 starter-web 存在）手写 JWS compact 序列化：
 *
 * <pre>
 * token = base64url(header{alg:HS256,typ:JWT}) + "." + base64url(claims) + "." + base64url(HMAC-SHA256)
 * claims = { sub, role, name, projectId?, iat, exp, jti, mock:true }
 * </pre>
 *
 * <h2>安全说明（demo 边界，不得说成生产级）</h2>
 * <ul>
 *   <li>密钥是仓库内公开演示值（{@code property.security.jwt-secret} 可配），<b>不具备生产安全性</b>；
 *       生产应走 KMS/环境变量 + 密钥轮换（P5 范围）。</li>
 *   <li>签名比较用 {@link MessageDigest#isEqual}（常量时间），防时序侧信道。</li>
 *   <li>撤销表为进程内 {@code ConcurrentHashMap.newKeySet()}：登出写 jti、重启清空（demo 口径；
 *       与 P2「审计/登录态进程内存储」一致，P5 迁 Redis）。</li>
 *   <li>无 refresh token / 无滑动续期 / 无密钥轮换 / 无速率限制（见任务报告遗留风险）。</li>
 *   <li>日志一律不打印 token 明文（只有 actorId/jti 可记）。</li>
 * </ul>
 */
public class JwtService {

    /** JWS header（固定 HS256；不接受调用方指定 alg，避免 alg=none/降级攻击面） */
    private static final byte[] HEADER_JSON =
            "{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8);

    private static final Base64.Encoder BASE64_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_DECODER = Base64.getUrlDecoder();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 撤销表容量上限（演示：超过后整体清空，避免无界增长；生产必须有 TTL 存储） */
    private static final int MAX_REVOKED = 10_000;

    private final byte[] secret;
    private final long ttlSeconds;
    private final Clock clock;
    private final Set<String> revokedJti = ConcurrentHashMap.newKeySet();

    /**
     * @param jwtSecret   HS256 演示密钥（UTF-8 字节；空/过短视为配置错误）
     * @param ttlSeconds  token 有效期（秒），契约默认 7200
     * @param clock       时钟（测试可注入固定时钟，验证过期）
     */
    public JwtService(String jwtSecret, long ttlSeconds, Clock clock) {
        if (jwtSecret == null || jwtSecret.length() < 16) {
            throw new IllegalArgumentException("property.security.jwt-secret 长度不足（演示至少 16 字符）");
        }
        if (ttlSeconds <= 0) {
            throw new IllegalArgumentException("property.security.token-ttl-seconds 必须为正数");
        }
        this.secret = jwtSecret.getBytes(StandardCharsets.UTF_8);
        this.ttlSeconds = ttlSeconds;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /** 为演示账号签发 token（sub=actorId，含 role/name/projectId/iat/exp/jti/mock）。 */
    public IssuedToken issue(DemoAccount account) {
        Instant now = clock.instant();
        Instant expiresAt = now.plusSeconds(ttlSeconds);
        String jti = UUID.randomUUID().toString();
        ObjectNode claims = MAPPER.createObjectNode();
        claims.put("sub", account.actorId());
        claims.put("role", account.role().value());
        claims.put("name", account.name());
        if (account.projectId() != null && !account.projectId().isBlank()) {
            claims.put("projectId", account.projectId());
        }
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", expiresAt.getEpochSecond());
        claims.put("jti", jti);
        claims.put("mock", true);

        String signingInput = BASE64_ENCODER.encodeToString(HEADER_JSON) + '.'
                + BASE64_ENCODER.encodeToString(writeJson(claims));
        String token = signingInput + '.' + BASE64_ENCODER.encodeToString(sign(signingInput));
        return new IssuedToken(token, jti, expiresAt);
    }

    /**
     * 校验签名与有效期并回读演示主体。
     *
     * <p>不校验撤销表（由调用方用 {@link #isRevoked(String)} 判断，便于登出幂等：已注销 token
     * 再次登出仍返回 200，而访问其它端点返回 401 {@code expired_token}）。
     *
     * @throws JwtException 格式非法/签名不符/已过期（异常信息不含 token 明文）
     */
    public JwtPayload verify(String token) {
        if (token == null || token.isBlank()) {
            throw new JwtException(JwtFailure.MALFORMED, "token 为空");
        }
        String[] parts = token.trim().split("\\.", -1);
        if (parts.length != 3) {
            throw new JwtException(JwtFailure.MALFORMED, "token 不是 JWS compact 三段格式");
        }
        String signingInput = parts[0] + '.' + parts[1];
        byte[] actualSignature;
        JsonNode claims;
        try {
            actualSignature = BASE64_DECODER.decode(parts[2]);
            claims = MAPPER.readTree(BASE64_DECODER.decode(parts[1]));
        } catch (IllegalArgumentException | java.io.IOException ex) {
            throw new JwtException(JwtFailure.MALFORMED, "token 不是合法 base64url/JSON");
        }
        byte[] expectedSignature = sign(signingInput);
        if (!MessageDigest.isEqual(expectedSignature, actualSignature)) {
            throw new JwtException(JwtFailure.INVALID_SIGNATURE, "token 签名不符（伪造或密钥不匹配）");
        }
        JsonNode header = readHeader(parts[0]);
        if (header == null || !"HS256".equals(header.path("alg").asText(null))) {
            throw new JwtException(JwtFailure.INVALID_SIGNATURE, "token header 不是 HS256");
        }
        String actorId = claims.path("sub").asText(null);
        String roleValue = claims.path("role").asText(null);
        String jti = claims.path("jti").asText(null);
        long exp = claims.path("exp").asLong(0L);
        long iat = claims.path("iat").asLong(0L);
        if (actorId == null || roleValue == null || jti == null || exp <= 0L) {
            throw new JwtException(JwtFailure.MALFORMED, "token 缺少 sub/role/exp/jti");
        }
        ActorRole role;
        try {
            role = ActorRole.fromValue(roleValue);
        } catch (IllegalArgumentException ex) {
            throw new JwtException(JwtFailure.MALFORMED, "token role 不是契约枚举值");
        }
        Instant now = clock.instant();
        Instant expiresAt = Instant.ofEpochSecond(exp);
        if (!now.isBefore(expiresAt)) {
            throw new JwtException(JwtFailure.EXPIRED, "token 已过期");
        }
        String name = claims.path("name").asText("");
        String projectId = claims.path("projectId").isMissingNode() || claims.path("projectId").isNull()
                ? null
                : claims.path("projectId").asText(null);
        return new JwtPayload(actorId, role, name, projectId,
                iat <= 0L ? null : Instant.ofEpochSecond(iat), expiresAt, jti);
    }

    /** 注销 token（登出）：jti 进本地撤销表；重复调用幂等。 */
    public void revoke(String jti) {
        if (jti == null || jti.isBlank()) {
            return;
        }
        if (revokedJti.size() >= MAX_REVOKED) {
            // 演示实现：超上限整体清空（生产必须用带 TTL 的存储，不得这样清空）
            revokedJti.clear();
        }
        revokedJti.add(jti);
    }

    /** token 是否已注销（登出后）。 */
    public boolean isRevoked(String jti) {
        return jti != null && revokedJti.contains(jti);
    }

    /** 当前撤销表大小（仅测试/诊断用）。 */
    public int revokedCount() {
        return revokedJti.size();
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private byte[] sign(String signingInput) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII));
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("HS256 签名失败（JDK 缺少 HmacSHA256？）", ex);
        }
    }

    private static byte[] writeJson(ObjectNode node) {
        try {
            return MAPPER.writeValueAsBytes(node);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("token claims 序列化失败", ex);
        }
    }

    private static JsonNode readHeader(String encodedHeader) {
        try {
            return MAPPER.readTree(BASE64_DECODER.decode(encodedHeader));
        } catch (IllegalArgumentException | java.io.IOException ex) {
            return null;
        }
    }

    /** token 签发结果（token 明文只回登录响应，不进日志）。 */
    public record IssuedToken(String token, String jti, Instant expiresAt) {
    }

    /** 校验后的 token 主体（对应契约 {@code Actor} 字段 + iat/exp/jti）。 */
    public record JwtPayload(String actorId, ActorRole role, String name, String projectId,
            Instant issuedAt, Instant expiresAt, String jti) {

        public com.property.ai.bff.dto.Actor toActor() {
            return new com.property.ai.bff.dto.Actor(actorId, role, name, projectId, true);
        }
    }

    /** 校验失败类型（映射 401 的 {@code details.reason}）。 */
    public enum JwtFailure {
        MALFORMED,
        INVALID_SIGNATURE,
        EXPIRED
    }

    /** token 校验异常（不携带 token 明文，避免异常栈泄漏凭证）。 */
    public static class JwtException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final JwtFailure failure;

        public JwtException(JwtFailure failure, String message) {
            super(message);
            this.failure = failure;
        }

        public JwtFailure failure() {
            return failure;
        }
    }
}
