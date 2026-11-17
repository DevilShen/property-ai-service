package com.property.ai.domain.dunning;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.property.ai.bff.dto.ArrearsItem;
import com.property.ai.bff.dto.ArrearsListResult;
import com.property.ai.domain.fee.FeeScope;
import com.property.ai.domain.fee.FeeService;

/**
 * 欠费分层数据源实现（P3-011/012）：复用费用域只读台账 {@link FeeService#arrears}。
 *
 * <p><b>为什么复用而不是重新查 ERP</b>：{@code /fees/arrears} 已封装
 * 「账期清单 → ERP 账单 → 房档 → 本地分层聚合（D1/D7/D15/D30）+ 缓存 + 降级」，
 * 催缴任务分层必须与欠费台账<b>同口径</b>（契约 {@code ArrearsBucket} 要求与 {@code ArrearsItem.overdueDays} 自洽），
 * 复用一个入口可避免两套分层算法漂移。金额仍原样取 ERP（AI 不自算金额，架构 §12.1）。
 *
 * <p>翻页：契约分页上限 {@code pageSize=100}，最多翻 {@value #MAX_PAGES} 页（=2000 行任务上限，
 * demo 数据 36 行欠费，足够）；超出只记 WARN 并截断（已在报告列为规模遗留风险）。
 */
@Component
public class FeeArrearsSource implements DunningArrearsSource {

    /** 契约分页上限（{@code pageSize} 1—100） */
    private static final int PAGE_SIZE = 100;
    /** 翻页保护（2000 行上限；P5 接 PostgreSQL 后改游标） */
    private static final int MAX_PAGES = 20;

    private final FeeService feeService;

    public FeeArrearsSource(FeeService feeService) {
        this.feeService = feeService;
    }

    @Override
    public List<ArrearsItem> allArrears(String traceId) {
        List<ArrearsItem> rows = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            FeeService.FeeResult<ArrearsListResult> result = feeService.arrears(null, null, page, PAGE_SIZE,
                    FeeScope.unrestricted(), traceId);
            ArrearsListResult data = result == null ? null : result.value();
            if (data == null || data.items() == null || data.items().isEmpty()) {
                break;
            }
            rows.addAll(data.items());
            if (rows.size() >= data.total() || data.items().size() < PAGE_SIZE) {
                break;
            }
        }
        return List.copyOf(rows);
    }
}
