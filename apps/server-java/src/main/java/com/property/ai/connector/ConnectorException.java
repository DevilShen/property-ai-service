package com.property.ai.connector;

import java.util.LinkedHashMap;
import java.util.Map;

import com.property.ai.bff.dto.ErrorCode;

/**
 * 连接器异常：统一携带 17 类错误码（契约 §ErrorCode），供 BFF 映射统一信封。
 *
 * <p>约定：
 * <ul>
 *   <li>{@code code} 只允许 {@link ErrorCode} 17 类，不新造字符串；</li>
 *   <li>{@code retryable} 遵循设计 §3.4：E-4001/E-4002/E-4003 对读可重试，写操作永不自动重试；</li>
 *   <li>{@code details} 仅放非敏感结构化补充（上游 HTTP 状态、能力编码），不含手机号/房号明细以外的隐私。</li>
 * </ul>
 */
public class ConnectorException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode code;
    private final boolean retryable;
    private final Map<String, Object> details;

    public ConnectorException(ErrorCode code, String message, boolean retryable) {
        this(code, message, retryable, Map.of());
    }

    public ConnectorException(ErrorCode code, String message, boolean retryable, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.retryable = retryable;
        this.details = details == null ? Map.of() : new LinkedHashMap<>(details);
    }

    public ErrorCode code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }

    public Map<String, Object> details() {
        return Map.copyOf(details);
    }

    public ConnectorException withDetail(String key, Object value) {
        Map<String, Object> merged = new LinkedHashMap<>(details);
        merged.put(key, value);
        return new ConnectorException(code, getMessage(), retryable, merged);
    }
}
