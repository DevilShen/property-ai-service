package com.property.ai.domain.ticket;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.property.ai.bff.dto.ErpRef;
import com.property.ai.bff.dto.IssueScope;
import com.property.ai.bff.dto.Ticket;
import com.property.ai.bff.dto.TicketAuditNode;
import com.property.ai.bff.dto.TicketCategory;
import com.property.ai.bff.dto.TicketFeedbackSummary;
import com.property.ai.bff.dto.TicketListItem;
import com.property.ai.bff.dto.TicketStatus;
import com.property.ai.bff.dto.TicketUrgency;

/**
 * 工单领域实体（平台侧扩展记录，**不是 ERP 工单主数据**）。
 *
 * <p>边界（设计 §24.5「不落库清单」）：工单主数据以 ERP 为准，本实体只保存
 * 「ERP 权威快照 + 平台侧扩展状态」：
 * <ul>
 *   <li>{@code erpNodes}：ERP 返回的审计节点快照（只读，每次读/写刷新）；</li>
 *   <li>{@code localNodes}：平台侧补充节点（改约/催办/回访等 ERP 不出节点的动作）；</li>
 *   <li>{@code version}：**平台维护的乐观锁版本号**（mock-erp/NC 未返回 version，故由平台计数）；
 *       多实例/重启后需要落库才能保持连续，P2 第 1 周为内存实现；</li>
 *   <li>{@code feedbackScore/reviewedAt}：回访结论（ERP 只存评价原文，REVIEWED 状态为平台侧语义）。</li>
 * </ul>
 *
 * <p>P5 迁 PG：本类 → {@code ticket_record} 表（ticket_no 唯一 + version 乐观锁 + project_id 隔离），
 * 仓储接口 {@link TicketRepository} 换 JPA/MyBatis 实现即可，服务层不动。
 */
public class TicketRecord {

    public String ticketNo = "";
    public String projectId = "";
    public String householdId = "";
    public String roomCode = "";
    /**
     * 业主主体归属（v1.1.0 数据范围，P3-028）：owner 建单 = 业主 {@code actorId}（如 owner-3-2-1802）；
     * staff 代报按房号解析演示业主账号回填；<b>ERP 历史种子单没有平台建单记录，恒为空 → owner 不可见</b>
     * （数据范围由服务端强制，见 {@code TicketService#assertReadScope}）。
     */
    public String ownerId = "";
    public TicketCategory category;
    public TicketUrgency urgency;
    public String location = "";
    public IssueScope downstairs = IssueScope.UNKNOWN;
    public String duration = "";
    public String slot = "";
    public String note;
    public String symptom;
    public TicketStatus status = TicketStatus.CREATED;
    public String team = "";
    public String eta = "";
    public int version = 1;
    public boolean aiCreated = true;
    public boolean mock = true;
    public ErpRef erp;
    public String traceId;
    public String createdAt = "";
    public String updatedAt = "";
    public int urgeCount;

    /** ERP 权威节点快照（按 ERP 返回顺序） */
    public List<TicketAuditNode> erpNodes = new ArrayList<>();
    /** 平台侧补充节点（改约/催办/回访；ERP 不出节点） */
    public List<TicketAuditNode> localNodes = new ArrayList<>();

    public Integer feedbackScore;
    public List<String> feedbackTags = List.of();
    public String feedbackNote;
    public String reviewedAt;

    /**
     * ERP 回写状态（P2-018：dispatch/complete 写操作后 30 秒确认窗口；失败进人工待办、不自动重写）。
     * 平台侧内部状态，不落 ERP；契约只通过 {@code TicketTransitionResult.erpWriteback} 暴露。
     */
    public ErpWritebackState writeback;

