package com.property.ai.bff.api.desk;

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
import com.property.ai.bff.dto.DeskQaSample;
import com.property.ai.bff.dto.DeskQaSampleListResult;
import com.property.ai.bff.dto.DeskQaScoreRequest;
import com.property.ai.bff.dto.DeskQaStats;
import com.property.ai.bff.dto.DeskQueue;
import com.property.ai.bff.dto.DeskReleaseResult;
import com.property.ai.bff.dto.DeskSla;
import com.property.ai.bff.dto.DeskTakeoverRequest;
import com.property.ai.bff.dto.DeskTakeoverResult;
import com.property.ai.domain.desk.DeskQueueService;
import com.property.ai.domain.desk.QaService;
import com.property.ai.security.ActorContext;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 坐席工作台 REST API（P4-012/013，契约 v1.2.0 {@code /api/v1/desk*} 7 个 operation）。
 *
 * <pre>
 * GET  /api/v1/desk/queue                              200 队列（等待时长 + SLA 倒计时 + 汇总）
 * POST /api/v1/desk/queue/{conversationId}/takeover    200 接管；已被接管 409 E-3002；不存在 404
 * POST /api/v1/desk/queue/{conversationId}/release     200 释放（仅本人接管；非本人 403）
 * GET  /api/v1/desk/sla?from=&amp;to=                    200 首响均值/超时率/分时明细
 * GET  /api/v1/desk/qa/samples?status=&amp;page=&amp;pageSize= 200 质检抽样（agent 只看自己）
 * POST /api/v1/desk/qa/samples/{sampleId}/score        200 评分（0—100；重复评分 409）
 * GET  /api/v1/desk/qa/stats                           200 质检统计（supervisor/it/admin）
 * </pre>
 *
 * <h2>鉴权与审计</h2>
 * <ul>
 *   <li>能力（{@code desk.queue/desk.takeover/desk.release/desk.sla/desk.qa.score/desk.qa.stats}）
 *       由 {@code SecurityFilter} + {@code PermissionMatrix} 统一执行；</li>
 *   <li>写审计 {@code desk.takeover} / {@code desk.qa.score}（{@code resourceType=conversation}，
 *       {@code resourceId=conversationId}）由 {@code AuditFilter} 落库，Controller 不重复写；</li>
 *   <li>{@code waitingSeconds}/{@code slaDueAt} 全部由服务端计算（契约：前端只做倒计时）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/desk")
public class DeskController {

    private final DeskQueueService deskQueueService;
    private final QaService qaService;

    public DeskController(DeskQueueService deskQueueService, QaService qaService) {
        this.deskQueueService = deskQueueService;
        this.qaService = qaService;
    }

    /** 坐席队列（只列 waiting，按等待时长降序）+ 汇总指标。 */
    @GetMapping("/queue")
    public ResponseEntity<ApiResponse<DeskQueue>> queue(HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        Actor actor = ActorContext.actorOrNull();
        return ResponseEntity.ok(ApiResponse.ok(deskQueueService.queue(actor), traceId));
    }

    /** 接管会话（写操作，互斥 409）。 */
    @PostMapping("/queue/{conversationId}/takeover")
    public ResponseEntity<ApiResponse<DeskTakeoverResult>> takeover(@PathVariable String conversationId,
            @RequestBody(required = false) DeskTakeoverRequest body, HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        Actor actor = ActorContext.actorOrNull();
        return ResponseEntity.ok(ApiResponse.ok(
                deskQueueService.takeover(conversationId, body, actor, traceId), traceId));
    }

    /** 释放会话（写操作；仅当前接管人可释放）。 */
    @PostMapping("/queue/{conversationId}/release")
    public ResponseEntity<ApiResponse<DeskReleaseResult>> release(@PathVariable String conversationId,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        Actor actor = ActorContext.actorOrNull();
        return ResponseEntity.ok(ApiResponse.ok(deskQueueService.release(conversationId, actor), traceId));
    }

    /** 坐席 SLA（首响均值/超时率/0—23 时分时明细）。 */
    @GetMapping("/sla")
    public ResponseEntity<ApiResponse<DeskSla>> sla(
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        return ResponseEntity.ok(ApiResponse.ok(deskQueueService.sla(from, to), traceId));
    }

    /** 质检抽样列表（分页 + 状态过滤；agent 只看自己的样本）。 */
    @GetMapping("/qa/samples")
    public ResponseEntity<ApiResponse<DeskQaSampleListResult>> samples(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "20") int pageSize,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        Actor actor = ActorContext.actorOrNull();
        return ResponseEntity.ok(ApiResponse.ok(qaService.samples(status, page, pageSize, actor), traceId));
    }

    /** 质检评分（写操作）：0—100 整数；重复评分 409（审计 {@code desk.qa.score}）。 */
    @PostMapping("/qa/samples/{sampleId}/score")
    public ResponseEntity<ApiResponse<DeskQaSample>> score(@PathVariable String sampleId,
            @RequestBody(required = false) DeskQaScoreRequest body, HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        Actor actor = ActorContext.actorOrNull();
        return ResponseEntity.ok(ApiResponse.ok(
                qaService.score(sampleId, body, actor, traceId, request.getRemoteAddr()), traceId));
    }

    /** 质检统计（管理视图：supervisor/it/admin）。 */
    @GetMapping("/qa/stats")
    public ResponseEntity<ApiResponse<DeskQaStats>> stats(HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        Actor actor = ActorContext.actorOrNull();
        return ResponseEntity.ok(ApiResponse.ok(qaService.stats(actor), traceId));
    }
}
