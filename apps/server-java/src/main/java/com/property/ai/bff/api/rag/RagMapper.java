package com.property.ai.bff.api.rag;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.property.ai.bff.dto.DegradedFallback;
import com.property.ai.bff.dto.DegradedInfo;
import com.property.ai.bff.dto.ErrorCode;
import com.property.ai.bff.dto.KnowledgeCategory;
import com.property.ai.bff.dto.KnowledgeSearchHit;
import com.property.ai.bff.dto.KnowledgeSearchResult;
import com.property.ai.bff.dto.RagComponent;
import com.property.ai.bff.dto.RagHealth;
import com.property.ai.bff.dto.RagMode;
import com.property.ai.bff.dto.RagQueryResult;
import com.property.ai.bff.dto.RagRefusalReason;
import com.property.ai.bff.dto.RagSource;
import com.property.ai.domain.knowledge.KnowledgeService;
import com.property.ai.domain.ticket.TicketTimes;

/**
 * RAG 契约映射（P6 W30 / task-80）：编排层内部响应 → 契约 v1.4.0 DTO；编排层不可用时的规则检索降级；健康聚合。
 *
 * <h2>映射原则（不编造）</h2>
 * <ul>
 *   <li><b>mode 如实反映链路</b>：编排层 {generative|retrieval_only|rules} 原样映射；未知取值按
 *       {@link RagMode#RETRIEVAL_ONLY} 保守处理（<b>不得把仅检索说成生成</b>，AGENTS.md NEVER #11）；</li>
 *   <li><b>来源卡片可追溯</b>：{@code version}/{@code owner}/{@code category} 优先用编排层返回，
 *       缺失时用知识库现状补齐（{@link KnowledgeService#views(String)}）；两者都没有 → 标「未声明」，不猜版本；</li>
 *   <li><b>snippet 只回脱敏片段</b>：≤{@value #MAX_SNIPPET} 字（不返回索引正文全文，NEVER #8）；</li>
 *   <li><b>降级不是错误</b>：编排层不可用 → {@code mode=rules} + {@code degraded{reason=rag_unavailable…}}
 *       + 2xx；无命中 → {@code answerable=false} + {@code no_evidence}（拒答转人工，不编造答案）。</li>
 * </ul>
 *
 * <h2>健康聚合口径</h2>
 * {@code status} = {@code ok}（全部组件 ok）/ {@code degraded}（存在 degraded/down，或 LLM 非 relay 真实中转）/
 * {@code down}（编排层或向量库不可用，且编排层自报 down）。编排层不可达 → {@code degraded} + orchestrator
 * 组件 {@code down}；detail 只含模型名/计数/开关与「token 是否配置」布尔，<b>不含 token 与内网 base-url</b>。
 */
final class RagMapper {

    /** 契约 RagSource.snippet「约 ≤200 字」 */
    static final int MAX_SNIPPET = 200;
    /** 规则检索降级取 top5（契约 topK 缺省 5） */
    static final int RULES_FALLBACK_TOP_K = 5;
    /** 编排层未提供时的占位（明确「未声明」，不是编造值） */
    static final String UNDECLARED = "未声明";
    /** 固定组件顺序（契约 RagHealth.components 顺序固定） */
    static final List<String> COMPONENT_ORDER =
            List.of("orchestrator", "llm", "embedding", "vectorStore", "reranker");
    /** 契约 {@code RagComponent.status} 枚举（v1.4.0 只允许 ok|degraded|down） */
    static final Set<String> COMPONENT_STATUSES = Set.of("ok", "degraded", "down");

    private RagMapper() {
    }

    // ==================================================================
    // 问答：编排层响应 → 契约 DTO
    // ==================================================================

