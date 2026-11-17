package com.property.ai.bff.api.recon;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.property.ai.bff.api.ApiResponse;
import com.property.ai.bff.api.ticket.ApiSupport;
import com.property.ai.bff.dto.ReconRunListResult;
import com.property.ai.recon.ReconService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 对账历史 REST（P2-027，契约 v0.3.2 {@code GET /api/v1/recon/runs}）。
 *
 * <p>复用 {@link ReconService} 已有的进程内 run 存储（P2-013 受理记录）补分页列表：
 * 按 {@code startedAt} 倒序（runId 单调递增，等价口径），明细仍走 {@code /api/v1/recon/report/{runId}}。
 * 无记录返回空列表（不造数）；只读，不触发新的对账。
 */
@RestController
@RequestMapping("/api/v1/recon")
public class ReconRunsController {

    private final ReconService reconService;

    public ReconRunsController(ReconService reconService) {
        this.reconService = reconService;
    }

    @GetMapping("/runs")
    public ApiResponse<ReconRunListResult> runs(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "20") int pageSize,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        ApiSupport.requirePageParams(page, pageSize);
        return ApiResponse.ok(reconService.listRuns(page, pageSize), traceId);
    }
}
