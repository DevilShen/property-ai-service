package com.property.ai.audit;

/**
 * 审计客户端 IP 脱敏（P2-031）：只保留回环/内网演示值，其余一律落 {@code masked}。
 *
 * <p>约束（任务卡 / AGENTS.md 隐私红线）：审计 ip 只存回环/内网演示值，不落真实公网 IP。
 * P2 全部为本地演示环境（127.0.0.1 / 内网段），接真实网关后由网关注入可信来源（P5）。
 */
public final class AuditIp {

    /** 无法识别/非回环内网时的占位值 */
    public static final String MASKED = "masked";
    /** 本机回环标准值（契约示例 127.0.0.1） */
    public static final String LOOPBACK = "127.0.0.1";

    private AuditIp() {
    }

    /** 归一客户端地址：回环/内网原样返回，其余脱敏为 {@code masked}。 */
    public static String sanitize(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.isBlank()) {
            return MASKED;
        }
        String value = remoteAddr.trim();
        if ("::1".equals(value) || "0:0:0:0:0:0:0:1".equals(value) || "localhost".equalsIgnoreCase(value)) {
            return LOOPBACK;
        }
        if (value.startsWith("127.")) {
            return value;
        }
        if (value.startsWith("10.") || value.startsWith("192.168.")) {
            return value;
        }
        if (value.startsWith("172.")) {
            String[] parts = value.split("\\.");
            if (parts.length >= 2) {
                try {
                    int second = Integer.parseInt(parts[1]);
                    if (second >= 16 && second <= 31) {
                        return value;
                    }
                } catch (NumberFormatException ignored) {
                    return MASKED;
                }
            }
        }
        return MASKED;
    }
}
