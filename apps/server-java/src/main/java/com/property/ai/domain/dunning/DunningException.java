package com.property.ai.domain.dunning;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.property.ai.bff.dto.DunningFrequencyCheck;
import com.property.ai.bff.dto.DunningStatus;
import com.property.ai.bff.dto.ErrorCode;

/**
 * 催缴域异常（P3-013/014/015）：只携带契约 17 类 {@link ErrorCode} + HTTP 覆盖 + {@code details}。
 *
 * <p>为什么不复用 {@code domain.ticket.DomainException}：那是 W5—W9 工单域内的通用异常
 * （私有构造 + 工单域工厂方法），本任务 write_scope 不含该文件；与投诉域
 * （{@code ComplaintException} + 专用 {@code @RestControllerAdvice}）同口径，
 * 催缴域自带轻量异常 + {@code DunningExceptionHandler}，<b>错误码/信封/HTTP 状态完全一致</b>。
 *
 * <p>契约依据（{@code docs/openapi/openapi.yaml} {@code /api/v1/dunning/*} 响应描述）：
 * <pre>
 * 400 E-1001 未人工确认（details.reason=confirmation_required）/ 参数缺失 / 时间窗非法
 * 400 E-1002 枚举未匹配（details{field,value,allowed}）
 * 403 E-2001 能力不足（details{requiredCapability, role, reason=capability_denied}）
 * 404 E-3001 任务不存在
 * 409 E-3002 任务已关闭/已缴费、话术版本过期（details.reason=stale_script）、话术命中禁区
 * 409 E-3003 频次红线拦截（details{reason=frequency_redline, windowDays, used, limit}）
 * </pre>
 * 不新造错误码；mock 通道失败不在此包装（{@code ConnectorException} → 503，走既有连接器错误口径）。
 */
public class DunningException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode code;
    private final Integer httpStatus;
    private final Map<String, Object> details;

    private DunningException(ErrorCode code, Integer httpStatus, String message, Map<String, Object> details) {
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
    // 工厂（只覆盖契约已声明的错误码/语义）
    // ------------------------------------------------------------------

    /** 400 E-1001（缺字段/取值非法/时间窗非法）。 */
    public static DunningException badRequest(String message, Map<String, Object> details) {
        return new DunningException(ErrorCode.E_1001, null, message, details);
    }

    /** 400 E-1002（枚举未匹配，回带可选值）。 */
    public static DunningException badEnum(String field, String value, List<String> allowed) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("field", field);
        details.put("value", value == null ? "" : value);
        details.put("allowed", allowed == null ? List.of() : List.copyOf(allowed));
        return new DunningException(ErrorCode.E_1002, null,
                "枚举值未匹配：" + field + "=" + value + "（可选 "
                        + String.join(" / ", allowed == null ? List.of() : allowed) + "）",
                details);
    }

    /** 400 E-1001：人工确认缺失/false（契约 {@code details.reason=confirmation_required}）。 */
    public static DunningException confirmationRequired() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", "confirmation_required");
        details.put("field", "confirmed");
        return new DunningException(ErrorCode.E_1001, null,
                "需人工确认后才能发送（confirmed 必须为 true；生成话术不等于业主同意外发）", details);
    }

    /** 403 E-2001（角色 × 能力矩阵拒绝，与 SecurityFilter 的 403 同口径；领域层防御性复校）。 */
    public static DunningException forbiddenCapability(String requiredCapability, String role) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("requiredCapability", requiredCapability == null ? "" : requiredCapability);
        details.put("role", role == null ? "" : role);
        details.put("reason", "capability_denied");
        return new DunningException(ErrorCode.E_2001, 403, "当前角色无催缴任务权限", details);
    }

    /** 404 E-3001（任务不存在；不暴露他人房号是否存在）。 */
    public static DunningException notFound(String taskId) {
        return new DunningException(ErrorCode.E_3001, 404, "催缴任务不存在：" + taskId,
                Map.of("taskId", taskId == null ? "" : taskId));
    }

    /** 409 E-3002：话术版本过期（契约 {@code details.reason=stale_script}）。 */
    public static DunningException staleScript(String requestedVersion, String currentVersion) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", "stale_script");
        details.put("scriptVersion", requestedVersion == null ? "" : requestedVersion);
        if (currentVersion != null) {
            details.put("currentVersion", currentVersion);
        }
        return new DunningException(ErrorCode.E_3002, 409,
                "话术版本已过期/未生成，请重新生成话术后再发送", details);
    }

    /** 409 E-3002：话术命中禁区（{@code forbiddenHits} 非空不得发送，服务端复核）。 */
    public static DunningException forbiddenScript(List<String> forbiddenHits) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", "forbidden_hits");
        details.put("forbiddenHits", forbiddenHits == null ? List.of() : List.copyOf(forbiddenHits));
        return new DunningException(ErrorCode.E_3002, 409,
                "话术命中禁区规则，禁止发送；请重新生成合规话术", details);
    }

    /**
     * 409 E-3002：任务已关闭/已缴费不可再操作（契约 {@code details.reason=already_closed}）。
     * 生成话术/发送/重复关闭都复用该口径。
     */
    public static DunningException alreadyClosed(DunningStatus status) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", "already_closed");
        details.put("status", status == null ? "" : status.value());
        return new DunningException(ErrorCode.E_3002, 409,
                "任务已关闭/已缴费，不能继续催缴动作（缴费以 ERP 实收为准）", details);
    }

    /** 409 E-3002：任务状态不允许生成话术（已关闭/已缴费之外的终态保护，防御性）。 */
    public static DunningException taskNotScriptable(DunningStatus status) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", "task_not_scriptable");
        details.put("status", status == null ? "" : status.value());
        return new DunningException(ErrorCode.E_3002, 409, "当前任务状态不允许生成话术", details);
    }

    /**
     * 409 E-3003：频次红线拦截（契约 {@code details{reason=frequency_redline, windowDays, used, limit}}）。
     * 复用人写幂等/状态冲突码表达「红线拦截」，<b>不新造错误码</b>。
     */
    public static DunningException frequencyRedline(DunningFrequencyCheck check) {
        DunningFrequencyCheck effective = check == null
                ? new DunningFrequencyCheck(DunningPolicy.FREQUENCY_WINDOW_DAYS, DunningPolicy.FREQUENCY_LIMIT,
                        DunningPolicy.FREQUENCY_LIMIT, false)
                : check;
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", "frequency_redline");
        details.put("windowDays", effective.windowDays());
        details.put("used", effective.used());
        details.put("limit", effective.limit());
        return new DunningException(ErrorCode.E_3003, 409,
                "该户本周期触达已达上限，本次发送被拦截", details);
    }

    /** 409 E-3003：同一幂等键携带不同载荷（幂等冲突；重放同载荷返回首次结果）。 */
    public static DunningException idempotencyConflict(String idempotencyKey) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", "idempotency_conflict");
        details.put("idempotencyKey", idempotencyKey == null ? "" : idempotencyKey);
        return new DunningException(ErrorCode.E_3003, 409,
                "同一幂等键不能携带不同载荷；请使用首次请求结果或换新的 Idempotency-Key", details);
    }

    /** 便于测试/调用方读取 {@code details.forbiddenHits} 的可读副本（非空表示命中禁区）。 */
    @SuppressWarnings("unchecked")
    public List<String> forbiddenHits() {
        Object value = details.get("forbiddenHits");
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
