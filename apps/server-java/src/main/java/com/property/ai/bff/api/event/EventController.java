package com.property.ai.bff.api.event;

import java.time.OffsetDateTime;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.property.ai.bff.api.ApiResponse;
import com.property.ai.bff.api.ticket.ApiSupport;
import com.property.ai.bff.dto.EventListResult;
import com.property.ai.bff.dto.EventType;
import com.property.ai.connector.ConnectorTimes;
import com.property.ai.domain.event.EventStore;
import com.property.ai.domain.project.ProjectService;
import com.property.ai.domain.ticket.DomainException;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 事件查询 REST（P5-019 / task-62，契约 v1.3.0 {@code GET /api/v1/events}，架构 §24.6）。
 *
 * <pre>
 * GET /api/v1/events?type&amp;projectId&amp;from&amp;to&amp;page&amp;pageSize → EventListResult（按 occurredAt 倒序）
 * </pre>
 *
 * <p>口径：查询 outbox 事件（内存实现，P5-019 后续任务换 Kafka）；{@code type} 过滤事件类型、{@code projectId}
 * 过滤项目（缺省 = 当前 actor 项目；跨项目 → 403 {@code E-2002} {@code cross_project}）、
 * {@code from}/{@code to} 按 {@code occurredAt} 闭区间过滤；只读端点<b>不落审计</b>。
 * 事件状态语义：{@code pending} 待投递 / {@code published} 已投递 / {@code failed} 可重试 /
 * {@code dead} 死信队列（不静默丢失）；内存 demo 无 dispatcher，事件保持 {@code pending}（不伪造已投递）。
 */
@RestController
@RequestMapping("/api/v1/events")
public class EventController {

    private final EventStore eventStore;
    private final ProjectService projectService;

    public EventController(EventStore eventStore, ProjectService projectService) {
        this.eventStore = eventStore;
        this.projectService = projectService;
    }

    @GetMapping
    public ApiResponse<EventListResult> list(
            @RequestParam(name = "type", required = false) String type,
            @RequestParam(name = "projectId", required = false) String projectId,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "20") int pageSize,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        ApiSupport.requirePageParams(page, pageSize);
        EventType eventType = parseType(type);
        OffsetDateTime fromTime = parseTime(from, "from");
        OffsetDateTime toTime = parseTime(to, "to");
        if (fromTime != null && toTime != null && fromTime.isAfter(toTime)) {
            throw DomainException.badRequest("from 不能晚于 to",
                    Map.of("field", "from", "value", from, "to", to));
        }
        String scopeProjectId = projectService.resolveScope(projectId, "event", traceId);
        return ApiResponse.ok(eventStore.list(eventType, scopeProjectId, fromTime, toTime, page, pageSize), traceId);
    }

    private static EventType parseType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return EventType.fromValue(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw DomainException.badDictionary("事件类型未匹配：" + raw, Map.of("field", "type", "value", raw));
        }
    }

    private static OffsetDateTime parseTime(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        OffsetDateTime parsed = ConnectorTimes.parseIsoOrNull(raw);
        if (parsed == null) {
            throw DomainException.badRequest(field + " 必须为 ISO 8601 时间",
                    Map.of("field", field, "value", raw));
        }
        return parsed;
    }
}
