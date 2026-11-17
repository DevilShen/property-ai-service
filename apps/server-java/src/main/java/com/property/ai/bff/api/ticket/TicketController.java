package com.property.ai.bff.api.ticket;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.property.ai.bff.api.ApiResponse;
import com.property.ai.bff.dto.Actor;
import com.property.ai.bff.dto.ErrorCode;
import com.property.ai.bff.dto.HandoffSummary;
import com.property.ai.bff.dto.Ticket;

import com.property.ai.bff.dto.TicketCreateRequest;
import com.property.ai.bff.dto.TicketCreateResult;
import com.property.ai.bff.dto.TicketFeedbackRequest;
import com.property.ai.bff.dto.TicketFeedbackResult;
import com.property.ai.bff.dto.TicketListResult;
import com.property.ai.bff.dto.TicketPushListResult;
import com.property.ai.bff.dto.TicketStatus;
import com.property.ai.bff.dto.TicketTransitionRequest;
import com.property.ai.bff.dto.TicketTransitionResult;
import com.property.ai.domain.project.ProjectService;
import com.property.ai.domain.ticket.FetchResult;
import com.property.ai.domain.ticket.TicketService;
import com.property.ai.security.ActorContext;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 报修/工单闭环 REST API（CAP-06/07/08/09/13/18）。
 *
 * <p>契约：{@code docs/openapi/openapi.yaml} v0.3.1（唯一事实源，不新增/改字段）。端点与状态码：
 * <pre>
 * POST /api/v1/tickets                       201 建单（Idempotency-Key 必填；缺 → 400 E-1001）
 * GET  /api/v1/tickets                       200 列表（status/team/category/dateFrom/dateTo/sort/page/pageSize）
 * GET  /api/v1/tickets/{ticketNo}            200 详情（7 节点时间轴 + 回访 feedback）
 * POST /api/v1/tickets/{ticketNo}/transition 200 迁移（写 + Idempotency-Key；dispatch/complete 带 erpWriteback）
 * POST /api/v1/tickets/{ticketNo}/feedback   200 回访（COMPLETED → REVIEWED；结果含 feedback 回显）
 * GET  /api/v1/tickets/{ticketNo}/pushes     200 推送记录（v0.3.1 增量：mock 通道留痕，按 sentAt 倒序）
 * GET  /api/v1/tickets/{ticketNo}/handoff-summary 200 转人工摘要（平台内生成）
 * </pre>
 *
 * <p>写操作不自动重试（客户端复用同一 {@code Idempotency-Key} 重试）；降级读返回 200 + {@code degraded}；
 * 完工回写失败返回 200 + {@code erpWriteback=failed} + {@code degraded{fallback:human}}（工单保持 ON_SITE）。
 */
@RestController
@RequestMapping("/api/v1/tickets")
public class TicketController {

    private final TicketService ticketService;
    /** 项目隔离（P5-007）：?projectId= 作用域解析 + 快照式项目过滤 + 跨项目 403 */
    private final ProjectService projectService;

    public TicketController(TicketService ticketService, ProjectService projectService) {
        this.ticketService = ticketService;
        this.projectService = projectService;
    }

