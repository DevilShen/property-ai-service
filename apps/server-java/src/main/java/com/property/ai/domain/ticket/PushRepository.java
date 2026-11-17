package com.property.ai.domain.ticket;

import java.util.List;

import com.property.ai.bff.dto.TicketPush;

/**
 * 推送记录仓储（P2 W6：契约 {@code GET /api/v1/tickets/{ticketNo}/pushes} 的**平台侧数据源**）。
 *
 * <p>口径（二选一实现，本任务选平台侧）：推送记录以平台侧 {@code PushRepository} 为准，
 * {@code services/mock-channel} 的 {@code /channels/records} 仅作留痕核对（联调时人工比对），
 * 生产/演示查询不依赖 mock-channel 可用性。
 *
 * <p>P2 W6 内存实现（{@link InMemoryPushRepository}，重启丢失）；P5 迁 PG：
 * <pre>
 * CREATE TABLE ticket_push (
 *   push_id     text PRIMARY KEY,
 *   ticket_no   text NOT NULL,
 *   channel     text NOT NULL,
 *   template    text NOT NULL,
 *   status      text NOT NULL,
 *   sent_at     timestamptz,
 *   content_masked text,
 *   recorded_at timestamptz NOT NULL
 * );
 * CREATE INDEX idx_ticket_push_ticket ON ticket_push (ticket_no, recorded_at DESC);
 * </pre>
 */
public interface PushRepository {

    /** 追加一条推送记录（同 pushId 覆盖；正常流程 pushId 唯一） */
    void save(TicketPush push);

    /** 按工单查推送记录（**最新在前**，与契约「按 sentAt 倒序」一致；无记录返回空列表，不造数） */
    List<TicketPush> findByTicketNo(String ticketNo);

    long count();

    /** 测试/演示重置（生产实现不提供） */
    void clear();
}
