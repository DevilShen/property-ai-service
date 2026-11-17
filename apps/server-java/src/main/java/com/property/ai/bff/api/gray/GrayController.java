package com.property.ai.bff.api.gray;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.property.ai.bff.api.ApiResponse;
import com.property.ai.bff.api.ConfigIdempotency;
import com.property.ai.bff.api.ticket.ApiSupport;
import com.property.ai.bff.dto.GrayCompareResult;
import com.property.ai.bff.dto.GrayPolicy;
import com.property.ai.bff.dto.GrayPolicyCreateRequest;
import com.property.ai.bff.dto.GrayPolicyUpdateRequest;
import com.property.ai.domain.gray.GrayService;
import com.property.ai.domain.project.ProjectService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 灰度策略 / 双跑比对 REST（P5-013 / task-62，契约 v1.3.0，架构 §24.4）。
 *
 * <pre>
 * GET   /api/v1/gray/policies                    → GrayPolicy[]（全量小集合，不分页）
 * POST  /api/v1/gray/policies                    → 201 GrayPolicy（scope+targetId 唯一，percent 0—100；
 *                                                  percent=0/enabled=false 时对已存在策略就地回滚）
 * PATCH /api/v1/gray/policies/{policyId}         → GrayPolicy（percent/enabled 至少一项；enabled=false 即回滚）
 * GET   /api/v1/gray/compare?from&to&connectorId → GrayCompareResult（双跑只读比对，逐能力 diffRate；
 *                                                  connectorId 缺省 = 全部已接入 mock 的灰度连接器）
 * </pre>
 *
 * <p>权限：{@code gray.policy.read}（列表/双跑）与 {@code gray.policy.manage}（创建/更新）均<b>仅 it/admin</b>
 * （契约 README §7.6），由 {@code SecurityFilter} + {@code PermissionMatrix} 统一执行；写操作落审计
 * {@code gray.policy.create}/{@code gray.policy.update}（资源类型 {@code gray_policy}），由
 * {@link GrayService} 显式记账（端点规则 auditAction 留 null，避免双写）。
 * 配置类动作可选 {@code Idempotency-Key}（同键同载荷回放首次结果，同键不同载荷 409 {@code E-3003}）。
 *
 * <p><b>项目隔离（P5 W22 / P5-023）</b>：4 个 operation 均接受可选 {@code ?projectId=}——缺省 = 当前 actor 项目
 * （{@code it/admin} 平台级缺省 = 全部项目）；策略不在请求项目作用域内 → 403 {@code E-2002}
 * {@code details{reason:cross_project}}；双跑比对项目参数只做访问作用域（连接器级只读采样不按项目过滤）。
 * 契约 §7.6：灰度是平台发布视图（仅 it/admin），受限角色仍先在能力层 403 {@code E-2001}。
 * <b>契约补口 P5-024</b>：{@code ?projectId=} 为向后兼容的可选 query 参数，v1.3.0 OpenAPI 尚未声明，
 * 已登记 {@code _dev/reports/P5-W22-QA素材.md} §待补契约清单（P5 W24 / v1.3.1 契约增量收口）。
 */
@RestController
@RequestMapping("/api/v1/gray")
public class GrayController {

    private final GrayService grayService;
    private final ConfigIdempotency idempotency;
    /** 项目隔离（P5 W22 / P5-023）：?projectId= 作用域解析 + 跨项目 403 cross_project */
    private final ProjectService projectService;

    public GrayController(GrayService grayService, ConfigIdempotency idempotency, ProjectService projectService) {
        this.grayService = grayService;
        this.idempotency = idempotency;
        this.projectService = projectService;
    }

    @GetMapping("/policies")
    public ApiResponse<List<GrayPolicy>> list(
            @RequestParam(name = "projectId", required = false) String projectId,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        // P5 W22 项目隔离：缺省 = 当前 actor 项目（it/admin 平台级缺省 = 全部）；跨项目 → 403 cross_project
        String scopeProjectId = projectService.resolveScope(projectId, "gray_policy", traceId);
        return ApiResponse.ok(grayService.list(scopeProjectId), traceId);
    }

    /** 创建灰度策略（201；仅 it/admin） */
    @PostMapping("/policies")
    public ResponseEntity<ApiResponse<GrayPolicy>> create(
            @RequestBody(required = false) GrayPolicyCreateRequest body,
            @RequestParam(name = "projectId", required = false) String projectId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        String scopeProjectId = projectService.resolveScope(projectId, "gray_policy", traceId);
        String fingerprint = "gray-create|" + (body == null ? "" : body.scope() + "|" + body.targetId() + "|"
                + body.percent() + "|" + body.enabled()) + "|" + (scopeProjectId == null ? "" : scopeProjectId);
        GrayPolicy policy = idempotency.execute(idempotencyKey, fingerprint,
                () -> grayService.create(body, traceId, request.getRemoteAddr(), scopeProjectId));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(policy, traceId));
    }

    /** 更新灰度策略（仅 it/admin；percent/enabled 至少一项，enabled=false 即暂停回滚） */
    @PatchMapping("/policies/{policyId}")
    public ApiResponse<GrayPolicy> update(@PathVariable("policyId") String policyId,
            @RequestBody(required = false) GrayPolicyUpdateRequest body,
            @RequestParam(name = "projectId", required = false) String projectId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        String scopeProjectId = projectService.resolveScope(projectId, "gray_policy", traceId);
        String fingerprint = "gray-update|" + policyId + "|" + (body == null ? ""
                : (body.percent() == null ? "" : body.percent()) + "|" + (body.enabled() == null ? "" : body.enabled()))
                + "|" + (scopeProjectId == null ? "" : scopeProjectId);
        GrayPolicy policy = idempotency.execute(idempotencyKey, fingerprint,
                () -> grayService.update(policyId, body, traceId, request.getRemoteAddr(), scopeProjectId));
        return ApiResponse.ok(policy, traceId);
    }

    /** 双跑比对（只读；from/to 必填，connectorId 省略 = 全部灰度连接器） */
    @GetMapping("/compare")
    public ApiResponse<GrayCompareResult> compare(
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "connectorId", required = false) String connectorId,
            @RequestParam(name = "projectId", required = false) String projectId,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        String scopeProjectId = projectService.resolveScope(projectId, "gray_policy", traceId);
        return ApiResponse.ok(grayService.compare(from, to, connectorId, traceId, scopeProjectId), traceId);
    }
}
