package com.property.ai.recon;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.property.ai.bff.dto.ReconDiffItem;
import com.property.ai.bff.dto.ReconReport;

/**
 * 对账差异 CSV 导出（P3-031，契约 v1.1.0 {@code reconReportExport}）。
 *
 * <h2>格式口径</h2>
 * <ul>
 *   <li>{@code text/csv} + UTF-8 <b>带 BOM</b>（Excel 直接双击打开不乱码）；</li>
 *   <li>RFC 4180（CRLF 行结束、字段含 {@code , " CR LF} 时用双引号包裹并把内部 {@code "} 转义为 {@code ""}）；</li>
 *   <li>内容 = <b>汇总行</b>（scope/runId/sampleSize/checked/matched/diffCount/diffRate/passed）+
 *       <b>差异明细</b>（列名与契约示例逐字一致：{@code key,kind,field,expected,actual,roomCode,billNo,ticketNo}）；
 *       无差异时只输出汇总行（不造明细、不补空行）；</li>
 *   <li>契约 example 是「差异行」节选（首行展示列名）；本实现按任务卡 P3-031 要求先汇总后明细，
 *       差异列名紧随汇总行（列名与契约完全一致，已在交付报告标注该组合顺序）。</li>
 * </ul>
 *
 * <p>不含真实业主信息：CSV 只导出对账差异字段（房号/单号/工单号均为演示数据），不落姓名/手机号。
 */
public final class ReconCsvExporter {

    /** UTF-8 BOM（Excel 兼容） */
    static final String BOM = "\uFEFF";
    /** 汇总行表头（P3-031：scope/runId/sampleSize/checked/matched/diffCount/diffRate/passed） */
    static final String SUMMARY_HEADER = "scope,runId,sampleSize,checked,matched,diffCount,diffRate,passed";
    /** 差异明细表头（契约 example 逐字：key,kind,field,expected,actual,roomCode,billNo,ticketNo） */
    static final String DETAIL_HEADER = "key,kind,field,expected,actual,roomCode,billNo,ticketNo";

    private static final String CRLF = "\r\n";

    private ReconCsvExporter() {
    }

    /**
     * 生成 CSV 字节（含 BOM）。
     *
     * @param report 已完成的对账报告快照（调用方负责 404/409 语义）
     */
    public static byte[] toCsv(ReconReport report) {
        StringBuilder builder = new StringBuilder();
        builder.append(SUMMARY_HEADER).append(CRLF);
        builder.append(summaryRow(report)).append(CRLF);
        List<ReconDiffItem> items = report.items() == null ? List.of() : report.items();
        if (!items.isEmpty()) {
            builder.append(DETAIL_HEADER).append(CRLF);
            for (ReconDiffItem item : items) {
                builder.append(detailRow(item)).append(CRLF);
            }
        }
        return (BOM + builder).getBytes(StandardCharsets.UTF_8);
    }

    private static String summaryRow(ReconReport report) {
        return String.join(",",
                csv(report.scope() == null ? "" : report.scope().value()),
                csv(report.runId()),
                String.valueOf(report.sampleSize()),
                String.valueOf(report.checked()),
                String.valueOf(report.matched()),
                String.valueOf(report.diffCount()),
                diffRate(report.diffRate()),
                String.valueOf(report.passed()));
    }

    private static String detailRow(ReconDiffItem item) {
        return String.join(",",
                csv(item.key()),
                csv(item.kind() == null ? "" : item.kind().value()),
                csv(item.field()),
                csv(item.expected()),
                csv(item.actual()),
                csv(item.roomCode()),
                csv(item.billNo()),
                csv(item.ticketNo()));
    }

    /** 差异率按对账口径定点 6 位输出（0.01 → 0.010000），避免科学计数法与浮点尾巴 */
    private static String diffRate(double rate) {
        return BigDecimal.valueOf(rate).setScale(6, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    /** RFC 4180 字段转义；{@code null} → 空串（CSV 不写 "null"） */
    static String csv(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        boolean needQuote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0;
        if (!needQuote) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
