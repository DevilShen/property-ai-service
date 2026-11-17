package com.property.ai.domain.dunning;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.property.ai.bff.dto.DunningChannel;
import com.property.ai.bff.dto.DunningScript;
import com.property.ai.bff.dto.DunningTone;

/**
 * 催缴话术生成器 + 禁区扫描（P3-013，契约 {@code DunningScript} / 产品 FR-BILL-03/04 红线）。
 *
 * <h2>生成 ≠ 外发</h2>
 * 生成只落话术版本（{@code v1}、{@code v2}…），不触达业主；必须再由人工 {@code confirmed:true}
 * 走 {@code POST /dunning/tasks/{taskId}/send}（P3 走 {@code services/mock-channel}，{@code status=mocked}）。
 *
 * <h2>两档模板（契约 {@code DunningTone}）</h2>
 * <ul>
 *   <li>{@code friendly} 温和提醒：首次/短期逾期（D1/D7），说明房号+账期+金额+滞纳金，给缴费入口与核实通道；</li>
 *   <li>{@code firm} 明确期限：长期逾期（D15/D30），额外给「已逾期 N 天」与缴费期限（任务 {@code dueAt} 业务日）；</li>
 * </ul>
 * 两档都不含完整手机号/身份证/地址（只使用 ERP 掩码业主名 {@code ownerNameMasked}），
 * <b>不承诺减免/免滞纳金</b>、<b>不威胁</b>、<b>不提群内/公开渠道</b>、<b>不做 AI 语音外呼</b>（AGENTS.md NEVER #4/#7）。
 *
 * <h2>禁区扫描（{@code forbiddenHits}）</h2>
 * 对生成内容（以及任何待发送话术）做规则表扫描，命中项写入 {@code forbiddenHits}（规则 code 列表）；
 * 非空 → 前端拦截 + {@code send} 服务端复核 409 {@code E-3002}（{@code details.reason=forbidden_hits}）。
 * 规则表见 {@link #FORBIDDEN_RULES}（起诉/拘留/冒充公安/曝光/群内公开/羞辱/承诺减免等）。
 */
@Component
public class DunningScriptGenerator {

    /** 话术里「业主称呼」兜底（无掩码时不用真实姓名） */
    private static final String OWNER_FALLBACK = "业主";

    /**
     * 禁区规则表（P3 红线口径；code 稳定、可写进报告与前端提示）。
     *
     * <p>规则 code 语义：
     * <ul>
     *   <li>{@code forbid_legal_threat} 威胁诉讼（起诉/法院/律师函/追究法律责任）；</li>
     *   <li>{@code forbid_law_enforcement_impersonation} 冒充公检法/威胁拘留（公安/警察/拘留/传唤）；</li>
     *   <li>{@code forbid_public_exposure} 公开渠道曝光欠费（曝光/公示/张贴/公告栏/全楼/邻居/朋友圈）；</li>
     *   <li>{@code forbid_group_discussion} 群内/群发提欠费（业主群/微信群/群发/群里）；</li>
     *   <li>{@code forbid_waiver_promise} 承诺减免/免滞纳金（减免/免除/免收/免缴/不用交/打折/可以少交）；</li>
     *   <li>{@code forbid_shaming} 羞辱/歧视性表述（老赖/丢人/无耻/羞辱/素质差）；</li>
     *   <li>{@code forbid_night_outbound_call} 22:00—8:00 外呼话术（现在致电/马上打电话/夜里上门）。</li>
     * </ul>
     */
    public static final List<ForbiddenRule> FORBIDDEN_RULES = List.of(
            new ForbiddenRule("forbid_legal_threat", "威胁起诉/法律追责",
                    Pattern.compile("起诉|法院|律师函|追究法律责任|法律诉讼|失信被执行人")),
            new ForbiddenRule("forbid_law_enforcement_impersonation", "冒充公检法/威胁拘留",
                    Pattern.compile("拘留|报警|公安|警察|派出所|公检法|传唤|刑事")),
            new ForbiddenRule("forbid_public_exposure", "公开渠道曝光欠费",
                    Pattern.compile("曝光|公示|公布|张贴|公告栏|全楼|邻居|朋友圈|公开欠费")),
            new ForbiddenRule("forbid_group_discussion", "群内/群发提及欠费",
                    Pattern.compile("群内|业主群|微信群|群发|群里")),
            new ForbiddenRule("forbid_waiver_promise", "承诺减免/免滞纳金",
                    Pattern.compile("减免|免除|免收|免缴|免滞纳金|不用交|可以少交|打折")),
            new ForbiddenRule("forbid_shaming", "羞辱/歧视性表述",
                    Pattern.compile("老赖|丢人|无耻|羞辱|素质差")),
            new ForbiddenRule("forbid_night_outbound_call", "22:00—8:00 外呼话术",
                    Pattern.compile("现在致电|马上打电话|夜里上门|凌晨联系")));

