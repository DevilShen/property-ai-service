package com.property.ai.bff.dto;

/**
 * 知识问答请求（P6 W30 / 契约 v1.4.0 增量；{@code POST /api/v1/rag/query}）。
 *
 * <p><b>契约</b>：OpenAPI v1.4.0 {@code components.schemas.RagQueryRequest}
 * （与 {@code packages/shared} 的 {@code RagQueryRequest} 三方同源）。字段：
 * <ul>
 *   <li>{@code question}：必填，1—500 字符（超长 → 400 {@code E-1001}）；</li>
 *   <li>{@code projectId}：可选项目作用域（缺省 = 当前 actor 项目；跨项目 → 403 {@code E-2002} {@code cross_project}，服务端强制）；</li>
 *   <li>{@code category}：可选知识分类（字典见 {@link KnowledgeCategory}，未匹配 → 400 {@code E-1001}）；</li>
 *   <li>{@code topK}：可选，进入生成上下文的片段数 1—10（缺省 5，越界 → 400 {@code E-1001}）。</li>
 * </ul>
 *
 * <p>权限：{@code rag.query}；只读语义（不写业务主数据、不落写审计）。仅 DTO，不含业务逻辑。
 */
public record RagQueryRequest(
        /** 自然语言问题（必填，1—500 字符） */
        String question,
        /** 项目作用域（可选） */
        String projectId,
        /** 知识分类（可选） */
        KnowledgeCategory category,
        /** 进入生成上下文的片段数（可选，1—10，缺省 5） */
        Integer topK) {
}
