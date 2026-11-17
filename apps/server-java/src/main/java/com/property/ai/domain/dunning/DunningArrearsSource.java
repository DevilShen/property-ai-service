package com.property.ai.domain.dunning;

import java.util.List;

import com.property.ai.bff.dto.ArrearsItem;

/**
 * 欠费分层数据源（P3-011 复用口径，领域边界）。
 *
 * <p>催缴域不直接依赖费用域实现：只消费契约 {@code ArrearsItem}（ERP 口径金额/逾期天数/分层/业主掩码）。
 * 生产实现见 {@link FeeArrearsSource}（{@code GET /api/v1/fees/arrears} 同源数据，
 * 即 mock-erp {@code /api/v1/bills?arrearsBucket=} + 本地分层聚合），单测可用 lambda/桩实现替换。
 *
 * <p>失败语义：ERP 超时/上游错误 → {@code ConnectorException}（E-4001/E-4002/E-4003），由 BFF 返回 503 + degraded；
 * <b>不伪造欠费数据</b>（不能凭空造任务）。
 */
public interface DunningArrearsSource {

    /**
     * 全量欠费分层行（去分页；{@code outstanding > 0 && overdueDays > 0}，按 ERP 口径原样返回）。
     *
     * @param traceId 链路 ID（透传连接器 {@code X-Trace-Id}）
     */
    List<ArrearsItem> allArrears(String traceId);

    /** 空数据源（测试/离线演示用：不生成任何任务，也不触发 ERP 调用）。 */
    static DunningArrearsSource empty() {
        return traceId -> List.of();
    }
}
