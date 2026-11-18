package com.property.ai.domain.knowledge.importer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.property.ai.bff.dto.KnowledgeImportFileType;

/**
 * ZIP 批量安全解包（task-86 / KIMP-002，契约 v1.5.0 {@code sourceType=batch}）。
 *
 * <h2>不可信输入处理（AGENTS.md NEVER #8 / 设计 §6）</h2>
 * 上传的 ZIP 一律视为不可信：只解析**白名单扩展名**文本类文件（txt/md/docx/pdf），
 * 目录项/隐藏文件/__MACOSX/.DS_Store/非白名单扩展名一律跳过（记 skipped，不报错）；
 * 以下情况**整包拒绝**：
 * <ul>
 *   <li><b>路径穿越</b>（{@code ../}、绝对路径、盘符、NUL）→ 400 {@code E-1001}
 *       （{@code details.reason=unsafe_zip_entry}），<b>不解压任何条目</b>；</li>
 *   <li><b>文件数超限</b>（> {@code maxZipFiles}，默认 50）或 <b>解压总量超限</b>
 *       （> {@code maxZipBytes}，默认 50MB）→ 413 {@code E-1001}（{@code payload_too_large}）；</li>
 *   <li>单文件 > 10MB → 413（与单文件上传同口径）；空包/无可解析文件 → 400（{@code empty_zip}）。</li>
 * </ul>
 * 解压用 {@link ZipInputStream} 流式读取 + 上限计数（防压缩炸弹），<b>不落盘</b>、
 * 不做递归解包（嵌套 zip 按非白名单扩展名跳过）。
 */
public final class KnowledgeImportZipExtractor {

    /** 解包限额（由 {@link KnowledgeImportProperties} 派生） */
    public record Limits(int maxFiles, long maxBytes, long maxFileBytes) {
    }

    /** 解包出的单文件（entryName 仅用于告警文案；fileName 取 basename） */
    public record Entry(String entryName, String fileName, KnowledgeImportFileType fileType, byte[] bytes) {
    }

    /**
     * 解包结果。
     *
     * @param entries 白名单内的有效文件（按 ZIP 内顺序）
     * @param skipped 被跳过的条目说明（目录/隐藏/非白名单/空文件），不阻断导入
     */
    public record Extraction(List<Entry> entries, List<String> skipped) {
    }

    private static final int HARD_ENTRY_CAP_PADDING = 100;
    private static final Map<String, KnowledgeImportFileType> EXTENSIONS = extensionMap();

    private KnowledgeImportZipExtractor() {
    }

    private static Map<String, KnowledgeImportFileType> extensionMap() {
        Map<String, KnowledgeImportFileType> map = new LinkedHashMap<>();
        map.put("txt", KnowledgeImportFileType.TXT);
        map.put("md", KnowledgeImportFileType.MD);
        map.put("markdown", KnowledgeImportFileType.MD);
        map.put("docx", KnowledgeImportFileType.DOCX);
        map.put("pdf", KnowledgeImportFileType.PDF);
        return Map.copyOf(map);
    }

