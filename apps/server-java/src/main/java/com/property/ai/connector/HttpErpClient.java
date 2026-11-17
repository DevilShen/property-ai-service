package com.property.ai.connector;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.property.ai.bff.dto.ErrorCode;

/**
 * ERP HTTP 客户端（出站唯一通道）：JDK {@link HttpClient} + 统一超时/重试/熔断/traceId/错误码翻译。
 *
 * <p>只允许连接器出站调用 mock-erp / 真实 NC；BFF 与前端不得直连 ERP（设计 §24.4）。
 *
 * <p>重试与幂等口径（契约 §4）：
 * <ul>
 *   <li>只读能力：最多 {@code property.erp.retry.read-max-attempts}（默认 2 = 首次 + 1 次重试，退避 200ms）；</li>
 *   <li>写能力（建单/迁移/回访/核验）：**永不自动重试**，超时后由客户端复用同一 {@code Idempotency-Key} 重试；</li>
 *   <li>业务错误（E-1xxx/E-2xxx/E-3xxx）说明上游链路正常：不计入熔断失败，且不重试；</li>
 *   <li>E-4001/E-4002/E-4003 计入熔断失败，连续达到阈值后 OPEN，后续请求快速失败并走降级。</li>
 * </ul>
 *
 * <p>故障注入（仅联调/演练）：{@code property.erp.fail-mode} 非空时给每个请求追加
 * {@code ?fail=<mode>} 与 {@code X-Mock-Fail: <mode>}，用于验证超时/5xx 降级与熔断冷却。生产必须为空。
 */
/**
 * <p><b>多连接器注入口径（P5 W19）</b>：本 Bean 是默认（NC）出站客户端（{@code @Primary}）；
 * 第 2 套金蝶用独立实例 {@code kingdeeHttpErpClient}（{@code ConnectorConfig}），互不串味。
 */
@Component
@Primary
public class HttpErpClient {

    private static final Logger log = LoggerFactory.getLogger(HttpErpClient.class);
    private static final String DEFAULT_USER_AGENT = "property-ai-connector/0.3 (P2 mock-erp)";

    private final ObjectMapper objectMapper;
    private final ConnectorProperties properties;
    private final CircuitBreaker circuitBreaker;
    private final ConnectorTelemetry telemetry;
    private final HttpClient httpClient;
    /** 多连接器（P5 W19）：非空时覆盖 {@code property.erp.base-url}（如金蝶 mock-erp-2 :4400） */
    private final String baseUrlOverride;
    /** 多连接器（P5 W19）：>0 时覆盖 {@code property.erp.timeout-ms} */
    private final int timeoutMsOverride;

    /** Spring 注入用构造（默认连接器出站客户端；多连接器实例走下方 6 参构造） */
    @org.springframework.beans.factory.annotation.Autowired
    public HttpErpClient(ObjectMapper objectMapper, ConnectorProperties properties,
            CircuitBreaker circuitBreaker, ConnectorTelemetry telemetry) {
        this(objectMapper, properties, circuitBreaker, telemetry, null, 0);
    }

