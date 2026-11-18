package com.property.ai.domain.knowledge.importer;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.property.ai.bff.api.rag.RagProperties;
import com.property.ai.bff.dto.KnowledgeImportFileType;

/**
 * 知识导入编排层 HTTP 客户端（task-86；契约 v1.5.0，设计 {@code docs/design/知识导入方案.md} §5.1）。
 *
 * <h2>职责边界</h2>
 * 解析/归一化/脱敏/切分/去重/向量入库全部在 Python 编排层（{@code services/ai-orchestrator}）；
 * Java BFF 只做「出站 HTTP + 契约映射 + 凭证/超时/降级」。<b>不接模型 SDK、不解析二进制文档</b>
 * （AGENTS.md NEVER #2）。
 *
 * <h2>内部协议（与 {@code rag/importer.py} + {@code rag/service.py} 对齐）</h2>
 * <pre>
 * POST /internal/rag/preview  body {fileName?, fileType(txt|md|docx|pdf), text?|contentBase64?, projectId, title?}
 *                             → 200 {ok:true,data:{suggestedTitle,charCount,piiMaskedCount,
 *                                    chunks[{seq,sectionPath,text,charCount}],totalChunks,
 *                                    warnings[{code,message}],duplicates[{knowledgeId,title,similarity}],
 *                                    contentMasked}}
 *                             → 4xx {ok:false,error:{code,message,reason?}}
 * POST /internal/rag/index    body {knowledgeId,title,projectId,category,owner,version,effectiveFrom?,
 *                              effectiveTo?,status,content,sourceDocId?,checksum}
 *                             → 200 {ok:true,data:{knowledgeId,chunks,indexedAt,skipped}}
 * POST /internal/rag/remove   body {knowledgeId} → 200 {ok:true,data:{removed}}
 * </pre>
 *
 * <h2>协议口径（与 {@code RagClient} 同源）</h2>
 * <ul>
 *   <li>鉴权：{@code X-Internal-Token}（fail-closed；未配置直接判配置缺陷，不发裸请求）；</li>
 *   <li>链路：{@code X-Trace-Id} 透传；超时：{@code property.rag.timeout-ms}，<b>导入解析不低于 15s</b>
 *       （PDF/docx 解析慢；上限取两者较大值，避免默认 8s 预算误杀大文档）；</li>
 *   <li>不自动重试（写操作不盲目重试；客户端重试靠 {@code Idempotency-Key}）；</li>
 *   <li>日志不落 token、正文、文件名之外的内容；只记路径/状态/耗时/分类。</li>
 * </ul>
 */
@Component
public class RagImportClient {

    private static final Logger log = LoggerFactory.getLogger(RagImportClient.class);

    /** 预览端点（解析 + 脱敏 + 切分 + 去重，不入库） */
    public static final String PREVIEW_PATH = "/internal/rag/preview";
    /** 单知识 upsert 端点（幂等：同 knowledgeId+checksum → skipped） */
    public static final String INDEX_PATH = "/internal/rag/index";
    /** 单知识撤销端点（删除全部 chunk，P1 预留） */
    public static final String REMOVE_PATH = "/internal/rag/remove";

    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";
    private static final String TRACE_HEADER = "X-Trace-Id";
    private static final String USER_AGENT = "property-ai-bff-knowledge-import/0.1 (P6 W34)";

    /** 导入解析预算下限（PDF/docx 解析较慢；取与 property.rag.timeout-ms 的较大值） */
    static final int IMPORT_MIN_TIMEOUT_MS = 15_000;

