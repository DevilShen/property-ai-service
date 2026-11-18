package com.property.ai.domain.knowledge.importer;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 知识导入限额/开关配置（前缀 {@code property.knowledge.import}，契约 v1.5.0 §7.9 / task-86）。
 *
 * <p>默认值与 {@code packages/shared} 的 {@code KNOWLEDGE_IMPORT_LIMITS} 逐项对齐（服务端强制、
 * 前端只做提示；<b>不是安全边界</b>）：单文件 ≤10MB、粘贴 ≤200,000 字符、ZIP ≤50 文件 / ≤50MB、
 * 导入 ≤10 次/分钟/账号、账号并发任务 ≤2。<b>超限不改契约形状</b>：413 {@code E-1001}
 * （{@code details.reason=payload_too_large}）、429 {@code E-4003}（{@code details.reason=rate_limited}）。
 *
 * <p>测试可直接用 {@code @SpringBootTest(properties=...)} 覆盖（例如限流用例把上限调小），
 * 生产/演示默认值不下调（下调仍符合「≤」语义，但会改变产品行为，需 lead 裁决）。
 */
@Component
@ConfigurationProperties(prefix = "property.knowledge.import")
public class KnowledgeImportProperties {

    /** 单文件上限（字节，默认 10MB） */
    private long maxFileBytes = 10L * 1024L * 1024L;
    /** 粘贴文本上限（字符，默认 200,000） */
    private int maxPasteChars = 200_000;
    /** ZIP 批量文件数上限（默认 50） */
    private int maxZipFiles = 50;
    /** ZIP 解压总量上限（字节，默认 50MB） */
    private long maxZipBytes = 50L * 1024L * 1024L;
    /** 导入频率上限（次/分钟/账号，默认 10） */
    private int maxPerMinute = 10;
    /** 账号并发任务上限（在途任务：pending/parsing/committing/indexing，默认 2） */
    private int maxConcurrentJobs = 2;
    /**
     * 直审开关（默认 true）：契约口径 {@code status=approved} 仅 it/admin；关闭时即便 it/admin 也拒绝直审
     * （403 {@code E-2001}，{@code details.reason=direct_approve_disabled}），用于演练/合规收紧场景。
     */
    private boolean directApproveEnabled = true;

    public long getMaxFileBytes() {
        return maxFileBytes;
    }

    public void setMaxFileBytes(long maxFileBytes) {
        this.maxFileBytes = maxFileBytes;
    }

    public int getMaxPasteChars() {
        return maxPasteChars;
    }

    public void setMaxPasteChars(int maxPasteChars) {
        this.maxPasteChars = maxPasteChars;
    }

    public int getMaxZipFiles() {
        return maxZipFiles;
    }

    public void setMaxZipFiles(int maxZipFiles) {
        this.maxZipFiles = maxZipFiles;
    }

    public long getMaxZipBytes() {
        return maxZipBytes;
    }

    public void setMaxZipBytes(long maxZipBytes) {
        this.maxZipBytes = maxZipBytes;
    }

    public int getMaxPerMinute() {
        return maxPerMinute;
    }

    public void setMaxPerMinute(int maxPerMinute) {
        this.maxPerMinute = maxPerMinute;
    }

    public int getMaxConcurrentJobs() {
        return maxConcurrentJobs;
    }

    public void setMaxConcurrentJobs(int maxConcurrentJobs) {
        this.maxConcurrentJobs = maxConcurrentJobs;
    }

    public boolean isDirectApproveEnabled() {
        return directApproveEnabled;
    }

    public void setDirectApproveEnabled(boolean directApproveEnabled) {
        this.directApproveEnabled = directApproveEnabled;
    }
}