    /**
     * 多连接器构造（P5 W19 / P5-005）：同一套出站语义（超时/重试/熔断/错误码翻译/traceId）按连接器实例化，
     * 只覆盖 base-url 与单次预算；<b>不改既有 4 参构造</b>（P2—P4 调用方无感）。
     *
     * @param baseUrlOverride ERP 基地址覆盖（{@code null}/空 = 用 {@code property.erp.base-url}）
     * @param timeoutMsOverride 单次调用预算覆盖（{@code <=0} = 用 {@code property.erp.timeout-ms}）
     */
    public HttpErpClient(ObjectMapper objectMapper, ConnectorProperties properties,
            CircuitBreaker circuitBreaker, ConnectorTelemetry telemetry, String baseUrlOverride,
            int timeoutMsOverride) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.circuitBreaker = circuitBreaker;
        this.telemetry = telemetry;
        this.baseUrlOverride = baseUrlOverride == null || baseUrlOverride.isBlank() ? null : baseUrlOverride.trim();
        this.timeoutMsOverride = Math.max(0, timeoutMsOverride);
        int connectTimeout = properties.getConnectTimeoutMs();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeout > 0 ? connectTimeout : 800))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** 实际生效的单次调用预算（毫秒） */
    private long effectiveTimeoutMs() {
        return timeoutMsOverride > 0 ? timeoutMsOverride : properties.getTimeoutMs();
    }

    /**
     * 调用 ERP 能力。
     *
     * @return ERP 统一信封（{@code {ok,data,error,message,mock,vendor,...}}）根节点
     */
    public JsonNode call(Capability capability, String method, String path, Map<String, String> query,
            Object body, Map<String, String> headers, String traceId) {
        if (!properties.isEnabled()) {
            throw ErpErrorMapper.unsupported(capability);
        }
        int maxAttempts = capability.readOnly()
                ? Math.max(1, properties.getRetry().getReadMaxAttempts())
                : 1;
        ConnectorException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (!circuitBreaker.allowRequest()) {
                throw ErpErrorMapper.circuitOpen(capability);
            }
            try {
                long startedAt = System.nanoTime();
                JsonNode envelope = send(capability, method, path, query, body, headers, traceId);
                long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
                circuitBreaker.recordSuccess();
                telemetry.recordSuccess();
                log.debug("[erp] {} {} ok in {}ms (attempt {}/{}, traceId={})",
                        method, path, elapsedMs, attempt, maxAttempts, traceId);
                return envelope;
            } catch (ConnectorException ex) {
                last = ex;
                telemetry.recordFailure(ex);
                if (ErpErrorMapper.retryable(ex.code())) {
                    circuitBreaker.recordFailure();
                } else {
                    // 业务错误：上游链路正常，不触发熔断
                    circuitBreaker.recordSuccess();
                }
                if (attempt < maxAttempts && ex.retryable()) {
                    log.info("[erp] {} {} 失败（{}），{}ms 后重试（{}/{}，traceId={}）",
                            method, path, ex.code().value(), properties.getRetry().getBackoffMs(),
                            attempt, maxAttempts, traceId);
                    sleepBackoff();
                    continue;
                }
                throw ex;
            }
        }
        throw last == null ? ErpErrorMapper.transport(capability, "unknown") : last;
    }

    /** 健康探测：GET /healthz（不重试，不抛业务码给调用方以外的地方用） */
    public JsonNode probeHealth() {
        long startedAt = System.nanoTime();
        try {
            JsonNode envelope = send(Capability.ROOM_QUERY, "GET", "/healthz", Map.of(), null, Map.of(),
                    "health-probe");
            circuitBreaker.recordSuccess();
            return envelope;
        } catch (ConnectorException ex) {
            if (ErpErrorMapper.retryable(ex.code())) {
                circuitBreaker.recordFailure();
            }
            throw ex;
        } finally {
            log.debug("[erp] health probe took {}ms", (System.nanoTime() - startedAt) / 1_000_000L);
        }
    }

    private JsonNode send(Capability capability, String method, String path, Map<String, String> query,
            Object body, Map<String, String> headers, String traceId) {
        telemetry.recordRequest();
        URI uri = buildUri(path, query);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(effectiveTimeoutMs()))
                .header("Accept", "application/json")
                .header("User-Agent", DEFAULT_USER_AGENT);
        if (traceId != null && !traceId.isBlank()) {
            builder.header("X-Trace-Id", traceId);
        }
        if (headers != null) {
            headers.forEach((name, value) -> {
                if (name != null && value != null) {
                    builder.header(name, value);
                }
            });
        }
        String failMode = properties.getFailMode();
        if (failMode != null && !failMode.isBlank()) {
            builder.header("X-Mock-Fail", failMode);
        }
        if ("POST".equalsIgnoreCase(method)) {
            String payload = "{}";
            if (body != null) {
                try {
                    payload = objectMapper.writeValueAsString(body);
                } catch (IOException ex) {
                    throw ErpErrorMapper.mapping(capability, "请求体序列化失败：" + ex.getMessage());
                }
            }
            builder.header("Content-Type", "application/json; charset=UTF-8");
            builder.POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
        } else {
            builder.GET();
        }

        HttpResponse<String> response;
        try {
            response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException ex) {
            throw ErpErrorMapper.timeout(capability, effectiveTimeoutMs());
        } catch (IOException ex) {
            throw ErpErrorMapper.transport(capability, ex.getClass().getSimpleName() + ": " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw ErpErrorMapper.transport(capability, "interrupted");
        }

        int status = response.statusCode();
        String rawBody = response.body() == null ? "" : response.body();
        JsonNode envelope = parseEnvelope(capability, rawBody, status);
        if (status >= 400 || envelope == null) {
            String upstreamCode = envelope == null ? null : envelope.path("error").asText(null);
            String upstreamMessage = envelope == null ? null : envelope.path("message").asText(null);
            throw ErpErrorMapper.upstream(capability, status, upstreamCode,
                    upstreamMessage != null ? upstreamMessage : truncate(rawBody));
        }
        if (envelope.has("ok") && !envelope.path("ok").asBoolean(true)) {
            throw ErpErrorMapper.upstream(capability, status, envelope.path("error").asText(null),
                    envelope.path("message").asText(null));
        }
        return envelope;
    }

    private JsonNode parseEnvelope(Capability capability, String rawBody, int status) {
        if (rawBody == null || rawBody.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(rawBody);
        } catch (IOException ex) {
            if (status >= 400) {
                return null;
            }
            throw ErpErrorMapper.mapping(capability, "ERP 响应不是合法 JSON：" + ex.getClass().getSimpleName());
        }
    }

    private URI buildUri(String path, Map<String, String> query) {
        String base = baseUrlOverride != null ? baseUrlOverride
                : (properties.getBaseUrl() == null ? "" : properties.getBaseUrl().trim());
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        StringJoiner joiner = new StringJoiner("&");
        Map<String, String> effectiveQuery = new LinkedHashMap<>();
        if (query != null) {
            effectiveQuery.putAll(query);
        }
        String failMode = properties.getFailMode();
        if (failMode != null && !failMode.isBlank()) {
            effectiveQuery.put("fail", failMode);
        }
        effectiveQuery.forEach((name, value) -> {
            if (name != null && value != null && !value.isBlank()) {
                joiner.add(encode(name) + "=" + encode(value));
            }
        });
        String fullPath = base + path;
        return joiner.length() == 0 ? URI.create(fullPath) : URI.create(fullPath + "?" + joiner);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String truncate(String raw) {
        if (raw == null) {
            return null;
        }
        String oneLine = raw.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 200 ? oneLine : oneLine.substring(0, 200) + "…";
    }

    private void sleepBackoff() {
        long backoff = properties.getRetry().getBackoffMs();
        if (backoff <= 0) {
            return;
        }
        try {
            Thread.sleep(backoff);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw ErpErrorMapper.transport(Capability.ROOM_QUERY, "interrupted while backing off");
        }
    }

    /** 供健康指示器使用：当前熔断状态 */
    public CircuitBreaker.State circuitState() {
        return circuitBreaker.state();
    }

    /** 供健康指示器使用：最近一次错误码（无则 null） */
    public ErrorCode lastErrorCode() {
        ConnectorTelemetry.Snapshot snapshot = telemetry.metrics().lastFailure();
        return snapshot == null ? null : ErpErrorMapper.knownCode(snapshot.code());
    }

    public ConnectorTelemetry.Metrics metrics() {
        return telemetry.metrics();
    }
}
