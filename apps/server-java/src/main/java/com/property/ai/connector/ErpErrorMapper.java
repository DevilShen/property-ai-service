package com.property.ai.connector;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import com.property.ai.bff.dto.ErrorCode;

/**
 * ERP 错误 → 契约 17 类错误码的纯函数映射（无 IO，便于单测）。
 *
 * <p>映射优先级（设计 §24.4「错误码翻译」）：
 * <ol>
 *   <li>上游信封里已是 17 类错误码（mock-erp 按契约返回 {@code error:"E-xxxx"}）→ 直接沿用；</li>
 *   <li>否则按 HTTP 状态兜底：400→E-1001、401/403→E-2003、404→E-3001、409→E-3002、
 *       429→E-4003、408/504→E-4001、5xx→E-4002；</li>
 *   <li>超时/网络不可达由调用侧显式给 E-4001；响应无法解析（映射失败）给 E-5002。</li>
 * </ol>
 *
 * <p>可重试口径（契约 §4 / 设计 §3.4）：E-4001/E-4002/E-4003 对**读**可重试 1 次；
 * 写操作无论何种错误都不自动重试（由客户端复用幂等键）。
 */
public final class ErpErrorMapper {

    private static final Pattern CODE_PATTERN = Pattern.compile("^E-\\d{4}$");

    private ErpErrorMapper() {
    }

    /** 解析上游错误码字符串：只接受 17 类已知码，其他返回 null（交给 HTTP 状态兜底） */
    public static ErrorCode knownCode(String upstreamCode) {
        if (upstreamCode == null) {
            return null;
        }
        String trimmed = upstreamCode.trim().toUpperCase();
        if (!CODE_PATTERN.matcher(trimmed).matches()) {
            return null;
        }
        try {
            return ErrorCode.fromValue(trimmed);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** HTTP 状态 → 契约错误码（上游没给可用错误码时的兜底） */
    public static ErrorCode byHttpStatus(int status) {
        return switch (status) {
            case 400, 422 -> ErrorCode.E_1001;
            case 401, 403 -> ErrorCode.E_2003;
            case 404 -> ErrorCode.E_3001;
            case 409 -> ErrorCode.E_3002;
            case 408, 504 -> ErrorCode.E_4001;
            case 429 -> ErrorCode.E_4003;
            default -> status >= 500 ? ErrorCode.E_4002 : ErrorCode.E_1001;
        };
    }

    /** 错误码是否对读操作可重试 */
    public static boolean retryable(ErrorCode code) {
        return code == ErrorCode.E_4001 || code == ErrorCode.E_4002 || code == ErrorCode.E_4003;
    }

    /** 超时（读可重试 1 次） */
    public static ConnectorException timeout(Capability capability, long timeoutMs) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("capability", capability.label());
        details.put("timeoutMs", timeoutMs);
        return new ConnectorException(
                ErrorCode.E_4001,
                "ERP 调用超时（" + capability.label() + "，预算 " + timeoutMs + "ms）",
                capability.readOnly(),
                details);
    }

    /** 网络层不可达/连接失败 */
    public static ConnectorException transport(Capability capability, String cause) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("capability", capability.label());
        if (cause != null && !cause.isBlank()) {
            details.put("cause", cause);
        }
        return new ConnectorException(
                ErrorCode.E_4001,
                "ERP 不可达（" + capability.label() + "）",
                capability.readOnly(),
                details);
    }

    /** 上游返回非 2xx 或 2xx 但 ok=false */
    public static ConnectorException upstream(Capability capability, int status, String upstreamCode,
            String upstreamMessage) {
        ErrorCode code = knownCode(upstreamCode);
        if (code == null) {
            code = byHttpStatus(status);
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("capability", capability.label());
        details.put("upstreamStatus", status);
        if (upstreamCode != null && !upstreamCode.isBlank()) {
            details.put("upstreamCode", upstreamCode);
        }
        String message = (upstreamMessage == null || upstreamMessage.isBlank())
                ? defaultMessage(code)
                : upstreamMessage;
        return new ConnectorException(code, message, capability.readOnly() && retryable(code), details);
    }

    /** 上游响应无法解析/字段映射失败（设计：响应映射失败 → E-5002，只答已确认部分并转人工） */
    public static ConnectorException mapping(Capability capability, String message) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("capability", capability.label());
        return new ConnectorException(ErrorCode.E_5002, message, false, details);
    }

    /** 能力未开通/连接器未启用（设计：能力缺失 → E-5001，隐藏入口） */
    public static ConnectorException unsupported(Capability capability) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("capability", capability.label());
        return new ConnectorException(ErrorCode.E_5001, "连接器未开通能力：" + capability.label(), false, details);
    }

    /** 熔断打开（快速失败，BFF 走降级阶梯） */
    public static ConnectorException circuitOpen(Capability capability) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("capability", capability.label());
        details.put("reason", "circuit_open");
        return new ConnectorException(
                ErrorCode.E_4001,
                "ERP 连接器熔断中（" + capability.label() + "），已进入降级",
                true,
                details);
    }

    private static String defaultMessage(ErrorCode code) {
        return switch (code) {
            case E_4001 -> "ERP 响应超时，已进入降级";
            case E_4002 -> "ERP 上游错误，已进入降级";
            case E_4003 -> "ERP 限流，请稍后重试";
            case E_3001 -> "ERP 无该记录";
            case E_3002 -> "ERP 状态不允许该操作";
            case E_3003 -> "幂等冲突（同键不同载荷）";
            case E_1001 -> "ERP 参数校验失败";
            case E_1002 -> "ERP 字典未匹配";
            default -> "ERP 调用失败";
        };
    }
}
