package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * OpenAPI: KnowledgeDetail（P4 W15 / v1.2.0 增量）。
 * 知识条目详情（P4 v1.2.0）：`KnowledgeItem` 全字段 + `versions[]` 版本历史。
 * 不做「详情/列表」字段分叉：列表用 `KnowledgeItem`，详情在其上追加 `versions`。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.2.0 严格对齐；仅 DTO，不含业务逻辑。
 * 可选字段以 class 级 {@code @JsonInclude(NON_NULL)} 保证未赋值时不序列化（契约默认不返回该字段）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KnowledgeDetail(
        /** 知识 ID */
        String knowledgeId,
        /** 条目标题 */
        String title,
        /** 答案正文（已脱敏） */
        String content,
        /** 当前版本号 */
        String version,
        /** 当前状态 */
        KnowledgeItemStatus status,
        /** 责任人（岗位/掩码名） */
        String owner,
        /** 生效日 */
        String effectiveFrom,
        /** 失效日 */
        String effectiveTo,
        /** 出处 */
        KnowledgeSource source,
        /** 分类 */
        KnowledgeCategory category,
        /** 是否来自 badcase 回流 */
        boolean badcase,
        /** 关联引用（可空） */
        List<String> refs,
        /** 版本历史（按提交时间升序） */
        List<KnowledgeVersion> versions) {
}
