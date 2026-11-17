package com.property.ai.bff.api.project;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.property.ai.bff.api.ApiResponse;
import com.property.ai.bff.api.ticket.ApiSupport;
import com.property.ai.bff.dto.Project;
import com.property.ai.domain.project.ProjectService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 多项目隔离 REST（P5-007 / task-62，契约 v1.3.0，架构 §24.7）。
 *
 * <pre>
 * GET /api/v1/projects              → data: Project[]（当前主体可见项目；全量小集合不分页）
 * GET /api/v1/projects/{projectId}  → Project（跨项目 403 E-2002 cross_project；不存在 404 E-3001）
 * </pre>
 *
 * <p>口径：{@code owner}/{@code steward}/{@code agent}/{@code supervisor} 仅本项目（{@code Actor.projectId}）；
 * {@code it}/{@code admin} 平台级可见全部演示项目。演示 3 个项目 {@code P-YQY} 云栖苑 / {@code P-JZY} 金竹苑 /
 * {@code P-LVY} 绿苑（<b>演示数据，非真实组织架构</b>；AGENTS.md NEVER #11）。
 * 角色 × 能力（{@code project.read}）由 {@code SecurityFilter} + {@code PermissionMatrix} 统一执行；
 * 数据范围（本项目/跨项目）由 {@link ProjectService} 强制，拒绝落 {@code access.deny} 审计。
 */
@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @GetMapping
    public ApiResponse<List<Project>> list(HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        return ApiResponse.ok(projectService.visibleProjects(projectService.currentActor()), traceId);
    }

    @GetMapping("/{projectId}")
    public ApiResponse<Project> get(@PathVariable("projectId") String projectId, HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        return ApiResponse.ok(projectService.get(projectId, traceId), traceId);
    }
}
