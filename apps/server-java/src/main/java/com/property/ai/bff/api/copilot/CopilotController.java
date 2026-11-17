package com.property.ai.bff.api.copilot;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.property.ai.bff.api.ApiResponse;
import com.property.ai.bff.api.ticket.ApiSupport;
import com.property.ai.bff.dto.Actor;
import com.property.ai.bff.dto.CopilotBroadcastRequest;
import com.property.ai.bff.dto.CopilotBroadcastResult;
import com.property.ai.bff.dto.CopilotDigest;
import com.property.ai.bff.dto.CopilotPortrait;
import com.property.ai.bff.dto.CopilotReport;
import com.property.ai.bff.dto.CopilotScript;
import com.property.ai.bff.dto.CopilotScriptRequest;
import com.property.ai.bff.dto.CopilotSummary;
import com.property.ai.bff.dto.CopilotSummaryRequest;
import com.property.ai.bff.dto.CopilotWorkspaceListResult;
import com.property.ai.domain.copilot.CopilotBroadcastService;
import com.property.ai.domain.copilot.CopilotService;
import com.property.ai.security.ActorContext;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Copilot 8 工作区 REST API（P4-011，契约 v1.2.0 {@code /api/v1/copilot*} 7 个 operation）。
 *
 * <pre>
 * GET  /api/v1/copilot/workspaces        200 固定 8 个工作区（summary/script/portrait/digest/group/knowledge/report/handoff）
 * POST /api/v1/copilot/summary           200 5 行摘要 + 3 条待办 + 知识引用（ticketNo / messages 二选一）
 * POST /api/v1/copilot/script            200 3 版话术 + 禁区扫描（只生成不外发）
 * GET  /api/v1/copilot/portrait?roomCode= 200 脱敏画像（无手机号/身份证/欠费金额）
 * GET  /api/v1/copilot/digest?date=      200 日报/摘要（数字来自服务端聚合）
 * POST /api/v1/copilot/group/broadcast   200 人工确认（confirmed=true）→ mock 通道受理；未确认 400 E-1001
 * GET  /api/v1/copilot/report?from=&amp;to=  200 周期指标 + 分组明细
 * </pre>
 *
 * <h2>鉴权与审计</h2>
 * <ul>
 *   <li>角色 × 能力（{@code copilot.use} / {@code copilot.broadcast}）由 {@code SecurityFilter} +
 *       {@code PermissionMatrix} 统一执行，Controller 不写角色判断；owner → 403 {@code E-2001}；</li>
 *   <li>唯一写操作 = 群发；审计 {@code copilot.broadcast}（{@code resourceType=conversation}，
 *       脱敏字段名单已登记）由 {@code AuditFilter} 统一落库，Controller 不重复写；</li>
 *   <li><b>不调模型</b>：全部结果 {@code mock:true}（平台侧规则组装；模型编排归 Python 层）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/copilot")
public class CopilotController {

    private final CopilotService copilotService;
    private final CopilotBroadcastService broadcastService;

    public CopilotController(CopilotService copilotService, CopilotBroadcastService broadcastService) {
        this.copilotService = copilotService;
        this.broadcastService = broadcastService;
    }

    /** 8 个工作区元数据（固定顺序 + 可用状态；不分页）。 */
    @GetMapping("/workspaces")
    public ResponseEntity<ApiResponse<CopilotWorkspaceListResult>> workspaces(HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        return ResponseEntity.ok(ApiResponse.ok(copilotService.workspaces(), traceId));
    }

    /** 工单/会话摘要（{@code ticketNo} 与 {@code messages} 二选一；都缺 400，工单不存在 404）。 */
    @PostMapping("/summary")
    public ResponseEntity<ApiResponse<CopilotSummary>> summary(
            @RequestBody(required = false) CopilotSummaryRequest body,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        Actor actor = ActorContext.actorOrNull();
        return ResponseEntity.ok(ApiResponse.ok(copilotService.summary(body, actor, traceId), traceId));
    }

    /** 话术生成（场景 × 情绪 × 渠道 → 3 版 + 禁区扫描；只生成不外发）。 */
    @PostMapping("/script")
    public ResponseEntity<ApiResponse<CopilotScript>> script(
            @RequestBody(required = false) CopilotScriptRequest body,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        Actor actor = ActorContext.actorOrNull();
        return ResponseEntity.ok(ApiResponse.ok(copilotService.script(body, actor), traceId));
    }

    /** 业主画像（脱敏；房号缺失/非法 400，房号不存在 404）。 */
    @GetMapping("/portrait")
    public ResponseEntity<ApiResponse<CopilotPortrait>> portrait(
            @RequestParam(name = "roomCode", required = false) String roomCode,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        Actor actor = ActorContext.actorOrNull();
        return ResponseEntity.ok(ApiResponse.ok(copilotService.portrait(roomCode, actor), traceId));
    }

    /** 日报/摘要（{@code date} 省略 = 今天；无数据日期回空数组与 0 值）。 */
    @GetMapping("/digest")
    public ResponseEntity<ApiResponse<CopilotDigest>> digest(
            @RequestParam(name = "date", required = false) String date,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        return ResponseEntity.ok(ApiResponse.ok(copilotService.digest(date), traceId));
    }

    /** 报表（{@code from}/{@code to} 省略 = 近 7 天；from &gt; to → 400）。 */
    @GetMapping("/report")
    public ResponseEntity<ApiResponse<CopilotReport>> report(
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        return ResponseEntity.ok(ApiResponse.ok(copilotService.report(from, to), traceId));
    }

    /**
     * 群服务人工确认群发（写操作）：{@code confirmed=true} 才受理，经 mock 通道留痕
     * （{@code status=mocked}）；<b>不做群内自动回复</b>。
     */
    @PostMapping("/group/broadcast")
    public ResponseEntity<ApiResponse<CopilotBroadcastResult>> broadcast(
            @RequestBody(required = false) CopilotBroadcastRequest body,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        return ResponseEntity.ok(ApiResponse.ok(broadcastService.broadcast(body, traceId), traceId));
    }
}