    private final RagProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public RagImportClient(RagProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.effectiveConnectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    // ==================================================================
    // 预览
    // ==================================================================

    /**
     * 调用编排层预览（{@code POST /internal/rag/preview}）。
     *
     * @param fileName 文件名（粘贴文本为 {@code null}）
     * @param fileType 文件类型（txt/md/docx/pdf；zip 在 Java 侧解包后逐文件调用）
     * @param text     粘贴文本（txt/md 直传；与 content 二选一）
     * @param content  文件字节（txt/md/docx/pdf；与 text 二选一）
     */
    public InternalPreview preview(String fileName, KnowledgeImportFileType fileType, String text, byte[] content,
            String projectId, String title, String traceId) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (fileName != null && !fileName.isBlank()) {
            body.put("fileName", fileName);
        }
        if (fileType != null) {
            body.put("fileType", fileType.value());
        }
        if (text != null) {
            body.put("text", text);
        } else if (content != null) {
            body.put("contentBase64", Base64.getEncoder().encodeToString(content));
        }
        if (projectId != null && !projectId.isBlank()) {
            body.put("projectId", projectId);
        }
        if (title != null && !title.isBlank()) {
            body.put("title", title);
        }
        JsonNode root = send("POST", PREVIEW_PATH, body, traceId);
        JsonNode payload = data(root, PREVIEW_PATH);
        if (payload == null) {
            throw RagImportException.invalidResponse(PREVIEW_PATH, "缺少 data 字段");
        }
        List<InternalChunk> chunks = new ArrayList<>();
        JsonNode chunksNode = payload.path("chunks");
        if (chunksNode.isArray()) {
            for (JsonNode node : chunksNode) {
                if (!node.isObject() || !node.path("text").isTextual()) {
                    continue;
                }
                chunks.add(new InternalChunk(
                        node.path("seq").isNumber() ? node.path("seq").asInt() : chunks.size() + 1,
                        text(node, "sectionPath"),
                        node.path("text").asText(),
                        node.path("charCount").isNumber() ? node.path("charCount").asInt()
                                : node.path("text").asText().length()));
            }
        }
        List<InternalWarning> warnings = new ArrayList<>();
        JsonNode warningsNode = payload.path("warnings");
        if (warningsNode.isArray()) {
            for (JsonNode node : warningsNode) {
                if (!node.isObject()) {
                    continue;
                }
                String code = text(node, "code");
                if (code != null) {
                    warnings.add(new InternalWarning(code, text(node, "message")));
                }
            }
        }
        List<InternalDuplicate> duplicates = new ArrayList<>();
        JsonNode duplicatesNode = payload.path("duplicates");
        if (duplicatesNode.isArray()) {
            for (JsonNode node : duplicatesNode) {
                if (!node.isObject()) {
                    continue;
                }
                String knowledgeId = text(node, "knowledgeId");
                if (knowledgeId == null) {
                    continue;
                }
                duplicates.add(new InternalDuplicate(
                        knowledgeId,
                        text(node, "title"),
                        node.path("similarity").isNumber()
                                ? BigDecimal.valueOf(node.path("similarity").asDouble())
                                : null));
            }
        }
        String suggestedTitle = text(payload, "suggestedTitle");
        int charCount = payload.path("charCount").isNumber() ? payload.path("charCount").asInt() : 0;
        int piiMaskedCount = payload.path("piiMaskedCount").isNumber()
                ? payload.path("piiMaskedCount").asInt() : 0;
        int totalChunks = payload.path("totalChunks").isNumber() ? payload.path("totalChunks").asInt()
                : chunks.size();
        String contentMasked = text(payload, "contentMasked");
        log.info("[knowledge-import] preview ok fileType={} chars={} chunks={} pii={} dup={} traceId={}",
                fileType == null ? "-" : fileType.value(), charCount, totalChunks, piiMaskedCount,
                duplicates.size(), traceId);
        return new InternalPreview(suggestedTitle, charCount, piiMaskedCount, List.copyOf(chunks), totalChunks,
                List.copyOf(warnings), List.copyOf(duplicates), contentMasked);
    }

    // ==================================================================
    // 索引 / 撤销
    // ==================================================================

