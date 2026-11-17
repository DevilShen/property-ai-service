package com.property.ai.bff.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * RAG 问答结果（P6 W30 / 契约 v1.4.0 增量；{@code POST /api/v1/rag/query}）。
 *
 * <p><b>契约</b>：OpenAPI v1.4.0 {@code components.schemas.RagQueryResult}
 * （与 {@code packages/shared} 的 {@code RagQueryResult} 三方同源）。字段：
 * <ul>
 *   <li>{@code answer}：答案正文（{@code answerable=false} 时为拒答说明；中文分点、先结论后依据）；</li>
 *   <li>{@code sources}：来源卡片（按 {@code index} 升序；拒答/无证据时可为空数组）；</li>
 *   <li>{@code confidence}：答案置信度 0—1（低于阈值 → {@code answerable=false} + {@code low_confidence}）；</li>
 *   <li>{@code answerable}：是否可答（false = 拒答 + 转人工，不编造）；</li>
 *   <li>{@code refusalReason}：拒答原因（可选，见 {@link RagRefusalReason}）；</li>
 *   <li>{@code mode}：链路模式（见 {@link RagMode}，必须如实反映实际链路）；</li>
 *   <li>{@code reranked}：是否实际执行并采纳 LLM-as-rerank（失败/超时/关闭 = false，原序透传）；</li>
 *   <li>{@code degraded}：降级标记（可选，见 {@link DegradedInfo}，降级不是错误）；</li>
 *   <li>{@code usage}：用量（可选，见 {@link RagUsage}）；</li>
 *   <li>{@code traceId}：链路 traceId（与响应信封同源）。</li>
 * </ul>
 *
 * <p><b>引用红线</b>：{@code answer} 中的 {@code [n]} 编号必须 ⊆ {@code sources[].index}，否则
 * {@code citation_invalid} 拒答。权限 {@code rag.query}；只读语义（不落写审计）。
 */
public record RagQueryResult(
        /** 答案正文（拒答时为拒答说明） */
        String answer,
        /** 来源卡片（按 index 升序） */
        List<RagSource> sources,
        /** 答案置信度（0—1） */
        BigDecimal confidence,
        /** 是否可答（false = 拒答 + 转人工） */
        boolean answerable,
        /** 拒答原因（可选，仅 answerable=false） */
        RagRefusalReason refusalReason,
        /** 链路模式（generative / retrieval_only / rules） */
        RagMode mode,
        /** 是否实际执行并采纳 LLM-as-rerank */
        boolean reranked,
        /** 降级标记（可选，降级不是错误） */
        DegradedInfo degraded,
        /** 用量（可选） */
        RagUsage usage,
        /** 链路 traceId */
        String traceId) {
}
