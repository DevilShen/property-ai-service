package com.property.ai.bff.dto;

import java.util.List;

/**
 * OpenAPI: KnowledgeListResult（P4 W15 / v1.2.0 增量）。
 * 知识分页结果（分页约定与 README §2 一致）。`status`/`category`/`owner`/生效期过滤由服务端执行，
 * **owner 角色只返回 `published`**（服务端强制，前端隐藏入口不是安全边界）。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.2.0 严格对齐；仅 DTO，不含业务逻辑。
 */
public record KnowledgeListResult(
        /** 知识条目 */
        List<KnowledgeItem> items,
        /** 页码（从 1 开始） */
        int page,
        /** 每页条数（默认 20，上限 100） */
        int pageSize,
        /** 总条数 */
        int total) {
}
