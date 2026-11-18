package com.property.ai.bff.dto;

/**
 * OpenAPI: KnowledgeImportError（P6 W34 / 契约 v1.5.0 增量）。
 *
 * <p>导入失败原因（仅 {@code status=failed} 时返回）。{@code code} 必须取 17 类 {@link ErrorCode}
 * （<b>不新造字符串</b>）；{@code message} 为给业务人员看的人话；{@code retryable=true} 时可复用同一任务重试
 * （如编排层 503 {@code E-5002}），{@code false} 时需换输入（如加密 PDF {@code E-5001}）。
 */
public record KnowledgeImportError(
        /** 17 类统一错误码 */
        ErrorCode code,
        /** 给用户看的人话原因（不含敏感值） */
        String message,
        /** 是否可重试 */
        boolean retryable) {
}
