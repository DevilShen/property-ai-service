package com.property.ai.bff.dto;

/**
 * OpenAPI: KnowledgeImportWarning（P6 W34 / 契约 v1.5.0 增量）。
 *
 * <p>导入警告：<b>警告不阻断导入</b>，仅提示。{@code code} 当前口径：
 * {@code pii_masked}（检测到 PII 已自动掩码）/ {@code duplicate_suspected}（疑似重复，见 duplicateOf）/
 * {@code truncated}（超长截断提示）/ {@code empty_text}（解析后空文本，任务最终 failed）/
 * {@code injection_risk}（疑似 Prompt 注入，内容仍按不可信证据处理）；未知 code 前端按通用警告展示。
 */
public record KnowledgeImportWarning(
        /** 警告码 */
        String code,
        /** 人话提示（不含明文敏感值） */
        String message) {
}
