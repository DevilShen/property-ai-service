package com.property.ai.connector.selfbuilt;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.property.ai.bff.dto.CapabilityCode;
import com.property.ai.bff.dto.CapabilityLevel;
import com.property.ai.bff.dto.CapabilityStatus;
import com.property.ai.connector.CapabilityCatalog;

/**
 * 自研 ERP（自有形态 3.0）厂商能力画像（P5-012 / task-65）。
 *
 * <h2>口径</h2>
 * 18 项标准能力逐项给出<b>三态 + 证据 + 替代通道</b>，分布刻意与另两家不同，用于多连接器横向对比：
 * <ul>
 *   <li>用友 NC 基线：18 supported（0/0）；</li>
 *   <li>金蝶 EAS 替身：14 / 3 / 1（degraded: invoice.apply / ticket.assign / notice.push；unsupported: parking.auth）；</li>
 *   <li><b>自研 3.0：12 / 4 / 2</b>（degraded: ticket.assign / invoice.apply / visit.record / dunning.task；
 *       unsupported: pay.jump / parking.auth）。</li>
 * </ul>
 *
 * <h2>来源与边界（AGENTS.md NEVER #4/#11）</h2>
 * 画像来自「自研 3.0 开放平台（模拟）能力目录 + 厂商开通单」：探测时连接器会先读 mock 数据面的
 * {@code GET /api/v1/meta/capabilities} 做<b>目录对齐与连通性证据</b>，再按本画像给出三态。
 * 本仓库内它是**测试替身**，不接真实自研 ERP；<b>该分布不代表任何真实厂商能力评估</b>，
 * 也不得在对外材料中把它说成已验证的真实 ERP 能力（契约 README §1.6 / §7.6「声明先于探测」）。
 */
public final class SelfBuiltCapabilityProfile {

    /** 单能力画像条目（三态 + 证据 + 替代通道） */
    public record Entry(CapabilityCode code, CapabilityStatus state, String evidence, String fallback) {
    }

    /** 降级能力（4 项）：厂商已开通但能力受限，需走替代路径 */
    private static final Map<CapabilityCode, String> DEGRADED_EVIDENCE = Map.of(
            CapabilityCode.TICKET_ASSIGN,
            "自研 3.0 工单派单为「工单池 + 抢单」模型，无强制派单接口；平台侧改人工指派或转派",
            CapabilityCode.INVOICE_APPLY,
            "自研 3.0 开票仅支持单张开票申请（批量为人工受理），无批量接口",
            CapabilityCode.VISIT_RECORD,
            "自研 3.0 回访为异步受理（T+1 回传），不提供实时回写",
            CapabilityCode.DUNNING_TASK,
            "自研 3.0 催缴仅导出欠费名单，无任务下发接口");

    /** 不支持能力（2 项）：厂商侧无对应实体/模块，入口必须隐藏并转人工 */
    private static final Map<CapabilityCode, String> UNSUPPORTED_EVIDENCE = Map.of(
            CapabilityCode.PAY_JUMP,
            "自研 3.0 无收银台/缴费跳转协议（缴费走线下 POS），不提供跳转链接",
            CapabilityCode.PARKING_AUTH,
            "自研 3.0 未开通停车模块（无月卡/临停授权实体）");

    private SelfBuiltCapabilityProfile() {
    }

    /** 全量 18 项画像（顺序 = 契约 {@link CapabilityCode} 枚举顺序 CAP-01…CAP-18） */
    public static List<Entry> entries() {
        List<Entry> rows = new ArrayList<>(CapabilityCode.values().length);
        for (CapabilityCode code : CapabilityCode.values()) {
            rows.add(entry(code));
        }
        return List.copyOf(rows);
    }

    /** 单能力画像 */
    public static Entry entry(CapabilityCode code) {
        CapabilityCatalog.Entry catalog = CapabilityCatalog.entry(code);
        if (DEGRADED_EVIDENCE.containsKey(code)) {
            return new Entry(code, CapabilityStatus.DEGRADED, DEGRADED_EVIDENCE.get(code), catalog.fallback());
        }
        if (UNSUPPORTED_EVIDENCE.containsKey(code)) {
            return new Entry(code, CapabilityStatus.UNSUPPORTED, UNSUPPORTED_EVIDENCE.get(code), catalog.fallback());
        }
        return new Entry(code, CapabilityStatus.SUPPORTED,
                "自研 3.0 开放平台提供该能力（目录对齐通过：仅支持只读/幂等查询，写操作按平台侧回写口径）",
                catalog.fallback());
    }

    /** 三态计数（供报告/测试断言：12 / 4 / 2） */
    public static Map<CapabilityStatus, Integer> counts() {
        Map<CapabilityStatus, Integer> counts = new EnumMap<>(CapabilityStatus.class);
        for (CapabilityStatus status : CapabilityStatus.values()) {
            counts.put(status, 0);
        }
        for (Entry entry : entries()) {
            counts.merge(entry.state(), 1, Integer::sum);
        }
        return counts;
    }

    /** 能力级别（沿用 18 项目录级别定义，不另造） */
    public static CapabilityLevel levelOf(CapabilityCode code) {
        return CapabilityCatalog.entry(code).level();
    }
}
