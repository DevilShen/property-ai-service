package com.property.ai.domain.ticket;

import java.util.List;
import java.util.Optional;

/**
 * 工单仓储接口（P2 第 1 周内存实现，P5 迁 PostgreSQL）。
 *
 * <p>为什么抽象成接口：服务层不感知存储细节，P5 换 PG 只新增实现类：
 * <pre>
 * CREATE TABLE ticket_record (
 *   ticket_no   text PRIMARY KEY,
 *   project_id  text NOT NULL,
 *   room_code   text NOT NULL,
 *   status      text NOT NULL,
 *   payload     jsonb NOT NULL,      -- 平台侧扩展（nodes/version/feedback/…）
 *   version     int  NOT NULL,       -- 乐观锁
 *   created_at  timestamptz NOT NULL,
 *   updated_at  timestamptz NOT NULL
 * );
 * CREATE INDEX idx_ticket_room_created ON ticket_record (project_id, room_code, created_at DESC);
 * </pre>
 *
 * <p>边界（设计 §24.5）：**工单主数据以 ERP 为准**，本仓储只存平台侧扩展记录（版本号/审计节点/回访结论），
 * 不做 ERP 主数据的二次落库、不做跨系统对账口径。
 */
public interface TicketRepository {

    Optional<TicketRecord> find(String ticketNo);

    void save(TicketRecord record);

    List<TicketRecord> findAll();

    long count();

    /** 测试/演示重置（生产实现不提供） */
    void clear();
}
