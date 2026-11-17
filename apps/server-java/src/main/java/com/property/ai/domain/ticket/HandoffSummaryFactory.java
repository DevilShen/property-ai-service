package com.property.ai.domain.ticket;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.property.ai.bff.dto.HandoffChannel;
import com.property.ai.bff.dto.HandoffHistoryItem;
import com.property.ai.bff.dto.HandoffSummary;
import com.property.ai.bff.dto.IssueScope;
import com.property.ai.bff.dto.Ticket;
import com.property.ai.bff.dto.TicketAuditNode;
import com.property.ai.bff.dto.TicketCategory;
import com.property.ai.bff.dto.TicketStatus;
import com.property.ai.bff.dto.TicketUrgency;

/**
 * 转人工摘要生成（CAP-18 handoff.human，**平台内生成，不调 ERP**）。
 *
 * <p>契约口径（{@code GET /api/v1/tickets/{ticketNo}/handoff-summary}）：
 * 一句话标题 + 摘要（≤5 行）+ 已采集要素 + 情绪 + 历史（脱敏）+ 建议动作 + 未完成动作 + 生成时间。
 * 不返回完整手机号/地址；姓名只回掩码（由调用方传 {@code ownerNameMasked}）。
 *
 * <p>P2 第 1 周为规则化生成（非模型生成）：同一工单输入稳定可复现，便于 e2e 断言；
 * W3 起由 Python 编排层在能力网关之上生成摘要，本类退化为回退/兜底实现。
 */
@Component
public class HandoffSummaryFactory {

    private static final Map<TicketCategory, String> CATEGORY_TEXT = new LinkedHashMap<>();
    private static final Map<TicketUrgency, String> URGENCY_TEXT = new LinkedHashMap<>();
    private static final Map<IssueScope, String> DOWNSTAIRS_TEXT = new LinkedHashMap<>();

    static {
        CATEGORY_TEXT.put(TicketCategory.PLUMBING, "水暖/给排水");
        CATEGORY_TEXT.put(TicketCategory.ELECTRICAL, "电气/照明");
        CATEGORY_TEXT.put(TicketCategory.ELEVATOR, "电梯");
        CATEGORY_TEXT.put(TicketCategory.DOOR_WINDOW, "门窗/入户");
        CATEGORY_TEXT.put(TicketCategory.PUBLIC_FACILITY, "公共设施");
        CATEGORY_TEXT.put(TicketCategory.OTHER, "其他");

        URGENCY_TEXT.put(TicketUrgency.NORMAL, "普通");
        URGENCY_TEXT.put(TicketUrgency.URGENT, "加急");
        URGENCY_TEXT.put(TicketUrgency.EMERGENCY, "应急");

        DOWNSTAIRS_TEXT.put(IssueScope.NONE, "未影响楼下");
        DOWNSTAIRS_TEXT.put(IssueScope.DOWNSTAIRS, "已漏到楼下");
        DOWNSTAIRS_TEXT.put(IssueScope.UNKNOWN, "影响范围待确认");
    }

