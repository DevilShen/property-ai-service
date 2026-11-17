package com.property.ai.bff.dto;

import java.math.BigDecimal;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * OpenAPI: BillExplanation（P3 W10 / v1.1.0 增量）。
 * 账单解释（P3 W10 增量）：公式 + 费项分摊明细 + 与上期差异 + 知识引用
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.1.0 严格对齐；仅 DTO，不含业务逻辑。
 * 可选字段以 class 级 {@code @JsonInclude(NON_NULL)} 保证未赋值时不序列化（契约默认不返回该字段）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BillExplanation(
        String roomCode,
        /** 账期 YYYY-MM */
        String period,
        /** 只解释单个费项时回带（PROPERTY_FEE / PUBLIC_ENERGY / PARKING_FEE / LATE_FEE） */
        String itemCode,
        /** 本期应缴合计（ERP 口径） */
        BigDecimal total,
        /** 整单计费公式 / 口径摘要 */
        String formula,
        List<BillExplanationLine> breakdown,
        BillExplanationDiff diffVsPrev,
        /** 解释引用的知识条目（至少 1 条；为空表示无出处，服务端应拒答而不是编造） */
        List<BillExplanationRef> knowledgeRefs,
        /** 数据时点（与账单 dataAsOf 同源） */
        String dataAsOf,
        boolean mock) {
}
