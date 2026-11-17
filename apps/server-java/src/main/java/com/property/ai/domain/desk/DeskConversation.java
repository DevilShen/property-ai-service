package com.property.ai.domain.desk;

import java.time.Instant;

import com.property.ai.bff.dto.DeskConversationStatus;
import com.property.ai.bff.dto.TicketPushChannel;

/**
 * 坐席会话（P4-012，契约 v1.2.0 {@code DeskQueueItem} 的服务端状态载体）。
 *
 * <p>状态机（契约 {@code DeskConversationStatus}）：{@code waiting → taken_over → closed}；
 * 同一会话同时只允许一个坐席接管（互斥由 {@link DeskConversationRepository} 串行化保证）。
 *
 * <p><b>演示口径</b>：会话由 {@link DeskSeedData} 的历史未结工单装配，等待起点在进程内装载时按演示阶梯
 * 回填（见仓储类），不是真实 IM 会话；P5 接会话存储后由真实事件驱动。
 */
public final class DeskConversation {

    private final String conversationId;
    private final String roomCode;
    private final String ownerNameMasked;
    private final TicketPushChannel channel;
    private final String preview;
    private final String category;
    /** 进入队列时间（epoch millis；由仓储按演示阶梯回填） */
    private final Instant enqueuedAt;
    /** 首响 SLA 截止时间（enqueuedAt + SLA 窗口） */
    private final Instant slaDueAt;

    private DeskConversationStatus status;
    /** 当前/最近接管坐席（质检抽样按「被抽检坐席」口径使用） */
    private String agentId;
    /** 最近一次接管时间（首响口径：接管即坐席首次人工响应） */
    private Instant takenAt;
    /** 最近一次释放时间（可选） */
    private Instant releasedAt;

    public DeskConversation(String conversationId, String roomCode, String ownerNameMasked,
            TicketPushChannel channel, String preview, String category, Instant enqueuedAt, Instant slaDueAt,
            DeskConversationStatus status, String agentId, Instant takenAt) {
        this.conversationId = conversationId;
        this.roomCode = roomCode;
        this.ownerNameMasked = ownerNameMasked;
        this.channel = channel;
        this.preview = preview;
        this.category = category;
        this.enqueuedAt = enqueuedAt;
        this.slaDueAt = slaDueAt;
        this.status = status;
        this.agentId = agentId;
        this.takenAt = takenAt;
    }

    public String conversationId() {
        return conversationId;
    }

    public String roomCode() {
        return roomCode;
    }

    public String ownerNameMasked() {
        return ownerNameMasked;
    }

    public TicketPushChannel channel() {
        return channel;
    }

    public String preview() {
        return preview;
    }

    public String category() {
        return category;
    }

    public Instant enqueuedAt() {
        return enqueuedAt;
    }

    public Instant slaDueAt() {
        return slaDueAt;
    }

    public DeskConversationStatus status() {
        return status;
    }

    public String agentId() {
        return agentId;
    }

    public Instant takenAt() {
        return takenAt;
    }

    public Instant releasedAt() {
        return releasedAt;
    }

    public void markTakenOver(String agent, Instant at) {
        this.status = DeskConversationStatus.TAKEN_OVER;
        this.agentId = agent;
        this.takenAt = at;
        this.releasedAt = null;
    }

    public void markWaiting(Instant at) {
        this.status = DeskConversationStatus.WAITING;
        this.takenAt = null;
        this.releasedAt = at;
    }

    public void markClosed(Instant at) {
        this.status = DeskConversationStatus.CLOSED;
        this.releasedAt = at;
    }

    /** 是否已超首响 SLA（等待中按当前时间判定；已接管按接管时间判定）。 */
    public boolean slaBreached(Instant now) {
        Instant respondedOrNow = takenAt != null ? takenAt : now;
        return respondedOrNow.isAfter(slaDueAt);
    }
}
