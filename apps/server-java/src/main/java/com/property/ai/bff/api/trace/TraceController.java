package com.property.ai.bff.api.trace;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.property.ai.bff.api.ApiResponse;
import com.property.ai.bff.api.ticket.ApiSupport;
import com.property.ai.bff.dto.ErrorCode;
import com.property.ai.bff.dto.TraceDetail;
import com.property.ai.bff.dto.TraceEvidence;
import com.property.ai.bff.dto.TraceSearchResult;
import com.property.ai.domain.ticket.DomainException;
import com.property.ai.domain.trace.TraceService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 全链路回溯 REST API（P4-018，契约 v1.2.0 {@code /api/v1/trace*} 3 个 operation）。
 *
 * <pre>
 * GET /api/v1/trace/search?actorId=&amp;action=&amp;from=&amp;to=&amp;page=&amp;pageSize=  200 检索（按 lastAt 降序分页）
 * GET /api/v1/trace/{traceId}                               200 时间线（entries 按 at 升序）；无数据 404 E-3001
 * GET /api/v1/trace/{traceId}/evidence?format=json|csv      200 json 证据包 / text/csv 文件流（BOM + Content-Disposition）
 * </pre>
 *
 * <h2>路由顺序（契约 §1.5 明确要求）</h2>
 * 字面量段 {@code /trace/search} 必须先于 {@code /trace/{traceId}} 匹配；Spring {@code PathPattern}
 * 天然「字面量 &gt; 变量」，本类仍按字面量在前声明并在契约测试中覆盖。
 *
 * <h2>文件流例外 + 审计</h2>
 * <ul>
 *   <li>{@code format=csv} 的 2xx 是 {@code text/csv} 文件流（<b>非 JSON 信封</b>，README §6.2 例外）；
 *       错误仍返回统一 {@code ErrorResponse}（JSON），由全局异常处理器兜底；</li>
 *   <li>导出是敏感动作：审计 {@code trace.export}（{@code resourceType=export}、
 *       {@code resourceId=traceId}）由 {@code AuditFilter} 统一落库；非法 format → 400 {@code E-1001}。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/trace")
public class TraceController {

    private final TraceService traceService;

    public TraceController(TraceService traceService) {
        this.traceService = traceService;
    }

    /** 回溯检索（字面量段，须先于 {@code /{traceId}}）。 */
    @GetMapping("/search")
    public ResponseEntity<ApiResponse<TraceSearchResult>> search(
            @RequestParam(name = "actorId", required = false) String actorId,
            @RequestParam(name = "action", required = false) String action,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "20") int pageSize,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        return ResponseEntity.ok(ApiResponse.ok(
                traceService.search(actorId, action, from, to, page, pageSize), traceId));
    }

    /** 证据包导出（json 信封 / csv 文件流；非法 format → 400 E-1001）。 */
    @GetMapping("/{traceId}/evidence")
    public ResponseEntity<?> evidence(@PathVariable String traceId,
            @RequestParam(name = "format", required = false) String format,
            HttpServletRequest request) {
        String requestTraceId = ApiSupport.traceId(request);
        String normalized = format == null ? "json" : format.trim().toLowerCase(Locale.ROOT);
        if (!"json".equals(normalized) && !"csv".equals(normalized)) {
            throw DomainException.badRequest("format 仅支持 json 或 csv",
                    Map.of("field", "format", "value", format, "allowed", java.util.List.of("json", "csv"),
                            "reason", "unsupported_format"));
        }
        if ("csv".equals(normalized)) {
            byte[] body = traceService.evidenceCsv(traceId);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
            headers.setContentDisposition(ContentDisposition.attachment()
                    .filename(traceService.csvFileName(traceId))
                    .build());
            headers.setContentLength(body.length);
            return new ResponseEntity<>(body, headers, org.springframework.http.HttpStatus.OK);
        }
        TraceEvidence evidence = traceService.evidence(traceId);
        return ResponseEntity.ok(ApiResponse.ok(evidence, requestTraceId));
    }

    /** 链路详情（时间线；无数据 404 E-3001）。 */
    @GetMapping("/{traceId}")
    public ResponseEntity<ApiResponse<TraceDetail>> detail(@PathVariable String traceId,
            HttpServletRequest request) {
        String requestTraceId = ApiSupport.traceId(request);
        return ResponseEntity.ok(ApiResponse.ok(traceService.detail(traceId), requestTraceId));
    }
}
