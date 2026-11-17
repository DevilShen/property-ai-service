package com.property.ai.bff.dto;

import java.math.BigDecimal;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * OpenAPI: ArrearsItem（P3 W10 / v1.1.0 增量）。
 * 欠费台账行（P3 W10 增量）。业主姓名只回掩码；total / outstanding / lateFee / overdueDays
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.1.0 严格对齐；仅 DTO，不含业务逻辑。
 * 可选字段以 class 级 {@code @JsonInclude(NON_NULL)} 保证未赋值时不序列化（契约默认不返回该字段）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ArrearsItem(
        /** 归一房号（如 1-1-101） */
        String roomCode,
        /** 业主姓名掩码（如「王**」；不返回手机号/身份证/地址） */
        String ownerNameMasked,
        /** 账期 YYYY-MM */
        String period,
        /** 该账期应缴合计 */
        BigDecimal total,
        /** 欠费金额（ERP 口径，含历史欠费） */
        BigDecimal outstanding,
        /** 逾期天数（0 = 未逾期；由 ERP 账单 dueDate 与服务日计算） */
        int overdueDays,
        ArrearsBucket bucket,
        /** 滞纳金（ERP 口径，AI 不减免也不承诺减免） */
        BigDecimal lateFee,
        /** 最近一次触达时间（从未触达则不返回该字段） */
        String lastContactAt) {
}
