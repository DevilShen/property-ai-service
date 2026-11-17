package com.property.ai.domain.dunning;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.property.ai.bff.dto.ArrearsBucket;
import com.property.ai.bff.dto.DunningChannel;
import com.property.ai.bff.dto.DunningFrequencyCheck;
import com.property.ai.bff.dto.DunningScript;
import com.property.ai.bff.dto.DunningStatus;
import com.property.ai.bff.dto.DunningTask;

/**
 * 催缴任务内存记录（P3-012，契约 {@code DunningTask}）。
 *
 * <p>不可变身份字段（房号/账期/欠费/分层/负责人/生成时间）来自 ERP 欠费分层数据生成时快照；
 * 可变状态字段（status / channel / scriptVersion / lastActionAt / closedAt）随动作推进，
 * 由 {@link DunningTaskService} 在包内同步修改（P3 为单实例 demo，P5 迁 PostgreSQL 后由乐观锁替代）。
 *
 * <p><b>隐私</b>：业主姓名只存 ERP 掩码 {@code ownerNameMasked}（如「王*」），<b>不存</b>手机号/身份证/地址；
 * 对外 DTO 只回掩码，日志只记 taskId/分层/状态（不记房号+金额组合以外的个人信息）。
 */
public final class DunningTaskRecord {

    // ---------------- 身份字段（生成时快照，不随动作变化） ----------------
    private final String taskId;
    private final String roomCode;
    private final String ownerNameMasked;
    private final String period;
    private final BigDecimal outstanding;
    private final BigDecimal lateFee;
    private final int overdueDays;
    private final ArrearsBucket bucket;
    private final String assignedTo;
    private final String createdAt;
    private final String dueAt;
    private final DunningChannel defaultChannel;

    // ---------------- 可变状态（包内同步修改） ----------------
    private volatile DunningStatus status = DunningStatus.PENDING;
    private volatile DunningChannel channel;
    private volatile String scriptVersion;
    private volatile DunningScript latestScript;
    private volatile int scriptSequence;
    private volatile String lastActionAt;
    private volatile String closedAt;
    private volatile String closeReason;
    private final List<SendAttempt> sendAttempts = Collections.synchronizedList(new ArrayList<>());

    public DunningTaskRecord(String taskId, String roomCode, String ownerNameMasked, String period,
            BigDecimal outstanding, BigDecimal lateFee, int overdueDays, ArrearsBucket bucket,
            DunningChannel defaultChannel, String assignedTo, String createdAt, String dueAt) {
        this.taskId = taskId;
        this.roomCode = roomCode;
        this.ownerNameMasked = ownerNameMasked;
        this.period = period;
        this.outstanding = outstanding == null ? BigDecimal.ZERO : outstanding;
        this.lateFee = lateFee == null ? BigDecimal.ZERO : lateFee;
        this.overdueDays = Math.max(0, overdueDays);
        this.bucket = bucket;
        this.defaultChannel = defaultChannel == null ? DunningChannel.WECOM : defaultChannel;
        this.channel = this.defaultChannel;
        this.assignedTo = assignedTo;
        this.createdAt = createdAt;
        this.dueAt = dueAt;
        this.lastActionAt = createdAt;
    }

    // ------------------------------------------------------------------
    // 读取
    // ------------------------------------------------------------------

    public String taskId() {
        return taskId;
    }

    public String roomCode() {
        return roomCode;
    }

    public String ownerNameMasked() {
        return ownerNameMasked;
    }

    public String period() {
        return period;
    }

    public BigDecimal outstanding() {
        return outstanding;
    }

    public BigDecimal lateFee() {
        return lateFee;
    }

    public int overdueDays() {
        return overdueDays;
    }

    public ArrearsBucket bucket() {
        return bucket;
    }

    public String assignedTo() {
        return assignedTo;
    }

    public String createdAt() {
        return createdAt;
    }

    public String dueAt() {
        return dueAt;
    }

    public DunningStatus status() {
        return status;
    }

    public DunningChannel channel() {
        return channel;
    }

    public String scriptVersion() {
        return scriptVersion;
    }

    public DunningScript latestScript() {
        return latestScript;
    }

    public String lastActionAt() {
        return lastActionAt;
    }

    public String closedAt() {
        return closedAt;
    }

    public String closeReason() {
        return closeReason;
    }

    /** 发送尝试快照（成功 + 失败；失败不计入频次红线，只留痕供对账）。 */
    public List<SendAttempt> sendAttempts() {
        synchronized (sendAttempts) {
            return List.copyOf(sendAttempts);
        }
    }

    // ------------------------------------------------------------------
    // 修改（包内服务调用）
    // ------------------------------------------------------------------

    /** 下一个话术版本序号（v1 → v2 → …；每次生成递增）。 */
    synchronized int nextScriptSequence() {
        scriptSequence += 1;
        return scriptSequence;
    }

    /** 应用新生成的话术：记版本/内容/渠道，状态 pending → script_ready（其它状态保持不变）。 */
    synchronized void applyScript(DunningScript script, String actionAt) {
        this.latestScript = script;
        this.scriptVersion = script.version();
        this.channel = script.channel() == null ? channel : script.channel();
        if (status == DunningStatus.PENDING) {
            status = DunningStatus.SCRIPT_READY;
        }
        this.lastActionAt = actionAt;
    }

    /**
     * 成功发送（mock 通道受理）：状态推进为 sent（终态 closed/paid 不动），
     * 并留痕 {@code pushId}（用于与 mock-channel records 对账）。
     */
    synchronized void markSent(String sentAt, String pushId, DunningChannel sentChannel, String version) {
        this.channel = sentChannel == null ? channel : sentChannel;
        if (status != DunningStatus.CLOSED && status != DunningStatus.PAID) {
            status = DunningStatus.SENT;
        }
        this.lastActionAt = sentAt == null ? lastActionAt : sentAt;
        sendAttempts.add(new SendAttempt(sentAt, pushId, sentChannel, version, true, null));
    }

    /** 记录一次失败发送（通道不可达/上游错误）：不推进状态、不计频次，只留痕。 */
    synchronized void recordFailedSend(String at, DunningChannel sentChannel, String version, String failureCode) {
        sendAttempts.add(new SendAttempt(at, null, sentChannel, version, false, failureCode));
    }

    /** 关闭任务（只改任务状态，不写财务数据；缴费以 ERP 实收为准）。 */
    synchronized void markClosed(String closedAt, String reason, String actionAt) {
        this.status = DunningStatus.CLOSED;
        this.closedAt = closedAt;
        this.closeReason = reason;
        this.lastActionAt = actionAt;
    }

    /** 转契约 DTO（{@code frequencyCheck} 由服务按当前窗口实时计算）。 */
    public DunningTask toDto(DunningFrequencyCheck frequencyCheck) {
        return new DunningTask(taskId, roomCode, ownerNameMasked, period, outstanding, bucket, status, channel,
                scriptVersion, assignedTo, createdAt, dueAt, lastActionAt, frequencyCheck);
    }

    /**
     * 一次发送尝试留痕。
     *
     * @param at           尝试时间（业务时区 ISO 8601）
     * @param pushId       mock-channel 消息 ID（成功时非空）
     * @param channel      尝试渠道
     * @param scriptVersion 使用的话术版本
     * @param success      是否被 mock 通道受理（{@code true} 才计频次）
     * @param failureCode  失败时的上游错误码（E-4001/E-4002/E-4003），成功为 {@code null}
     */
    public record SendAttempt(String at, String pushId, DunningChannel channel, String scriptVersion,
            boolean success, String failureCode) {
    }
}
