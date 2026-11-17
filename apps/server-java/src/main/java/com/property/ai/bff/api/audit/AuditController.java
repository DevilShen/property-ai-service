package com.property.ai.bff.api.audit;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.property.ai.audit.AuditService;
import com.property.ai.bff.api.ApiResponse;
import com.property.ai.bff.api.ticket.ApiSupport;
import com.property.ai.bff.dto.AuditAction;
import com.property.ai.bff.dto.AuditEvent;
import com.property.ai.bff.dto.AuditEventListResult;
import com.property.ai.bff.dto.AuditResourceType;
import com.property.ai.bff.dto.ErrorCode;
import com.property.ai.domain.project.ProjectService;
import com.property.ai.domain.ticket.DomainException;
import com.property.ai.domain.ticket.TicketTimes;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 审计查询 REST（P2-031，契约 v0.3.3 {@code /api/v1/audit/events}）：
 *
 * <pre>
 * GET /api/v1/audit/events            分页 + 过滤（actorId/action/resourceType/resourceId/from/to）
 * GET /api/v1/audit/events/{eventId}  单条（不存在 → 404 E-3001）
 * </pre>
 *
 * <p><b>读权限</b>：仅 {@code it}/{@code admin}/{@code supervisor}（能力 {@code audit.read}），由
 * {@link com.property.ai.security.SecurityFilter} 统一鉴权；其它角色 → 403 {@code E-2001} +
 * {@code details{requiredCapability:audit.read, role, reason:capability_denied}} 并落 {@code access.deny} 审计。
 *
 * <p>P2 审计为进程内演示存储（重启清空、上限 2000），P5 接 ES/对象存储；只回脱敏信息
 * （{@code maskedFields} 只列字段名，不含手机号/姓名/金额原文）。
 */
@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    private final AuditService auditService;
    /** 项目隔离（P5-007）：?projectId= 作用域解析 + 快照式项目过滤 + 跨项目 403 */
    private final ProjectService projectService;

    public AuditController(AuditService auditService, ProjectService projectService) {
        this.auditService = auditService;
        this.projectService = projectService;
    }

    /** 审计事件分页（按 at 倒序；过滤参数全部可选，from/to 含端点）。 */
    @GetMapping("/events")
    public ApiResponse<AuditEventListResult> events(
            @RequestParam(name = "actorId", required = false) String actorId,
            @RequestParam(name = "action", required = false) String action,
            @RequestParam(name = "resourceType", required = false) String resourceType,
            @RequestParam(name = "resourceId", required = false) String resourceId,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "projectId", required = false) String projectId,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "20") int pageSize,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        ApiSupport.requirePageParams(page, pageSize);
        // P5 W19 项目隔离：缺省 = 当前 actor 项目；跨项目 → 403 E-2002 cross_project（服务端强制）
        String scopeProjectId = projectService.resolveScope(projectId, "audit", traceId);
        AuditService.AuditQuery query = new AuditService.AuditQuery(
                blankToNull(actorId),
                parseAction(action),
                parseResourceType(resourceType),
                blankToNull(resourceId),
                parseTime("from", from),
                parseTime("to", to),
                page,
                pageSize);
        return ApiResponse.ok(auditService.list(query, scopeProjectId), traceId);
    }

    /** 审计事件详情（不存在 → 404 E-3001）。 */
    @GetMapping("/events/{eventId}")
    public ApiResponse<AuditEvent> event(@PathVariable("eventId") String eventId, HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        AuditEvent event = auditService.find(eventId)
                .orElseThrow(() -> DomainException.notFound(ErrorCode.E_3001,
                        "审计事件不存在：" + eventId, Map.of("eventId", eventId == null ? "" : eventId)));
        return ApiResponse.ok(event, traceId);
    }

    // ------------------------------------------------------------------
    // 参数解析（非法 → 400 E-1001，统一错误信封）
    // ------------------------------------------------------------------

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static AuditAction parseAction(String raw) {
        String value = blankToNull(raw);
        if (value == null) {
            return null;
        }
        try {
            return AuditAction.fromValue(value);
        } catch (IllegalArgumentException ex) {
            throw DomainException.badRequest("action 取值非法（见契约 AuditAction 枚举）",
                    Map.of("field", "action", "value", value));
        }
    }

    private static AuditResourceType parseResourceType(String raw) {
        String value = blankToNull(raw);
        if (value == null) {
            return null;
        }
        try {
            return AuditResourceType.fromValue(value);
        } catch (IllegalArgumentException ex) {
            throw DomainException.badRequest("resourceType 取值非法（见契约 AuditResourceType 枚举）",
                    Map.of("field", "resourceType", "value", value));
        }
    }

    private static Instant parseTime(String field, String raw) {
        String value = blankToNull(raw);
        if (value == null) {
            return null;
        }
        Instant instant = TicketTimes.parseInstant(value);
        if (instant == null) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("field", field);
            details.put("value", value);
            details.put("format", "ISO 8601 date-time（含时区，如 2026-11-15T20:00:00+08:00）");
            throw DomainException.badRequest(field + " 不是合法 ISO 8601 时间", details);
        }
        return instant;
    }
}