    /**
     * 扫描话术正文（纯函数，无 IO；{@code content} 为空返回空命中）。
     *
     * @return 命中的规则 code 列表（按规则表顺序、去重；空列表 = 未命中，可发送）
     */
    public static List<String> scan(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        Set<String> hits = new LinkedHashSet<>();
        for (ForbiddenRule rule : FORBIDDEN_RULES) {
            if (rule.pattern().matcher(content).find()) {
                hits.add(rule.code());
            }
        }
        return List.copyOf(hits);
    }

    /**
     * 生成话术（含禁区扫描与版本号）。
     *
     * @param task            任务记录（房号/账期/金额/滞纳金/逾期天数/业主掩码）
     * @param tone            语气（{@code null} → friendly）
     * @param channel         渠道（{@code null} → wecom）
     * @param versionSequence 版本序号（1 → {@code v1}；由任务记录递增，重复生成为新版本）
     */
    public DunningScript generate(DunningTaskRecord task, DunningTone tone, DunningChannel channel,
            int versionSequence) {
        DunningTone effectiveTone = tone == null ? DunningTone.FRIENDLY : tone;
        DunningChannel effectiveChannel = channel == null ? DunningChannel.WECOM : channel;
        String content = content(task, effectiveTone);
        return new DunningScript(content, scan(content), versionLabel(versionSequence), effectiveTone,
                effectiveChannel);
    }

    /** 版本号口径：{@code v} + 序号（v1 / v2 / …）。 */
    public static String versionLabel(int versionSequence) {
        return "v" + Math.max(1, versionSequence);
    }

    /**
     * 模板正文（两档语气；金额/滞纳金/逾期天数直接取任务快照，<b>不自算、不承诺减免</b>）。
     *
     * <p>可见性：包级（单测直接断言模板文字，不必经过服务）。
     */
    String content(DunningTaskRecord task, DunningTone tone) {
        String owner = maskOwner(task.ownerNameMasked());
        String lateFeeClause = lateFeeClause(task.lateFee());
        if (tone == DunningTone.FIRM) {
            return owner + "您好，" + task.roomCode() + " 房屋 " + task.period() + " 期物业费 "
                    + money(task.outstanding()) + " 元已逾期 " + task.overdueDays() + " 天" + lateFeeClause
                    + "。请于 " + businessDay(task.dueAt()) + " 前在业主端「账单」页完成缴费；"
                    + "如已缴费请忽略本消息，如对金额或缴费方式有疑问，请回复本消息联系管家核实。";
        }
        return owner + "您好，您 " + task.roomCode() + " 房屋 " + task.period() + " 期物业费 "
                + money(task.outstanding()) + " 元尚未缴纳" + lateFeeClause
                + "。请方便时在业主端「账单」页直接缴费；如对金额有疑问，可回复本消息，我帮您预约管家核实。";
    }

    /** 业主称呼：掩码原样（如「王*」）+ 兜底「业主」；不补全真实姓名。 */
    private static String maskOwner(String masked) {
        return masked == null || masked.isBlank() ? OWNER_FALLBACK : masked.trim();
    }

    /** 滞纳金从句：ERP 口径金额 &gt; 0 才提（0 元不提，避免误导）。 */
    private static String lateFeeClause(BigDecimal lateFee) {
        if (lateFee == null || lateFee.signum() <= 0) {
            return "";
        }
        return "（含滞纳金 " + money(lateFee) + " 元）";
    }

    /** 金额展示：去掉无意义的尾随 0（408 → 408；3.60 → 3.6，与 ERP 口径一致、不重算）。 */
    static String money(BigDecimal amount) {
        if (amount == null) {
            return "0";
        }
        BigDecimal stripped = amount.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0).toPlainString() : stripped.toPlainString();
    }

    /** 业务日（{@code dueAt} 前 10 位 = yyyy-MM-dd；缺失时用「本月底前」式兜底，不编造具体日期）。 */
    static String businessDay(String dueAt) {
        if (dueAt != null && dueAt.length() >= 10) {
            return dueAt.substring(0, 10);
        }
        return "近期";
    }

    /**
     * 禁区规则。
     *
     * @param code    稳定规则 code（写进 {@code forbiddenHits}）
     * @param label   人话说明（报告/前端提示）
     * @param pattern 命中正则（对生成内容 {@code find()}）
     */
    public record ForbiddenRule(String code, String label, Pattern pattern) {
    }

    /** 便于报告/测试：全部规则 code（顺序稳定）。 */
    public static List<String> forbiddenRuleCodes() {
        List<String> codes = new ArrayList<>();
        for (ForbiddenRule rule : FORBIDDEN_RULES) {
            codes.add(rule.code());
        }
        return List.copyOf(codes);
    }
}
