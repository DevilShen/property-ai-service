package com.property.ai.domain.fee;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.property.ai.bff.dto.BillExplanation;
import com.property.ai.bff.dto.BillExplanationDiff;
import com.property.ai.bff.dto.BillExplanationLine;
import com.property.ai.bff.dto.BillExplanationRef;
import com.property.ai.bff.dto.ErrorCode;
import com.property.ai.domain.ticket.DomainException;

/**
 * 账单解释组装（P3 W10，契约 v1.1.0 {@code GET /api/v1/fees/explain}）。
 *
 * <p>职责：把 ERP 账单（金额事实）+ ERP 费项字典（公式/口径）+ 知识库 mock（出处）组装成
 * {@link BillExplanation}，并执行<b>可追溯性校验</b>：
 * <ul>
 *   <li>每个解释行的计费规则必须来自费项字典 {@code formula} 或账单行 {@code note}（口径缺失 → 拒答）；</li>
 *   <li>每个解释行必须有知识条目（{@code knowledgeId}）且条目在知识库 mock 中<b>存在且 published</b>，
 *       否则拒答（404 {@code E-6002} 引用缺失），<b>不得编造</b>；</li>
 *   <li>{@code diffVsPrev.reason} 必须能追溯到本次 {@code knowledgeRefs}（见
 *       {@link FeeExplainComposer#diffOf}）。</li>
 * </ul>
 *
 * <p>金额纪律：{@code total} 与各费项 {@code amount} 原样取 ERP 账单，本类不做任何加减乘除；
 * 只拼装文案与引用。
 */
@Service
public class FeeExplainService {

    private static final Logger log = LoggerFactory.getLogger(FeeExplainService.class);

    /** 滞纳金费项编码（不在账单 items 里，由账单 lateFee 字段单独给出） */
    static final String LATE_FEE = "LATE_FEE";

    private final FeeKnowledgeBase knowledge;

    public FeeExplainService(FeeKnowledgeBase knowledge) {
        this.knowledge = knowledge;
    }

