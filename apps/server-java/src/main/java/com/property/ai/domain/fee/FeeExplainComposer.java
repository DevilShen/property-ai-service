package com.property.ai.domain.fee;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.property.ai.bff.dto.BillExplanationDiff;
import com.property.ai.bff.dto.BillExplanationRef;
import com.property.ai.bff.dto.ErrorCode;
import com.property.ai.domain.ticket.DomainException;

/**
 * 账单解释文案纯函数（P3 W10，无 IO，可直接单测）。
 *
 * <p>口径（契约 v1.1.0 {@code BillExplanation} / 产品 FR-BILL-02「先解释清楚再提醒缴费」）：
 * <ul>
 *   <li>{@code formula}：由<b>ERP 费项字典</b>的 {@code formula} 字段 + 知识条目版本拼装，
 *       不在 Java 里写死单价/金额（金额纪律：AI 不自算，架构 §12.1）；</li>
 *   <li>{@code base}：分摊基数/计价依据来自 ERP 房档（面积/车位），只做展示描述；</li>
 *   <li>{@code rule}：优先费项字典公式，缺失时用 ERP 账单行 {@code note}（两者都无 → 口径缺失，拒答）；</li>
 *   <li>{@code diffVsPrev.reason}：ERP 给出的差额原因原文（已带知识条目版本，如「（K-002 V1.1）」），
 *       必须能追溯到本次 {@code knowledgeRefs}，否则拒答（404 {@code E-6002}）。</li>
 * </ul>
 */
public final class FeeExplainComposer {

    /** 差额为 0 时不返回 diffVsPrev（契约：只在与上期存在差异时返回） */
    public static final BigDecimal ZERO = BigDecimal.ZERO;

    private FeeExplainComposer() {
    }

