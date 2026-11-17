package com.property.ai.bff.dto;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * OpenAPI: BillInfo（账单只读（CAP-03 bill.query））。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v1.1.0 严格对齐；仅 DTO，不含业务逻辑。
 *
 * <p>v0.3.2 增量：末尾追加可选 {@code freshness}（数据新鲜度）。</p>
 *
 * <p>v1.1.0 增量（P3 W10，末尾继续追加可选 {@code overdueDays} / {@code arrearsDays} /
 * {@code explainRef}）：为不破坏 P2 既有 {@code new BillInfo(...)} 构造调用（连接器映射/对账/测试），
 * 同时保留 22 参数（含 freshness、无 P3 字段）与 21 参数（W5 形状）两个旧构造重载；
 * P3 字段为 null 时不序列化（{@code @JsonInclude(NON_NULL)}），P2 响应形状不变。</p>
 */
public record BillInfo(
        String period,
        BigDecimal total,
        BigDecimal due,
        List<BillItem> items,
        BigDecimal prevTotal,
        BigDecimal diff,
        Citation cite,
        String diffReason,
        String billNo,
        String householdId,
        String projectId,
        String roomCode,
        BigDecimal paid,
        BigDecimal outstanding,
        BigDecimal lateFee,
        BillStatus status,
        String dueDate,
        String currency,
        BillSource source,
        String dataAsOf,
        boolean mock,
        /** v0.3.2 增量：数据新鲜度（命中缓存/降级 fallback=cache 时必带，可为 null） */
        DataFreshness freshness,
        /** v1.1.0 增量：逾期天数（0 = 未逾期；由 ERP 账单 dueDate 与服务日计算，可为 null） */
        Integer overdueDays,
        /** v1.1.0 增量：连续欠费天数（ERP 欠费台账口径，跨账期累计，可为 null） */
        Integer arrearsDays,
        /** v1.1.0 增量：账单解释引用（前端调 GET /api/v1/fees/explain，可为 null） */
        String explainRef) {

    /** v0.3.2 兼容构造（含 freshness、无 P3 字段；P2 W5—W8 既有调用不破）。 */
    public BillInfo(
            String period,
            BigDecimal total,
            BigDecimal due,
            List<BillItem> items,
            BigDecimal prevTotal,
            BigDecimal diff,
            Citation cite,
            String diffReason,
            String billNo,
            String householdId,
            String projectId,
            String roomCode,
            BigDecimal paid,
            BigDecimal outstanding,
            BigDecimal lateFee,
            BillStatus status,
            String dueDate,
            String currency,
            BillSource source,
            String dataAsOf,
            boolean mock,
            DataFreshness freshness) {
        this(period, total, due, items, prevTotal, diff, cite, diffReason, billNo, householdId,
                projectId, roomCode, paid, outstanding, lateFee, status, dueDate, currency,
                source, dataAsOf, mock, freshness, null, null, null);
    }

    /** P2 W5 兼容构造（无 freshness / 无 P3 字段；null 时不序列化，W5 响应形状不破坏）。 */
    public BillInfo(
            String period,
            BigDecimal total,
            BigDecimal due,
            List<BillItem> items,
            BigDecimal prevTotal,
            BigDecimal diff,
            Citation cite,
            String diffReason,
            String billNo,
            String householdId,
            String projectId,
            String roomCode,
            BigDecimal paid,
            BigDecimal outstanding,
            BigDecimal lateFee,
            BillStatus status,
            String dueDate,
            String currency,
            BillSource source,
            String dataAsOf,
            boolean mock) {
        this(period, total, due, items, prevTotal, diff, cite, diffReason, billNo, householdId,
                projectId, roomCode, paid, outstanding, lateFee, status, dueDate, currency,
                source, dataAsOf, mock, null, null, null, null);
    }

    /** v0.3.2 增量字段：非缓存直读时省略，保持 W5 响应形状。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Override
    public DataFreshness freshness() {
        return freshness;
    }

    /** v1.1.0 增量字段：无逾期时省略（P2 响应形状不破坏）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Override
    public Integer overdueDays() {
        return overdueDays;
    }

    /** v1.1.0 增量字段：无欠费时省略。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Override
    public Integer arrearsDays() {
        return arrearsDays;
    }

    /** v1.1.0 增量字段：无解释引用时省略。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Override
    public String explainRef() {
        return explainRef;
    }
}
