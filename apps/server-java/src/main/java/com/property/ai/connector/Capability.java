package com.property.ai.connector;

/**
 * 标准能力编码（能力网关只认能力、不认厂商）。
 *
 * <p>契约对照：{@code docs/openapi/README.md} §3 迁移对照表；设计 {@code 02-架构设计.md} §24.4
 * （能力网关是 AI 侧访问 ERP 的唯一入口，连接器负责协议转换/超时/重试/熔断/降级/能力探测）。
 *
 * <p>{@code readOnly} 决定重试口径：读能力可自动重试 1 次；写能力（建单/迁移/回访）**不自动重试**，
 * 只由客户端复用同一 {@code Idempotency-Key} 重试（契约 §4）。
 *
 * <p>{@link #OWNER_VERIFY} 在 ERP 侧有失败计数/锁定副作用，按写口径处理（不自动重试），
 * 避免一次网络超时被计成两次核验失败。
 */
public enum Capability {

    ROOM_QUERY("CAP-01", "room.query", true),
    OWNER_VERIFY("CAP-02", "owner.verify", false),
    BILL_QUERY("CAP-03", "bill.query", true),
    TICKET_CREATE("CAP-06", "ticket.create", false),
    TICKET_STATUS("CAP-08", "ticket.status", true),
    TICKET_LIST("CAP-08", "ticket.list", true),
    TICKET_TRANSITION("CAP-07/09/13", "ticket.transition", false),
    TICKET_FEEDBACK("CAP-13", "visit.record", false);

    private final String code;
    private final String label;
    private final boolean readOnly;

    Capability(String code, String label, boolean readOnly) {
        this.code = code;
        this.label = label;
        this.readOnly = readOnly;
    }

    /** 设计文档里的能力编码，如 CAP-01 */
    public String code() {
        return code;
    }

    /** 能力名（与契约阅读顺序一致），如 room.query */
    public String label() {
        return label;
    }

    /** 是否只读（只读可自动重试 1 次；写操作不自动重试） */
    public boolean readOnly() {
        return readOnly;
    }

    /**
     * 映射到契约 18 项能力码（{@link CapabilityCode}，用于能力矩阵/降级台账聚合）。
     *
     * <p>一个连接器能力可能覆盖多个设计能力编码（如 {@link #TICKET_TRANSITION} 覆盖 CAP-07/09/13），
     * 台账按契约能力码聚合时取主编码（派单 CAP-07 / 完工 CAP-09 / 回访 CAP-13 归并口径见任务卡 P2-025）。
     */
    public com.property.ai.bff.dto.CapabilityCode contractCode() {
        return switch (this) {
            case ROOM_QUERY -> com.property.ai.bff.dto.CapabilityCode.ROOM_QUERY;
            case OWNER_VERIFY -> com.property.ai.bff.dto.CapabilityCode.OWNER_VERIFY;
            case BILL_QUERY -> com.property.ai.bff.dto.CapabilityCode.BILL_QUERY;
            case TICKET_CREATE -> com.property.ai.bff.dto.CapabilityCode.TICKET_CREATE;
            case TICKET_STATUS, TICKET_LIST -> com.property.ai.bff.dto.CapabilityCode.TICKET_STATUS;
            case TICKET_TRANSITION -> com.property.ai.bff.dto.CapabilityCode.TICKET_ASSIGN;
            case TICKET_FEEDBACK -> com.property.ai.bff.dto.CapabilityCode.VISIT_RECORD;
        };
    }
}