    /** 整单计费公式：`应缴 = a + b + c；a：规则（K-xxx Vx.y）；…`（无可用口径 → {@code null}，由调用方拒答）。 */
    public static String formula(List<FeeExplainComposerLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return null;
        }
        StringBuilder names = new StringBuilder();
        StringBuilder details = new StringBuilder();
        boolean first = true;
        for (FeeExplainComposerLine line : lines) {
            if (line.rule() == null || line.rule().isBlank()) {
                return null;
            }
            if (!first) {
                names.append(" + ");
            }
            names.append(line.name());
            first = false;
            if (details.length() > 0) {
                details.append("；");
            }
            details.append(line.name()).append("：").append(line.rule());
            if (line.refLabel() != null && !line.refLabel().isBlank()) {
                details.append(line.refLabel());
            }
        }
        return "应缴 = " + names + "；" + details;
    }

    /**
     * 与上期差异（{@code amount} 直接取 ERP {@code diff}，AI 不重算）。
     *
     * @return 差额为 0/空 → {@code null}；原因不可追溯 → 抛 404 {@code E-6002}（引用缺失拒答）
     */
    public static BillExplanationDiff diffOf(BigDecimal diff, String reason, List<BillExplanationRef> refs) {
        if (diff == null || diff.signum() == 0) {
            return null;
        }
        String text = reason == null ? "" : reason.trim();
        if (text.isEmpty()) {
            throw refuse("与上期存在差额但 ERP 未给出可追溯原因（diffReason 缺失）", "diff_reason_missing", refs);
        }
        boolean traceable = refs != null && refs.stream()
                .anyMatch(ref -> ref.knowledgeId() != null && text.contains(ref.knowledgeId()));
        if (!traceable) {
            throw refuse("与上期差额原因无法追溯到知识条目：" + text, "diff_reason_untraceable", refs);
        }
        return new BillExplanationDiff(diff, text);
    }

    /** 单行解释（费项名 + 金额 + 分摊基数 + 计费规则），金额原样取 ERP 账单行。 */
    public static FeeExplainComposerLine lineOf(FeeErpClient.ErpBillLine billLine, FeeErpClient.FeeItemDef def,
            FeeErpClient.ErpRoom room, FeeErpClient.ErpBill bill, FeeKnowledgeBase.Entry knowledge) {
        String itemCode = billLine.itemCode() == null ? "" : billLine.itemCode();
        String name = billLine.name() != null && !billLine.name().isBlank()
                ? billLine.name()
                : (def == null ? itemCode : def.name());
        return new FeeExplainComposerLine(itemCode, name, billLine.amount(), baseOf(itemCode, room, bill),
                ruleOf(def, billLine), refLabel(knowledge));
    }

    /** 分摊基数 / 计价依据（展示口径，只读 ERP 房档与账单字段，不做金额运算）。 */
    public static String baseOf(String itemCode, FeeErpClient.ErpRoom room, FeeErpClient.ErpBill bill) {
        String area = room == null || room.areaM2() == null ? null : room.areaM2().stripTrailingZeros().toPlainString();
        String code = itemCode == null ? "" : itemCode;
        return switch (code) {
            case "PROPERTY_FEE" -> area == null ? "按房档建筑面积计" : "建筑面积 " + area + " ㎡";
            case "PUBLIC_ENERGY" -> (area == null ? "按房档建筑面积分摊" : "建筑面积 " + area + " ㎡")
                    + "；按公区实际电费 ÷ 项目总建筑面积 × 本户建筑面积月分摊";
            case "PARKING_FEE" -> room != null && room.parkingNo() != null && !room.parkingNo().isBlank()
                    ? "固定车位 " + room.parkingNo()
                    : "固定车位（房档未登记车位号）";
            case "LATE_FEE" -> "未缴本金 × 日费率 × 逾期天数（逾期 "
                    + (bill == null || bill.overdueDays() == null ? 0 : bill.overdueDays()) + " 天）";
            default -> "按 ERP 账单口径计费";
        };
    }

    /** 计费规则：费项字典公式 → 账单行 note → {@code null}（口径缺失，拒答）。 */
    public static String ruleOf(FeeErpClient.FeeItemDef def, FeeErpClient.ErpBillLine billLine) {
        if (def != null && def.formula() != null && !def.formula().isBlank()) {
            return def.formula();
        }
        if (billLine != null && billLine.note() != null && !billLine.note().isBlank()) {
            return billLine.note();
        }
        return null;
    }

    /** 知识条目展示后缀（如「（K-002 V1.1）」；缺失返回空串）。 */
    public static String refLabel(FeeKnowledgeBase.Entry knowledge) {
        if (knowledge == null || knowledge.knowledgeId() == null) {
            return "";
        }
        String version = knowledge.version() == null || knowledge.version().isBlank()
                ? ""
                : " " + knowledge.version();
        return "（" + knowledge.knowledgeId() + version + "）";
    }

    /** 引用缺失拒答（404 E-6002，契约 feeExplain 404 语义；不新造错误码）。 */
    public static DomainException refuse(String message, String reason, List<BillExplanationRef> refs) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", reason);
        details.put("knowledgeRefs", refs == null
                ? List.of()
                : refs.stream().map(BillExplanationRef::knowledgeId).filter(java.util.Objects::nonNull).toList());
        return DomainException.notFound(ErrorCode.E_6002, "账单解释拒答：" + message, details);
    }

    /** 解释单行（组装中间结构，便于单测公式拼装）。 */
    public record FeeExplainComposerLine(
            String itemCode,
            String name,
            BigDecimal amount,
            String base,
            String rule,
            String refLabel) {
    }

    /** 引用列表去重（按出现顺序；大小写不敏感）。 */
    public static List<String> distinctIds(List<String> ids) {
        if (ids == null) {
            return List.of();
        }
        List<String> rows = new ArrayList<>();
        for (String id : ids) {
            if (id == null || id.isBlank()) {
                continue;
            }
            String normalized = id.trim();
            boolean exists = rows.stream().anyMatch(item -> item.equalsIgnoreCase(normalized));
            if (!exists) {
                rows.add(normalized);
            }
        }
        return List.copyOf(rows);
    }
}