    /** 用 ERP 契约 DTO 刷新权威字段（不覆盖 version / 平台侧回访结论） */
    public void applyFrom(Ticket ticket) {
        if (ticket == null) {
            return;
        }
        ticketNo = ticket.ticketNo() != null && !ticket.ticketNo().isBlank() ? ticket.ticketNo() : ticketNo;
        projectId = orKeep(ticket.projectId(), projectId);
        householdId = orKeep(ticket.householdId(), householdId);
        roomCode = orKeep(ticket.roomCode(), roomCode);
        ownerId = orKeep(ticket.ownerId(), ownerId);
        category = ticket.category() != null ? ticket.category() : category;
        urgency = ticket.urgency() != null ? ticket.urgency() : urgency;
        location = orKeep(ticket.location(), location);
        if (location == null || location.isBlank()) {
            // 非平台建单（如 ERP 演示工单）没有报修位置：用 ERP 现象摘要兜底，避免契约必填字段为空
            location = ticket.symptom() == null ? "" : ticket.symptom();
        }
        downstairs = ticket.downstairs() != null ? ticket.downstairs() : downstairs;
        duration = orKeep(ticket.duration(), duration);
        slot = orKeep(ticket.slot(), slot);
        note = ticket.note() != null ? ticket.note() : note;
        symptom = ticket.symptom() != null ? ticket.symptom() : symptom;
        status = ticket.status() != null ? ticket.status() : status;
        team = xorEmpty(ticket.team(), team);
        eta = xorEmpty(ticket.eta(), eta);
        aiCreated = ticket.aiCreated();
        mock = ticket.mock();
        erp = ticket.erp() != null ? ticket.erp() : erp;
        traceId = ticket.traceId() != null ? ticket.traceId() : traceId;
        createdAt = orKeep(ticket.createdAt(), createdAt);
        updatedAt = orKeep(ticket.updatedAt(), updatedAt);
        erpNodes = ticket.nodes() == null ? new ArrayList<>() : new ArrayList<>(ticket.nodes());
        // 回访结论为平台侧语义：ERP 仍为 COMPLETED 时保持 REVIEWED，避免回访后状态回退
        if (reviewedAt != null && status == TicketStatus.COMPLETED) {
            status = TicketStatus.REVIEWED;
        }
    }

    public void appendLocalNode(TicketAuditNode node) {
        if (node != null) {
            localNodes.add(node);
        }
    }

    public String statusText() {
        return TicketTransitions.statusText(status);
    }

    /** 合并 ERP 快照节点 + 平台侧节点，按时间升序（契约：nodes 按时间升序） */
    public List<TicketAuditNode> mergedNodes() {
        List<TicketAuditNode> merged = new ArrayList<>(erpNodes);
        merged.addAll(localNodes);
        merged.sort(Comparator.comparing(TicketAuditNode::at,
                Comparator.nullsLast(Comparator.naturalOrder())));
        List<TicketAuditNode> normalised = new ArrayList<>(merged.size());
        for (TicketAuditNode node : merged) {
            String text = node.statusText() == null || node.statusText().isBlank()
                    ? TicketTransitions.statusText(node.status())
                    : node.statusText();
            normalised.add(new TicketAuditNode(node.status(), text, node.at(), node.who(), node.ownerText()));
        }
        return normalised;
    }

    public Ticket toTicket() {
        return new Ticket(
                ticketNo,
                projectId,
                householdId,
                roomCode,
                category,
                urgency,
                location,
                downstairs,
                duration,
                slot,
                note,
                symptom,
                status,
                statusText(),
                team,
                eta,
                mergedNodes(),
                version,
                aiCreated,
                mock,
                erp,
                traceId,
                createdAt,
                updatedAt,
                feedbackSummary(),
                blankToNull(ownerId));
    }

    /** 回访评价摘要（未回访返回 null：{@code Ticket.feedback} 不序列化，保持 W5 响应形状） */
    public TicketFeedbackSummary feedbackSummary() {
        if (reviewedAt == null || reviewedAt.isBlank() || feedbackScore == null) {
            return null;
        }
        return new TicketFeedbackSummary(feedbackScore, feedbackTags, feedbackNote, reviewedAt);
    }

    public TicketListItem toListItem() {
        return new TicketListItem(
                ticketNo,
                roomCode,
                category,
                urgency,
                status,
                statusText(),
                team,
                eta,
                version,
                aiCreated,
                mock,
                createdAt,
                updatedAt);
    }

    private static String orKeep(String candidate, String current) {
        return candidate == null || candidate.isBlank() ? current : candidate;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String xorEmpty(String candidate, String current) {
        if (candidate == null) {
            return current;
        }
        return candidate;
    }
}
