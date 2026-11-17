package com.property.ai.domain.trace;

import java.util.List;

import com.property.ai.bff.dto.TraceEntry;
import com.property.ai.bff.dto.TraceEvidence;

/**
 * 证据包 CSV 导出器（P4-018，契约 v1.2.0 {@code GET /trace/{traceId}/evidence?format=csv}）。
 *
 * <h2>契约口径（README §6.2 文件流例外 + 契约 evidence 描述）</h2>
 * <ul>
 *   <li>RFC 4180：字段含逗号/引号/换行时用双引号包裹，内部引号翻倍；行分隔 {@code CRLF}；</li>
 *   <li>UTF-8 <b>with BOM</b>（{@code \uFEFF}），保证 Excel 打开中文不乱码；</li>
 *   <li>首行列名固定：{@code seq,at,actorId,role,action,resourceType,resourceId,result,durationMs}；</li>
 *   <li>2xx 是 {@code text/csv} 文件流，<b>不是统一 JSON 信封</b>；错误仍走 {@code ErrorResponse}（JSON）。</li>
 * </ul>
 *
 * <p>导出内容是 {@link TraceService#evidence(String)} 的<b>脱敏后</b>节点（房号已打码）；
 * 敏感字段清单在 JSON 形态的 {@code maskedFields} 中声明。
 */
public final class TraceCsvExporter {

    /** UTF-8 BOM（Excel 兼容） */
    static final char BOM = '\uFEFF';
    private static final String CRLF = "\r\n";

    private TraceCsvExporter() {
    }

    /** 渲染 CSV 文本（含 BOM；{@code evidence.entries()} 必须已脱敏）。 */
    public static String export(TraceEvidence evidence) {
        StringBuilder out = new StringBuilder();
        out.append(BOM);
        out.append(String.join(",", TraceService.CSV_HEADER)).append(CRLF);
        List<TraceEntry> entries = evidence == null || evidence.entries() == null
                ? List.of()
                : evidence.entries();
        int seq = 0;
        for (TraceEntry entry : entries) {
            seq++;
            out.append(seq).append(',')
                    .append(cell(entry.at())).append(',')
                    .append(cell(entry.actorId())).append(',')
                    .append(cell(entry.role() == null ? null : entry.role().value())).append(',')
                    .append(cell(entry.action())).append(',')
                    .append(cell(entry.resourceType())).append(',')
                    .append(cell(entry.resourceId())).append(',')
                    .append(cell(entry.result() == null ? null : entry.result().value())).append(',')
                    .append(entry.durationMs() == null ? "" : entry.durationMs())
                    .append(CRLF);
        }
        return out.toString();
    }

    /** 单字段编码（空值输出空串；需要时加引号并转义）。 */
    static String cell(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        boolean needQuote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        if (!needQuote) {
            return value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
