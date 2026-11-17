package com.property.ai.bff.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * OpenAPI: Ticket（工单详情（7 节点时间轴））。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.1.0 严格对齐；仅 DTO，不含业务逻辑。
 *
 * <p>v0.3.1 增量：末尾追加可选 {@code feedback}（回访评价落库，刷新后仍在）。
 * v1.1.0 增量（P3 W10）：末尾继续追加可选 {@code ownerId}（业主主体 ID，数据范围归属；架构 §14.2）。
 * 为不破坏 P2 既有 {@code new Ticket(...)} 构造调用（Service/领域服务/测试），保留
 * 24 参数（含 feedback、无 ownerId）与 23 参数（W5 形状）两个旧构造重载；
 * {@code feedback} / {@code ownerId} 为 null 时不序列化（{@code @JsonInclude(NON_NULL)}），
 * P2 响应形状不变。</p>
 */
public record Ticket(
        String ticketNo,
        String projectId,
        String householdId,
        String roomCode,
        TicketCategory category,
        TicketUrgency urgency,
        String location,
        IssueScope downstairs,
        String duration,
        String slot,
        String note,
        String symptom,
        TicketStatus status,
        String statusText,
        String team,
        String eta,
        List<TicketAuditNode> nodes,
        /** 乐观锁版本号 */
        int version,
        boolean aiCreated,
        boolean mock,
        ErpRef erp,
        String traceId,
        String createdAt,
        String updatedAt,
        /** v0.3.1 增量：回访评价（可为 null） */
        TicketFeedbackSummary feedback,
        /** v1.1.0 增量：业主主体 ID（数据范围归属，如 owner-3-2-1802；staff 端点可为 null） */
        String ownerId) {

    /** v0.3.1 兼容构造（含 feedback、无 ownerId；P2 W5—W8 既有调用不破）。 */
    public Ticket(
            String ticketNo,
            String projectId,
            String householdId,
            String roomCode,
            TicketCategory category,
            TicketUrgency urgency,
            String location,
            IssueScope downstairs,
            String duration,
            String slot,
            String note,
            String symptom,
            TicketStatus status,
            String statusText,
            String team,
            String eta,
            List<TicketAuditNode> nodes,
            int version,
            boolean aiCreated,
            boolean mock,
            ErpRef erp,
            String traceId,
            String createdAt,
            String updatedAt,
            TicketFeedbackSummary feedback) {
        this(ticketNo, projectId, householdId, roomCode, category, urgency, location, downstairs,
                duration, slot, note, symptom, status, statusText, team, eta, nodes, version,
                aiCreated, mock, erp, traceId, createdAt, updatedAt, feedback, null);
    }

    /** P2 W5 兼容构造（无 feedback / 无 ownerId；null 时不序列化）。 */
    public Ticket(
            String ticketNo,
            String projectId,
            String householdId,
            String roomCode,
            TicketCategory category,
            TicketUrgency urgency,
            String location,
            IssueScope downstairs,
            String duration,
            String slot,
            String note,
            String symptom,
            TicketStatus status,
            String statusText,
            String team,
            String eta,
            List<TicketAuditNode> nodes,
            int version,
            boolean aiCreated,
            boolean mock,
            ErpRef erp,
            String traceId,
            String createdAt,
            String updatedAt) {
        this(ticketNo, projectId, householdId, roomCode, category, urgency, location, downstairs,
                duration, slot, note, symptom, status, statusText, team, eta, nodes, version,
                aiCreated, mock, erp, traceId, createdAt, updatedAt, null, null);
    }

    /** v0.3.1 增量字段：未回访时省略，保持 W5 响应形状。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Override
    public TicketFeedbackSummary feedback() {
        return feedback;
    }

    /** v1.1.0 增量字段：无归属主体时省略（P2 响应形状不破坏）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Override
    public String ownerId() {
        return ownerId;
    }
}
