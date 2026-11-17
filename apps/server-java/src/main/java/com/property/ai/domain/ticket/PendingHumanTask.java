package com.property.ai.domain.ticket;

/**
 * 人工待办（写操作失败/降级 {@code fallback=human} 时落待办，**不假成功、不编造工单号**）。
 *
 * <p>契约口径：
 * <ul>
 *   <li>{@code POST /api/v1/tickets} ERP 失败 → 503 + {@code degraded{fallback:human}} + 落「待人工建单」队列；</li>
 *   <li>{@code /transition}、{@code /feedback} 失败 → 503 + 转人工（设计 §26.2-8：写成功但回写失败要 30 秒确认/1 分钟告警）。</li>
 * </ul>
 *
 * <p>P2 第 1 周内存队列（供验收与后续 P2-018 待办页消费）；P5 迁 PG 待办表 + 告警。
 */
public record PendingHumanTask(
        /** 动作类型，如 ticket.create / ticket.dispatch / ticket.feedback */
        String kind,
        /** 关联工单号（建单失败时为空串，不得编造） */
        String ticketNo,
        /** 房号（归一值，仅用于人工定位；不进模型上下文） */
        String roomCode,
        String reason,
        String errorCode,
        String at) {

    public static PendingHumanTask of(String kind, String ticketNo, String roomCode, String reason,
            String errorCode) {
        return new PendingHumanTask(kind, ticketNo == null ? "" : ticketNo, roomCode,
                reason == null ? "" : reason, errorCode, TicketTimes.nowIso());
    }
}
