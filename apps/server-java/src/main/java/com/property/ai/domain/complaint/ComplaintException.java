package com.property.ai.domain.complaint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.property.ai.bff.dto.ComplaintStatus;
import com.property.ai.bff.dto.ComplaintTransitionAction;
import com.property.ai.bff.dto.ErrorCode;

/**
 * 投诉域异常（P3-021/023）：只携带契约 17 类 {@link ErrorCode} + HTTP 覆盖 + {@code details}。
 *
 * <p>为什么不复用 {@code com.property.ai.domain.ticket.DomainException}：那是在 W5—W9 工单域内定义的
 * 包级通用异常类（私有构造 + 工单域工厂方法），本任务 write_scope 不含该文件；为避免跨域改文件与
 * 「费用 agent」并行冲突，投诉域自带轻量异常 + 专用 {@code @RestControllerAdvice}（见
 * {@code bff/api/complaint/ComplaintExceptionHandler}），<b>错误码/信封/HTTP 状态与工单域完全同口径</b>。
 *
 * <p>契约依据（{@code docs/openapi/README.md} §4 / 各 path 响应描述）：
 * <pre>
 * 400 E-1001 参数缺失/非法、E-1002 枚举未匹配
 * 403 E-2001 能力不足（details{requiredCapability, role, reason:capability_denied}）
 * 403 E-2002 数据越权（details{reason:out_of_scope, resourceType, resourceId, roomCode?}）
 * 404 E-3001 投诉不存在
 * 409 E-3002 非法迁移（details{status, allowed}）/ 版本冲突（details{reason:version_conflict}）
 * </pre>
 * 不新造错误码。
 */
public class ComplaintException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode code;
    private final Integer httpStatus;
    private final Map<String, Object> details;

    private ComplaintException(ErrorCode code, Integer httpStatus, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
        this.details = details == null ? Map.of() : new LinkedHashMap<>(details);
    }

    public ErrorCode code() {
        return code;
    }

    /** {@code null} = 按 {@link ErrorCode} 推导 HTTP 状态（由 BFF 统一判定）。 */
    public Integer httpStatus() {
        return httpStatus;
    }

    public Map<String, Object> details() {
        return Map.copyOf(details);
    }

    // ------------------------------------------------------------------
    // 工厂（只覆盖契约已声明的错误码）
    // ------------------------------------------------------------------

    /** 400 E-1001（缺字段/取值非法/时间窗非法）。 */
    public static ComplaintException badRequest(String message, Map<String, Object> details) {
        return new ComplaintException(ErrorCode.E_1001, null, message, details);
    }

    /** 400 E-1002（枚举未匹配，回带可选值）。 */
    public static ComplaintException badEnum(String field, String value, List<String> allowed) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("field", field);
        details.put("value", value == null ? "" : value);
        details.put("allowed", allowed == null ? List.of() : List.copyOf(allowed));
        return new ComplaintException(ErrorCode.E_1002, null,
                "枚举值未匹配：" + field + "=" + value + "（可选 " + String.join(" / ", allowed == null ? List.of() : allowed) + "）",
                details);
    }

    /** 403 E-2001（角色 × 能力矩阵拒绝，与 SecurityFilter 的 403 同口径）。 */
    public static ComplaintException forbiddenCapability(String requiredCapability, String role) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("requiredCapability", requiredCapability == null ? "" : requiredCapability);
        details.put("role", role == null ? "" : role);
        details.put("reason", "capability_denied");
        return new ComplaintException(ErrorCode.E_2001, 403, "当前角色无该投诉动作权限", details);
    }

    /** 403 E-2002（owner 跨房/他人投诉：数据越权，契约 §7.2 数据范围）。 */
    public static ComplaintException outOfScope(String resourceType, String resourceId, String roomCode) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", "out_of_scope");
        details.put("resourceType", resourceType == null ? "complaint" : resourceType);
        details.put("resourceId", resourceId == null ? "" : resourceId);
        if (roomCode != null && !roomCode.isBlank()) {
            details.put("roomCode", roomCode);
        }
        return new ComplaintException(ErrorCode.E_2002, 403, "无权访问他人房号/他人的投诉", details);
    }

    /** 404 E-3001（不暴露他人房号是否存在：不存在才 404，存在但越权走 403 E-2002）。 */
    public static ComplaintException notFound(String complaintId) {
        return new ComplaintException(ErrorCode.E_3001, 404, "投诉不存在：" + complaintId,
                Map.of("complaintId", complaintId == null ? "" : complaintId));
    }

    /**
     * 409 E-3002 非法迁移（契约 {@code details{status, allowed}}；
     * {@code allowed} = 当前状态可执行的动作 code 列表）。
     */
    public static ComplaintException invalidTransition(ComplaintStatus status, ComplaintTransitionAction action) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", "invalid_transition");
        details.put("status", status == null ? "" : status.value());
        details.put("action", action == null ? "" : action.value());
        details.put("allowed", ComplaintTransitions.allowedActions(status));
        details.put("statusText", ComplaintTransitions.statusText(status));
        return new ComplaintException(ErrorCode.E_3002, 409,
                "当前状态（" + ComplaintTransitions.statusText(status) + "）不支持「"
                        + ComplaintTransitions.actionText(action) + "」，可选动作："
                        + String.join(" / ", ComplaintTransitions.allowedActions(status)),
                details);
    }

    /**
     * 409 E-3002 乐观锁版本冲突（契约 transition 描述：{@code details.reason=version_conflict}，
     * 客户端重取详情重试）。<b>不是</b> E-3003：E-3003 在契约里是「同一幂等键不同载荷」。
     */
    public static ComplaintException versionConflict(int expectedVersion, int currentVersion) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", "version_conflict");
        details.put("version", expectedVersion);
        details.put("currentVersion", currentVersion);
        return new ComplaintException(ErrorCode.E_3002, 409,
                "投诉版本已变化（当前 " + currentVersion + "，请求 " + expectedVersion + "），请重新获取详情后重试",
                details);
    }

    /** 便于测试/调用方读取：{@code details.allowed} 的可读副本（非法迁移时非空）。 */
    @SuppressWarnings("unchecked")
    public List<String> allowedActions() {
        Object value = details.get("allowed");
        if (value instanceof List<?> list) {
            List<String> copy = new ArrayList<>();
            for (Object item : list) {
                copy.add(String.valueOf(item));
            }
            return List.copyOf(copy);
        }
        return List.of();
    }
}