    /**
     * 组装账单解释。
     *
     * @param bill        ERP 账单（金额事实源）
     * @param room        ERP 房档（分摊基数：面积/车位；可为 {@code null}）
     * @param itemCode    只解释单个费项（{@code PROPERTY_FEE}/{@code PUBLIC_ENERGY}/{@code PARKING_FEE}/
     *                    {@code LATE_FEE}）；{@code null} = 解释整单
     * @param dictionary  ERP 费项字典（公式/口径事实源）
     */
    public BillExplanation explain(FeeErpClient.ErpBill bill, FeeErpClient.ErpRoom room, String itemCode,
            List<FeeErpClient.FeeItemDef> dictionary, String traceId) {
        if (bill == null) {
            throw DomainException.notFound(ErrorCode.E_3001, "该账期无账单，无法解释",
                    Map.of("reason", "bill_missing"));
        }
        if (bill.total() == null) {
            throw DomainException.upstream(ErrorCode.E_5002, "ERP 账单缺少应缴合计（total），无法解释",
                    Map.of("field", "total", "roomCode", bill.roomCode(), "period", bill.period()), null, false);
        }
        String normalizedItem = normalizeItem(itemCode);
        Map<String, FeeErpClient.FeeItemDef> dict = byCode(dictionary);
        if (normalizedItem != null && !dict.containsKey(normalizedItem)) {
            throw DomainException.badRequest("itemCode 取值非法（不在 ERP 费项字典内）",
                    Map.of("field", "itemCode", "value", normalizedItem,
                            "allowed", dict.keySet().stream().toList()));
        }

        List<FeeErpClient.ErpBillLine> lines = selectLines(bill, dict, normalizedItem);
        if (lines.isEmpty()) {
            throw DomainException.notFound(ErrorCode.E_3001,
                    normalizedItem == null ? "该账期无可解释的费项" : "该费项本期无发生额：" + normalizedItem,
                    Map.of("roomCode", nullToDash(bill.roomCode()), "period", nullToDash(bill.period()),
                            "itemCode", nullToDash(normalizedItem)));
        }

        // 1) 知识出处收集与校验（缺失/过期 → 拒答 E-6002）
        Map<String, FeeKnowledgeBase.Entry> knowledgeById = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (FeeErpClient.ErpBillLine line : lines) {
            FeeErpClient.FeeItemDef def = dict.get(line.itemCode());
            ids.add(line.knowledgeId() != null ? line.knowledgeId() : (def == null ? null : def.knowledgeId()));
        }
        if (bill.cite() != null) {
            ids.add(bill.cite().kbId());
        }
        for (String id : FeeExplainComposer.distinctIds(ids)) {
            Optional<FeeKnowledgeBase.Entry> entry = knowledge.findPublished(id);
            if (entry.isPresent()) {
                knowledgeById.put(id.toUpperCase(java.util.Locale.ROOT), entry.get());
            } else {
                missing.add(id);
            }
        }
        if (!missing.isEmpty()) {
            log.info("[fee] explain 拒答：知识出处缺失 {}（roomCode={} period={}）", missing, bill.roomCode(),
                    bill.period());
            throw FeeExplainComposer.refuse("知识出处缺失或已失效（不得编造）", "knowledge_missing", List.of());
        }
        List<BillExplanationRef> refs = knowledgeRefs(lines, dict, knowledgeById, bill);
        if (refs.isEmpty()) {
            throw FeeExplainComposer.refuse("无知识出处，不能解释账单", "knowledge_refs_empty", List.of());
        }

        // 2) 解释行（公式/分摊基数/规则；规则缺失 → 拒答）
        List<FeeExplainComposer.FeeExplainComposerLine> composed = new ArrayList<>();
        List<BillExplanationLine> breakdown = new ArrayList<>();
        for (FeeErpClient.ErpBillLine line : lines) {
            FeeErpClient.FeeItemDef def = dict.get(line.itemCode());
            String lineKnowledgeId = line.knowledgeId() != null ? line.knowledgeId()
                    : (def == null ? null : def.knowledgeId());
            if (lineKnowledgeId == null || lineKnowledgeId.isBlank()) {
                throw FeeExplainComposer.refuse("费项无知识出处（knowledgeId 缺失）",
                        "knowledge_missing", refs);
            }
            FeeKnowledgeBase.Entry entry = knowledgeById.get(lineKnowledgeId.toUpperCase(java.util.Locale.ROOT));
            if (def == null || FeeExplainComposer.ruleOf(def, line) == null || entry == null) {
                throw FeeExplainComposer.refuse("费项计费口径缺失（ERP 费项字典无该费项规则）",
                        "fee_item_rule_missing", refs);
            }
            FeeExplainComposer.FeeExplainComposerLine composeLine = FeeExplainComposer.lineOf(line, def, room,
                    bill, entry);
            composed.add(composeLine);
            breakdown.add(new BillExplanationLine(composeLine.itemCode(), composeLine.name(),
                    composeLine.amount(), composeLine.base(), composeLine.rule()));
        }
        String formula = FeeExplainComposer.formula(composed);
        if (formula == null) {
            throw FeeExplainComposer.refuse("计费公式缺失，无法解释", "formula_missing", refs);
        }

        // 3) 与上期差异（原因必须可追溯；金额取 ERP）
        BillExplanationDiff diff = FeeExplainComposer.diffOf(bill.diff(), bill.diffReason(), refs);

        return new BillExplanation(
                bill.roomCode(),
                bill.period(),
                normalizedItem,
                bill.total(),
                formula,
                List.copyOf(breakdown),
                diff,
                refs,
                bill.dataAsOf(),
                bill.mock());
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 选择解释行：整单 = ERP 账单 items（+ 滞纳金）；单费项 = 仅该费项（滞纳金取 lateFee 字段）。 */
    private static List<FeeErpClient.ErpBillLine> selectLines(FeeErpClient.ErpBill bill,
            Map<String, FeeErpClient.FeeItemDef> dict, String itemCode) {
        List<FeeErpClient.ErpBillLine> all = new ArrayList<>(bill.items() == null ? List.of() : bill.items());
        BigDecimal lateFee = bill.lateFee();
        if (lateFee != null && lateFee.signum() > 0) {
            FeeErpClient.FeeItemDef lateDef = dict.get(LATE_FEE);
            all.add(new FeeErpClient.ErpBillLine(LATE_FEE,
                    lateDef == null ? "滞纳金" : lateDef.name(), lateFee,
                    lateDef == null ? null : lateDef.knowledgeId(), null));
        }
        if (itemCode == null) {
            return List.copyOf(all);
        }
        return all.stream().filter(line -> itemCode.equalsIgnoreCase(line.itemCode())).toList();
    }

    /** 知识引用列表（按解释行出现顺序去重；每行必须有可引用出处，缺失已在调用前拒答）。 */
    private static List<BillExplanationRef> knowledgeRefs(List<FeeErpClient.ErpBillLine> lines,
            Map<String, FeeErpClient.FeeItemDef> dict, Map<String, FeeKnowledgeBase.Entry> knowledgeById,
            FeeErpClient.ErpBill bill) {
        List<String> ids = new ArrayList<>();
        for (FeeErpClient.ErpBillLine line : lines) {
            FeeErpClient.FeeItemDef def = dict.get(line.itemCode());
            ids.add(line.knowledgeId() != null ? line.knowledgeId() : (def == null ? null : def.knowledgeId()));
        }
        if (bill.cite() != null) {
            ids.add(bill.cite().kbId());
        }
        List<BillExplanationRef> refs = new ArrayList<>();
        for (String id : FeeExplainComposer.distinctIds(ids)) {
            FeeKnowledgeBase.Entry entry = knowledgeById.get(id.toUpperCase(java.util.Locale.ROOT));
            if (entry == null) {
                continue;
            }
            refs.add(new BillExplanationRef(entry.knowledgeId(), entry.title(), entry.version()));
        }
        return List.copyOf(refs);
    }

    private static Map<String, FeeErpClient.FeeItemDef> byCode(List<FeeErpClient.FeeItemDef> dictionary) {
        Map<String, FeeErpClient.FeeItemDef> map = new LinkedHashMap<>();
        if (dictionary != null) {
            for (FeeErpClient.FeeItemDef item : dictionary) {
                if (item.itemCode() != null) {
                    map.put(item.itemCode().toUpperCase(java.util.Locale.ROOT), item);
                }
            }
        }
        return map;
    }

    private static String normalizeItem(String itemCode) {
        if (itemCode == null || itemCode.isBlank()) {
            return null;
        }
        return itemCode.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private static String nullToDash(String value) {
        return value == null ? "" : value;
    }
}
