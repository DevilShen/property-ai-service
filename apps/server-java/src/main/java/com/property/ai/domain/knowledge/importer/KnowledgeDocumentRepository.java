package com.property.ai.domain.knowledge.importer;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

import com.property.ai.bff.dto.KnowledgeImportFileType;
import com.property.ai.bff.dto.KnowledgeImportSourceType;
import com.property.ai.domain.ticket.TicketTimes;

/**
 * 知识原文文档仓储（task-86，内存 demo；design §5.4 {@code knowledge_documents}）。
 *
 * <h2>原文不清理（用户裁决，契约 v1.5.0）</h2>
 * 导入的原文/脱敏文<b>长期保留</b>（retention 字段不进契约、不做自动删除）：
 * <ul>
 *   <li>{@code rawText}：txt/md 的原文文本（便于运营回溯/复核）；</li>
 *   <li>{@code rawBase64}：docx/pdf 的原始二进制（Base64 存档，Java 侧不解析二进制；
 *       解析能力在 Python 编排层）。<b>不对外返回</b>，只在仓储内保留；</li>
 *   <li>{@code maskedText}：编排层返回的脱敏归一化文本（预览/入库口径）；</li>
 *   <li>{@code checksum}：脱敏归一化文本 SHA-256（去重与幂等口径，与编排层同源）。</li>
 * </ul>
 *
 * <p><b>demo 口径</b>：进程内 {@link ConcurrentMap}，重启清空；P5 迁 PG/对象存储，
 * 接口不变（不得把本实现说成生产对象存储）。
 */
@Component
public class KnowledgeDocumentRepository {

    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * 原文文档记录。
     *
     * @param documentId 文档 ID（{@code DOC-yyyyMMdd-NNNN}）
     * @param projectId  项目归属（服务端强制）
     * @param jobId      来源导入任务
     * @param sourceType 入口类型（paste/file/batch）
     * @param rawText    txt/md 原文（docx/pdf 为 null，原文存 {@code rawBase64}）
     * @param rawBase64  docx/pdf 原始二进制 Base64（txt/md 为 null）
     * @param maskedText 脱敏归一化文本（编排层 contentMasked；预览/入库口径）
     */
    public record DocumentRecord(String documentId, String projectId, String jobId,
            KnowledgeImportSourceType sourceType, String fileName, KnowledgeImportFileType fileType, long sizeBytes,
            String checksum, String rawText, String rawBase64, String maskedText, String createdBy,
            String createdAt) {
    }

    private final ConcurrentMap<String, DocumentRecord> documents = new ConcurrentHashMap<>();
    private final AtomicInteger sequence = new AtomicInteger();
    private final Clock clock = Clock.system(TicketTimes.BUSINESS_ZONE);

    public String nextDocumentId() {
        Instant now = clock.instant();
        String day = DAY_FORMAT.format(now.atZone(TicketTimes.BUSINESS_ZONE));
        return "DOC-" + day + "-" + String.format("%04d", sequence.incrementAndGet());
    }

    public void save(DocumentRecord record) {
        if (record == null || record.documentId() == null) {
            return;
        }
        documents.put(record.documentId(), record);
    }

    public Optional<DocumentRecord> find(String documentId) {
        if (documentId == null || documentId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(documents.get(documentId.trim()));
    }

    /** 某任务的原文文档（按创建顺序；批量任务多个）。 */
    public List<DocumentRecord> byJob(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            return List.of();
        }
        List<DocumentRecord> matched = new ArrayList<>();
        for (DocumentRecord record : documents.values()) {
            if (jobId.equals(record.jobId())) {
                matched.add(record);
            }
        }
        matched.sort(java.util.Comparator.comparing(DocumentRecord::documentId));
        return List.copyOf(matched);
    }

    /** 文档总量（诊断/测试用）。 */
    public int size() {
        return documents.size();
    }

    /** 演示/测试重置（生产实现不调用；<b>不代表生产可清理原文</b>）。 */
    public void clearForTest() {
        documents.clear();
        sequence.set(0);
    }
}
