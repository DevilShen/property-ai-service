package com.property.ai.domain.ticket;

import java.time.Duration;
import java.time.Instant;

import com.property.ai.bff.dto.ErpWriteback;
import com.property.ai.bff.dto.ErpWritebackStatus;
import com.property.ai.bff.dto.TicketStatus;

/**
 * ERP 回写状态（平台侧内部状态，**不是契约 DTO**）：架构 §24.6 / 契约 v0.3.1 {@code ErpWriteback}。
 *
 * <p>状态机：
 * <pre>
 * 写请求成功 ──► pending（30 秒确认窗口，nextRetryAt = issuedAt+30s）
 *                 │
 *                 ├─ 30s ~ 5min 内查询到 ERP 目标状态一致 ──► confirmed
 *                 ├─ 查询到 ERP 状态不一致且 ≥5min 未确认 ──► failed + 人工待办（不自动重写）
 *                 └─ 查询时 ERP 不可用且 ≥5min 未确认 ─────► failed + 人工待办（不自动重写）
 * 写请求失败 ──► failed（立即，attempts=1，不自动重试）
 * </pre>
 *
 * <p>口径：<b>写操作不自动重试</b>；{@code attempts} 只统计「写尝试次数」（0=未发出、1=已发出一次），
 * 确认查询次数单独记在 {@link #confirmAttempts}（不对外暴露，避免与契约语义混淆）。
 */
public final class ErpWritebackState {

    /** 30 秒确认窗口（契约 v0.3.1：pending = 30 秒确认窗口内） */
    public static final Duration CONFIRM_WINDOW = Duration.ofSeconds(30);
    /** 5 分钟未确认 → 告警转人工（架构 §24.6） */
    public static final Duration HUMAN_ALARM = Duration.ofMinutes(5);

    public static final String PENDING_MESSAGE = "ERP 受理中，30 秒后确认回写结果";
    public static final String CONFIRMED_MESSAGE = "ERP 已确认回写";

    /** 回写动作类型（dispatch / complete） */
    public enum Kind {
        DISPATCH,
        COMPLETE
    }

    public final Kind kind;
    public ErpWritebackStatus status;
    public int attempts;
    public Instant issuedAt;
    public Instant confirmDueAt;
    public String erpRef;
    public String message;
    /** 回写成功后 ERP 应处于的目标状态（pending→confirmed 的判定依据） */
    public TicketStatus targetStatus;
    public int confirmAttempts;
    public Instant confirmedAt;

    private ErpWritebackState(Kind kind, ErpWritebackStatus status, int attempts, Instant issuedAt,
            String erpRef, String message, TicketStatus targetStatus) {
        this.kind = kind;
        this.status = status;
        this.attempts = attempts;
        this.issuedAt = issuedAt;
        this.confirmDueAt = issuedAt.plus(CONFIRM_WINDOW);
        this.erpRef = erpRef;
        this.message = message;
        this.targetStatus = targetStatus;
    }

    /** 写请求成功 → pending（30 秒确认窗口内） */
    public static ErpWritebackState pending(Kind kind, String erpRef, TicketStatus targetStatus, Instant now) {
        return new ErpWritebackState(kind, ErpWritebackStatus.PENDING, 1, now, erpRef, PENDING_MESSAGE,
                targetStatus);
    }

    /** 写请求失败 → failed（立即进人工待办，不自动重写） */
    public static ErpWritebackState failed(Kind kind, String erpRef, String message, Instant now) {
        return new ErpWritebackState(kind, ErpWritebackStatus.FAILED, 1, now, erpRef,
                message == null || message.isBlank() ? "ERP 回写失败，已转人工处理（不自动重写）" : message, null);
    }

    /** 是否已过 30 秒确认窗口 */
    public boolean confirmationDue(Instant now) {
        return !now.isBefore(confirmDueAt);
    }

    /** 距发出是否已超过 5 分钟告警线 */
    public boolean humanAlarmDue(Instant now) {
        return !now.isBefore(issuedAt.plus(HUMAN_ALARM));
    }

    /** 转契约 DTO（{@code TokenTransitionResult.erpWriteback}） */
    public ErpWriteback toDto() {
        return new ErpWriteback(status, attempts, status == ErpWritebackStatus.PENDING
                ? TicketTimes.iso(confirmDueAt)
                : null, erpRef, message);
    }
}
