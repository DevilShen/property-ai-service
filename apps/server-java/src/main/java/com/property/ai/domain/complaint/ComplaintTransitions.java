package com.property.ai.domain.complaint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.property.ai.bff.dto.ComplaintLevel;
import com.property.ai.bff.dto.ComplaintStatus;
import com.property.ai.bff.dto.ComplaintTransitionAction;

/**
 * 投诉状态机（P3-021 / 契约 v1.1.0 {@code ComplaintStatus} / {@code ComplaintTransitionAction}）。
 *
 * <p>状态机（契约 {@code POST /complaints/{complaintId}/transition} 描述表逐行对齐）：
 * <pre>
 * action          允许 from 状态                                      to 状态
 * accept          submitted / reopened                               accepted
 * first_response  accepted / processing / escalated / reopened        processing（记录 firstResponseAt）
 * escalate        accepted / processing                              escalated（escalatedTo 回带）
 * close           submitted / accepted / processing / escalated        closed
 * reopen          closed                                             reopened（可再次 accept）
 * </pre>
 *
 * <p>非法迁移由领域服务转 409 {@code E-3002} + {@code details{status, allowed}}（契约「非法迁移 → 409」），
 * {@link #allowedActions(ComplaintStatus)} 给出的就是 {@code details.allowed}。
 *
 * <p>边界：本类只做「动作 × 当前状态 → 目标状态」的纯规则判定，<b>不判权限、不判版本、不落库</b>；
 * 权限矩阵见 {@code com.property.ai.security.PermissionMatrix}（动作级能力
 * {@code complaint.handle}/{@code complaint.escalate}/{@code complaint.read}）。
 */
public final class ComplaintTransitions {

    /**
     * 单动作规则：动作 → 允许的来源状态集合 + 目标状态。
     *
     * <p>注意 {@code first_response} 的目标状态是 {@code processing}（契约表），
     * 且允许从 {@code processing} 再次触发（补记首响/调整记录），首响时间只记第一次（见服务层）。
     */
    public record Rule(ComplaintTransitionAction action, Set<ComplaintStatus> from, ComplaintStatus to) {

        public boolean allows(ComplaintStatus status) {
            return status != null && from.contains(status);
        }
    }

    private static final Map<ComplaintTransitionAction, Rule> RULES = buildRules();

    private ComplaintTransitions() {
    }

    private static Map<ComplaintTransitionAction, Rule> buildRules() {
        EnumMap<ComplaintTransitionAction, Rule> rules = new EnumMap<>(ComplaintTransitionAction.class);
        rules.put(ComplaintTransitionAction.ACCEPT, new Rule(ComplaintTransitionAction.ACCEPT,
                statuses(ComplaintStatus.SUBMITTED, ComplaintStatus.REOPENED), ComplaintStatus.ACCEPTED));
        rules.put(ComplaintTransitionAction.FIRST_RESPONSE, new Rule(ComplaintTransitionAction.FIRST_RESPONSE,
                statuses(ComplaintStatus.ACCEPTED, ComplaintStatus.PROCESSING, ComplaintStatus.ESCALATED,
                        ComplaintStatus.REOPENED),
                ComplaintStatus.PROCESSING));
        rules.put(ComplaintTransitionAction.ESCALATE, new Rule(ComplaintTransitionAction.ESCALATE,
                statuses(ComplaintStatus.ACCEPTED, ComplaintStatus.PROCESSING), ComplaintStatus.ESCALATED));
        rules.put(ComplaintTransitionAction.CLOSE, new Rule(ComplaintTransitionAction.CLOSE,
                statuses(ComplaintStatus.SUBMITTED, ComplaintStatus.ACCEPTED, ComplaintStatus.PROCESSING,
                        ComplaintStatus.ESCALATED),
                ComplaintStatus.CLOSED));
        rules.put(ComplaintTransitionAction.REOPEN, new Rule(ComplaintTransitionAction.REOPEN,
                statuses(ComplaintStatus.CLOSED), ComplaintStatus.REOPENED));
        return Collections.unmodifiableMap(rules);
    }

    private static Set<ComplaintStatus> statuses(ComplaintStatus... values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(List.of(values)));
    }

    /** 动作规则（{@code null} 动作返回 {@code null}，由调用方转 400 E-1001）。 */
    public static Rule rule(ComplaintTransitionAction action) {
        return action == null ? null : RULES.get(action);
    }

    /** 当前状态是否允许该动作（非法迁移 → 调用方转 409 E-3002）。 */
    public static boolean allows(ComplaintTransitionAction action, ComplaintStatus status) {
        Rule rule = rule(action);
        return rule != null && rule.allows(status);
    }

    /** 该动作允许的来源状态（用于 409 {@code details.allowedFrom}）。 */
    public static List<String> allowedFrom(ComplaintTransitionAction action) {
        Rule rule = rule(action);
        if (rule == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (ComplaintStatus status : ComplaintStatus.values()) {
            if (rule.from().contains(status)) {
                values.add(status.value());
            }
        }
        return List.copyOf(values);
    }

    /**
     * 当前状态可执行的动作 code 列表（契约 409 {@code details.allowed}）。
     *
     * <p>顺序固定为 {@code ComplaintTransitionAction} 枚举顺序（accept / first_response / escalate / close /
     * reopen），给前端稳定渲染。
     */
    public static List<String> allowedActions(ComplaintStatus status) {
        if (status == null) {
            return List.of();
        }
        List<String> actions = new ArrayList<>();
        for (ComplaintTransitionAction action : ComplaintTransitionAction.values()) {
            if (allows(action, status)) {
                actions.add(action.value());
            }
        }
        return List.copyOf(actions);
    }

    /** 状态中文文案（前端展示/审计说明用；不是契约字段）。 */
    public static String statusText(ComplaintStatus status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case SUBMITTED -> "已提交";
            case ACCEPTED -> "已受理";
            case PROCESSING -> "处理中";
            case ESCALATED -> "已升级";
            case CLOSED -> "已结案";
            case REOPENED -> "已重开";
        };
    }

    /** 动作中文文案（409 文案/审计 note）。 */
    public static String actionText(ComplaintTransitionAction action) {
        if (action == null) {
            return "";
        }
        return switch (action) {
            case ACCEPT -> "受理";
            case FIRST_RESPONSE -> "首次响应";
            case ESCALATE -> "升级";
            case CLOSE -> "结案";
            case REOPEN -> "重开";
        };
    }

    /** 级别中文文案（产品 §8.3 口径，非契约字段）。 */
    public static String levelText(ComplaintLevel level) {
        if (level == null) {
            return "";
        }
        return switch (level) {
            case L1 -> "一级（立即接管 + 30 分钟上报）";
            case L2 -> "二级（24 小时首复 + 48 小时方案）";
            case L3 -> "三级（2 小时首响 + 48 小时闭环）";
        };
    }
}
