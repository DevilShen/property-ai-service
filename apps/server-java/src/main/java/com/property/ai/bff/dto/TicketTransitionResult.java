package com.property.ai.bff.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * OpenAPI: TicketTransitionResult（状态迁移结果（新状态 + 新增审计节点 + v0.3.1 回写/派单回显））。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v0.3.1 严格对齐；仅 DTO，不含业务逻辑。
 *
 * <p>v0.3.1 增量：末尾追加可选 {@code erpWriteback}（dispatch / complete 都会带，
 * {@code pending} = 30 秒确认窗口内）与 {@code dispatch}（派单回显）。保留 7 参数旧构造重载，
 * 新增字段为 null 时不序列化，W5 响应形状不变。</p>
 */
public record TicketTransitionResult(
        String ticketNo,
        TicketStatus status,
        String statusText,
        int version,
        List<TicketAuditNode> nodes,
        String auditEventId,
        boolean mock,
        /** v0.3.1 增量：ERP 回写结果（dispatch / complete） */
        ErpWriteback erpWriteback,
        /** v0.3.1 增量：派单回显（仅 dispatch） */
        DispatchPayload dispatch) {

    /** P2 W5 兼容构造（无 erpWriteback/dispatch）。 */
    public TicketTransitionResult(
            String ticketNo,
            TicketStatus status,
            String statusText,
            int version,
            List<TicketAuditNode> nodes,
            String auditEventId,
            boolean mock) {
        this(ticketNo, status, statusText, version, nodes, auditEventId, mock, null, null);
    }

    /** v0.3.1 增量字段：无回写/非派单时省略，保持 W5 响应形状。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Override
    public ErpWriteback erpWriteback() {
        return erpWriteback;
    }

    /** v0.3.1 增量字段：非 dispatch 动作省略，保持 W5 响应形状。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Override
    public DispatchPayload dispatch() {
        return dispatch;
    }
}
