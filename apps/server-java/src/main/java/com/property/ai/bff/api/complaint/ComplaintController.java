package com.property.ai.bff.api.complaint;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.property.ai.bff.api.ApiResponse;
import com.property.ai.bff.api.ticket.ApiSupport;
import com.property.ai.bff.dto.Actor;
import com.property.ai.bff.dto.Complaint;
import com.property.ai.bff.dto.ComplaintCreateRequest;
import com.property.ai.bff.dto.ComplaintListResult;
import com.property.ai.bff.dto.ComplaintStats;
import com.property.ai.bff.dto.ComplaintTransitionRequest;
import com.property.ai.domain.complaint.ComplaintService;
import com.property.ai.domain.project.ProjectService;
import com.property.ai.security.ActorContext;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 投诉受理/分级 SLA/流转/统计 REST API（P3-021/022/023，契约 v1.1.0）。
 *
 * <pre>
 * POST /api/v1/complaints                          201 受理（owner 本户 / staff 本项目；分级 + SLA 起点）
 * GET  /api/v1/complaints                          200 列表（status/level/roomCode/sort/page/pageSize；owner 仅本户）
 * GET  /api/v1/complaints/stats                    200 统计（total/byStatus/byLevel/avgFirstResponseMinutes/slaBreachCount/period）
 * GET  /api/v1/complaints/{complaintId}            200 详情（含 audit[] 与 SLA；owner 他人 → 403 E-2002）
 * POST /api/v1/complaints/{complaintId}/transition 200 流转（accept/first_response/escalate/close/reopen + version 乐观锁）
 * </pre>
 *
 * <p>鉴权/数据范围分工（契约 README §7.2）：
 * <ul>
 *   <li>角色 × 能力（{@code complaint.create}/{@code complaint.read}/{@code complaint.handle}/
 *       {@code complaint.escalate}/{@code complaint.stats}）由 {@code SecurityFilter} +
 *       {@code PermissionMatrix} 统一执行；动作级能力（handle/escalate）由领域服务二次校验；</li>
 *   <li>owner 本户数据范围由领域服务强制（跨房 403 {@code E-2002} + {@code details.reason=out_of_scope}）；</li>
 *   <li>审计：写操作由 {@link ComplaintService} 显式落 {@code complaint.create}/{@code complaint.transition}
 *       （resource = {@code complaint:{id}}）；403 拒绝由 {@code SecurityFilter} 落 {@code access.deny}。</li>
 * </ul>
 *
 * <p><b>demo 口径</b>：投诉数据为演示数据（进程内存储，不写 ERP、不外发通知、不做群内自动回复）；
 * 不接真实客服工单系统，不得说成生产数据。
 */
@RestController
@RequestMapping("/api/v1/complaints")
public class ComplaintController {

    private final ComplaintService complaintService;
    /** 项目隔离（P5-007）：?projectId= 作用域解析 + 跨项目 403 cross_project */
    private final ProjectService projectService;

    public ComplaintController(ComplaintService complaintService, ProjectService projectService) {
        this.complaintService = complaintService;
        this.projectService = projectService;
    }

    /** 提交投诉（201；契约 {@code complaintCreate}）。 */
    @PostMapping
    public ResponseEntity<ApiResponse<Complaint>> create(
            @RequestBody(required = false) ComplaintCreateRequest requestBody,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        Actor actor = ActorContext.actorOrNull();
        Complaint created = complaintService.create(requestBody, actor, traceId, request.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created, traceId));
    }

    /**
     * 投诉列表（契约 {@code complaintList}）。
     *
     * <p>{@code status}/{@code level}/{@code sort} 用字符串接收、领域层解析：枚举未匹配 → 400 {@code E-1002}
     * （与契约「枚举未匹配」一致），分页非法 → 400 {@code E-1001}；不用枚举参数以免框架异常口径漂移。
     */
    @GetMapping
    public ResponseEntity<ApiResponse<ComplaintListResult>> list(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "level", required = false) String level,
            @RequestParam(name = "roomCode", required = false) String roomCode,
            @RequestParam(name = "sort", required = false) String sort,
            @RequestParam(name = "projectId", required = false) String projectId,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "20") int pageSize,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        // P5 W19 项目隔离：缺省 = 当前 actor 项目；跨项目 → 403 E-2002 cross_project（服务端强制）
        String scopeProjectId = projectService.resolveScope(projectId, "complaint", traceId);
        ComplaintListResult result = complaintService.list(status, level, roomCode, sort, page, pageSize,
                ActorContext.actorOrNull(), scopeProjectId);
        return ResponseEntity.ok(ApiResponse.ok(result, traceId));
    }

    /** 投诉统计（契约 {@code complaintStats}；管理视图，仅 staff）。 */
    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<ComplaintStats>> stats(
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "level", required = false) String level,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        ComplaintStats stats = complaintService.stats(from, to, level, ActorContext.actorOrNull());
        return ResponseEntity.ok(ApiResponse.ok(stats, traceId));
    }

    /** 投诉详情（契约 {@code complaintGet}；owner 他人投诉 403 E-2002，不存在 404 E-3001）。 */
    @GetMapping("/{complaintId}")
    public ResponseEntity<ApiResponse<Complaint>> get(@PathVariable String complaintId,
            @RequestParam(name = "projectId", required = false) String projectId,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        Complaint complaint = complaintService.get(complaintId, ActorContext.actorOrNull());
        // P5 W19：详情同样受项目作用域约束（跨项目 → 403 E-2002 cross_project）
        String scopeProjectId = projectService.resolveScope(projectId, "complaint", traceId);
        projectService.requireResourceProject(scopeProjectId, complaintService.projectIdOf(complaintId),
                "complaint", complaintId, traceId);
        return ResponseEntity.ok(ApiResponse.ok(complaint, traceId));
    }

    /** 投诉流转（契约 {@code complaintTransition}；version 乐观锁必填）。 */
    @PostMapping("/{complaintId}/transition")
    public ResponseEntity<ApiResponse<Complaint>> transition(
            @PathVariable String complaintId,
            @RequestBody(required = false) ComplaintTransitionRequest requestBody,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        Complaint complaint = complaintService.transition(complaintId, requestBody, ActorContext.actorOrNull(),
                traceId, request.getRemoteAddr());
        return ResponseEntity.ok(ApiResponse.ok(complaint, traceId));
    }
}