    public HandoffSummary build(Ticket ticket, String ownerNameMasked) {
        TicketStatus status = ticket.status();
        String categoryText = categoryText(ticket.category());
        String urgencyText = urgencyText(ticket.urgency());
        String downstairsText = downstairsText(ticket.downstairs());

        String title = buildTitle(ticket, categoryText, urgencyText);

        List<String> summary = new ArrayList<>();
        summary.add("报修位置：" + blankToDash(ticket.location()) + "（" + categoryText + "，" + downstairsText + "）");
        summary.add("紧急程度：" + urgencyText + "；预约时段：" + blankToDash(ticket.slot())
                + (blank(ticket.duration()) ? "" : "；持续 " + ticket.duration()));
        summary.add("当前状态：" + TicketTransitions.statusText(status)
                + (blank(ticket.team()) ? "" : "；处理班组：" + ticket.team())
                + (blank(ticket.eta()) ? "" : "；" + ticket.eta()));
        if (!blank(ticket.note())) {
            summary.add("业主补充：" + ticket.note());
        }
        summary.add("工单号：" + ticket.ticketNo() + (ticket.aiCreated() ? "（AI 客服自动建单）" : "（人工建单）"));
        if (summary.size() > 5) {
            summary = new ArrayList<>(summary.subList(0, 5));
        }

        Map<String, String> elements = new LinkedHashMap<>();
        elements.put("category", ticket.category() == null ? "" : ticket.category().value());
        elements.put("urgency", ticket.urgency() == null ? "" : ticket.urgency().value());
        elements.put("location", blankToDash(ticket.location()));
        elements.put("downstairs", ticket.downstairs() == null ? "" : ticket.downstairs().value());
        elements.put("duration", blankToDash(ticket.duration()));
        elements.put("slot", blankToDash(ticket.slot()));

        List<HandoffHistoryItem> history = new ArrayList<>();
        for (TicketAuditNode node : ticket.nodes() == null ? List.<TicketAuditNode>of() : ticket.nodes()) {
            String text = TicketTransitions.statusText(node.status())
                    + (blank(node.ownerText()) ? "" : "：" + node.ownerText());
            history.add(new HandoffHistoryItem(node.at(), HandoffChannel.SYSTEM, text));
        }
        if (history.size() > 10) {
            history = new ArrayList<>(history.subList(history.size() - 10, history.size()));
        }

        return new HandoffSummary(
                ticket.ticketNo(),
                ticket.roomCode(),
                ownerNameMasked == null ? "" : ownerNameMasked,
                title,
                status,
                TicketTransitions.statusText(status),
                summary,
                elements,
                emotion(ticket),
                history,
                suggestedActions(ticket),
                pendingActions(status),
                TicketTimes.nowIso(),
                ticket.mock());
    }

    private String buildTitle(Ticket ticket, String categoryText, String urgencyText) {
        StringBuilder title = new StringBuilder(categoryText);
        if (!blank(ticket.location())) {
            title.append(" · ").append(ticket.location());
        }
        List<String> tags = new ArrayList<>();
        if (ticket.downstairs() == IssueScope.DOWNSTAIRS) {
            tags.add("已漏到楼下");
        }
        if (ticket.urgency() == TicketUrgency.EMERGENCY) {
            tags.add("紧急");
        }
        if (!tags.isEmpty()) {
            title.append("（").append(String.join("，", tags)).append("）");
        }
        return title.toString();
    }

    private String emotion(Ticket ticket) {
        if (ticket.urgency() == TicketUrgency.EMERGENCY || ticket.urgency() == TicketUrgency.URGENT
                || ticket.downstairs() == IssueScope.DOWNSTAIRS) {
            return "着急";
        }
        return "平静";
    }

    private List<String> suggestedActions(Ticket ticket) {
        List<String> actions = new ArrayList<>();
        if (ticket.urgency() == TicketUrgency.EMERGENCY || ticket.downstairs() == IssueScope.DOWNSTAIRS) {
            actions.add("核实是否影响楼下并留证");
        }
        switch (ticket.status()) {
            case CREATED, ACCEPTED -> {
                actions.add("尽快派单并告知上门时间");
                actions.add("完工后主动回访");
            }
            case DISPATCHED -> {
                actions.add("同步上门进度");
                actions.add("完工后主动回访");
            }
            case ON_SITE -> {
                actions.add("同步处理进度");
                actions.add("完工后主动回访");
            }
            case COMPLETED -> {
                actions.add("主动回访并记录评价");
                actions.add("涉及费用时说明费项口径");
            }
            case REVIEWED -> actions.add("归档并回填知识库");
            case ARCHIVED -> actions.add("无需动作（已归档）");
        }
        return actions;
    }

    private List<String> pendingActions(TicketStatus status) {
        return switch (status) {
            case CREATED, ACCEPTED -> List.of("派单", "完工确认", "回访评价");
            case DISPATCHED, ON_SITE -> List.of("完工确认", "回访评价");
            case COMPLETED -> List.of("回访评价");
            case REVIEWED -> List.of("归档");
            case ARCHIVED -> List.of();
        };
    }

    private static String categoryText(TicketCategory category) {
        return category == null ? "其他" : CATEGORY_TEXT.getOrDefault(category, category.value());
    }

    private static String urgencyText(TicketUrgency urgency) {
        return urgency == null ? "普通" : URGENCY_TEXT.getOrDefault(urgency, urgency.value());
    }

    private static String downstairsText(IssueScope scope) {
        return scope == null ? "影响范围待确认" : DOWNSTAIRS_TEXT.getOrDefault(scope, scope.value());
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToDash(String value) {
        return blank(value) ? "未填写" : value;
    }
}
