package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * OpenAPI: KnowledgeImportCreateRequest（P6 W34 / 契约 v1.5.0 增量）。
 *
 * <p>粘贴文本 / 缺口沉淀导入请求：{@code POST /api/v1/knowledge/imports} 的 {@code application/json} 分支
 * （文件/批量走同一 path 的 {@code multipart/form-data} 分支，本 DTO 不承载二进制）。
 * <ul>
 *   <li>{@code title}/{@code content} 必填；{@code content} ≤200,000 字符（超限 413 {@code E-1001}）；</li>
 *   <li>{@code sourceType} 只允许 {@code paste}（知识运营粘贴）/ {@code badcase}（RAG 拒答缺口沉淀）；</li>
 *   <li>{@code projectId} 由服务端按 actor 项目强制（缺省 = 当前 actor 项目；跨项目 403 {@code E-2002}
 *       {@code cross_project}），前端传参不是安全边界。</li>
 * </ul>
 *
 * <p>仅 DTO，不含业务逻辑；可选字段以 class 级 {@code @JsonInclude(NON_NULL)} 保证未赋值时不序列化。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KnowledgeImportCreateRequest(
        /** 知识标题（1—200 字符） */
        String title,
        /** 粘贴正文（Markdown/纯文本；1—200,000 字符） */
        String content,
        /** 项目作用域（可选；缺省 = 当前 actor 项目） */
        String projectId,
        /** 分类（可选；复用既有 KnowledgeCategory） */
        KnowledgeCategory category,
        /** 入口类型：paste（缺省）/ badcase */
        String sourceType,
        /** 生效日 `YYYY-MM-DD`（可选，预览页预填；最终以 commit 为准） */
        String effectiveFrom,
        /** 失效日 `YYYY-MM-DD`（可选） */
        String effectiveTo,
        /** 责任人（岗位/掩码名，可选） */
        String owner) {
}
