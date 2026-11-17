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
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.property.ai.bff.dto.ErrorCode;

/**
 * mock 推送通道客户端（{@code services/mock-channel}，默认 {@code 127.0.0.1:4300}）。
 *
 * <p>⚠️ 测试替身，**不是真实短信/企微/微信通道**：mock-channel 只把「谁/何时/模板/脱敏正文/通道」
 * 记在内存里，不产生任何真实投递。所有回执 {@code mock=true}。
 *
 * <p>失败口径（P2-019）：通道不可达/超时/5xx → {@link ConnectorException}（E-4001/E-4002/E-4003），
 * 由 {@code PushService} 记录 {@code TicketPush.status=failed}；**不重试、不伪造成功、不阻塞主流程**。
 * 通道不可达时只打一次性 WARN 日志（连接器式降级，避免刷屏）。
 */
@Component
public class HttpPushChannel implements PushChannel {

    private static final Logger log = LoggerFactory.getLogger(HttpPushChannel.class);
    private static final String USER_AGENT = "property-ai-push-connector/0.3 (P2 mock-channel)";
    private static final String PUSH_PATH = "/channels/push";

    private final ObjectMapper objectMapper;
    private final ChannelProperties properties;
    private final HttpClient httpClient;
    /** 通道不可达只告警一次（连接器式降级：一次性日志，不逐条刷屏） */
    private final AtomicBoolean unreachableLogged = new AtomicBoolean(false);

    public HttpPushChannel(ObjectMapper objectMapper, ChannelProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public PushReceipt push(PushMessage message) {
        if (!properties.isEnabled()) {
            throw new ConnectorException(ErrorCode.E_5001, "推送通道未启用（property.channel.enabled=false）", false,
                    Map.of("channel", "mock-channel"));
        }
        URI uri = buildUri();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("channel", message.channel());
        body.put("ticketNo", message.ticketNo());
        body.put("template", message.template());
        body.put("payload", message.payload());
        if (message.toMasked() != null && !message.toMasked().isBlank()) {
            body.put("toMasked", message.toMasked());
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("User-Agent", USER_AGENT);
        String failMode = properties.getFailMode();
        if (failMode != null && !failMode.isBlank()) {
            builder.header("X-Mock-Fail", failMode);
        }
        try {
            builder.POST(HttpRequest.BodyPublishers.ofString(
                    objectMapper.writeValueAsString(body), StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new ConnectorException(ErrorCode.E_5002, "推送请求体序列化失败", false);
        }

        HttpResponse<String> response;
        long startedAt = System.nanoTime();
        try {
            response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException ex) {
            throw new ConnectorException(ErrorCode.E_4001,
                    "推送通道超时（mock-channel，预算 " + properties.getTimeoutMs() + "ms）", false,
                    Map.of("channel", "mock-channel", "timeoutMs", properties.getTimeoutMs()));
        } catch (IOException ex) {
            if (unreachableLogged.compareAndSet(false, true)) {
                log.warn("[push] mock 推送通道不可达（{}）：后续推送直接记 failed，不阻塞工单主流程，不再逐条告警",
                        properties.getBaseUrl());
            }
            throw new ConnectorException(ErrorCode.E_4001,
                    "推送通道不可达（mock-channel：" + ex.getClass().getSimpleName() + "）", false,
                    Map.of("channel", "mock-channel"));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ConnectorException(ErrorCode.E_4001, "推送被中断", false, Map.of("channel", "mock-channel"));
        }
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
        return parseReceipt(response.statusCode(), response.body(), elapsedMs);
    }

    /** 供测试断言「不可达只告警一次」 */
    boolean unreachableLogged() {
        return unreachableLogged.get();
    }

    private PushReceipt parseReceipt(int status, String rawBody, long elapsedMs) {
        JsonNode envelope = null;
        if (rawBody != null && !rawBody.isBlank()) {
            try {
                envelope = objectMapper.readTree(rawBody);
            } catch (IOException ex) {
                if (status < 400) {
                    throw new ConnectorException(ErrorCode.E_5002, "推送通道响应不是合法 JSON", false);
                }
            }
        }
        if (status >= 400 || envelope == null || (envelope.has("ok") && !envelope.path("ok").asBoolean(true))) {
            String upstreamCode = envelope == null ? null : envelope.path("error").asText(null);
            String message = envelope == null ? null : envelope.path("message").asText(null);
            ErrorCode code = mapErrorCode(status, upstreamCode);
            throw new ConnectorException(code,
                    "推送通道返回失败（HTTP " + status + "）" + (message == null ? "" : "：" + message), false,
                    Map.of("channel", "mock-channel", "upstreamStatus", status, "latencyMs", elapsedMs));
        }
        JsonNode data = envelope.path("data");
        return new PushReceipt(
                text(data, "messageId", envelope.path("messageId").asText(null)),
                text(data, "channel", envelope.path("channel").asText(null)),
                text(data, "status", envelope.path("status").asText("queued")),
                text(data, "sentAt", null),
                text(data, "contentMasked", null),
                data.path("mock").asBoolean(envelope.path("mock").asBoolean(true)));
    }

    private static ErrorCode mapErrorCode(int status, String upstreamCode) {
        ErrorCode known = ErpErrorMapper.knownCode(upstreamCode);
        if (known != null) {
            return known;
        }
        return switch (status) {
            case 429 -> ErrorCode.E_4003;
            case 400, 422 -> ErrorCode.E_1002;
            default -> status >= 500 ? ErrorCode.E_4002 : ErrorCode.E_4001;
        };
    }

    private URI buildUri() {
        String base = properties.getBaseUrl() == null ? "" : properties.getBaseUrl().trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        StringJoiner joiner = new StringJoiner("&");
        String failMode = properties.getFailMode();
        if (failMode != null && !failMode.isBlank()) {
            joiner.add("fail=" + URLEncoder.encode(failMode, StandardCharsets.UTF_8));
        }
        String fullPath = base + PUSH_PATH;
        return joiner.length() == 0 ? URI.create(fullPath) : URI.create(fullPath + "?" + joiner);
    }

    private static String text(JsonNode node, String field, String fallback) {
        if (node == null) {
            return fallback;
        }
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? fallback : value.asText();
    }
}
