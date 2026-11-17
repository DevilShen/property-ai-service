package com.property.ai.bff.api.fee;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
import com.property.ai.bff.dto.FeeCatalogChange;
import com.property.ai.bff.dto.FeeCatalogChangeListResult;
import com.property.ai.bff.dto.FeeCatalogChangeRequest;
import com.property.ai.bff.dto.FeeCatalogChangeReviewRequest;
import com.property.ai.bff.dto.FeeCatalogChangeStatus;
import com.property.ai.bff.dto.ReviewDecision;
import com.property.ai.domain.fee.FeeCatalogChangeService;
import com.property.ai.domain.ticket.DomainException;
import com.property.ai.security.ActorContext;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 费项口径变更 REST API（P4 W18 补口 / 契约 v1.2.0 {@code feeCatalogChangeList|Create|Review}）。
 *
 * <p>背景：契约 v1.2.0 已声明 3 个 operation，但 P3-032 只落了 {@code domain/fee/FeeCatalogChangeService}
 * 服务层（当时契约 v1.1.0 无该端点），认证后 404。本 Controller 是<b>纯 REST 适配层</b>：
 * 状态机、能力二次校验、双人复核、审计全部复用既有服务，不重复实现业务规则。
 *
 * <pre>
 * GET  /api/v1/fees/catalog/changes                     200 列表（status/feeItemCode?/page/pageSize）
 * POST /api/v1/fees/catalog/changes                     201 提交（pending_review，不直接改计费）
 * POST /api/v1/fees/catalog/changes/{changeId}/review   200 审核（approved 生效 / rejected 关闭）
 * </pre>
 *
 * <h2>分层与鉴权</h2>
 * <ul>
 *   <li>角色 × 能力（{@code fee.catalog.change} / {@code fee.catalog.review}，<b>仅 it/admin</b>）由
 *       {@code SecurityFilter} + {@code PermissionMatrix} 统一执行，Controller 不写角色判断；</li>
 *   <li>写操作审计（{@code fee.catalog.change}/{@code fee.catalog.review}，资源类型 {@code fee_catalog}）
 *       由 {@link FeeCatalogChangeService} 在状态机内落库（含审核失败留痕），因此 {@code PermissionMatrix}
 *       端点规则<b>不</b>声明 {@code auditAction}，避免 AuditFilter 与服务层双写；</li>
 *   <li>404 {@code E-3001} / 409 {@code E-3002}（终态、同一费项已有待审单）/ 403 {@code E-2001}
 *       （自审 {@code details.reason=self_review_forbidden}）/ 400 {@code E-1001} 的业务口径由领域服务给出，
 *       异常统一经 {@code TicketApiExceptionHandler} 转契约错误信封。</li>
 * </ul>
 *
 * <h2>查询口径</h2>
 * <p>{@code status} 过滤省略 = 全部（契约 {@code FeeCatalogChangeStatus} 枚举，非法值 → 400 {@code E-1001}
 * + {@code details.allowed}）；{@code feeItemCode} 是<b>契约外的可选增量过滤</b>（任务卡要求按费项筛选，
 * 向后兼容：不传即全量，不改变契约响应形状）；分页沿用 README §2（{@code page ≥ 1}、{@code pageSize 1—100}）。
 * 列表按提交时间倒序（新 → 旧，服务层排序）。
 */
@RestController
@RequestMapping("/api/v1/fees/catalog/changes")
public class FeeCatalogController {

    /** 契约 {@code FeeCatalogChangeStatus} 全部合法取值（400 details.allowed 用） */
    private static final List<String> STATUS_VALUES = java.util.Arrays.stream(FeeCatalogChangeStatus.values())
            .map(FeeCatalogChangeStatus::value).toList();

    private final FeeCatalogChangeService catalogChangeService;

    public FeeCatalogController(FeeCatalogChangeService catalogChangeService) {
        this.catalogChangeService = catalogChangeService;
    }

    // ------------------------------------------------------------------
    // 列表
    // ------------------------------------------------------------------

