package com.property.ai.bff.api.dunning;

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
import com.property.ai.bff.api.ticket.ApiSupport;
import com.property.ai.bff.dto.DunningCloseRequest;
import com.property.ai.bff.dto.DunningCloseResult;
import com.property.ai.bff.dto.DunningFunnel;
import com.property.ai.bff.dto.DunningScript;
import com.property.ai.bff.dto.DunningScriptRequest;
import com.property.ai.bff.dto.DunningSendRequest;
import com.property.ai.bff.dto.DunningSendResult;
import com.property.ai.bff.dto.DunningTask;
import com.property.ai.bff.dto.DunningTaskListResult;
import com.property.ai.domain.dunning.DunningFunnelService;
import com.property.ai.domain.dunning.DunningTaskService;
import com.property.ai.security.ActorContext;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 催缴任务 REST API（P3-011…015，契约 v1.1.0 {@code /api/v1/dunning/*}）。
 *
 * <pre>
 * GET  /api/v1/dunning/tasks                       200 任务列表（status/bucket/assignee/page/pageSize + frequencyCheck）
 * GET  /api/v1/dunning/tasks/{taskId}              200 任务详情（不存在 404 E-3001）
 * POST /api/v1/dunning/tasks/{taskId}/script       200 生成话术（三档语气 + 禁区扫描；生成≠外发）
 * POST /api/v1/dunning/tasks/{taskId}/send         200 人工确认后单户触达 mock 通道（status=mocked）
 * POST /api/v1/dunning/tasks/{taskId}/close        200 关闭任务（reason 必填；只改状态不写财务数据）
 * GET  /api/v1/dunning/funnel?from&to              200 转化漏斗（reached→responded→promised→paid）
 * </pre>
 *
 * <p>鉴权/数据范围（契约 README §7.2）：能力 {@code dunning.task}（steward/agent/supervisor）由
 * {@code SecurityFilter} + {@code PermissionMatrix} 统一执行（owner/it/admin → 403 {@code E-2001}
 * {@code details.requiredCapability=dunning.task}）；领域服务按同一能力做防御性复校。
 *
 * <p>审计：写操作由 {@link DunningTaskService} 显式落 {@code dunning.script}/{@code dunning.send}/
 * {@code dunning.close}（resourceType={@code dunning}、resourceId=taskId）；只读端点不落审计；
 * 403 拒绝由 {@code SecurityFilter} 落 {@code access.deny}（resourceType={@code dunning}）。
 *
 * <p><b>demo 边界</b>：任务为进程内内存数据（从费用域欠费分层惰性生成）；触达只经
 * {@code services/mock-channel} 测试替身记录（{@code status=mocked}），<b>不接真实短信/企微/微信，
 * 不做 AI 语音外呼、不群发、不在公开渠道提欠费</b>（AGENTS.md NEVER #4/#7，产品 FR-BILL-04 红线）。
 */
@RestController
@RequestMapping("/api/v1/dunning")
public class DunningController {

    /** 可选幂等键（契约 {@code IdempotencyKeyOptional}；8—64 字符） */
    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final DunningTaskService taskService;
    private final DunningFunnelService funnelService;

    public DunningController(DunningTaskService taskService, DunningFunnelService funnelService) {
        this.taskService = taskService;
        this.funnelService = funnelService;
    }

    /** 任务列表（契约 {@code dunningTaskList}）。 */
    @GetMapping("/tasks")
    public ResponseEntity<ApiResponse<DunningTaskListResult>> list(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "bucket", required = false) String bucket,
            @RequestParam(name = "assignee", required = false) String assignee,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "20") int pageSize,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        ApiSupport.requirePageParams(page, pageSize);
        DunningTaskListResult result = taskService.list(status, bucket, assignee, page, pageSize,
                ActorContext.actorOrNull(), traceId);
        return ResponseEntity.ok(ApiResponse.ok(result, traceId));
    }

    /** 任务详情（契约 {@code dunningTaskGet}；含实时 {@code frequencyCheck}）。 */
    @GetMapping("/tasks/{taskId}")
    public ResponseEntity<ApiResponse<DunningTask>> get(@PathVariable String taskId,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        DunningTask task = taskService.get(taskId, ActorContext.actorOrNull(), traceId);
        return ResponseEntity.ok(ApiResponse.ok(task, traceId));
    }

    /** 生成话术（契约 {@code dunningScript}；生成不等于外发，需人工确认后 send）。 */
    @PostMapping("/tasks/{taskId}/script")
    public ResponseEntity<ApiResponse<DunningScript>> script(
            @PathVariable String taskId,
            @RequestBody(required = false) DunningScriptRequest requestBody,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        DunningScript script = taskService.generateScript(taskId, requestBody, ActorContext.actorOrNull(), traceId,
                request.getRemoteAddr());
        return ResponseEntity.ok(ApiResponse.ok(script, traceId));
    }

    /**
     * 发送催缴（契约 {@code dunningSend}）：必须 {@code confirmed:true} → 频次红线 → mock 通道单户触达。
     *
     * <p>可选 {@code Idempotency-Key} 头：同键同载荷重放返回首次结果（不重复触达/不重复计频次）；
     * 同键不同载荷 409 {@code E-3003}（{@code details.reason=idempotency_conflict}）。
     */
    @PostMapping("/tasks/{taskId}/send")
    public ResponseEntity<ApiResponse<DunningSendResult>> send(
            @PathVariable String taskId,
            @RequestBody(required = false) DunningSendRequest requestBody,
            @RequestHeader(name = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        DunningSendResult result = taskService.send(taskId, requestBody, idempotencyKey,
                ActorContext.actorOrNull(), traceId, request.getRemoteAddr());
        return ResponseEntity.ok(ApiResponse.ok(result, traceId));
    }

    /** 关闭任务（契约 {@code dunningTaskClose}；reason 必填并落审计）。 */
    @PostMapping("/tasks/{taskId}/close")
    public ResponseEntity<ApiResponse<DunningCloseResult>> close(
            @PathVariable String taskId,
            @RequestBody(required = false) DunningCloseRequest requestBody,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        DunningCloseResult result = taskService.close(taskId, requestBody, ActorContext.actorOrNull(), traceId,
                request.getRemoteAddr());
        return ResponseEntity.ok(ApiResponse.ok(result, traceId));
    }

    /** 转化漏斗（契约 {@code dunningFunnel}；from/to 省略 = 最近 7 天，含 to）。 */
    @GetMapping("/funnel")
    public ResponseEntity<ApiResponse<DunningFunnel>> funnel(
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        DunningFunnel funnel = funnelService.funnel(from, to, ActorContext.actorOrNull(), traceId);
        return ResponseEntity.ok(ApiResponse.ok(funnel, traceId));
    }
}
