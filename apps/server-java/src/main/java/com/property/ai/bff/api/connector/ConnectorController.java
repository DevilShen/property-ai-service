package com.property.ai.bff.api.connector;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.property.ai.bff.api.ApiResponse;
import com.property.ai.bff.api.ConfigIdempotency;
import com.property.ai.bff.api.ticket.ApiSupport;
import com.property.ai.bff.dto.ConnectorDetail;
import com.property.ai.bff.dto.ConnectorSdk;
import com.property.ai.bff.dto.ConnectorSummary;
import com.property.ai.bff.dto.ErrorCode;
import com.property.ai.bff.dto.ProbeLatestResult;
import com.property.ai.bff.dto.ProbeListResult;
import com.property.ai.bff.dto.ProbeReport;
import com.property.ai.connector.ConnectorDefinition;
import com.property.ai.connector.ConnectorRegistry;
import com.property.ai.connector.ProbeService;
import com.property.ai.domain.ticket.DomainException;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 连接器与能力探测 REST（P2-025，契约 v0.3.2）：
 *
 * <pre>
 * GET  /api/v1/connectors                         → ConnectorSummary[]（5 套 ERP 注册表摘要）
 * GET  /api/v1/connectors/{connectorId}           → ConnectorDetail（摘要 + 18 项能力三态）
 * POST /api/v1/connectors/{connectorId}/probe     → ProbeReport（探测日报；失败 503 且不更新矩阵）
 * GET  /api/v1/connectors/{connectorId}/probe/latest → ProbeLatestResult（最近一次 + 近 7 日历史）
 * GET  /api/v1/connectors/{connectorId}/probes    → ProbeListResult（分页历史）
 * </pre>
 *
 * <p>能力矩阵是入口显隐/话术承诺的唯一依据（前端不硬编码）；P2 W7 仅用友 NC（{@code yonyou-nc-mock}，
 * mock-erp 测试替身）可真实探测，其余连接器为注册声明（探测 → 503 {@code E-5001}）。
 */
@RestController
@RequestMapping("/api/v1/connectors")
public class ConnectorController {

    private final ConnectorRegistry registry;
    private final ProbeService probeService;
    private final ConfigIdempotency idempotency;

    public ConnectorController(ConnectorRegistry registry, ProbeService probeService,
            ConfigIdempotency idempotency) {
        this.registry = registry;
        this.probeService = probeService;
        this.idempotency = idempotency;
    }

    @GetMapping
    public ApiResponse<List<ConnectorSummary>> list(HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        return ApiResponse.ok(probeService.summaries(), traceId);
    }

    @GetMapping("/{connectorId}")
    public ApiResponse<ConnectorDetail> get(@PathVariable("connectorId") String connectorId,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        ConnectorDefinition definition = requireDefinition(connectorId);
        // v1.3.0 增量：详情回带 sdk（可选字段；未实现 SPI 的连接器返回注册声明 SDK）
        return ApiResponse.ok(new ConnectorDetail(probeService.summary(definition),
                probeService.capabilityStates(connectorId), registry.sdkOf(definition.connectorId()).orElse(null)),
                traceId);
    }

    @PostMapping("/{connectorId}/probe")
    public ApiResponse<ProbeReport> probe(
            @PathVariable("connectorId") String connectorId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        ConnectorDefinition definition = requireDefinition(connectorId);
        ProbeReport report = idempotency.execute(idempotencyKey, "probe|" + definition.connectorId(),
                () -> probeService.probe(definition.connectorId(), traceId));
        return ApiResponse.ok(report, traceId);
    }

    /**
     * 连接器 SDK 声明（P5 W19 / P5-005，契约 v1.3.0 {@code connectorSdk}）。
     *
     * <p>口径：{@code sdkVersion} / 声明能力（⊆18 项 {@code CapabilityCode}）/ {@code fieldCount} /
     * {@code limits{timeoutMs,pageSizeMax,rateLimitPerMin}}；<b>声明先于探测、探测覆盖声明</b>
     * （能力是否真的可用以 {@code POST /probes} 结果为准）。未注册连接器 → 404 {@code E-3001}。
     */
    @GetMapping("/{connectorId}/sdk")
    public ApiResponse<ConnectorSdk> sdk(@PathVariable("connectorId") String connectorId,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        ConnectorDefinition definition = requireDefinition(connectorId);
        ConnectorSdk sdk = registry.sdkOf(definition.connectorId())
                .orElseThrow(() -> DomainException.notFound(ErrorCode.E_3001,
                        "连接器未注册 SDK 声明：" + connectorId, Map.of("connectorId", connectorId)));
        return ApiResponse.ok(sdk, traceId);
    }

    @GetMapping("/{connectorId}/probe/latest")
    public ApiResponse<ProbeLatestResult> latest(@PathVariable("connectorId") String connectorId,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        requireDefinition(connectorId);
        return ApiResponse.ok(probeService.latest(connectorId), traceId);
    }

    @GetMapping("/{connectorId}/probes")
    public ApiResponse<ProbeListResult> probes(
            @PathVariable("connectorId") String connectorId,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "20") int pageSize,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        requireDefinition(connectorId);
        ApiSupport.requirePageParams(page, pageSize);
        return ApiResponse.ok(probeService.list(connectorId, page, pageSize), traceId);
    }

    private ConnectorDefinition requireDefinition(String connectorId) {
        return registry.find(connectorId).orElseThrow(() -> DomainException.notFound(ErrorCode.E_3001,
                "连接器未注册：" + connectorId, Map.of("connectorId", connectorId == null ? "" : connectorId)));
    }
}
