package com.property.ai.bff.api.mapping;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.web.bind.annotation.GetMapping;
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
import com.property.ai.bff.dto.MappingPublishRequest;
import com.property.ai.bff.dto.MappingRollbackRequest;
import com.property.ai.bff.dto.MappingTrialReport;
import com.property.ai.bff.dto.MappingTrialRequest;
import com.property.ai.bff.dto.MappingVersion;
import com.property.ai.domain.mapping.MappingService;
import com.property.ai.domain.project.ProjectService;
import com.property.ai.domain.ticket.DomainException;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 映射版本化 REST（P2-024，契约 v0.3.2 §1.2；P5 W19 / v1.3.0 多连接器增量）：
 *
 * <pre>
 * GET  /api/v1/mappings?connectorId=             → data: MappingVersion[]（全量小集合；可按连接器隔离过滤）
 * GET  /api/v1/mappings/{mappingId}              → MappingVersion（含 fields[]）
 * POST /api/v1/mappings/{mappingId}/trial        → MappingTrialReport（默认 200 样本，阈值 0.02）
 * POST /api/v1/mappings/{mappingId}/publish      → MappingVersion（trial_passed → published；可选 body.connectorId）
 * POST /api/v1/mappings/{mappingId}/rollback     → MappingVersion（回到上一个 published 版本；可选 body.connectorId）
 * </pre>
 *
 * <p>状态机：draft → trial_passed → published → rolled_back；publish 前置不满足 → 409 {@code E-3002}；
 * 不可回滚 → 409；目标版本/映射不存在 → 404 {@code E-3001}；试跑样本数非法 → 400 {@code E-1001}。
 *
 * <p><b>P5 W19 多连接器口径（v1.3.0）</b>：映射仓储按 {@code connectorId} 隔离（用友 NC / 金蝶 EAS 各自版本，
 * 互不污染）；发布/回滚的 {@code connectorId} 为<b>可选作用域</b>——省略 = 映射自身连接器（v0.3.2 行为不变），
 * 显式指定且与映射绑定连接器不一致 → 403 {@code E-2002} {@code details{reason=cross_connector}}
 * （映射不能跨连接器发布/回滚）；列表 {@code connectorId} 非法/未注册 → 400 {@code E-1001}。
 * 配置类动作可选 {@code Idempotency-Key}（同键同载荷回放首次结果，同键不同载荷 409 {@code E-3003}）。
 *
 * <p><b>项目隔离（P5 W22 / P5-023）</b>：5 个 operation 均接受可选 {@code ?projectId=}——缺省 = 当前 actor 项目
 * （{@code it/admin} 平台级缺省 = 全部项目）；映射不在请求项目作用域内 → 403 {@code E-2002}
 * {@code details{reason:cross_project}}（先判项目作用域，再判连接器作用域 {@code cross_connector}）。
 * 契约 §7.6：映射是平台配置视图（仅 it/admin 可读），受限角色仍先在能力层 403 {@code E-2001}。
 * <b>契约补口 P5-024</b>：{@code ?projectId=} 为向后兼容的可选 query 参数，v1.3.0 OpenAPI 尚未声明，
 * 已登记 {@code _dev/reports/P5-W22-QA素材.md} §待补契约清单（P5 W24 / v1.3.1 契约增量收口）。
 */
@RestController
@RequestMapping("/api/v1/mappings")
public class MappingController {

    /** mappingId 格式：MAP-…（契约示例 MAP-NC-TICKET / MAP-KD-BILL） */
    private static final Pattern MAPPING_ID_PATTERN = Pattern.compile("^MAP-[A-Za-z0-9-]{2,40}$");

    private final MappingService mappingService;
    private final ConfigIdempotency idempotency;
    /** 项目隔离（P5 W22 / P5-023）：?projectId= 作用域解析 + 跨项目 403 cross_project */
    private final ProjectService projectService;

    public MappingController(MappingService mappingService, ConfigIdempotency idempotency,
            ProjectService projectService) {
        this.mappingService = mappingService;
        this.idempotency = idempotency;
        this.projectService = projectService;
    }

    @GetMapping
    public ApiResponse<List<MappingVersion>> list(
            @RequestParam(name = "connectorId", required = false) String connectorId,
            @RequestParam(name = "projectId", required = false) String projectId,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        // P5 W22 项目隔离：缺省 = 当前 actor 项目（it/admin 平台级缺省 = 全部）；跨项目 → 403 cross_project
        String scopeProjectId = projectService.resolveScope(projectId, "mapping", traceId);
        return ApiResponse.ok(mappingService.list(connectorId, traceId, scopeProjectId), traceId);
    }

