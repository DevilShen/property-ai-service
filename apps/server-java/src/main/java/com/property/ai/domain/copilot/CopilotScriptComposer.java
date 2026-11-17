package com.property.ai.domain.copilot;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.property.ai.bff.dto.CopilotEmotion;
import com.property.ai.bff.dto.CopilotScript;
import com.property.ai.bff.dto.CopilotScriptRequest;
import com.property.ai.bff.dto.CopilotScriptVersion;
import com.property.ai.bff.dto.CopilotTone;
import com.property.ai.bff.dto.TicketPushChannel;
import com.property.ai.domain.dunning.DunningScriptGenerator;

/**
 * Copilot 话术生成器（P4-011，契约 v1.2.0 {@code POST /copilot/script}、{@code CopilotScript}）。
 *
 * <h2>生成 ≠ 外发（红线）</h2>
 * 只生成 3 版话术（{@code gentle}/{@code standard}/{@code firm}，顺序与 {@link CopilotTone} 一致），
 * <b>不调用模型、不触达业主</b>；真实发送必须人工确认（AGENTS.md NEVER #4/#7）。
 * 结果 {@code mock:true} = 平台侧规则组装（模型编排归 Python 层）。
 *
 * <h2>禁区扫描（复用催缴域 7 条规则）</h2>
 * 复用 {@link DunningScriptGenerator#scan(String)} 的 7 条稳定规则
 * （威胁诉讼 / 冒充公检法 / 公开曝光 / 群内提欠费 / 承诺减免 / 羞辱 / 夜间外呼），
 * 命中非空 → 前端必须提示改写、不得直接复制外发。
 *
 * <h2>渠道口径</h2>
 * {@code channel} 只影响文案长度/格式（短信更短），不触发任何外发：
 * {@code sms} 截断到 70 字（超出以「…」标注），{@code inapp} 120 字，{@code wecom} 全文。
 */
@Component
public class CopilotScriptComposer {

    /** 短信渠道正文上限（字符；超出截断并加省略号，避免长短信分片） */
    static final int SMS_MAX_CHARS = 70;
    /** 站内消息渠道正文上限 */
    static final int INAPP_MAX_CHARS = 120;

    /**
     * 生成 3 版话术（场景 × 情绪 × 渠道）。
     *
     * @param request 入参（{@code scenario} 必填，由 {@code CopilotService} 前置校验）
     */
    public CopilotScript compose(CopilotScriptRequest request) {
        String scenario = request.scenario() == null ? "" : request.scenario().trim();
        CopilotEmotion emotion = request.emotion() == null ? CopilotEmotion.NEUTRAL : request.emotion();
        TicketPushChannel channel = request.channel() == null ? TicketPushChannel.WECOM : request.channel();
        String ticketSuffix = request.ticketNo() == null || request.ticketNo().isBlank()
                ? ""
                : "（工单 " + request.ticketNo().trim() + "）";

        List<CopilotScriptVersion> versions = new ArrayList<>(CopilotTone.values().length);
        for (CopilotTone tone : CopilotTone.values()) {
            String content = limit(content(tone, emotion, scenario) + ticketSuffix, channel);
            versions.add(new CopilotScriptVersion(tone, content, DunningScriptGenerator.scan(content)));
        }
        return new CopilotScript(List.copyOf(versions), true);
    }

    /** 单版正文（情绪只调整开场，不改承诺口径）。 */
    String content(CopilotTone tone, CopilotEmotion emotion, String scenario) {
        String opening = opening(emotion);
        String topic = scenario.isEmpty() ? "您反馈的问题" : "您反馈的「" + scenario + "」";
        return switch (tone) {
            case GENTLE -> opening + "您好，我是物业客服。" + topic + "我们已记录并安排专人跟进核实，"
                    + "处理进展会及时同步给您；给您带来不便，敬请谅解。";
            case STANDARD -> opening + "您好，我是物业客服。" + topic + "已受理，"
                    + "我们将在 30 分钟内核实并给出处理方案；如需上门会提前与您确认时间。";
            case FIRM -> opening + "您好，关于" + topic + "的处理，请按小区管理规约配合整改；"
                    + "如 24 小时内仍未处理，我们将按管理规定升级处理并留存记录，届时会同步处理结果。";
        };
    }

    /** 情绪开场（愤怒先共情、焦虑先安抚；其它不加）。 */
    private static String opening(CopilotEmotion emotion) {
        return switch (emotion) {
            case ANGRY -> "非常理解您的心情，";
            case ANXIOUS -> "请放心，";
            case CALM, NEUTRAL -> "";
        };
    }

    /** 渠道长度限制（只截断文案，不改变语义方向）。 */
    private static String limit(String content, TicketPushChannel channel) {
        int max = switch (channel) {
            case SMS -> SMS_MAX_CHARS;
            case INAPP -> INAPP_MAX_CHARS;
            case WECOM -> Integer.MAX_VALUE;
        };
        if (content.length() <= max) {
            return content;
        }
        return content.substring(0, max - 1) + "…";
    }
}
