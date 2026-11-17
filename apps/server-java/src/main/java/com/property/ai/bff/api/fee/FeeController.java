package com.property.ai.bff.api.fee;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.property.ai.bff.api.ApiResponse;
import com.property.ai.bff.api.ticket.ApiSupport;
import com.property.ai.bff.dto.Actor;
import com.property.ai.bff.dto.ActorRole;
import com.property.ai.bff.dto.ArrearsBucket;
import com.property.ai.bff.dto.ArrearsListResult;
import com.property.ai.bff.dto.BillExplanation;
import com.property.ai.bff.dto.ErrorCode;
import com.property.ai.domain.fee.FeeScope;
import com.property.ai.domain.fee.FeeService;
import com.property.ai.domain.ticket.DomainException;
import com.property.ai.security.ActorContext;
import com.property.ai.security.PermissionMatrix;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 费用只读视图（P3 W10 / 契约 v1.1.0：{@code feeArrears} / {@code feeExplain}）。
 *
 * <p>只读不落库（架构 §12.1）：{@code total/outstanding/lateFee/items[].amount} 全部以 ERP 返回为准，
 * AI 不自算金额；解释必须可追溯（{@code knowledgeRefs}），无出处拒答（{@code E-6002}）。
 *
 * <h2>权限（契约 §7.2；能力 {@code bill.query}）</h2>
 * <pre>
 * /fees/arrears ：owner（仅本户）/ steward / supervisor ✅；agent / it / admin ❌ 403 E-2001
 * /fees/explain ：owner（仅本户）/ steward / agent / supervisor ✅；it / admin ❌ 403 E-2001
 * </pre>
 * <p><b>实现说明（demo 口径）</b>：{@code PermissionMatrix} 尚未注册 {@code /api/v1/fees/*} 端点规则
 * （P3-028 数据范围任务负责），因此 Filter 层只做「已登录」校验；<b>能力与数据范围在本 Controller 内按
 * {@link PermissionMatrix#allows} 只读判定</b>，不新造能力码（{@code bill.explain} 不存在 → 复用
 * {@code bill.query} 并对 agent 在 arrears 上特判，与契约 §7.2 一致）。
 * owner 跨房解释 → 403 {@code E-2002} + {@code details{reason=out_of_scope}}（数据范围由服务端强制）。
 *
 * <p>只读端点不落 {@code AuditEvent}（与 P2 只读端点一致）；ERP 超时/失败 → 有陈旧快照时
 * 2xx + {@code degraded{fallback=cache}}，否则 503 + {@code degraded}。
 */
@RestController
@RequestMapping("/api/v1/fees")
public class FeeController {

    private static final Pattern PERIOD_PATTERN = Pattern.compile("^\\d{4}-\\d{2}$");
    private static final String CAPABILITY = PermissionMatrix.BILL_QUERY;
    private static final List<String> BUCKET_ALLOWED = List.of("D1", "D7", "D15", "D30");

    private final FeeService feeService;

    public FeeController(FeeService feeService) {
        this.feeService = feeService;
    }

    // ------------------------------------------------------------------
    // 欠费台账
    // ------------------------------------------------------------------

    /** 欠费台账（逾期分层 D1/D7/D15/D30 + 分层统计 + 分页）。 */
    @GetMapping("/arrears")
    public ResponseEntity<ApiResponse<ArrearsListResult>> arrears(
            @RequestParam(name = "building", required = false) String building,
            @RequestParam(name = "bucket", required = false) String bucket,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "20") int pageSize,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        ApiSupport.requirePageParams(page, pageSize);
        ArrearsBucket bucketValue;
        try {
            bucketValue = parseBucket(bucket);
        } catch (IllegalArgumentException ex) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("field", "bucket");
            details.put("value", bucket == null ? "" : bucket);
            details.put("allowed", BUCKET_ALLOWED);
            return ResponseEntity.badRequest().body(ApiResponse.error(ErrorCode.E_1002,
                    "bucket 取值非法（可选 D1|D7|D15|D30）", traceId, details));
        }

        Actor actor = ActorContext.actorOrNull();
        ResponseEntity<ApiResponse<ArrearsListResult>> denied = capabilityDenied(actor, true, traceId);
        if (denied != null) {
            return denied;
        }
        FeeService.FeeResult<ArrearsListResult> result = feeService.arrears(building, bucketValue, page, pageSize,
                FeeScope.of(actor), traceId);
        return ok(result, traceId);
    }

    // ------------------------------------------------------------------
    // 账单解释
    // ------------------------------------------------------------------

    /** 账单解释（公式 / 分摊明细 / 与上期差异 / 知识引用）。 */
    @GetMapping("/explain")
    public ResponseEntity<ApiResponse<BillExplanation>> explain(
            @RequestParam(name = "roomCode") String roomCode,
            @RequestParam(name = "period") String period,
            @RequestParam(name = "itemCode", required = false) String itemCode,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        String normalizedRoom = ApiSupport.requireRoomCode(roomCode);
        String normalizedPeriod = requirePeriod(period);
        String normalizedItem = itemCode == null || itemCode.isBlank()
                ? null
                : itemCode.trim().toUpperCase(java.util.Locale.ROOT);

        Actor actor = ActorContext.actorOrNull();
        ResponseEntity<ApiResponse<BillExplanation>> denied = capabilityDenied(actor, false, traceId);
        if (denied != null) {
            return denied;
        }
        FeeScope scope = FeeScope.of(actor);
        if (!scope.allowsRoom(normalizedRoom)) {
            return outOfScope(normalizedRoom, traceId);
        }
        FeeService.FeeResult<BillExplanation> result = feeService.explain(normalizedRoom, normalizedPeriod,
                normalizedItem, scope, traceId);
        return ok(result, traceId);
    }

    // ------------------------------------------------------------------
    // 权限（能力矩阵只读判定 + 数据范围）
    // ------------------------------------------------------------------

    /**
     * 能力判定（403 {@code E-2001} + {@code details{requiredCapability,role,reason=capability_denied}}）。
     *
     * @param arrears {@code true} = 欠费台账（agent 不可见，契约 §7.2）
     * @return 403 响应；放行返回 {@code null}
     */
    private static <T> ResponseEntity<ApiResponse<T>> capabilityDenied(Actor actor, boolean arrears,
            String traceId) {
        if (actor == null) {
            // 未鉴权上下文（property.security.enabled=false 的测试/离线演示）：Filter 层不拦截，能力判定跳过
            return null;
        }
        boolean allowed = PermissionMatrix.allows(actor.role(), CAPABILITY)
                && !(arrears && actor.role() == ActorRole.AGENT);
        if (allowed) {
            return null;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("requiredCapability", CAPABILITY);
        details.put("role", actor.role() == null ? "" : actor.role().value());
        details.put("reason", "capability_denied");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(ErrorCode.E_2001,
                "当前角色无该费用视图权限", traceId, details, null, false));
    }

    /**
     * 数据范围越权（owner 显式跨房）→ 403 {@code E-2002} + {@code details{reason=out_of_scope}}。
     * 拒绝落 {@code access.deny} 审计属 P3-028/P3-031 数据范围任务（P2 只读端点无写审计口径）。
     */
    private static <T> ResponseEntity<ApiResponse<T>> outOfScope(String roomCode, String traceId) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", "out_of_scope");
        details.put("resourceType", "bill");
        details.put("resourceId", roomCode);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(ErrorCode.E_2002,
                "仅可查看本户账单解释", traceId, details, null, false));
    }

    // ------------------------------------------------------------------
    // 参数解析
    // ------------------------------------------------------------------

    /** bucket 解析：空 = 全部分层；非法值 → {@link IllegalArgumentException}（400 E-1002 + allowed）。 */
    static ArrearsBucket parseBucket(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return ArrearsBucket.fromValue(raw.trim().toUpperCase(java.util.Locale.ROOT));
    }

    /** 账期校验（缺失由 Spring 兜底 400；格式非法 → 400 E-1001）。 */
    private static String requirePeriod(String period) {
        String normalized = period == null ? "" : period.trim();
        if (!PERIOD_PATTERN.matcher(normalized).matches()) {
            throw DomainException.badRequest("period 必须为 YYYY-MM（如 2026-11）",
                    Map.of("field", "period", "value", normalized));
        }
        return normalized;
    }

    /** 成功响应（无降级 / 有降级 2xx + degraded）。 */
    private static <T> ResponseEntity<ApiResponse<T>> ok(FeeService.FeeResult<T> result, String traceId) {
        if (result.degraded() == null) {
            return ResponseEntity.ok(ApiResponse.ok(result.value(), traceId));
        }
        return ResponseEntity.ok(ApiResponse.okDegraded(result.value(), result.degraded(), traceId));
    }
}
