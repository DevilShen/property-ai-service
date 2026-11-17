package com.property.ai.bff.dto;

/**
 * RAG 单组件健康（P6 W30 / 契约 v1.4.0 增量；{@code RagHealth.components[]}）。
 *
 * <p><b>契约</b>：OpenAPI v1.4.0 {@code components.schemas.RagComponent}。
 * {@code name} = {@code orchestrator} / {@code llm} / {@code embedding} / {@code vectorStore} / {@code reranker}
 * （组件顺序固定）；{@code status} = {@code ok|degraded|down}（{@code down} = 不可用，
 * {@code degraded} = 可用但降级/mock）；{@code detail} = 人话说明（只含模型名/计数/开关，
 * <b>不含密钥、内网 baseUrl 与业主隐私</b>）。
 */
public record RagComponent(
        /** 组件名（orchestrator / llm / embedding / vectorStore / reranker） */
        String name,
        /** 组件状态（ok / degraded / down） */
        String status,
        /** 人话说明（模型名/数量/开关；不含密钥与内网地址） */
        String detail) {
}
