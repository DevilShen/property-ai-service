package com.property.ai.recon;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.property.ai.bff.api.ApiResponse;
import com.property.ai.bff.api.ticket.ApiSupport;
import com.property.ai.bff.dto.ReconReport;
import com.property.ai.bff.dto.ReconRunRequest;
import com.property.ai.bff.dto.ReconRunResult;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 只读对账 REST（P2-013，契约 {@code docs/openapi/openapi.yaml} v0.3.0 §6）：
 *
 * <pre>
 * POST /api/v1/recon/run                 → 202 + { ok, data: ReconRunResult, traceId }（异步受理）
 * GET  /api/v1/recon/report/{runId}      → 200 + { ok, data: ReconReport, traceId }（轮询；runId 不存在 → 404 E-3001）
 * GET  /api/v1/recon/report/{runId}/export?format=csv → 200 text/csv 附件（P3-031；错误仍 ErrorResponse）
 * </pre>
 *
 * <p>契约要点：{@code Idempotency-Key} 可选（同键 24h 内返回同一 runId）；{@code scope} 必填
 * （room|bill|ticket）；{@code sampleSize} 默认 200（1—1000）；阈值 {@code threshold=0.02}。
 * 参数缺失/越界 → 400 {@code E-1001}；ERP 不可用不会把 202 变成 503——异步执行，结果由 report 的
 * {@code status=failed}/{@code passed=false} 表达（P2-014：不假成功）。
 */
@RestController
@RequestMapping("/api/v1/recon")
public class ReconController {

    private final ReconService reconService;

    public ReconController(ReconService reconService) {
        this.reconService = reconService;
    }

    /** 触发只读对账（异步受理，契约 202） */
    @PostMapping("/run")
    public ResponseEntity<ApiResponse<ReconRunResult>> run(
            @RequestBody(required = false) ReconRunRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest httpRequest) {
        String traceId = ApiSupport.traceId(httpRequest);
        ReconRunResult result = reconService.startRun(request, idempotencyKey, traceId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(result, traceId));
    }

    /** 对账报告（按 runId 轮询；running 时 items 为空） */
    @GetMapping("/report/{runId}")
    public ApiResponse<ReconReport> report(@PathVariable("runId") String runId, HttpServletRequest httpRequest) {
        String traceId = ApiSupport.traceId(httpRequest);
        return ApiResponse.ok(reconService.report(runId), traceId);
    }

    /**
     * 对账差异 CSV 导出（P3-031，契约 v1.1.0 {@code reconReportExport}）。
     *
     * <p><b>文件流例外</b>（契约 README §6.2）：2xx 是 {@code text/csv; charset=utf-8} 附件下载，
     * <b>不是</b>统一 JSON 信封；错误仍统一 {@code ErrorResponse}（400 format 非 csv E-1001 /
     * 404 runId 不存在 E-3001 / 409 报告未完成 E-3002）。响应头带
     * {@code Content-Disposition: attachment; filename="recon-<runId>.csv"}，内容 UTF-8 with BOM。
     *
     * <p>审计：导出是敏感动作，端点策略在 {@code PermissionMatrix} 声明
     * {@code AuditAction.RECON_EXPORT} + {@code AuditResourceType.EXPORT}，由 {@code AuditFilter}
     * 统一落账（resourceId=runId，脱敏后留痕）；权限 {@code recon.report}（supervisor/it/admin）。
     */
    @GetMapping("/report/{runId}/export")
    public ResponseEntity<byte[]> export(@PathVariable("runId") String runId,
            @RequestParam(name = "format", required = false) String format,
            HttpServletRequest httpRequest) {
        ReconService.CsvExport export = reconService.exportCsv(runId, format);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"recon-" + export.runId() + ".csv\"")
                .body(export.bytes());
    }
}
