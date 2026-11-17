package com.property.ai.bff.dto;

import java.util.List;

/**
 * RAG 编排健康与模型信息（P6 W30 / 契约 v1.4.0 增量；{@code GET /api/v1/rag/health}）。
 *
 * <p><b>契约</b>：OpenAPI v1.4.0 {@code components.schemas.RagHealth}
 * （与 {@code packages/shared} 的 {@code RagHealth} 三方同源）。字段：
 * <ul>
 *   <li>{@code status}：{@code ok}（全部组件 ok）/ {@code degraded}（存在 degraded/down）/
 *       {@code down}（编排层或向量库不可用）；</li>
 *   <li>{@code components[]}：组件健康（见 {@link RagComponent}，顺序固定：orchestrator → llm → embedding →
 *       vectorStore → reranker）；</li>
 *   <li>{@code llm}：{@code provider}/{@code model}/{@code mode}（mode = {@code relay|mock|retrieval_only}）；</li>
 *   <li>{@code embedding}：{@code model}/{@code dim}；</li>
 *   <li>{@code vectorStore}：{@code backend}/{@code count}（chroma / memory）；</li>
 *   <li>{@code reranked}：是否开启 LLM-as-rerank；{@code degraded}：是否存在降级；</li>
 *   <li>{@code generatedAt}：生成时间（ISO 8601）。</li>
 * </ul>
 *
 * <p>权限：{@code rag.health}（<b>仅 it/admin</b>）；只读端点不落审计、不触发模型生成。
 * <b>demo 边界</b>：{@code llm.mode} 必须如实标注，无真实中转凭据时 {@code degraded=true}，
 * 不得把 PoC 模型/本地向量库说成生产可用（AGENTS.md NEVER #11）；不含密钥与 baseUrl。
 */
public record RagHealth(
        String status,
        List<RagComponent> components,
        Llm llm,
        Embedding embedding,
        VectorStore vectorStore,
        boolean reranked,
        boolean degraded,
        String generatedAt) {

    /** LLM（公司中转 / 本地 PoC）信息（mode: relay|mock|retrieval_only） */
    public record Llm(String provider, String model, String mode) {
    }

    /** Embedding 模型信息（model + 向量维度 dim） */
    public record Embedding(String model, int dim) {
    }

    /** 向量库信息（backend: chroma|memory；count = 已索引片段数） */
    public record VectorStore(String backend, int count) {
    }
}