    /**
     * 安全解包。
     *
     * @throws KnowledgeImportException 路径穿越 400 / 超限 413 / 空包 400 / ZIP 损坏 400
     */
    public static Extraction extract(byte[] zipBytes, Limits limits) {
        Limits effective = limits == null ? new Limits(50, 50L * 1024L * 1024L, 10L * 1024L * 1024L) : limits;
        if (zipBytes == null || zipBytes.length == 0) {
            throw emptyZip("ZIP 内容为空");
        }
        // 魔数前置校验：非 PK 头直接判损坏（ZipInputStream 对纯文本返回 0 条目，会误报为空包）
        if (zipBytes.length < 2 || zipBytes[0] != 'P' || zipBytes[1] != 'K') {
            throw KnowledgeImportException.badRequest("ZIP 解析失败（文件不是有效 ZIP：缺少 PK 头）",
                    Map.of("reason", "invalid_zip", "field", "file"));
        }
        List<Entry> entries = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        long totalBytes = 0L;
        int seenEntries = 0;
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(zipBytes),
                StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                seenEntries++;
                if (seenEntries > effective.maxFiles() + HARD_ENTRY_CAP_PADDING) {
                    throw KnowledgeImportException.payloadTooLarge(
                            "ZIP 条目过多（上限 " + effective.maxFiles() + " 个文件）", "payload_too_large",
                            Map.of("limit", effective.maxFiles(), "field", "file"));
                }
                String rawName = entry.getName() == null ? "" : entry.getName();
                if (entry.isDirectory() || rawName.endsWith("/")) {
                    continue;
                }
                requireSafeEntryName(rawName);
                String fileName = baseName(rawName);
                if (isJunk(fileName, rawName)) {
                    skipped.add(fileName + "（隐藏/系统文件，已跳过）");
                    continue;
                }
                KnowledgeImportFileType fileType = fileTypeOf(fileName);
                if (fileType == null) {
                    skipped.add(fileName + "（非白名单扩展名，已跳过）");
                    continue;
                }
                if (entries.size() >= effective.maxFiles()) {
                    throw KnowledgeImportException.payloadTooLarge(
                            "ZIP 文件数超限（>" + effective.maxFiles() + "）", "payload_too_large",
                            Map.of("limit", effective.maxFiles(), "field", "file", "reason", "payload_too_large"));
                }
                byte[] bytes = readEntry(zip, effective.maxFileBytes(), totalBytes, effective.maxBytes(), fileName);
                if (bytes.length == 0) {
                    skipped.add(fileName + "（空文件，已跳过）");
                    continue;
                }
                totalBytes += bytes.length;
                if (totalBytes > effective.maxBytes()) {
                    throw KnowledgeImportException.payloadTooLarge(
                            "ZIP 解压总量超限（>" + effective.maxBytes() + " 字节 / 50MB）", "payload_too_large",
                            Map.of("limit", effective.maxBytes(), "field", "file", "reason", "payload_too_large"));
                }
                entries.add(new Entry(rawName, fileName, fileType, bytes));
            }
        } catch (KnowledgeImportException ex) {
            throw ex;
        } catch (IOException ex) {
            throw KnowledgeImportException.badRequest("ZIP 解析失败（文件损坏或不是有效 ZIP）："
                    + ex.getClass().getSimpleName(), Map.of("reason", "invalid_zip", "field", "file"));
        }
        if (entries.isEmpty()) {
            throw emptyZip(skipped.isEmpty() ? "ZIP 内没有可解析文件"
                    : "ZIP 内没有可解析文件（已跳过：" + String.join("；", skipped) + "）");
        }
        return new Extraction(List.copyOf(entries), List.copyOf(skipped));
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private static KnowledgeImportException emptyZip(String message) {
        return KnowledgeImportException.badRequest(message,
                Map.of("reason", "empty_zip", "field", "file", "required", true));
    }

    /** 路径穿越/绝对路径/盘符/NUL → 400；不解压任何条目。 */
    static void requireSafeEntryName(String rawName) {
        String name = rawName.replace('\\', '/');
        boolean unsafe = name.isBlank()
                || name.indexOf('\u0000') >= 0
                || name.startsWith("/")
                || name.matches("^[A-Za-z]:.*")
                || hasParentSegment(name);
        if (!unsafe) {
            try {
                Path normalized = Path.of(name).normalize();
                String text = normalized.toString().replace('\\', '/');
                unsafe = normalized.isAbsolute() || text.equals("..") || text.startsWith("../");
            } catch (RuntimeException ex) {
                unsafe = true;
            }
        }
        if (unsafe) {
            throw KnowledgeImportException.badRequest(
                    "ZIP 条目路径不安全（疑似路径穿越）：" + sanitize(rawName),
                    Map.of("reason", "unsafe_zip_entry", "field", "file", "entry", sanitize(rawName)));
        }
    }

    private static boolean hasParentSegment(String name) {
        for (String segment : name.split("/")) {
            if ("..".equals(segment)) {
                return true;
            }
        }
        return false;
    }

    private static String baseName(String rawName) {
        String name = rawName.replace('\\', '/');
        int index = name.lastIndexOf('/');
        return index >= 0 ? name.substring(index + 1) : name;
    }

    private static boolean isJunk(String fileName, String rawName) {
        String normalized = rawName.replace('\\', '/').toLowerCase(Locale.ROOT);
        return fileName.startsWith(".") || normalized.startsWith("__macosx/") || normalized.contains("/__macosx/")
                || "thumbs.db".equalsIgnoreCase(fileName) || "desktop.ini".equalsIgnoreCase(fileName);
    }

    private static KnowledgeImportFileType fileTypeOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return null;
        }
        return EXTENSIONS.get(fileName.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /** 流式读取单条目（超过 perFile 上限立即 413，避免把压缩炸弹读进内存）。 */
    private static byte[] readEntry(ZipInputStream zip, long maxFileBytes, long alreadyBytes, long maxTotalBytes,
            String fileName) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        long written = 0L;
        int read;
        while ((read = zip.read(buffer)) != -1) {
            written += read;
            if (written > maxFileBytes) {
                throw KnowledgeImportException.payloadTooLarge(
                        "ZIP 内单文件超限（>10MB）：" + fileName, "payload_too_large",
                        Map.of("limit", maxFileBytes, "field", "file", "file", sanitize(fileName)));
            }
            if (alreadyBytes + written > maxTotalBytes) {
                throw KnowledgeImportException.payloadTooLarge(
                        "ZIP 解压总量超限（>50MB）", "payload_too_large",
                        Map.of("limit", maxTotalBytes, "field", "file", "reason", "payload_too_large"));
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        String text = value.replaceAll("[\\r\\n\\t]", " ");
        return text.length() <= 120 ? text : text.substring(0, 120);
    }
}
