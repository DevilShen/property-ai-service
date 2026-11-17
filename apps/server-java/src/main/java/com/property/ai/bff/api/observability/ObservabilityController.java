package com.property.ai.bff.api.observability;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.property.ai.bff.api.ApiResponse;
import com.property.ai.bff.api.ticket.ApiSupport;
import com.property.ai.bff.dto.ObservabilityHealth;
import com.property.ai.bff.dto.ObservabilityMetrics;
import com.property.ai.security.PermissionMatrix;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 可观测性 REST（P5-020 实现 / P5 W24 契约 v1.3.1 收口）。
 *
 * <pre>
 * GET /api/v1/observability/metrics → ObservabilityMetrics（请求/错误/连接器健康/缓存/事件积压）
 * GET /api/v1/observability/health  → ObservabilityHealth（erp/channel/cache/event 组件汇总 + 降级说明）
 * </pre>
 *
 * <p><b>契约</b>：OpenAPI v1.3.1 已声明本组端点与 DTO（P5 W24 / task-69）——
 * {@code ObservabilityMetrics} / {@code ObservabilityHealth} / {@code ObservabilityHealthComponent}
 * 与 {@code packages/shared} 及 {@link com.property.ai.bff.dto} 三方同源；
 * 对应 `docs/openapi/README.md` §6.9 / §7.6 与 `docs/ops/P5-告警规则.md`。
 *
 * <p>权限：{@code observability.read}（仅 it/admin，平台运维视图）；owner/staff → 403 {@code E-2001}；
 * 无 token → 401 {@code E-2001}（由 {@code SecurityFilter} + {@link PermissionMatrix} 统一执行）。
 * 两个端点都是只读，不写业务主数据、不落写审计。
 */
@RestController
@RequestMapping("/api/v1/observability")
public class ObservabilityController {

    private final ObservabilityService observabilityService;

    public ObservabilityController(ObservabilityService observabilityService) {
        this.observabilityService = observabilityService;
    }

    /** 指标快照（契约 `observabilityMetrics`，v1.3.1；只读） */
    @GetMapping("/metrics")
    public ApiResponse<ObservabilityMetrics> metrics(HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        return ApiResponse.ok(observabilityService.metrics(), traceId);
    }

    /** 组件健康汇总（契约 `observabilityHealth`，v1.3.1；只读） */
    @GetMapping("/health")
    public ApiResponse<ObservabilityHealth> health(HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        return ApiResponse.ok(observabilityService.health(), traceId);
    }
}
