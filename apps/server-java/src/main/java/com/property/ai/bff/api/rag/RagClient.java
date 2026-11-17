package com.property.ai.bff.api.rag;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.property.ai.bff.dto.KnowledgeCategory;
import com.property.ai.bff.dto.RagUsage;

/**
 * RAG 编排层 HTTP 客户端（P6 W30 / task-80，出站唯一通道）。
 *
 * <h2>职责边界（AGENTS.md §1 / NEVER #2）</h2>
 * Java BFF <b>只做 HTTP 调用</b>，不下模型 SDK、不拼 Prompt、不做检索/重排/生成：
 * <ul>
 *   <li>{@code POST /internal/rag/answer}：混合检索 + 重排 + 四约束生成 + 引用校验 + 拒答判定；</li>
 *   <li>{@code GET /internal/rag/health}：编排层/模型/索引健康；</li>
 *   <li>{@code POST /internal/rag/reindex}：单条知识重建索引（写操作）。</li>
 * </ul>
 *
 * <h2>协议口径</h2>
 * <ul>
 *   <li>鉴权：{@code X-Internal-Token}（fail-closed；未配置时本站直接判 {@code TOKEN_MISSING}，不发裸请求）；</li>
 *   <li>链路：{@code X-Trace-Id} 透传（与 BFF 响应信封 traceId 同源，便于全链路回溯）；</li>
 *   <li>超时：单次预算 {@code property.rag.timeout-ms}（默认 8000ms），<b>不自动重试</b>
 *       （问答重试会放大延迟且降级路径已存在；重建索引写操作不盲目重试）；</li>
 *   <li>解析：宽容 JSON（编排层 W32/W33 字段演进中：缺字段按「未声明」处理，不猜值、不编造）；</li>
 *   <li>日志/异常：<b>不落 token、问题原文、答案正文、snippet</b>，只记路径/状态码/耗时/traceId/错误分类。</li>
 * </ul>
 */
@Component
public class RagClient {

    private static final Logger log = LoggerFactory.getLogger(RagClient.class);

    /** 问答端点（W33 生成层；未就绪时 404 → Java 侧降级规则检索） */
    public static final String ANSWER_PATH = "/internal/rag/answer";
    /** 健康端点（W32 已提供，W33 扩展模型/组件状态） */
    public static final String HEALTH_PATH = "/internal/rag/health";
    /** 重建索引端点（W33） */
    public static final String REINDEX_PATH = "/internal/rag/reindex";

    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";
    private static final String TRACE_HEADER = "X-Trace-Id";
    private static final String USER_AGENT = "property-ai-bff-rag/0.1 (P6 W30)";

