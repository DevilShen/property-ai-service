package com.property.ai.event;

import java.util.List;
import java.util.Optional;

import com.property.ai.bff.dto.EventEnvelope;

/**
 * Outbox 仓储（P5-019，架构 §24.6：「事务性 outbox + 至少一次投递 + 消费幂等 + 死信队列」）。
 *
 * <h2>状态机（本任务落地，{@code domain/event/EventStore} 内部实现）</h2>
 * <pre>
 * pending ──投递成功──► published（终态，不在回退）
 *    │  └─投递失败──► failed ──重试──► pending ──投递成功──► published
 *    │                   │  └────超过 maxAttempts / 人工──► dead（DLQ，可查、可重放）
 *    └──────────────────────────────────────────────────► dead（人工直接移入）
 * dead ──replay（人工重放，重试计数归零）──► pending
 * </pre>
 *
 * <p>本接口是「可运维的 outbox 门面」：查询 pending/DLQ、手动重试、人工移入 DLQ、批量派发、死信重放。
 * 默认实现 {@link InMemoryOutboxStore}（进程内，重启清空）；PG 落地时 outbox 表见
 * {@code docs/ops/PG-schema.sql} 的 {@code events_outbox}（status/retry_count/last_error/next_retry_at），
 * Kafka 迁移设计见 {@code docs/ops/Kafka-迁移方案.md}。
 */
public interface OutboxStore {

    /** 生效投递通道（{@code memory} | {@code kafka}；降级时为 memory） */
    String mode();

    /** 按 eventId 查询（不存在返回 empty） */
    Optional<EventEnvelope> find(String eventId);

    /** 待投递事件（{@code status=pending}，新→旧） */
    List<EventEnvelope> pending();

    /** 死信队列（{@code status=dead}，新→旧；DLQ 可查，不静默丢失） */
    List<EventEnvelope> dlq();

    /** 某事件的已重试次数（未投递/不存在为 0） */
    int retryCount(String eventId);

    /**
     * 手动重试一次投递（运维/调度入口）。
     *
     * <p>语义：已 published 幂等返回；重试次数已达上限 → 移入 dead；否则
     * {@code pending/failed/dead → pending}（重试次数 +1）后调用 {@link EventPublisher}：
     * 成功 → published，失败 → failed（保留 {@code lastError}）。
     *
     * @throws IllegalArgumentException 事件不存在
     */
    EventEnvelope retry(String eventId);

    /**
     * 人工把事件移入 DLQ（{@code status=dead}，保留 lastError）。
     *
     * @throws IllegalArgumentException 事件不存在
     * @throws IllegalStateException    事件已 published（已投递事件不允许回退）
     */
    EventEnvelope deadLetter(String eventId);

    /**
     * 批量派发全部 pending 事件（demo dispatcher，无 @Scheduled 自动触发；供运维/演练/测试调用）。
     *
     * @return 本次成功投递（published）的事件数
     */
    int dispatchPending();

    /**
     * 死信重放：把 DLQ 中的事件重试计数归零后重新投递一次。
     *
     * @return 重放后成功投递（published）的事件数；仍失败的事件回到 failed（可继续 retry）
     */
    int replayDead();
}
