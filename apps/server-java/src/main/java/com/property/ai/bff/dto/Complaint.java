package com.property.ai.bff.dto;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * OpenAPI: Complaint（P3 W10 / v1.1.0 增量）。
 * 投诉单据（P3 W10 增量）。slaDueAt 为首响 SLA 截止（从 createdAt 起算：L1 30 分钟 /
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.1.0 严格对齐；仅 DTO，不含业务逻辑。
 * 可选字段以 class 级 {@code @JsonInclude(NON_NULL)} 保证未赋值时不序列化（契约默认不返回该字段）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Complaint(
        String complaintId,
        String roomCode,
        String ownerNameMasked,
        /** 投诉分类 code（字典项，如 noise 噪音 / parking 停车 / fee 费用 / attitude 服务态度 / public_area 公共区域 / other 其它；P3 不锁死枚 */
        String category,
        ComplaintLevel level,
        ComplaintStatus status,
        /** 投诉内容（已脱敏；六要素采集见产品 §8.3） */
        String content,
        /** 首次响应时间（first_response 动作后回带；未响应不返回） */
        String firstResponseAt,
        /** 首响 SLA 截止时间（L1 30 分钟 / L2 24 小时 / L3 2 小时） */
        String slaDueAt,
        /** 升级后受理层级/岗位（escalate 后回带，如「客服主管」） */
        String escalatedTo,
        /** 联动工单号（投诉升级为工单时回带，P3-022；未联动不返回） */
        String ticketNo,
        String createdAt,
        String updatedAt,
        /** 乐观锁版本号（transition 必须回传） */
        int version,
        List<ComplaintAuditEntry> audit) {
}
