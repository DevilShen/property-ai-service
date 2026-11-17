package com.property.ai.domain.ticket;

import java.util.LinkedHashMap;
import java.util.Map;

import com.property.ai.bff.dto.DegradedInfo;
import com.property.ai.bff.dto.ErrorCode;

/**
 * 领域异常：携带契约 17 类错误码 + 可选 HTTP 覆盖 + details + 降级信息。
 *
 * <p>分层约定：领域层不直接依赖 Spring MVC，HTTP 状态由 BFF 的
 * {@code com.property.ai.bff.api.ticket.ApiErrors} 依据错误码推导；
 * 个别契约特例（如 422 同房 24h 重复建单 E-1001）用 {@link #httpStatus()} 显式覆盖。
 */
public class DomainException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode code;
    private final Integer httpStatus;
    private final Map<String, Object> details;
    private final DegradedInfo degraded;
    private final boolean retryable;

    private DomainException(ErrorCode code, String message, Integer httpStatus, Map<String, Object> details,
            DegradedInfo degraded, boolean retryable) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
        this.details = details == null ? Map.of() : new LinkedHashMap<>(details);
        this.degraded = degraded;
        this.retryable = retryable;
    }

    public ErrorCode code() {
        return code;
    }

    /** null = 按错误码推导 HTTP 状态 */
    public Integer httpStatus() {
        return httpStatus;
    }

    public Map<String, Object> details() {
        return Map.copyOf(details);
    }

    public DegradedInfo degraded() {
        return degraded;
    }

    public boolean retryable() {
        return retryable;
    }

    // ------------------------------------------------------------------
    // 工厂
    // ------------------------------------------------------------------

    /** 400 E-1001 参数缺失/非法 */
    public static DomainException badRequest(String message, Map<String, Object> details) {
        return new DomainException(ErrorCode.E_1001, message, null, details, null, false);
    }

    /** 400 E-1003 房号无法归一 */
    public static DomainException badRoomCode(String roomCode) {
        return new DomainException(ErrorCode.E_1003, "房号无法识别，请确认楼栋-单元-房间", null,
                Map.of("roomCode", roomCode == null ? "" : roomCode), null, false);
    }

    /**
     * 400 E-1002 字典/枚举未匹配（P5 W19 {@code GET /events?type=} 等枚举筛选参数口径）：
     * 契约声明「事件类型未匹配 → 400 {@code E-1002}」，复用既有错误码，不新造字符串。
     */
    public static DomainException badDictionary(String message, Map<String, Object> details) {
        return new DomainException(ErrorCode.E_1002, message, 400, details, null, false);
    }

    /**
     * 400 E-1003 派单班组非法（P2-017：{@code action=dispatch} 带 teamName/teamId 但不在排班表内）。
     * 契约允许 400 E-1001/E-1003；这里用 E-1003（参数值不可接受），details 给出可选班组，便于前端纠正。
     */
    public static DomainException unknownScheduleTeam(String teamNameOrId) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("field", "teamName");
        details.put("value", teamNameOrId == null ? "" : teamNameOrId);
        details.put("reason", "team_not_in_schedule");
        details.put("hint", "班组不存在或不参与排班，可不传 teamName 由服务端自动派单");
        return new DomainException(ErrorCode.E_1003, "班组不存在或不参与排班：" + teamNameOrId, 400, details, null,
                false);
    }

    /** 403 E-2001 未核验/核验锁定 */
    public static DomainException unverified(String message, Map<String, Object> details, DegradedInfo degraded) {
        return new DomainException(ErrorCode.E_2001, message, 403, details, degraded, false);
    }

    /**
     * 403 E-2001 角色 × 能力矩阵拒绝（P3-029 owner 自有单能力；与 SecurityFilter 的 403 同口径）：
     * {@code details{requiredCapability, role, reason=capability_denied}}，不新造错误码。
     */
    public static DomainException forbiddenCapability(String requiredCapability, String role) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("requiredCapability", requiredCapability == null ? "" : requiredCapability);
        details.put("role", role == null ? "" : role);
        details.put("reason", "capability_denied");
        return new DomainException(ErrorCode.E_2001, "当前角色无该工单动作权限", 403, details, null, false);
    }

    /**
     * 403 E-2001 通用主体/流程拒绝（如 P3-032 口径变更「提交人不得自审」）：
     * 仍复用既有错误码，{@code details} 说明具体原因。
     */
    public static DomainException forbidden(String message, Map<String, Object> details) {
        return new DomainException(ErrorCode.E_2001, message, 403, details, null, false);
    }

    /**
     * 403 E-2002 数据越权（P3-028 owner 跨房/他人单）：
     * {@code details{reason=out_of_scope, resourceType, resourceId, roomCode?}}，不新造错误码。
     */
    public static DomainException outOfScope(String resourceType, String resourceId, String roomCode) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", "out_of_scope");
        details.put("resourceType", resourceType == null ? "ticket" : resourceType);
        details.put("resourceId", resourceId == null ? "" : resourceId);
        if (roomCode != null && !roomCode.isBlank()) {
            details.put("roomCode", roomCode);
        }
        return new DomainException(ErrorCode.E_2002, "无权访问他人房号/他人的工单", 403, details, null, false);
    }

    /** 404 E-3001 不存在 */
    public static DomainException notFound(ErrorCode code, String message, Map<String, Object> details) {
        return new DomainException(code, message, 404, details, null, false);
    }

    /**
     * 403 E-2002 跨项目越权（P5 W19 / v1.3.0 多项目隔离，契约 {@code ForbiddenProjectScope}）：
     * {@code details{reason=cross_project, resourceType, resourceId, projectId}}，复用既有错误码，
     * <b>不新造错误码字符串</b>；拒绝同时由 {@code domain/project/ProjectService} 落 {@code access.deny} 审计
     * （资源类型 {@code project}）。
     */
    public static DomainException crossProject(String resourceType, String resourceId, String projectId) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", "cross_project");
        details.put("resourceType", resourceType == null ? "project" : resourceType);
        details.put("resourceId", resourceId == null ? "" : resourceId);
        if (projectId != null && !projectId.isBlank()) {
            details.put("projectId", projectId);
        }
        return new DomainException(ErrorCode.E_2002, "无权访问其它项目的数据", 403, details, null, false);
    }

    /**
     * 403 E-2002 跨连接器作用域（P5 W19 / v1.3.0 映射中心多连接器）：
     * {@code details{reason=cross_connector, mappingId, connectorId, boundConnectorId}}；
     * 映射不能跨连接器发布/回滚（契约 {@code mappingPublish}/{@code mappingRollback} 描述）。
     */
    public static DomainException crossConnector(String mappingId, String connectorId, String boundConnectorId) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", "cross_connector");
        details.put("resourceType", "mapping");
        details.put("resourceId", mappingId == null ? "" : mappingId);
        details.put("mappingId", mappingId == null ? "" : mappingId);
        details.put("connectorId", connectorId == null ? "" : connectorId);
        details.put("boundConnectorId", boundConnectorId == null ? "" : boundConnectorId);
        return new DomainException(ErrorCode.E_2002, "映射不能跨连接器操作（连接器作用域不匹配）", 403, details, null,
                false);
    }

    /** 409 E-3002 状态不允许 / 版本冲突 */
    public static DomainException conflict(String message, Map<String, Object> details) {
        return new DomainException(ErrorCode.E_3002, message, 409, details, null, false);
    }

    /** 409 E-3003 幂等冲突（同键不同载荷） */
    public static DomainException idempotencyConflict(String idempotencyKey) {
        return new DomainException(ErrorCode.E_3003, "该请求已提交，正在使用首次结果；同一幂等键不能携带不同载荷",
                409, Map.of("idempotencyKey", idempotencyKey == null ? "" : idempotencyKey), null, false);
    }

    /** 422 E-1001 同房 24h 内重复建单需人工确认（契约 POST /tickets 422） */
    public static DomainException duplicateNeedConfirm(String roomCode, String existingTicketNo) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", "duplicate_24h");
        details.put("roomCode", roomCode);
        if (existingTicketNo != null && !existingTicketNo.isBlank()) {
            details.put("ticketNo", existingTicketNo);
        }
        return new DomainException(ErrorCode.E_1001, "该房 24 小时内已有同类报修，请确认是否重复建单", 422, details,
                null, false);
    }

    /** 503 上游失败（读：进降级阶梯；写：转人工待办、不假成功） */
    public static DomainException upstream(ErrorCode code, String message, Map<String, Object> details,
            DegradedInfo degraded, boolean retryable) {
        return new DomainException(code, message, 503, details, degraded, retryable);
    }

    /** 写操作失败：不自动重试，落人工待办 */
    public static DomainException writeFailed(ErrorCode code, String message, Map<String, Object> details,
            DegradedInfo degraded) {
        return new DomainException(code, message, 503, details, degraded, true);
    }

    /** 500 内部错误（未预期异常兜底） */
    public static DomainException internal(String message) {
        return new DomainException(ErrorCode.E_9001, message, 500, Map.of(), null, false);
    }
}
