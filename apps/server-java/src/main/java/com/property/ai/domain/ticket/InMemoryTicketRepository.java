package com.property.ai.domain.ticket;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Repository;

/**
 * 工单仓储内存实现（P2 第 1 周）。
 *
 * <p>⚠️ 仅进程内有效：重启/多实例会丢平台侧版本号与审计节点（ERP 主数据不受影响）。
 * 这是**已知演示限制**，P5 按 {@link TicketRepository} 的 PG 建表说明替换实现，服务层不改。
 * 禁止把本实现当生产存储（AGENTS.md：生产目标 PostgreSQL 16，不得用 JSON/内存落业务主数据）。
 */
@Repository
public class InMemoryTicketRepository implements TicketRepository {

    private final ConcurrentMap<String, TicketRecord> records = new ConcurrentHashMap<>();

    @Override
    public Optional<TicketRecord> find(String ticketNo) {
        if (ticketNo == null || ticketNo.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(records.get(ticketNo));
    }

    @Override
    public void save(TicketRecord record) {
        if (record == null || record.ticketNo == null || record.ticketNo.isBlank()) {
            throw new IllegalArgumentException("工单记录缺少 ticketNo，无法保存");
        }
        records.put(record.ticketNo, record);
    }

    @Override
    public List<TicketRecord> findAll() {
        return List.copyOf(records.values());
    }

    @Override
    public long count() {
        return records.size();
    }

    @Override
    public void clear() {
        records.clear();
    }
}