    @GetMapping("/{mappingId}")
    public ApiResponse<MappingVersion> get(@PathVariable("mappingId") String mappingId,
            @RequestParam(name = "projectId", required = false) String projectId,
            HttpServletRequest request) {
        requireMappingId(mappingId);
        String traceId = ApiSupport.traceId(request);
        String scopeProjectId = projectService.resolveScope(projectId, "mapping", traceId);
        return ApiResponse.ok(mappingService.get(mappingId, traceId, scopeProjectId), traceId);
    }

    @PostMapping("/{mappingId}/trial")
    public ApiResponse<MappingTrialReport> trial(
            @PathVariable("mappingId") String mappingId,
            @RequestBody(required = false) MappingTrialRequest body,
            @RequestParam(name = "projectId", required = false) String projectId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        requireMappingId(mappingId);
        String traceId = ApiSupport.traceId(request);
        Integer sampleSize = body == null ? null : body.sampleSize();
        String scopeProjectId = projectService.resolveScope(projectId, "mapping", traceId);
        String fingerprint = "trial|" + mappingId + "|" + (sampleSize == null ? "default" : sampleSize);
        MappingTrialReport report = idempotency.execute(idempotencyKey, fingerprint,
                () -> mappingService.trial(mappingId, sampleSize, traceId, scopeProjectId));
        return ApiResponse.ok(report, traceId);
    }

    /**
     * 发布映射版本（可选 body {@code connectorId} 作用域；v1.3.0 增量，省略 body 与 v0.3.2 行为一致）。
     */
    @PostMapping("/{mappingId}/publish")
    public ApiResponse<MappingVersion> publish(
            @PathVariable("mappingId") String mappingId,
            @RequestBody(required = false) MappingPublishRequest body,
            @RequestParam(name = "projectId", required = false) String projectId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        requireMappingId(mappingId);
        String traceId = ApiSupport.traceId(request);
        String connectorId = body == null ? null : body.connectorId();
        String scopeProjectId = projectService.resolveScope(projectId, "mapping", traceId);
        String fingerprint = "publish|" + mappingId + "|" + (connectorId == null ? "" : connectorId)
                + "|" + (scopeProjectId == null ? "" : scopeProjectId);
        MappingVersion version = idempotency.execute(idempotencyKey, fingerprint,
                () -> mappingService.publish(mappingId, connectorId, MappingService.DEFAULT_OPERATOR,
                        scopeProjectId));
        return ApiResponse.ok(version, traceId);
    }

    /**
     * 回滚映射版本（body 末尾追加可选 {@code connectorId}；{@code toVersion} 省略 = 上一个已发布版本）。
     */
    @PostMapping("/{mappingId}/rollback")
    public ApiResponse<MappingVersion> rollback(
            @PathVariable("mappingId") String mappingId,
            @RequestBody(required = false) MappingRollbackRequest body,
            @RequestParam(name = "projectId", required = false) String projectId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        requireMappingId(mappingId);
        String traceId = ApiSupport.traceId(request);
        String toVersion = body == null ? null : body.toVersion();
        String reason = body == null ? null : body.reason();
        String connectorId = body == null ? null : body.connectorId();
        String scopeProjectId = projectService.resolveScope(projectId, "mapping", traceId);
        String fingerprint = "rollback|" + mappingId + "|" + (toVersion == null ? "" : toVersion)
                + "|" + (connectorId == null ? "" : connectorId)
                + "|" + (scopeProjectId == null ? "" : scopeProjectId);
        MappingVersion version = idempotency.execute(idempotencyKey, fingerprint,
                () -> mappingService.rollback(mappingId, toVersion, reason, connectorId, scopeProjectId));
        return ApiResponse.ok(version, traceId);
    }

    private static void requireMappingId(String mappingId) {
        if (mappingId == null || mappingId.isBlank()) {
            throw DomainException.badRequest("mappingId 不能为空", Map.of("field", "mappingId"));
        }
        if (!MAPPING_ID_PATTERN.matcher(mappingId.trim()).matches()) {
            throw DomainException.badRequest("mappingId 格式非法（如 MAP-NC-TICKET）",
                    Map.of("field", "mappingId", "value", mappingId));
        }
    }
}
