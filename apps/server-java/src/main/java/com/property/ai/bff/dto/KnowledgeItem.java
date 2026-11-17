package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * OpenAPI: KnowledgeItem（P4 W15 / v1.2.0 增量）。
 * 知识条目（P4 v1.2.0 新增独立 schema）。**回答引用四要素** = `knowledgeId` + `version` +
 * `effectiveFrom`/`effectiveTo` + `owner`，引用必须带版本；过期条目不得作为答案依据。
 * 隐私：`owner` 只回岗位/掩码名，不回手机号/工号明文。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.2.0 严格对齐；仅 DTO，不含业务逻辑。
 * 可选字段以 class 级 {@code @JsonInclude(NON_NULL)} 保证未赋值时不序列化（契约默认不返回该字段）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KnowledgeItem(
        /** 知识 ID（稳定主键，可回溯到知识库条目） */
        String knowledgeId,
        /** 条目标题 */
        String title,
        /** 答案正文（条款原文结构化，不含自由发挥；已脱敏） */
        String content,
        /** 版本号（引用必须带版本，如 V1.2） */
        String version,
        /** 生命周期状态（检索只返回 published） */
        KnowledgeItemStatus status,
        /** 责任人（岗位/掩码名） */
        String owner,
        /** 生效日 `YYYY-MM-DD` */
        String effectiveFrom,
        /** 失效日 `YYYY-MM-DD`（过期降级为「以最新公示为准」并转管家） */
        String effectiveTo,
        /** 出处（制度/条款） */
        KnowledgeSource source,
        /** 分类 */
        KnowledgeCategory category,
        /** 是否来自 badcase 回流（答错/答不上 → 回流共建） */
        boolean badcase,
        /** 关联引用（条款原文/附件/关联知识 ID；可空） */
        List<String> refs) {
}
