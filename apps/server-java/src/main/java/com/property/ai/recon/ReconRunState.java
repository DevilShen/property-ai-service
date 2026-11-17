package com.property.ai.recon;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import com.property.ai.bff.dto.ReconDiffItem;
import com.property.ai.bff.dto.ReconDiffKind;
import com.property.ai.bff.dto.ReconReport;
import com.property.ai.bff.dto.ReconRunResult;
import com.property.ai.bff.dto.ReconRunStatus;
import com.property.ai.bff.dto.ReconScope;

/**
 * 单次对账运行状态（线程安全）：{@code queued → running → completed|failed}，供异步执行与轮询共用。
 *
 * <p>统计口径（契约 {@code ReconReport}）：
 * <ul>
 *   <li>{@code checked} = 实际参与对账的对象数（不是请求的 sampleSize）；</li>
 *   <li>{@code matched} = 该对象零差异；{@code diffCount} = 该对象存在 ≥1 条字段级差异（一条对象只计 1）；</li>
 *   <li>{@code diffRate = diffCount / checked}（定点 6 位；checked=0 时记 0）；</li>
 *   <li>{@code passed = status==completed && checked>0 && diffRate <= threshold}；
 *       失败 run 一律 {@code passed=false}（不把失败当通过）。</li>
 * </ul>
 */
final class ReconRunState {

    private final String runId;
    private final ReconScope scope;
    private final int requestedSampleSize;
    private final String startedAt;
    private final List<ReconDiffItem> items = new ArrayList<>();

    private ReconRunStatus status = ReconRunStatus.QUEUED;
    private String finishedAt;
    private long finishedEpochMs;
    private int checked;
    private int matched;
    private int diffCount;

    ReconRunState(String runId, ReconScope scope, int requestedSampleSize, String startedAt) {
        this.runId = runId;
        this.scope = scope;
        this.requestedSampleSize = requestedSampleSize;
        this.startedAt = startedAt;
    }

    synchronized void markRunning() {
        if (status == ReconRunStatus.QUEUED) {
            status = ReconRunStatus.RUNNING;
        }
    }

    /** 记录一个对账对象的结果：零差异计 matched，否则计 diffCount 并收集字段级差异 */
    synchronized void recordObject(List<ReconDiffItem> objectDiffs) {
        checked++;
        if (objectDiffs == null || objectDiffs.isEmpty()) {
            matched++;
            return;
        }
        diffCount++;
        items.addAll(objectDiffs);
    }

    /** 正常收尾：样本为 0 时不算通过（无样本的对账没有意义，但也不伪造差异） */
    synchronized void complete() {
        if (checked == 0) {
            items.add(new ReconDiffItem(runId, ReconDiffKind.MISSING, "sampleSize", "≥1 条样本", "0",
                    null, null, null));
        }
        status = ReconRunStatus.COMPLETED;
        finishedAt = ReconTimes.nowIso();
        finishedEpochMs = System.currentTimeMillis();
    }

    /** 失败收尾（ERP 不可用/未预期异常）：不返回假数据，只追加失败原因（不计入 diffRate） */
    synchronized void fail(String code, String reason) {
        status = ReconRunStatus.FAILED;
        items.add(new ReconDiffItem(runId, ReconDiffKind.MISSING, "erp_read", "ERP 只读可用",
                (code == null ? "E-9001" : code) + (reason == null || reason.isBlank() ? "" : "：" + reason),
                null, null, null));
        finishedAt = ReconTimes.nowIso();
        finishedEpochMs = System.currentTimeMillis();
    }

    synchronized ReconRunStatus status() {
        return status;
    }

    /** 请求的抽样条数（contract sampleSize） */
    synchronized int sampleSize() {
        return requestedSampleSize;
    }

    synchronized ReconScope scope() {
        return scope;
    }

    synchronized long finishedEpochMs() {
        return finishedEpochMs;
    }

    synchronized boolean finished() {
        return status == ReconRunStatus.COMPLETED || status == ReconRunStatus.FAILED;
    }

    /** 已检查对象数（排障/日志用） */
    synchronized int checkedCount() {
        return checked;
    }

    /** 受理结果（{@code POST /recon/run} 202 载荷） */
    synchronized ReconRunResult result() {
        return new ReconRunResult(runId, scope, requestedSampleSize, status, startedAt, true);
    }

    /** 报告快照（{@code GET /recon/report/{runId}} 载荷） */
    synchronized ReconReport snapshot(double threshold) {
        double rate = diffRate(checked, diffCount);
        boolean passed = status == ReconRunStatus.COMPLETED && checked > 0
                && BigDecimal.valueOf(rate).compareTo(BigDecimal.valueOf(threshold)) <= 0;
        return new ReconReport(runId, scope, status, requestedSampleSize, checked, matched, diffCount, rate,
                threshold, passed, List.copyOf(items), startedAt, finishedAt, true);
    }

    private static double diffRate(int checked, int diffs) {
        if (checked <= 0) {
            return 0.0;
        }
        return BigDecimal.valueOf(diffs)
                .divide(BigDecimal.valueOf(checked), 6, RoundingMode.HALF_UP)
                .doubleValue();
    }
}
