package com.property.ai.domain.ticket;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Repository;

import com.property.ai.bff.dto.TicketPush;

/**
 * 推送记录内存实现（P2 W6）。
 *
 * <p>⚠️ 仅进程内有效：重启丢记录（mock-channel 也只留痕在内存）。这是**已知演示限制**，
 * P5 按 {@link PushRepository} 的 PG 建表说明替换实现，服务层不改。
 * 禁止当生产存储（AGENTS.md：生产目标 PostgreSQL 16）。
 */
@Repository
public class InMemoryPushRepository implements PushRepository {

    private final ConcurrentMap<String, CopyOnWriteArrayList<TicketPush>> byTicket = new ConcurrentHashMap<>();

    @Override
    public void save(TicketPush push) {
        if (push == null || push.ticketNo() == null || push.ticketNo().isBlank()) {
            throw new IllegalArgumentException("推送记录缺少 ticketNo，无法保存");
        }
        CopyOnWriteArrayList<TicketPush> list =
                byTicket.computeIfAbsent(push.ticketNo(), key -> new CopyOnWriteArrayList<>());
        list.add(push);
    }

    @Override
    public List<TicketPush> findByTicketNo(String ticketNo) {
        if (ticketNo == null || ticketNo.isBlank()) {
            return List.of();
        }
        CopyOnWriteArrayList<TicketPush> list = byTicket.get(ticketNo);
        if (list == null) {
            return List.of();
        }
        // 插入顺序为推送时序 → 倒序即「最新在前」
        List<TicketPush> reversed = new ArrayList<>(list);
        java.util.Collections.reverse(reversed);
        return List.copyOf(reversed);
    }

    @Override
    public long count() {
        return byTicket.values().stream().mapToLong(List::size).sum();
    }

    @Override
    public void clear() {
        byTicket.clear();
    }

    /** 供测试/验收读取某工单最新一条（不影响业务语义） */
    public Optional<TicketPush> latest(String ticketNo) {
        List<TicketPush> items = findByTicketNo(ticketNo);
        return items.isEmpty() ? Optional.empty() : Optional.of(items.get(0));
    }
}