    /** 调用编排层单知识索引（{@code POST /internal/rag/index}；幂等，同 knowledgeId+checksum → skipped）。 */
    public InternalIndex index(IndexRequest request, String traceId) {
        if (request == null) {
            throw RagImportException.invalidResponse(INDEX_PATH, "缺少请求参数");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("knowledgeId", request.knowledgeId());
        body.put("title", request.title());
        body.put("projectId", request.projectId());
        body.put("category", request.category() == null ? "" : request.category());
        body.put("owner", request.owner() == null ? "" : request.owner());
        body.put("version", request.version());
        if (request.effectiveFrom() != null) {
            body.put("effectiveFrom", request.effectiveFrom());
        }
        if (request.effectiveTo() != null) {
            body.put("effectiveTo", request.effectiveTo());
        }
        body.put("status", request.status());
        body.put("content", request.content());
        if (request.sourceDocId() != null) {
            body.put("sourceDocId", request.sourceDocId());
        }
        body.put("checksum", request.checksum());
        JsonNode root = send("POST", INDEX_PATH, body, traceId);
        JsonNode payload = data(root, INDEX_PATH);
        if (payload == null || !payload.path("knowledgeId").isTextual()) {
            throw RagImportException.invalidResponse(INDEX_PATH, "缺少 knowledgeId 字段");
        }
        int chunks = payload.path("chunks").isNumber() ? payload.path("chunks").asInt() : 0;
        boolean skipped = payload.path("skipped").isBoolean() && payload.path("skipped").asBoolean();
        log.info("[knowledge-import] index ok knowledgeId={} chunks={} skipped={} traceId={}",
                payload.path("knowledgeId").asText(), chunks, skipped, traceId);
        return new InternalIndex(payload.path("knowledgeId").asText(), chunks, text(payload, "indexedAt"), skipped);
    }

    /** 调用编排层撤销（{@code POST /internal/rag/remove}；P1 预留，不在 P0 主链路）。 */
    public InternalRemove remove(String knowledgeId, String traceId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("knowledgeId", knowledgeId);
        JsonNode root = send("POST", REMOVE_PATH, body, traceId);
        JsonNode payload = data(root, REMOVE_PATH);
        int removed = payload == null ? 0 : payload.path("removed").asInt(0);
        log.info("[knowledge-import] remove ok knowledgeId={} removed={} traceId={}", knowledgeId, removed, traceId);
        return new InternalRemove(knowledgeId, removed);
    }

    // ==================================================================
    // 传输
    // ==================================================================

    private JsonNode send(String method, String path, Map<String, Object> body, String traceId) {
        if (!properties.isEnabled()) {
            throw RagImportException.disabled();
        }
        if (!properties.hasInternalToken()) {
            // fail-closed：不在未配置 token 时发明文裸请求
            throw RagImportException.tokenMissing();
        }
        long startedAt = System.nanoTime();
        long timeoutMs = effectiveTimeoutMs();
        HttpResponse<String> response;
        try {
            response = httpClient.send(buildRequest(method, path, body, traceId, timeoutMs),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException ex) {
            log.warn("[knowledge-import] {} 超时 {}ms traceId={} elapsed={}ms", path, timeoutMs, traceId,
                    elapsedMs(startedAt));
            throw RagImportException.timeout(timeoutMs, path);
        } catch (IOException ex) {
            log.warn("[knowledge-import] {} 不可达 kind={} traceId={}", path, ex.getClass().getSimpleName(), traceId);
            throw RagImportException.transport(path, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw RagImportException.transport(path, ex);
        }
        int status = response.statusCode();
        String raw = response.body() == null ? "" : response.body();
        long elapsed = elapsedMs(startedAt);
        if (status < 200 || status >= 300) {
            if (status == 401 || status == 403) {
                log.warn("[knowledge-import] {} 鉴权失败 status={} elapsed={}ms traceId={}", path, status, elapsed,
                        traceId);
                throw RagImportException.unauthorized(path, status);
            }
            if (status >= 400 && status < 500 && status != 404 && status != 405 && status != 429) {
                JsonNode error = parseQuietly(raw);
                String reason = error == null ? "" : text(error.path("error"), "reason");
                String message = error == null ? null : text(error.path("error"), "message");
                log.info("[knowledge-import] {} 拒绝 status={} reason={} elapsed={}ms traceId={}", path, status,
                        reason, elapsed, traceId);
                throw RagImportException.rejected(path, status, message, reason);
            }
            log.warn("[knowledge-import] {} 上游失败 status={} elapsed={}ms traceId={}", path, status, elapsed,
                    traceId);
            throw RagImportException.upstream(path, status);
        }
        JsonNode root = parseOrThrow(path, raw);
        if (root.path("ok").isBoolean() && !root.path("ok").asBoolean(true)) {
            JsonNode error = root.path("error");
            throw RagImportException.rejected(path, 502, text(error, "message"), text(error, "reason"));
        }
        log.info("[knowledge-import] {} ok status={} elapsed={}ms traceId={}", path, status, elapsed, traceId);
        return root;
    }

    private long effectiveTimeoutMs() {
        return Math.max(IMPORT_MIN_TIMEOUT_MS, properties.effectiveTimeoutMs());
    }

    private HttpRequest buildRequest(String method, String path, Map<String, Object> body, String traceId,
            long timeoutMs) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(buildUri(path))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .header(INTERNAL_TOKEN_HEADER, properties.getInternalToken().trim());
        if (traceId != null && !traceId.isBlank()) {
            builder.header(TRACE_HEADER, traceId);
        }
        String payload;
        try {
            payload = objectMapper.writeValueAsString(body == null ? Map.of() : body);
        } catch (IOException ex) {
            throw RagImportException.invalidResponse(path, "请求体序列化失败");
        }
        builder.header("Content-Type", "application/json; charset=UTF-8");
        builder.POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
        return builder.build();
    }

    private URI buildUri(String path) {
        String base = properties.getBaseUrl() == null ? "" : properties.getBaseUrl().trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + path);
    }

    private JsonNode parseOrThrow(String path, String raw) {
        if (raw == null || raw.isBlank()) {
            throw RagImportException.invalidResponse(path, "空响应体");
        }
        try {
            return objectMapper.readTree(raw);
        } catch (IOException ex) {
            throw RagImportException.invalidResponse(path, "非合法 JSON");
        }
    }

    private JsonNode parseQuietly(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (IOException ex) {
            return null;
        }
    }

    /** 兼容 {@code {ok,data}} 信封与扁平体（编排层内部端点统一为信封）；缺 data 返回 null。 */
    private static JsonNode data(JsonNode root, String path) {
        if (root == null || !root.isObject()) {
            return null;
        }
        if (root.hasNonNull("data") && root.path("data").isObject()) {
            return root.path("data");
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private static long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }

    // ==================================================================
    // 载体（只承载编排层实际返回值；字段可 null，本层不补值/不编造）
    // ==================================================================

    /** 预览响应（{@code POST /internal/rag/preview} 的 data）。 */
    public record InternalPreview(String suggestedTitle, int charCount, int piiMaskedCount,
            List<InternalChunk> chunks, int totalChunks, List<InternalWarning> warnings,
            List<InternalDuplicate> duplicates, String contentMasked) {
    }

    /** 预览切分块（{@code seq} 由编排层给出，Java 侧统一重排为 1 起连续）。 */
    public record InternalChunk(int seq, String sectionPath, String text, int charCount) {
    }

    /** 预览警告（不阻断导入）。 */
    public record InternalWarning(String code, String message) {
    }

    /** 疑似重复候选（{@code similarity} 0—1）。 */
    public record InternalDuplicate(String knowledgeId, String title, BigDecimal similarity) {
    }

    /** 索引响应（{@code POST /internal/rag/index} 的 data）。 */
    public record InternalIndex(String knowledgeId, int chunks, String indexedAt, boolean skipped) {
    }

    /** 撤销响应（{@code POST /internal/rag/remove} 的 data）。 */
    public record InternalRemove(String knowledgeId, int removed) {
    }

    /** 索引请求（字段与编排层内部协议一一对应）。 */
    public record IndexRequest(String knowledgeId, String title, String projectId, String category, String owner,
            String version, String effectiveFrom, String effectiveTo, String status, String content,
            String sourceDocId, String checksum) {
    }
}