    private final RagProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public RagClient(RagProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.effectiveConnectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    // ------------------------------------------------------------------
    // 问答
    // ------------------------------------------------------------------

    /**
     * 调用编排层问答（{@code POST /internal/rag/answer}）。
     *
     * @param question  自然语言问题（已由 Controller 校验 1—500 字符）
     * @param projectId 生效项目作用域（{@code null} = 平台级：不传，交由编排层默认项目）
     * @param category  可选知识分类
     * @param topK      进入生成上下文的片段数（1—10）
     * @param traceId   链路 traceId
     * @throws RagClientException 不可用/超时/5xx/响应不可解析（调用方据此降级规则检索）
     */
    public InternalAnswer answer(String question, String projectId, KnowledgeCategory category, int topK,
            String traceId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("question", question);
        if (projectId != null && !projectId.isBlank()) {
            body.put("projectId", projectId);
        }
        if (category != null) {
            body.put("category", category.value());
        }
        body.put("topK", topK);
        JsonNode root = send("POST", ANSWER_PATH, body, traceId);
        return parseAnswer(root, traceId);
    }

    private InternalAnswer parseAnswer(JsonNode root, String traceId) {
        JsonNode payload = unwrap(root);
        if (payload == null || !payload.path("answer").isTextual()) {
            throw RagClientException.invalidResponse(ANSWER_PATH, "缺少 answer 字段");
        }
        List<InternalSource> sources = new ArrayList<>();
        JsonNode sourcesNode = payload.path("sources");
        int fallbackIndex = 0;
        if (sourcesNode.isArray()) {
            for (JsonNode node : sourcesNode) {
                if (!node.isObject()) {
                    continue;
                }
                fallbackIndex++;
                int index = node.path("index").isNumber() ? node.path("index").asInt() : fallbackIndex;
                sources.add(new InternalSource(
                        index,
                        text(node, "knowledgeId"),
                        text(node, "title"),
                        text(node, "version"),
                        text(node, "owner"),
                        text(node, "category"),
                        node.path("score").isNumber() ? node.path("score").asDouble() : null,
                        text(node, "snippet"),
                        text(node, "sectionPath")));
            }
        }
        Double confidence = payload.path("confidence").isNumber() ? payload.path("confidence").asDouble() : null;
        boolean answerable = payload.path("answerable").isBoolean()
                ? payload.path("answerable").asBoolean()
                : true;
        return new InternalAnswer(
                payload.path("answer").asText(),
                List.copyOf(sources),
                confidence,
                answerable,
                text(payload, "refusalReason"),
                text(payload, "mode"),
                payload.path("reranked").isBoolean() && payload.path("reranked").asBoolean(),
                payload.path("degraded"),
                text(payload, "versionNotice"),
                text(payload, "rerankMode"),
                parseUsage(payload.path("usage")),
                payload.path("latencyMs").isNumber() ? payload.path("latencyMs").asInt() : null,
                firstNonBlank(text(payload, "traceId"), traceId));
    }

    private RagUsage parseUsage(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        Integer prompt = node.path("promptTokens").isNumber() ? node.path("promptTokens").asInt() : null;
        Integer completion = node.path("completionTokens").isNumber() ? node.path("completionTokens").asInt() : null;
        Integer rerankCalls = node.path("rerankCalls").isNumber() ? node.path("rerankCalls").asInt() : null;
        if (prompt == null && completion == null && rerankCalls == null) {
            return null;
        }
        return new RagUsage(prompt, completion, rerankCalls);
    }

    // ------------------------------------------------------------------
    // 健康
    // ------------------------------------------------------------------

    /**
     * 调用编排层健康（{@code GET /internal/rag/health}），返回原始 JSON 根节点。
     *
     * <p>返回 {@code JsonNode} 而不是 DTO：W32 已提供索引/模型/阈值快照，W33 追加 llm/embedding/
     * vectorStore/reranker 组件；由 {@link RagMapper} 宽容映射，避免编排层字段演进导致整条 health 500。
     */
    public JsonNode health(String traceId) {
        return send("GET", HEALTH_PATH, null, traceId);
    }

    // ------------------------------------------------------------------
    // 重建索引（写）
    // ------------------------------------------------------------------

    /** 调用编排层重建索引（{@code POST /internal/rag/reindex}，body {@code {knowledgeId}}）。 */
    public InternalReindex reindex(String knowledgeId, String traceId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("knowledgeId", knowledgeId);
        JsonNode root = send("POST", REINDEX_PATH, body, traceId);
        JsonNode payload = unwrap(root);
        if (payload == null || !payload.path("chunks").isNumber()) {
            throw RagClientException.invalidResponse(REINDEX_PATH, "缺少 chunks 字段");
        }
        int chunks = Math.max(0, payload.path("chunks").asInt());
        String indexedAt = text(payload, "indexedAt");
        String echoedId = firstNonBlank(text(payload, "knowledgeId"), knowledgeId);
        Integer latencyMs = payload.path("latencyMs").isNumber() ? payload.path("latencyMs").asInt() : null;
        return new InternalReindex(echoedId, chunks, indexedAt, latencyMs);
    }

    // ------------------------------------------------------------------
    // 传输
    // ------------------------------------------------------------------

    private JsonNode send(String method, String path, Map<String, Object> body, String traceId) {
        if (!properties.isEnabled()) {
            throw RagClientException.disabled();
        }
        if (!properties.hasInternalToken()) {
            // fail-closed：编排层未配置 token 时一律 401；本站不发明文裸请求
            throw RagClientException.tokenMissing();
        }
        long startedAt = System.nanoTime();
        HttpResponse<String> response;
        try {
            response = httpClient.send(buildRequest(method, path, body, traceId),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException ex) {
            long elapsed = elapsedMs(startedAt);
            log.warn("[rag] {} 超时 {}ms（traceId={} elapsed={}ms）", path, properties.effectiveTimeoutMs(), traceId,
                    elapsed);
            throw RagClientException.timeout(properties.effectiveTimeoutMs(), path);
        } catch (IOException ex) {
            log.warn("[rag] {} 不可达 kind={} traceId={}", path, ex.getClass().getSimpleName(), traceId);
            throw RagClientException.transport(path, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw RagClientException.transport(path, ex);
        }
        long elapsed = elapsedMs(startedAt);
        int status = response.statusCode();
        String raw = response.body() == null ? "" : response.body();
        if (status < 200 || status >= 300) {
            log.warn("[rag] {} 上游失败 status={} elapsed={}ms traceId={}", path, status, elapsed, traceId);
            throw RagClientException.upstream(path, status);
        }
        JsonNode root = parseJson(path, raw);
        JsonNode payload = unwrap(root);
        if (payload != null && payload.path("ok").isBoolean() && !payload.path("ok").asBoolean(true)) {
            log.warn("[rag] {} 返回 ok=false elapsed={}ms traceId={}", path, elapsed, traceId);
            throw RagClientException.upstream(path, 502);
        }
        log.info("[rag] {} ok status={} elapsed={}ms traceId={}", path, status, elapsed, traceId);
        return root;
    }

    private HttpRequest buildRequest(String method, String path, Map<String, Object> body, String traceId) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(buildUri(path))
                .timeout(Duration.ofMillis(properties.effectiveTimeoutMs()))
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .header(INTERNAL_TOKEN_HEADER, properties.getInternalToken().trim());
        if (traceId != null && !traceId.isBlank()) {
            builder.header(TRACE_HEADER, traceId);
        }
        if ("POST".equalsIgnoreCase(method)) {
            String payload;
            try {
                payload = objectMapper.writeValueAsString(body == null ? Map.of() : body);
            } catch (IOException ex) {
                throw RagClientException.invalidResponse(path, "请求体序列化失败");
            }
            builder.header("Content-Type", "application/json; charset=UTF-8");
            builder.POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
        } else {
            builder.GET();
        }
        return builder.build();
    }

    private URI buildUri(String path) {
        String base = properties.getBaseUrl() == null ? "" : properties.getBaseUrl().trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + path);
    }

    private JsonNode parseJson(String path, String raw) {
        if (raw == null || raw.isBlank()) {
            throw RagClientException.invalidResponse(path, "空响应体");
        }
        try {
            return objectMapper.readTree(raw);
        } catch (IOException ex) {
            throw RagClientException.invalidResponse(path, "非合法 JSON");
        }
    }

    /** 兼容两种响应形态：扁平体（W32/W33 内部端点）与 {@code {ok, data:{...}}} 信封。 */
    private static JsonNode unwrap(JsonNode root) {
        if (root == null || !root.isObject()) {
            return null;
        }
        JsonNode data = root.path("data");
        if (data.isObject() && (data.hasNonNull("answer") || data.hasNonNull("status") || data.hasNonNull("chunks"))) {
            return data;
        }
        return root;
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private static long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }

    // ------------------------------------------------------------------
    // 内部响应载体（只承载「编排层实际返回」，字段可为 null，不在本层补值/编造）
    // ------------------------------------------------------------------

    /** 编排层问答应答（对齐 W33 生成层响应；字段缺失 = 编排层未提供）。 */
    public record InternalAnswer(
            String answer,
            List<InternalSource> sources,
            Double confidence,
            boolean answerable,
            String refusalReason,
            String mode,
            boolean reranked,
            JsonNode degraded,
            String versionNotice,
            String rerankMode,
            RagUsage usage,
            Integer latencyMs,
            String traceId) {
    }

    /** 编排层来源片段（Chunk）：version/owner/category 可能由 W33 提供，缺失时由 BFF 用知识库补齐。 */
    public record InternalSource(
            int index,
            String knowledgeId,
            String title,
            String version,
            String owner,
            String category,
            Double score,
            String snippet,
            String sectionPath) {
    }

    /** 编排层重建索引结果。 */
    public record InternalReindex(String knowledgeId, int chunks, String indexedAt, Integer latencyMs) {
    }
}