    /**
     * 变更单列表（契约 {@code feeCatalogChangeList}）：状态/费项过滤 + 分页，按提交时间倒序。
     *
     * <p>{@code status} 非法 → 400 {@code E-1001}（{@code details.allowed} 给可选值）；
     * {@code page}/{@code pageSize} 越界 → 400 {@code E-1001}（契约统一分页约定）。
     */
    @GetMapping
    public ResponseEntity<ApiResponse<FeeCatalogChangeListResult>> list(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "feeItemCode", required = false) String feeItemCode,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "20") int pageSize,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        ApiSupport.requirePageParams(page, pageSize);
        FeeCatalogChangeStatus statusFilter = parseStatus(status);
        String itemCodeFilter = normalizeItemCode(feeItemCode);

        List<FeeCatalogChange> matched = new ArrayList<>();
        for (com.property.ai.domain.fee.FeeCatalogChange change : catalogChangeService.list()) {
            if (statusFilter != null && !statusFilter.value().equals(change.status().value())) {
                continue;
            }
            if (itemCodeFilter != null && !itemCodeFilter.equals(change.feeItemCode())) {
                continue;
            }
            matched.add(toDto(change));
        }

        int total = matched.size();
        int offset = (page - 1) * pageSize;
        List<FeeCatalogChange> pageItems = offset >= total
                ? List.of()
                : List.copyOf(matched.subList(offset, Math.min(total, offset + pageSize)));
        return ResponseEntity.ok(ApiResponse.ok(
                new FeeCatalogChangeListResult(pageItems, page, pageSize, total), traceId));
    }

    // ------------------------------------------------------------------
    // 提交
    // ------------------------------------------------------------------

    /**
     * 提交口径变更（契约 {@code feeCatalogChangeCreate}，201）：进入 {@code pending_review}，<b>不生效</b>。
     *
     * <p>同一费项已有 {@code pending_review} 变更 → 409 {@code E-3002}
     * （{@code details.reason=change_pending}，由服务层状态机卡点给出）。
     */
    @PostMapping
    public ResponseEntity<ApiResponse<FeeCatalogChange>> create(
            @RequestBody(required = false) FeeCatalogChangeRequest requestBody,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        if (requestBody == null) {
            throw DomainException.badRequest("请求体必填（feeItemCode/newFormula/reason）",
                    Map.of("field", "body", "required", true));
        }
        Actor actor = ActorContext.actorOrNull();
        com.property.ai.domain.fee.FeeCatalogChange change = catalogChangeService.submit(
                requestBody.feeItemCode(), requestBody.newFormula(), requestBody.reason(), actor, traceId,
                request.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(toDto(change), traceId));
    }

    // ------------------------------------------------------------------
    // 审核
    // ------------------------------------------------------------------

    /**
     * 审核口径变更（契约 {@code feeCatalogChangeReview}）：{@code approved} → 新口径生效；
     * {@code rejected} → 关闭（{@code note} 必填）。
     *
     * <p>契约 §fee.catalog：404 {@code E-3001}（不存在）、409 {@code E-3002}（已终态）、
     * 403 {@code E-2001}（自审 {@code self_review_forbidden}）、400 {@code E-1001}（{@code decision} 非法 /
     * 驳回缺 {@code note}）。状态机与生效点（{@code FeeDictionary} 公式覆盖）在服务层，Controller 不改写。
     */
    @PostMapping("/{changeId}/review")
    public ResponseEntity<ApiResponse<FeeCatalogChange>> review(@PathVariable String changeId,
            @RequestBody(required = false) FeeCatalogChangeReviewRequest requestBody,
            HttpServletRequest request) {
        String traceId = ApiSupport.traceId(request);
        if (requestBody == null || requestBody.decision() == null) {
            throw DomainException.badRequest("审核必须提供 decision（approved | rejected）",
                    Map.of("field", "decision", "required", true, "allowed",
                            List.of(ReviewDecision.APPROVED.value(), ReviewDecision.REJECTED.value())));
        }
        boolean approve = requestBody.decision() == ReviewDecision.APPROVED;
        Actor actor = ActorContext.actorOrNull();
        com.property.ai.domain.fee.FeeCatalogChange change = catalogChangeService.review(changeId, approve,
                requestBody.note(), actor, traceId, request.getRemoteAddr());
        return ResponseEntity.ok(ApiResponse.ok(toDto(change), traceId));
    }

    // ------------------------------------------------------------------
    // DTO 映射 / 参数解析
    // ------------------------------------------------------------------

    /** 领域实体 → 契约 DTO（可选字段 reviewedBy 未审核时由 {@code @JsonInclude(NON_NULL)} 省略）。 */
    static FeeCatalogChange toDto(com.property.ai.domain.fee.FeeCatalogChange change) {
        return new FeeCatalogChange(
                change.changeId(),
                change.feeItemCode(),
                change.newFormula(),
                change.reason(),
                FeeCatalogChangeStatus.fromValue(change.status().value()),
                change.submittedBy(),
                change.reviewedBy());
    }

    /** status 解析：省略 = 全部；非法 → 400 E-1001 + allowed（契约行为态由领域服务给出）。 */
    static FeeCatalogChangeStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        for (FeeCatalogChangeStatus item : FeeCatalogChangeStatus.values()) {
            if (item.value().equals(value)) {
                return item;
            }
        }
        throw DomainException.badRequest("status 取值非法（可选 pending_review|approved|rejected）",
                Map.of("field", "status", "value", raw, "allowed", STATUS_VALUES));
    }

    /** 费项编码归一（与领域服务一致：trim + 大写）；空 = 不过滤。 */
    static String normalizeItemCode(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim().toUpperCase(Locale.ROOT);
    }
}
