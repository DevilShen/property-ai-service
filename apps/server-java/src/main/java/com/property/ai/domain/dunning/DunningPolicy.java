package com.property.ai.domain.dunning;

import java.util.List;

import com.property.ai.bff.dto.ArrearsBucket;
import com.property.ai.bff.dto.DunningChannel;
import com.property.ai.bff.dto.DunningTone;

/**
 * 催缴策略常量（P3-011/012，契约 v1.1.0 {@code ArrearsBucket} / {@code DunningTone} / {@code DunningFrequencyCheck}）。
 *
 * <h2>分层 → 下一步动作（P3-012 生成口径，产品 §7.4 / 契约 {@code ArrearsBucket} 描述）</h2>
 * <pre>
 * 分层        逾期天数   语气       渠道    下一次应触达（dueAt = createdAt + N 天）
 * D1          1—7 天    友好 friendly  企微 wecom   3 天（首轮温和提醒，留缴费缓冲）
 * D7          8—15 天   友好 friendly  企微 wecom   2 天
 * D15         16—30 天  坚定 firm      企微 wecom   1 天（明确期限）
 * D30         &gt;30 天    坚定 firm      短信 sms     0 天（当日；&gt;90 天按 D30 归档）
 * </pre>
 * <p>该表是<b>平台侧任务生成策略</b>（不是 ERP 金额口径）：金额/账龄/滞纳金一律取 ERP
 * {@code /fees/arrears} 分层数据，本类只决定语气、渠道与节奏。
 *
 * <h2>频次红线（契约 {@code DunningFrequencyCheck}）</h2>
 * 单户每周触达 ≤{@value #FREQUENCY_LIMIT} 次（窗口 {@value #FREQUENCY_WINDOW_DAYS} 天，按房号聚合、跨任务）；
 * 22:00—8:00 不外呼（外呼红线，P3 无真实外呼通道，见 {@link DunningFrequencyPolicy.ChannelMode}）。
 *
 * <h2>负责人（契约 {@code DunningTask.assignedTo}：岗位名/掩码，不是真实姓名）</h2>
 * 演示账号显示名（{@code DemoAccounts}）轮转分配，仅用于「下一步动作」展示，不做真实派工与消息通知。
 */
public final class DunningPolicy {

    /** 频次统计窗口（天）：单户每周口径 */
    public static final int FREQUENCY_WINDOW_DAYS = 7;
    /** 窗口内允许触达上限（次） */
    public static final int FREQUENCY_LIMIT = 2;

    /**
     * mock-channel 模板 code（自定义字符串，{@code services/mock-channel} 允许任意模板）：
     * 记录里 {@code templateKnown=false}，文案取 {@code payload.content}（即催缴话术），
     * <b>不是</b>三次推送模板，也不产生真实短信/企微外发。
     */
    public static final String PUSH_TEMPLATE = "dunning";

    /** 负责人轮转表（演示账号显示名；岗位名口径，不含真实姓名/手机号） */
    public static final List<String> ASSIGNEE_ROSTER = List.of("李管家", "陈坐席", "赵主管");

    /** 任务生成排序（严重度降序：D30 优先触达） */
    public static final List<ArrearsBucket> GENERATION_ORDER =
            List.of(ArrearsBucket.D30, ArrearsBucket.D15, ArrearsBucket.D7, ArrearsBucket.D1);

    private DunningPolicy() {
    }

    /** 单分层策略（语气 + 默认渠道 + 下一次应触达天数）。 */
    public record BucketPolicy(DunningTone tone, DunningChannel channel, int dueInDays) {
    }

    /**
     * 分层 → 策略（未知/空分层按 D1 处理：最轻语气，避免误用「坚定/短信」）。
     */
    public static BucketPolicy bucketPolicy(ArrearsBucket bucket) {
        if (bucket == null) {
            return new BucketPolicy(DunningTone.FRIENDLY, DunningChannel.WECOM, 3);
        }
        return switch (bucket) {
            case D1 -> new BucketPolicy(DunningTone.FRIENDLY, DunningChannel.WECOM, 3);
            case D7 -> new BucketPolicy(DunningTone.FRIENDLY, DunningChannel.WECOM, 2);
            case D15 -> new BucketPolicy(DunningTone.FIRM, DunningChannel.WECOM, 1);
            case D30 -> new BucketPolicy(DunningTone.FIRM, DunningChannel.SMS, 0);
        };
    }

    /** 分层严重度（D30 &gt; D15 &gt; D7 &gt; D1；用于列表/生成排序，越大越紧急）。 */
    public static int severity(ArrearsBucket bucket) {
        if (bucket == null) {
            return 0;
        }
        return switch (bucket) {
            case D1 -> 1;
            case D7 -> 2;
            case D15 -> 3;
            case D30 -> 4;
        };
    }

    /** 负责人轮转（按生成序号取模；index &lt; 0 也能落到合法下标）。 */
    public static String assigneeAt(int index) {
        int size = ASSIGNEE_ROSTER.size();
        return ASSIGNEE_ROSTER.get(Math.floorMod(index, size));
    }
}