    /**
     * 编排层应答 → {@code RagQueryResult}。
     *
     * @param internal       编排层返回（字段可为 null）
     * @param requestTraceId BFF 链路 traceId（契约要求 result.traceId 与响应信封同源）
     * @param views          本项目可见知识视图（{@code knowledgeId → View}，用于补齐 version/owner/category/snippet）
     */
    static RagQueryResult fromInternal(RagClient.InternalAnswer internal, String requestTraceId,
            Map<String, KnowledgeService.View> views) {
        List<RagSource> sources = new ArrayList<>();
        int fallbackIndex = 0;
        for (RagClient.InternalSource source : internal.sources()) {
            fallbackIndex++;
            int index = source.index() > 0 ? source.index() : fallbackIndex;
            KnowledgeService.View view = viewOf(views, source.knowledgeId());
            String version = firstNonBlank(source.version(), view == null ? null : view.version(), UNDECLARED);
            String owner = firstNonBlank(source.owner(), view == null ? null : view.owner(), UNDECLARED);
            KnowledgeCategory category = parseCategory(source.category(),
                    view == null ? null : view.category());
            String title = firstNonBlank(source.title(), view == null ? null : view.title(),
                    firstNonBlank(source.knowledgeId(), UNDECLARED));
            String snippet = firstNonBlank(source.snippet(), view == null ? null : truncate(view.content()), "");
            sources.add(new RagSource(index, firstNonBlank(source.knowledgeId(), UNDECLARED), title, version, owner,
                    category, clampScore(source.score()), snippet));
        }
        sources.sort((left, right) -> Integer.compare(left.index(), right.index()));

        RagMode mode = parseMode(internal.mode());
        RagRefusalReason refusalReason = parseRefusalReason(internal.refusalReason());
        boolean answerable = internal.answerable();
        if (!answerable && refusalReason == null) {
            // 编排层判拒答但未给原因：按「无证据」口径（不新造原因，不编造答案）
            refusalReason = RagRefusalReason.NO_EVIDENCE;
        }
        if (answerable) {
            // 可答时 refusalReason 不应出现（契约：仅 answerable=false 时返回）
            refusalReason = null;
        }
        DegradedInfo degraded = degradedFromInternal(internal.degraded(), mode);
        return new RagQueryResult(firstNonBlank(internal.answer(), ""), List.copyOf(sources),
                confidence(internal.confidence()), answerable, refusalReason, mode, internal.reranked(),
                degraded, internal.usage(), requestTraceId);
    }

