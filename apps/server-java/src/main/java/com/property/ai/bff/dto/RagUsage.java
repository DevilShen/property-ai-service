package com.property.ai.bff.dto;

/**
 * RAG 用量（P6 W30 / 契约 v1.4.0 增量；{@code RagQueryResult.usage}）。
 *
 * <p><b>契约</b>：OpenAPI v1.4.0 {@code components.schemas.RagUsage}，<b>全部字段可选</b>
 * （未统计时省略 = null，Jackson 序列化不输出）。只用于成本观测与评测，不参与业务判定；
 * 无 LLM 调用（{@code mode=retrieval_only|rules}）时可省略，或仅给 {@code rerankCalls=0}。
 */
public record RagUsage(
        /** 生成阶段输入 tokens（省略 = 未统计） */
        Integer promptTokens,
        /** 生成阶段输出 tokens（省略 = 未统计） */
        Integer completionTokens,
        /** LLM-as-rerank 调用次数（0 = 未重排/原序透传） */
        Integer rerankCalls) {
}
