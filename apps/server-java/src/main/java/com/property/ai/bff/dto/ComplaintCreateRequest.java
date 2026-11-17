package com.property.ai.bff.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * OpenAPI: ComplaintCreateRequest（P3 W10 / v1.1.0 增量）。
 * 提交投诉入参（P3 W10 增量）。服务端只强制 roomCode + category + content
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.1.0 严格对齐；仅 DTO，不含业务逻辑。
 * 可选字段以 class 级 {@code @JsonInclude(NON_NULL)} 保证未赋值时不序列化（契约默认不返回该字段）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ComplaintCreateRequest(
        /** 归一房号；owner 只能提交本户（跨房 → 403 E-2002） */
        String roomCode,
        /** 投诉分类 code（字典项，如 noise / parking / fee / attitude / public_area / other） */
        String category,
        ComplaintLevel level,
        /** 投诉内容（前端引导六要素；服务端落地前脱敏） */
        String content,
        /** 联系手机号后四位（仅回访用；不接收完整号码，不回传） */
        String contactTail4,
        /** 附件数量（P3 只计数，不传文件） */
        int attachments) {
}
