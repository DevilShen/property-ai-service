package com.property.ai.domain.ticket;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.property.ai.bff.dto.TicketStatus;
import com.property.ai.bff.dto.TicketTransitionAction;

/**
 * 工单状态机常量（7 节点），**口径与 shared {@code TICKET_TRANSITIONS} / OpenAPI 完全一致**（契约唯一事实源）。
 *
 * <pre>
 * CREATED → ACCEPTED → DISPATCHED → ON_SITE → COMPLETED → REVIEWED → ARCHIVED
 *
 * | action     | 允许 from                        | to          | payload 关键字段        |
 * | dispatch   | CREATED / ACCEPTED               | DISPATCHED  | team 必填；eta 可选      |
 * | on_site    | DISPATCHED                       | ON_SITE     | arrivedAt 可选          |
 * | complete   | ON_SITE                          | COMPLETED   | note / finishedAt 可选  |
 * | reschedule | ACCEPTED / DISPATCHED / ON_SITE  | 不变        | slot 必填；reason 可选   |
 * | urge       | CREATED / ACCEPTED / DISPATCHED / ON_SITE | 不变 | reason / channel 可选 |
 * </pre>
 *
 * <p>{@code ARCHIVED} 由归档作业完成（P2-018），不在 HTTP transition 中开放；回访仅 {@code COMPLETED → REVIEWED}。
 *
 * <p>状态中文文案沿用 shared {@code TICKET_STATUS_LABELS}（服务端下发，前端不硬编码）。
 */
public final class TicketTransitions {

    /** 单个迁移动作规则 */
    public record Rule(Set<TicketStatus> from, TicketStatus to, String payloadHint) {

        public boolean allows(TicketStatus status) {
            return status != null && from.contains(status);
        }

        /** to == null 表示状态不变（改约/催办） */
        public boolean keepsStatus() {
            return to == null;
        }
    }

    private static final Map<TicketTransitionAction, Rule> RULES = new LinkedHashMap<>();

    static {
        RULES.put(TicketTransitionAction.DISPATCH,
                new Rule(EnumSet.of(TicketStatus.CREATED, TicketStatus.ACCEPTED), TicketStatus.DISPATCHED,
                        "teamName（W5 旧字段 team 兼容）可选；缺省由服务端自动派单（P2-017）"));
        RULES.put(TicketTransitionAction.ON_SITE,
                new Rule(EnumSet.of(TicketStatus.DISPATCHED), TicketStatus.ON_SITE, "arrivedAt 可选"));
        RULES.put(TicketTransitionAction.COMPLETE,
                new Rule(EnumSet.of(TicketStatus.ON_SITE), TicketStatus.COMPLETED,
                        "resolution 必填（结案说明）；note / erpCloseRef 可选"));
        RULES.put(TicketTransitionAction.RESCHEDULE,
                new Rule(EnumSet.of(TicketStatus.ACCEPTED, TicketStatus.DISPATCHED, TicketStatus.ON_SITE), null,
                        "slot 必填；reason 可选"));
        RULES.put(TicketTransitionAction.URGE,
                new Rule(EnumSet.of(TicketStatus.CREATED, TicketStatus.ACCEPTED, TicketStatus.DISPATCHED,
                        TicketStatus.ON_SITE), null, "reason / channel 可选"));
    }

    /** 动作顺序（allowed 列表输出顺序，与 shared TICKET_TRANSITION_ACTIONS 一致） */
    public static final List<TicketTransitionAction> OPEN_ACTIONS = List.of(
            TicketTransitionAction.DISPATCH,
            TicketTransitionAction.ON_SITE,
            TicketTransitionAction.COMPLETE,
            TicketTransitionAction.RESCHEDULE,
            TicketTransitionAction.URGE);

    /** 回访评价允许的 from 状态（成功后 → REVIEWED） */
    public static final Set<TicketStatus> FEEDBACK_FROM = EnumSet.of(TicketStatus.COMPLETED);

    /** 归档作业允许的 from 状态（P2-018，不开放 HTTP action） */
    public static final Set<TicketStatus> ARCHIVE_FROM = EnumSet.of(TicketStatus.REVIEWED);

    private static final Map<TicketStatus, String> STATUS_LABELS = new LinkedHashMap<>();

    static {
        STATUS_LABELS.put(TicketStatus.CREATED, "待受理");
        STATUS_LABELS.put(TicketStatus.ACCEPTED, "已受理");
        STATUS_LABELS.put(TicketStatus.DISPATCHED, "已派单");
        STATUS_LABELS.put(TicketStatus.ON_SITE, "处理中");
        STATUS_LABELS.put(TicketStatus.COMPLETED, "已完工");
        STATUS_LABELS.put(TicketStatus.REVIEWED, "已回访");
        STATUS_LABELS.put(TicketStatus.ARCHIVED, "已归档");
    }

    private static final Map<TicketTransitionAction, String> ACTION_LABELS = new LinkedHashMap<>();

    static {
        ACTION_LABELS.put(TicketTransitionAction.DISPATCH, "派单");
        ACTION_LABELS.put(TicketTransitionAction.ON_SITE, "上门");
        ACTION_LABELS.put(TicketTransitionAction.COMPLETE, "完工");
        ACTION_LABELS.put(TicketTransitionAction.RESCHEDULE, "改约");
        ACTION_LABELS.put(TicketTransitionAction.URGE, "催办");
    }

    private TicketTransitions() {
    }

    public static Rule rule(TicketTransitionAction action) {
        Rule rule = action == null ? null : RULES.get(action);
        if (rule == null) {
            throw new IllegalArgumentException("未定义的状态迁移动作：" + action);
        }
        return rule;
    }

    public static boolean canTransition(TicketTransitionAction action, TicketStatus from) {
        return action != null && RULES.containsKey(action) && RULES.get(action).allows(from);
    }

    /** 当前状态下允许的动作（非法迁移时放进 {@code details.allowed}） */
    public static List<TicketTransitionAction> allowedActions(TicketStatus status) {
        List<TicketTransitionAction> allowed = new ArrayList<>();
        for (TicketTransitionAction action : OPEN_ACTIONS) {
            if (RULES.get(action).allows(status)) {
                allowed.add(action);
            }
        }
        return allowed;
    }

    /** {@code details.allowed} 的字符串形态（契约示例：["on_site","reschedule","urge"]） */
    public static List<String> allowedActionValues(TicketStatus status) {
        return allowedActions(status).stream().map(TicketTransitionAction::value).toList();
    }

    public static boolean canFeedback(TicketStatus status) {
        return status != null && FEEDBACK_FROM.contains(status);
    }

    /** 状态中文文案（与 shared TICKET_STATUS_LABELS 一致） */
    public static String statusText(TicketStatus status) {
        return status == null ? "" : STATUS_LABELS.getOrDefault(status, status.value());
    }

    public static String actionText(TicketTransitionAction action) {
        return action == null ? "" : ACTION_LABELS.getOrDefault(action, action.value());
    }
}