    /** 编排层 `degraded` 是 bool（W33 内部口径）→ 契约 {@link DegradedInfo}。 */
    static DegradedInfo degradedFromInternal(JsonNode node, RagMode mode) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isBoolean()) {
            if (!node.asBoolean()) {
                return null;
            }
            String reason = mode == RagMode.GENERATIVE
                    ? "rag_degraded：编排层标记降级（生成链路降级，已返回可用答案）"
                    : "rag_degraded：编排层标记降级（" + mode.value() + " 链路，未使用完整生成）";
            return new DegradedInfo(reason, DegradedFallback.NONE, true, ErrorCode.E_5001, null);
        }
        if (!node.isObject()) {
            return null;
        }
        String reason = text(node, "reason");
        if (reason == null || reason.isBlank()) {
            reason = "rag_degraded：编排层标记降级";
        }
        DegradedFallback fallback = parseFallback(text(node, "fallback"));
        boolean retryable = node.path("retryable").isBoolean() && node.path("retryable").asBoolean();
        ErrorCode code = parseCode(text(node, "code"));
        return new DegradedInfo(reason, fallback, retryable, code, text(node, "dataAsOf"));
    }

    // ==================================================================
    // 问答：规则检索降级（编排层不可用/超时/5xx）
    // ==================================================================

    /**
     * 规则检索降级结果（{@code KnowledgeService.search} top5 → sources，{@code mode=rules}）。
     *
     * <p>语义：<b>不生成答案</b>，只回规则命中的现行条文原文片段 + {@code [n]} 编号；
     * 无命中 → {@code answerable=false} + {@code no_evidence} + 转人工。降级原因带 {@code rag_unavailable}
     * 标记与失败分类（超时/不可达/上游错误/端点未就绪），便于前端与运维定位。
     */
    static RagQueryResult rulesFallback(KnowledgeSearchResult search, Map<String, KnowledgeService.View> views,
            String requestTraceId, RagClientException failure) {
        List<RagSource> sources = new ArrayList<>();
        List<KnowledgeSearchHit> hits = search == null ? List.of() : search.items();
        int index = 0;
        for (KnowledgeSearchHit hit : hits) {
            if (index >= RULES_FALLBACK_TOP_K) {
                break;
            }
            index++;
            KnowledgeService.View view = viewOf(views, hit.knowledgeId());
            KnowledgeCategory category = view == null ? KnowledgeCategory.OTHER : view.category();
            String snippet = view == null ? "" : truncate(view.content());
            sources.add(new RagSource(index, hit.knowledgeId(), hit.title(), hit.version(), hit.owner(), category,
                    clampScore(hit.score()), snippet));
        }
        boolean answerable = !sources.isEmpty();
        String answer = answerable ? rulesAnswer(sources) : rulesRefusalAnswer();
        BigDecimal confidence = answerable ? clamp(sources.get(0).score()) : scale(0);
        DegradedInfo degraded = new DegradedInfo(
                "rag_unavailable[" + failure.kind().name().toLowerCase(Locale.ROOT) + "]："
                        + failure.kind().label() + "（" + failure.getMessage() + "），"
                        + (answerable ? "已降级为规则检索（GET /knowledge/search top5），仅返回原文片段，未生成答案"
                                : "已降级为规则检索但无命中，按无证据拒答并转人工"),
                DegradedFallback.HUMAN, failure.retryable(), failure.code(), null);
        return new RagQueryResult(answer, List.copyOf(sources), confidence, answerable,
                answerable ? null : RagRefusalReason.NO_EVIDENCE, RagMode.RULES, false, degraded, null,
                requestTraceId);
    }

    /** 规则检索命中时的答案：原文片段 + [n] 编号（不归纳、不补数字，避免编造）。 */
    private static String rulesAnswer(List<RagSource> sources) {
        StringBuilder builder = new StringBuilder("【规则检索模式】编排层不可用，以下为知识库命中的现行条文原文片段"
                + "（未生成答案，仅按关键词规则检索返回）：\n");
        for (RagSource source : sources) {
            builder.append('[').append(source.index()).append("] ")
                    .append(source.knowledgeId()).append(' ').append(source.version())
                    .append("《").append(source.title()).append("》：")
                    .append(source.snippet()).append('\n');
        }
        builder.append("如需进一步解释，请转人工客服。");
        return builder.toString();
    }

    private static String rulesRefusalAnswer() {
        return "知识库中未检索到与该问题相关的现行条文（规则检索模式）。"
                + "已记录该缺口并转人工客服跟进，请勿依据本回复自行操作。";
    }

    // ==================================================================
    // 健康
    // ==================================================================

    /**
     * 编排层可达 → 契约 {@code RagHealth}（优先读 W33 扩展字段，缺失按 W32 字段保守推导）。
     */
    static RagHealth healthReachable(JsonNode root, RagProperties properties) {
        JsonNode payload = unwrap(root);
        String service = text(payload, "service");
        long uptime = payload.path("uptimeSeconds").isNumber() ? payload.path("uptimeSeconds").asLong() : -1;
        String javaConfig = "BFF 配置：enabled=" + properties.isEnabled() + "，timeout-ms="
                + properties.effectiveTimeoutMs() + "，token=" + (properties.hasInternalToken() ? "已配置" : "未配置");

        Map<String, RagComponent> components = new LinkedHashMap<>();
        components.put("orchestrator", new RagComponent("orchestrator", "ok",
                "编排层 /internal/rag/health 可达" + (service == null ? "" : "（service=" + service + "）")
                        + (uptime >= 0 ? "，uptime=" + uptime + "s" : "") + "；" + javaConfig));

        // LLM：优先读 W33 显式对象；否则按「未声明运行模式」保守标 retrieval_only + degraded
        JsonNode llmNode = payload.path("llm");
        String llmProvider;
        String llmModel;
        String llmMode;
        String llmStatus;
        if (llmNode.isObject()) {
            llmProvider = firstNonBlank(text(llmNode, "provider"), "relay");
            llmModel = firstNonBlank(text(llmNode, "model"), text(payload.path("models"), "llm"), UNDECLARED);
            llmMode = normalizeLlmMode(text(llmNode, "mode"), llmNode.path("canCall"));
            // W34：mode 如实（relay 之外一律 degraded），status 归一化为契约枚举（编排层旧版可能给 mock）
            llmStatus = normalizeComponentStatus(text(llmNode, "status"),
                    "relay".equals(llmMode) ? "ok" : "degraded");
            String llmBudget = llmNode.path("timeoutMs").isNumber()
                    ? "，生成预算=" + llmNode.path("timeoutMs").asLong() + "ms（BFF 预算="
                            + properties.effectiveTimeoutMs() + "ms；超预算即 rules 降级）"
                    : "";
            components.put("llm", new RagComponent("llm", llmStatus,
                    "provider=" + llmProvider + "，model=" + llmModel + "，mode=" + llmMode
                            + ("relay".equals(llmMode) ? "" : "（生成不可用，不报 relay）") + llmBudget));
        } else {
            llmProvider = "orchestrator";
            llmModel = firstNonBlank(text(payload.path("models"), "llm"), UNDECLARED);
            llmMode = "retrieval_only";
            components.put("llm", new RagComponent("llm", "degraded",
                    "编排层 health 未声明 LLM 运行模式（model=" + llmModel + "）；按仅检索口径标注（不猜成 relay）"));
        }

        // Embedding：W33 显式对象 → W32 models.embed / models.embed_dim
        JsonNode embeddingNode = payload.path("embedding");
        String embedModel;
        int embedDim;
        if (embeddingNode.isObject()) {
            embedModel = firstNonBlank(text(embeddingNode, "model"), UNDECLARED);
            embedDim = embeddingNode.path("dim").isNumber() ? embeddingNode.path("dim").asInt() : 1;
            // W34：编排层旧版把 mock embedding 报成 status=mock（非契约枚举）→ 归一化为 degraded
            components.put("embedding", new RagComponent("embedding",
                    normalizeComponentStatus(text(embeddingNode, "status"),
                            embeddingNode.path("mock").asBoolean(false) ? "degraded" : "ok"),
                    "model=" + embedModel + "，dim=" + embedDim
                            + (embeddingNode.path("mock").asBoolean(false) ? "，mock（非真实向量）" : "")));
        } else {
            JsonNode models = payload.path("models");
            embedModel = firstNonBlank(stripProvider(text(models, "embed")), UNDECLARED);
            embedDim = models.path("embed_dim").isNumber() ? models.path("embed_dim").asInt() : 1;
            components.put("embedding", new RagComponent("embedding", "ok",
                    "model=" + embedModel + "，dim=" + embedDim + (models.has("embed_dim") ? "" : "（维度未声明，占位 1）")));
        }

        // 向量库：W33 显式对象 → W32 index{backend,collection,count}
        JsonNode storeNode = payload.path("vectorStore");
        String storeBackend;
        int storeCount;
        if (storeNode.isObject()) {
            storeBackend = firstNonBlank(text(storeNode, "backend"), UNDECLARED);
            storeCount = storeNode.path("count").isNumber() ? storeNode.path("count").asInt() : 0;
            components.put("vectorStore", new RagComponent("vectorStore",
                    normalizeComponentStatus(text(storeNode, "status"), "ok"),
                    "backend=" + storeBackend + "，count=" + storeCount));
        } else {
            JsonNode index = payload.path("index");
            storeBackend = firstNonBlank(text(index, "backend"), UNDECLARED);
            storeCount = index.path("chunks").isNumber() ? index.path("chunks").asInt() : 0;
            components.put("vectorStore", new RagComponent("vectorStore", "ok",
                    "backend=" + storeBackend + "，count=" + storeCount));
        }

        // 重排：W33 reranker 对象 → W32 models.rerank + rerankMode
        // W34 语义修复（task-82）：`mode=off` / `enabled=false` 是**策略默认**（W30/W32 实测 k3 推理型在线不可用），
        // 报 status=ok + 「重排已关闭（enabled=false，策略默认）」，不得让 /rag/health 因此误报 degraded=true；
        // 只有「重排开启但模型不可用」才是真实降级 → degraded。
        JsonNode rerankerNode = payload.path("reranker");
        boolean reranked;
        String rerankDetail;
        if (rerankerNode.isObject()) {
            boolean enabled = !rerankerNode.has("enabled") || rerankerNode.path("enabled").asBoolean(true);
            String rerankMode = firstNonBlank(text(rerankerNode, "mode"), "off");
            boolean policyOff = !enabled || "off".equalsIgnoreCase(rerankMode);
            reranked = !policyOff;
            String modelPart = text(rerankerNode, "model") == null ? "" : "，model=" + text(rerankerNode, "model");
            if (policyOff) {
                rerankDetail = "重排已关闭（enabled=false，策略默认），mode=" + rerankMode + modelPart;
                components.put("reranker", new RagComponent("reranker", "ok", rerankDetail));
            } else {
                rerankDetail = "mode=" + rerankMode + "，enabled=true" + modelPart
                        + (text(rerankerNode, "detail") == null ? "" : "；" + text(rerankerNode, "detail"));
                components.put("reranker", new RagComponent("reranker",
                        normalizeComponentStatus(text(rerankerNode, "status"), "ok"), rerankDetail));
            }
        } else {
            String rerankMode = firstNonBlank(text(payload, "rerankMode"), "off");
            boolean policyOff = "off".equalsIgnoreCase(rerankMode);
            reranked = !policyOff;
            rerankDetail = "rerankMode=" + rerankMode
                    + (text(payload.path("models"), "rerank") == null ? ""
                            : "，model=" + text(payload.path("models"), "rerank"));
            components.put("reranker", new RagComponent("reranker", "ok",
                    policyOff ? "重排已关闭（enabled=false，策略默认），" + rerankDetail : rerankDetail));
        }

        // 编排层若直接给了 components[]，以编排层为准覆盖同名项（保留固定顺序 + 未知名追加）；
        // W34：覆盖时同样做枚举归一化（不接受 off/mock 等非契约取值）。
        JsonNode declared = payload.path("components");
        if (declared.isArray()) {
            for (JsonNode item : declared) {
                String name = text(item, "name");
                if (name == null) {
                    continue;
                }
                components.put(name, new RagComponent(name,
                        normalizeComponentStatus(text(item, "status"), "degraded"),
                        firstNonBlank(text(item, "detail"), "")));
            }
        }

        List<RagComponent> ordered = new ArrayList<>();
        for (String name : COMPONENT_ORDER) {
            RagComponent component = components.remove(name);
            if (component != null) {
                ordered.add(component);
            }
        }
        ordered.addAll(components.values());

        boolean allOk = ordered.stream().allMatch(component -> "ok".equals(component.status()));
        boolean anyDown = ordered.stream().anyMatch(component -> "down".equals(component.status()));
        boolean selfDegraded = payload.path("degraded").isBoolean() && payload.path("degraded").asBoolean();
        String declaredStatus = text(payload, "status");
        boolean declaredDegraded = "degraded".equals(declaredStatus);
        boolean degraded = !allOk || selfDegraded || declaredDegraded || !"relay".equals(llmMode);
        String status;
        if ("down".equals(declaredStatus) || anyDown) {
            status = "down";
        } else if (degraded) {
            status = "degraded";
        } else {
            status = "ok";
        }
        RagHealth.Llm llm = new RagHealth.Llm(llmProvider, llmModel, llmMode);
        RagHealth.Embedding embedding = new RagHealth.Embedding(embedModel, Math.max(1, embedDim));
        RagHealth.VectorStore vectorStore = new RagHealth.VectorStore(storeBackend, Math.max(0, storeCount));
        return new RagHealth(status, List.copyOf(ordered), llm, embedding, vectorStore, reranked, degraded,
                TicketTimes.nowIso());
    }

    /** 编排层不可达 → 契约 {@code RagHealth}（status=degraded，components 全部 down，保守标注不猜模型）。 */
    static RagHealth healthUnreachable(RagClientException failure, RagProperties properties) {
        String detail = "编排层不可达（" + failure.kind().label() + "：" + failure.getMessage()
                + "）；BFF 配置：enabled=" + properties.isEnabled() + "，timeout-ms="
                + properties.effectiveTimeoutMs() + "，token="
                + (properties.hasInternalToken() ? "已配置" : "未配置");
        List<RagComponent> components = List.of(
                new RagComponent("orchestrator", "down", detail),
                new RagComponent("llm", "down", "编排层不可达，无法探测 LLM（无生成能力）"),
                new RagComponent("embedding", "down", "编排层不可达，无法探测 embedding"),
                new RagComponent("vectorStore", "down", "编排层不可达，无法探测向量库"),
                new RagComponent("reranker", "down", "编排层不可达，无法探测重排开关"));
        return new RagHealth("degraded", components,
                new RagHealth.Llm("unknown", UNDECLARED, "retrieval_only"),
                new RagHealth.Embedding(UNDECLARED, 1),
                new RagHealth.VectorStore(UNDECLARED, 0),
                false, true, TicketTimes.nowIso());
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /** 知识视图索引（大小写不敏感；{@code null/空} → 空表）。 */
    static Map<String, KnowledgeService.View> indexViews(List<KnowledgeService.View> views) {
        Map<String, KnowledgeService.View> index = new LinkedHashMap<>();
        if (views == null) {
            return index;
        }
        for (KnowledgeService.View view : views) {
            if (view != null && view.knowledgeId() != null) {
                index.put(view.knowledgeId().toUpperCase(Locale.ROOT), view);
            }
        }
        return index;
    }

    private static KnowledgeService.View viewOf(Map<String, KnowledgeService.View> views, String knowledgeId) {
        if (views == null || knowledgeId == null || knowledgeId.isBlank()) {
            return null;
        }
        return views.get(knowledgeId.trim().toUpperCase(Locale.ROOT));
    }

    static RagMode parseMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return RagMode.RETRIEVAL_ONLY;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        for (RagMode mode : RagMode.values()) {
            if (mode.value().equals(value)) {
                return mode;
            }
        }
        // 未知取值不虚构为 generative（NEVER #11）
        return RagMode.RETRIEVAL_ONLY;
    }

    static RagRefusalReason parseRefusalReason(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        for (RagRefusalReason reason : RagRefusalReason.values()) {
            if (reason.value().equals(value)) {
                return reason;
            }
        }
        return RagRefusalReason.NO_EVIDENCE;
    }

    private static KnowledgeCategory parseCategory(String raw, KnowledgeCategory fallback) {
        if (raw != null && !raw.isBlank()) {
            String value = raw.trim().toLowerCase(Locale.ROOT);
            for (KnowledgeCategory category : KnowledgeCategory.values()) {
                if (category.value().equals(value)) {
                    return category;
                }
            }
        }
        return fallback == null ? KnowledgeCategory.OTHER : fallback;
    }

    private static DegradedFallback parseFallback(String raw) {
        if (raw != null && !raw.isBlank()) {
            try {
                return DegradedFallback.fromValue(raw.trim().toLowerCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // 契约枚举外取值：按无替代路径处理
            }
        }
        return DegradedFallback.NONE;
    }

    private static ErrorCode parseCode(String raw) {
        if (raw != null && !raw.isBlank()) {
            try {
                return ErrorCode.fromValue(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // 非契约错误码：不采纳
            }
        }
        return null;
    }

    private static String normalizeLlmMode(String raw, JsonNode canCall) {
        String mode = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (Set.of("relay", "mock", "retrieval_only").contains(mode)) {
            return mode;
        }
        if (canCall.isBoolean() && !canCall.asBoolean()) {
            return "retrieval_only";
        }
        return "retrieval_only";
    }

    /**
     * 组件状态归一化 → 契约 {@code RagComponent.status} 枚举 {@code ok|degraded|down}（W34 语义修复）。
     *
     * <p>为什么需要：编排层旧版对「重排关闭」报 {@code off}、对 mock embedding 报 {@code mock}，
     * 都不是契约枚举值；Java 直通会让 {@code /rag/health} 输出非法枚举，并让「策略默认关闭」
     * 被误判成降级（{@code degraded=true}），从而污染演示/运维判断。
     * <ul>
     *   <li>已知枚举 {@code ok|degraded|down} → 原样透传；</li>
     *   <li>{@code off|disabled|not_enabled|skip|skipped} → {@code ok}（策略关闭不是故障）；</li>
     *   <li>其它未知取值（含 {@code mock}、空串、拼写错误）→ {@code degraded}（保守：可用但降级）。</li>
     * </ul>
     */
    static String normalizeComponentStatus(String raw, String fallback) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (COMPONENT_STATUSES.contains(value)) {
            return value;
        }
        if (value.isEmpty()) {
            return fallback == null || fallback.isBlank() ? "degraded" : fallback.trim().toLowerCase(Locale.ROOT);
        }
        if (Set.of("off", "disabled", "not_enabled", "not-enabled", "skip", "skipped").contains(value)) {
            return "ok";
        }
        return "degraded";
    }


    private static String stripProvider(String embed) {
        if (embed == null) {
            return null;
        }
        int colon = embed.indexOf(':');
        return colon >= 0 && colon + 1 < embed.length() ? embed.substring(colon + 1) : embed;
    }

    private static JsonNode unwrap(JsonNode root) {
        if (root == null || !root.isObject()) {
            return root;
        }
        JsonNode data = root.path("data");
        return data.isObject() ? data : root;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /** 契约 0—1 分数（缺失/越界收敛到 [0,1]，保留两位）。 */
    private static BigDecimal clamp(Double value) {
        if (value == null || value.isNaN()) {
            return scale(0);
        }
        return scale(Math.max(0, Math.min(1, value)));
    }

    /** {@code RagSource.score} 是 double 原语：同一 0—1 收敛口径，保留两位小数。 */
    private static double clampScore(Double value) {
        return clamp(value).doubleValue();
    }

    private static BigDecimal scale(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal confidence(Double value) {
        return clamp(value);
    }

    /** 片段截断：去换行/多空白，≤{@value #MAX_SNIPPET} 字（超出加省略号）。 */
    static String truncate(String content) {
        if (content == null) {
            return "";
        }
        String oneLine = content.replaceAll("\\s+", " ").trim();
        if (oneLine.length() <= MAX_SNIPPET) {
            return oneLine;
        }
        return oneLine.substring(0, MAX_SNIPPET) + "…";
    }
}