    /** 报修建单（201；幂等重放返回首次结果） */
    @PostMapping
    public ResponseEntity<ApiResponse<TicketCreateResult>> create(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) TicketCreateRequest requestBody,
            HttpServletRequest request) {
        String key = ApiSupport.requireIdempotencyKey(idempotencyKey);
        String traceId = traceId(requestBody == null ? null : requestBody.traceId(), request);
        // P3-028：建单写归属（owner = 自己；staff 代报按房号解析演示业主账号）
        TicketCreateResult result = ticketService.create(requestBody, key, traceId, actorOrNull());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(result, traceId));
    }

    /**
     * 工单列表（分页 + status/team 筛选 + v0.3.1 增量 category/dateFrom/dateTo/sort）。
     *
     * <p>{@code sort} 用字符串接收并在领域层校验（非法 → 400 E-1001），避免枚举解析失败落到框架异常。
     * <p>v1.1.0 增量（P3-028）：{@code mine} 透传领域层——owner 无论 {@code mine} 与否都强制按本户归属过滤，
     * staff 不受本户范围限制（数据范围由服务端强制，前端隐藏入口不是安全边界）。
     * <p>P5 W19 修复（task-64）：项目作用域（{@code ?projectId=}）作为过滤条件下沉领域层，在**分页前**过滤，
     * {@code total} = 本项目命中总数（不再等于本页条数；契约 v1.3.0 形状不变）。
     */
    @GetMapping
    public ResponseEntity<ApiResponse<TicketListResult>> list(
            @RequestParam(name = "status", required = false) TicketStatus status,
            @RequestParam(name = "team", required = false) String team,
            @RequestParam(name = "category", required = false) String category,
            @RequestParam(name = "dateFrom", required = false) String dateFrom,
            @RequestParam(name = "dateTo", required = false) String dateTo,
            @RequestParam(name = "sort", required = false) String sort,
            @RequestParam(name = "mine", defaultValue = "false") boolean mine,
            @RequestParam(name = "projectId", required = false) String projectId,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "20") int pageSize,
            HttpServletRequest request) {
        ApiSupport.requirePageParams(page, pageSize);
        String traceId = ApiSupport.traceId(request);
        // P5 W19 项目隔离：缺省 = 当前 actor 项目；跨项目 → 403 E-2002 cross_project（服务端强制）
        String scopeProjectId = projectService.resolveScope(projectId, "ticket", traceId);
        FetchResult<TicketListResult> scoped = ticketService.list(status, team, category, dateFrom, dateTo, sort,
                page, pageSize, mine, actorOrNull(), scopeProjectId, traceId);
        if (scoped.isDegraded() && scoped.data().total() == 0) {
            // 降级但平台侧无快照：不返回"空列表冒充正常"，按契约 503 + degraded 转人工
            ErrorCode code = scoped.degraded().code() == null ? ErrorCode.E_4001 : scoped.degraded().code();
            return ResponseEntity.status(ApiSupport.statusFor(code)).body(ApiResponse.error(code,
                    "工单列表暂不可用（ERP 降级），已转人工处理", traceId, Map.of(),
                    scoped.degraded(), true));
        }
        return respond(scoped, traceId);
    }

    /** 工单详情（7 节点时间轴；ERP 不可用时用平台快照降级；owner 跨房/种子单 → 403 E-2002） */
    @GetMapping("/{ticketNo}")
    public ResponseEntity<ApiResponse<Ticket>> get(@PathVariable String ticketNo,
            @RequestParam(name = "projectId", required = false) String projectId,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        // P5 W19：详情同样受项目作用域约束（跨项目 → 403 E-2002 cross_project）
        String scopeProjectId = projectService.resolveScope(projectId, "ticket", traceId);
        FetchResult<Ticket> result = ticketService.get(ticketNo, traceId, actorOrNull());
        projectService.requireResourceProject(scopeProjectId,
                ProjectService.projectOf(result.data().projectId()), "ticket", ticketNo, traceId);
        return respond(result, traceId);
    }

    /**
     * 状态迁移（dispatch/on_site/complete/reschedule/urge；write + 幂等键 + 乐观锁 version）。
     *
     * <p>P3-029：owner 仅可对本户自有单执行 {@code reschedule}/{@code urge}（能力码
     * {@code ticket.reschedule.own}/{@code ticket.urge.own}）；其它动作 → 403 {@code E-2001}，
     * 跨房 → 403 {@code E-2002}，均由领域服务二次校验。staff 走既有 {@code ticket.assign}。
     */
    @PostMapping("/{ticketNo}/transition")
    public ResponseEntity<ApiResponse<TicketTransitionResult>> transition(
            @PathVariable String ticketNo,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) TicketTransitionRequest requestBody,
            HttpServletRequest request) {
        String key = ApiSupport.requireIdempotencyKey(idempotencyKey);
        String traceId = ApiSupport.traceId(request);
        FetchResult<TicketTransitionResult> result = ticketService.transition(
                ticketNo,
                requestBody == null ? null : requestBody.action(),
                requestBody == null ? null : requestBody.payload(),
                requestBody == null ? 0 : requestBody.version(),
                key,
                traceId,
                actorOrNull());
        // 完工回写失败：200 + erpWriteback=failed + degraded{human}（工单保持 ON_SITE，不自动重写）
        return respond(result, traceId);
    }

    /** 回访评价（仅 COMPLETED → REVIEWED；score ≤2 触发主管提醒；owner 仅本户自有单） */
    @PostMapping("/{ticketNo}/feedback")
    public ResponseEntity<ApiResponse<TicketFeedbackResult>> feedback(
            @PathVariable String ticketNo,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) TicketFeedbackRequest requestBody,
            HttpServletRequest request) {
        String key = ApiSupport.requireIdempotencyKey(idempotencyKey);
        String traceId = ApiSupport.traceId(request);
        TicketFeedbackResult result = ticketService.feedback(ticketNo, requestBody, key, traceId, actorOrNull());
        return ResponseEntity.ok(ApiResponse.ok(result, traceId));
    }

    /** 转人工摘要（摘要 ≤5 行 + 要素 + 情绪 + 历史 + 建议/未完成动作） */
    @GetMapping("/{ticketNo}/handoff-summary")
    public ResponseEntity<ApiResponse<HandoffSummary>> handoffSummary(@PathVariable String ticketNo,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        FetchResult<HandoffSummary> result = ticketService.handoff(ticketNo, traceId);
        return respond(result, traceId);
    }

    /**
     * 工单推送记录（v0.3.1 增量，P2-019）：三次推送（accepted/dispatched/completed）留痕，按 sentAt 倒序。
     *
     * <p>P2 W6 全部由 {@code services/mock-channel} 受理（{@code mock=true}、{@code status=mocked}），
     * **不接真实企微/短信**；无记录返回 {@code items: []}（不造数）；工单不存在 → 404 E-3001。
     */
    @GetMapping("/{ticketNo}/pushes")
    public ResponseEntity<ApiResponse<TicketPushListResult>> pushes(@PathVariable String ticketNo,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        FetchResult<TicketPushListResult> result = ticketService.pushes(ticketNo, traceId, actorOrNull());
        return respond(result, traceId);
    }

    /**
     * 当前演示主体（{@code property.security.enabled=false} 的离线演示/测试模式返回 {@code null}）：
     * 传给领域服务做数据范围（owner 本户）与动作级能力二次校验；角色判断本身仍只在 PermissionMatrix。
     */
    private static Actor actorOrNull() {
        return ActorContext.actorOrNull();
    }

    private <T> ResponseEntity<ApiResponse<T>> respond(FetchResult<T> result, String traceId) {
        if (result.isDegraded()) {
            return ResponseEntity.ok(ApiResponse.okDegraded(result.data(), result.degraded(), traceId));
        }
        return ResponseEntity.ok(ApiResponse.ok(result.data(), traceId));
    }

    private String traceId(String clientTraceId, HttpServletRequest request) {
        if (clientTraceId != null && !clientTraceId.isBlank()) {
            return clientTraceId.trim();
        }
        return ApiSupport.traceId(request);
    }
}
