package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * OpenAPI: KnowledgeImportCommitRequest（P6 W34 / 契约 v1.5.0 增量）。
 *
 * <p>确认预览并提交：{@code POST /api/v1/knowledge/imports/{jobId}/commit} 请求体，
 * <b>全部字段可选</b>（省略 = 用任务已有 title / 分类缺省 / 全部块 / 待审）。
 * <ul>
 *   <li>{@code status} 缺省 = {@code pending_review}（默认待审，走既有 {@code /knowledge/{id}/review}）；
 *       {@code approved} 为 <b>it/admin 直审</b>（其它角色 403 {@code E-2001}）；{@code draft} 仅存草稿；</li>
 *   <li>{@code chunks} 按预览块 {@code seq} 只导入勾选块（省略 = 全部；越界 400 {@code E-1001}）；</li>
 *   <li>写操作落审计 {@code knowledge.import.commit}（资源类型 {@code knowledge}，{@code resourceId=jobId}）。</li>
 * </ul>
 *
 * <p>仅 DTO，不含业务逻辑；可选字段以 class 级 {@code @JsonInclude(NON_NULL)} 保证未赋值时不序列化。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KnowledgeImportCommitRequest(
        /** 知识标题（省略 = 任务 title/suggestedTitle） */
        String title,
        /** 分类（省略 = 任务分类/缺省分类） */
        KnowledgeCategory category,
        /** 责任人（岗位/掩码名；省略 = 创建人对应岗位） */
        String owner,
        /** 生效日 `YYYY-MM-DD`（省略 = 当日） */
        String effectiveFrom,
        /** 失效日 `YYYY-MM-DD`（倒置 400 E-1001） */
        String effectiveTo,
        /** 提交后状态（见 {@link KnowledgeImportCommitStatus}；缺省 pending_review） */
        KnowledgeImportCommitStatus status,
        /** 只导入指定预览块（按 seq；省略 = 全部块） */
        List<Integer> chunks,
        /** 审核说明/来源备注（≤500 字符，落审计与审核记录） */
        String reviewNote) {
}
