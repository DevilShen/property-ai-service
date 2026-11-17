package com.property.ai.domain.fee;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.property.ai.audit.AuditIp;
import com.property.ai.audit.AuditService;
import com.property.ai.bff.dto.Actor;
import com.property.ai.bff.dto.ActorRole;
import com.property.ai.bff.dto.AuditAction;
import com.property.ai.bff.dto.AuditResourceType;
import com.property.ai.bff.dto.AuditResult;
import com.property.ai.domain.ticket.DomainException;
import com.property.ai.domain.ticket.TicketTimes;
import com.property.ai.security.PermissionMatrix;

/**
 * 费项/口径变更卡点（P3-032）：<b>提交 → pending_review → 审核通过才生效</b>。
 *
 * <h2>REST 端点（P4 W18 补口，契约 v1.2.0）</h2>
 * P3-032 落地时契约 v1.1.0 只有 {@code /api/v1/fees/arrears}、{@code /api/v1/fees/explain}，
 * 没有 {@code /api/v1/fees/catalog/changes}，因此当时只实现<b>领域服务 + 单元测试</b>；契约 v1.2.0 已补齐
 * {@code GET/POST /api/v1/fees/catalog/changes} + {@code POST .../{changeId}/review}，由
 * {@code bff/api/fee/FeeCatalogController} 做纯 REST 适配（本类仍是唯一状态机与业务口径来源）。
 *
 * <h2>卡点语义（口径变更必须双人复核，不留后门）</h2>
 * <ol>
 *   <li><b>提交</b>（{@code fee.catalog.change}，it/admin）：写变更单，状态 {@code pending_review}，
 *       <b>不修改 FeeDictionary</b>——口径只有审核通过才生效；同一费项已有 {@code pending_review}
 *       变更单时再提交 → 409 {@code E-3002}（{@code details.reason=change_pending}，契约
 *       {@code feeCatalogChangeCreate} 的 409 口径）；</li>
 *   <li><b>审核</b>（{@code fee.catalog.review}，it/admin）：通过 → {@code approved} 并把新公式写入
 *       {@link FeeDictionary} 公式覆盖表（费用解释/{@code /meta/fee-items} 读取处立即生效）；
 *       驳回 → {@code rejected}（审核意见必填）；</li>
 *   <li><b>双人复核</b>：提交人不得自审（同一个 actorId 审核自己的变更 → 403 {@code E-2001}）；</li>
 *   <li>终态（approved/rejected）不可再审 → 409 {@code E-3002}
 *       {@code details.reason=invalid_change_status}。</li>
 * </ol>
 *
 * <h2>审计口径（契约 v1.2.0 专用枚举）</h2>
 * 提交/审核（含审核失败）落 {@link AuditAction#FEE_CATALOG_CHANGE} /
 * {@link AuditAction#FEE_CATALOG_REVIEW}，资源类型 {@link AuditResourceType#FEE_CATALOG}、
 * {@code resourceId = changeId}——<b>不再复用</b> {@code mapping.publish}/{@code mapping}（v1.1.0 过渡口径）。
 * 审计由本服务在状态机内统一写入，{@code PermissionMatrix} 端点规则不声明 {@code auditAction}，
 * 因此 AuditFilter 不会重复落同一动作（单次请求单条审计）。
 *
 * <p>存储：进程内 {@link ConcurrentHashMap}（demo，重启丢数据；P5 迁 PostgreSQL，接口/口径不变）。
 */
@Service
public class FeeCatalogChangeService {

    private static final Logger log = LoggerFactory.getLogger(FeeCatalogChangeService.class);

    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int MAX_FORMULA_LENGTH = 300;
    private static final int MAX_REASON_LENGTH = 200;
    private static final int MAX_NOTE_LENGTH = 200;

    private final ConcurrentMap<String, FeeCatalogChange> changes = new ConcurrentHashMap<>();
    private final AtomicInteger sequence = new AtomicInteger();
    private final AtomicReference<String> sequenceDay = new AtomicReference<>("");

    private final AuditService auditService;
    private final FeeDictionary feeDictionary;

    private Clock clock = Clock.system(TicketTimes.BUSINESS_ZONE);

    public FeeCatalogChangeService(AuditService auditService, FeeDictionary feeDictionary) {
        this.auditService = auditService;
        this.feeDictionary = feeDictionary;
    }

    /** 测试注入固定时钟（变更单号/时间断言用；生产不调用） */
    void setClockForTest(Clock clock) {
        this.clock = clock == null ? Clock.system(TicketTimes.BUSINESS_ZONE) : clock;
    }

    // ==================================================================
    // 提交
    // ==================================================================

    /**
     * 提交口径变更（状态 {@code pending_review}，<b>不生效</b>）；同一费项已有待审单 → 409
     * {@code E-3002}（{@code details.reason=change_pending}）。
     *
     * @param feeItemCode 费项编码（必填，统一大写）
     * @param newFormula  新公式/口径（必填，人可读）
     * @param reason      变更原因（必填，复核依据）
     * @param actor       当前演示主体（{@code null} = 离线演示/测试模式，不做能力拦截）
     */
    public FeeCatalogChange submit(String feeItemCode, String newFormula, String reason, Actor actor,
            String traceId, String clientIp) {
        assertCapability(actor, PermissionMatrix.FEE_CATALOG_CHANGE);
        String code = normalizeItemCode(feeItemCode);
        if (code.isEmpty()) {
            throw DomainException.badRequest("费项口径变更必须提供 feeItemCode",
                    Map.of("field", "feeItemCode", "required", true));
        }
        String formula = requireText("newFormula", newFormula, MAX_FORMULA_LENGTH, "新公式/口径");
        String changeReason = requireText("reason", reason, MAX_REASON_LENGTH, "变更原因");
        assertNoPendingChange(code);

        Instant now = clock.instant();
        String at = TicketTimes.iso(now);
        FeeCatalogChange change = new FeeCatalogChange(nextChangeId(now), code, formula.trim(), changeReason.trim(),
                FeeCatalogChangeStatus.PENDING_REVIEW, actorIdOf(actor), at, null, null, null);
        changes.put(change.changeId(), change);

        recordAudit(actor, change.changeId(), AuditAction.FEE_CATALOG_CHANGE, AuditResult.SUCCESS, traceId, clientIp,
                List.of("newFormula"));
        log.info("[fee-catalog] submit ok changeId={} itemCode={} status=pending_review traceId={}",
                change.changeId(), code, traceId);
        return change;
    }

    // ==================================================================
    // 审核（通过才生效）
    // ==================================================================

    /**
     * 审核口径变更（it/admin，且不得自审）：通过 → {@code approved} + 生效到 {@link FeeDictionary}；
     * 驳回 → {@code rejected}（审核意见必填）。
     *
     * @param approve    {@code true} 通过 / {@code false} 驳回
     * @param reviewNote 审核意见（驳回必填）
     */
    public FeeCatalogChange review(String changeId, boolean approve, String reviewNote, Actor actor,
            String traceId, String clientIp) {
        assertCapability(actor, PermissionMatrix.FEE_CATALOG_REVIEW);
        String id = changeId == null ? "" : changeId.trim();
        FeeCatalogChange change = id.isEmpty() ? null : changes.get(id);
        if (change == null) {
            throw DomainException.notFound(com.property.ai.bff.dto.ErrorCode.E_3001,
                    "口径变更单不存在：" + id, Map.of("changeId", id));
        }
        try {
            if (change.status().terminal()) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("reason", "invalid_change_status");
                details.put("changeId", change.changeId());
                details.put("status", change.status().value());
                details.put("allowed", List.of());
                details.put("hint", "approved/rejected 为终态，不可重复审核；新调整请重新提交变更单");
                throw DomainException.conflict("该口径变更已处理（status=" + change.status().value() + "），不可重复审核",
                        details);
            }
            String reviewer = actorIdOf(actor);
            if (change.submittedBy() != null && !change.submittedBy().isBlank()
                    && change.submittedBy().equals(reviewer)) {
                throw DomainException.forbidden("提交人不得审核自己提交的口径变更（需他人复核）",
                        Map.of("reason", "self_review_forbidden", "changeId", change.changeId(),
                                "requiredCapability", PermissionMatrix.FEE_CATALOG_REVIEW));
            }
            String note = reviewNote == null ? "" : reviewNote.trim();
            if (!approve && note.isEmpty()) {
                throw DomainException.badRequest("驳回口径变更必须填写审核意见（reviewNote）",
                        Map.of("field", "reviewNote", "required", true));
            }
            if (note.length() > MAX_NOTE_LENGTH) {
                throw DomainException.badRequest("reviewNote 长度不能超过 " + MAX_NOTE_LENGTH + " 字符",
                        Map.of("field", "reviewNote", "length", note.length(), "maximum", MAX_NOTE_LENGTH));
            }
            String at = TicketTimes.iso(clock.instant());
            FeeCatalogChangeStatus target = approve
                    ? FeeCatalogChangeStatus.APPROVED
                    : FeeCatalogChangeStatus.REJECTED;
            FeeCatalogChange updated = change.reviewed(target, reviewer, at, note.isEmpty() ? null : note);
            changes.put(updated.changeId(), updated);
            if (approve) {
                // 卡点通过后**立即生效**：覆盖 FeeDictionary 公式读取（费用解释与 /meta/fee-items 同一事实源）
                feeDictionary.applyFormulaOverride(updated.feeItemCode(), updated.newFormula());
            }
            recordAudit(actor, updated.changeId(), AuditAction.FEE_CATALOG_REVIEW, AuditResult.SUCCESS, traceId,
                    clientIp, List.of("reviewNote"));
            log.info("[fee-catalog] review ok changeId={} itemCode={} result={} reviewer={} effective={} traceId={}",
                    updated.changeId(), updated.feeItemCode(), target.value(), reviewer, approve, traceId);
            return updated;
        } catch (RuntimeException ex) {
            recordAudit(actor, change.changeId(), AuditAction.FEE_CATALOG_REVIEW, AuditResult.FAILED, traceId,
                    clientIp, List.of());
            throw ex;
        }
    }

    // ==================================================================
    // 查询 / 测试重置
    // ==================================================================

    /** 全部变更单（提交时间倒序：新 → 旧） */
    public List<FeeCatalogChange> list() {
        List<FeeCatalogChange> all = new ArrayList<>(changes.values());
        all.sort(Comparator.comparing(FeeCatalogChange::submittedAt,
                Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(FeeCatalogChange::changeId));
        return List.copyOf(all);
    }

    /** 按变更单号查（不存在返回空） */
    public Optional<FeeCatalogChange> find(String changeId) {
        return changeId == null ? Optional.empty() : Optional.ofNullable(changes.get(changeId.trim()));
    }

    /** 演示/测试重置（生产实现不调用） */
    public void resetForTest() {
        changes.clear();
        sequence.set(0);
        sequenceDay.set("");
        feeDictionary.clearFormulaOverrides();
        clock = Clock.system(TicketTimes.BUSINESS_ZONE);
    }

    // ==================================================================
    // 内部
    // ==================================================================

    private void assertCapability(Actor actor, String capability) {
        if (actor == null) {
            // security 关闭的离线演示/测试模式：与既有领域服务口径一致，不做能力拦截
            log.warn("[fee-catalog] 无演示主体（security 关闭），跳过能力校验 capability={}", capability);
            return;
        }
        if (!PermissionMatrix.allows(actor.role(), capability)) {
            throw DomainException.forbiddenCapability(capability,
                    actor.role() == null ? "" : actor.role().value());
        }
    }

    private static String normalizeItemCode(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * 同一费项只允许一张待审变更单（契约 {@code feeCatalogChangeCreate} 409
     * {@code details.reason=change_pending}）：避免并发口径互相覆盖，先审完再提交新的。
     */
    private void assertNoPendingChange(String feeItemCode) {
        for (FeeCatalogChange existing : changes.values()) {
            if (existing.status() == FeeCatalogChangeStatus.PENDING_REVIEW
                    && existing.feeItemCode().equals(feeItemCode)) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("reason", "change_pending");
                details.put("feeItemCode", feeItemCode);
                details.put("changeId", existing.changeId());
                details.put("status", existing.status().value());
                details.put("hint", "同一费项已有待审核变更，请先完成审核/驳回后再提交");
                throw DomainException.conflict("费项 " + feeItemCode + " 已有待审核的口径变更（"
                        + existing.changeId() + "），不可重复提交", details);
            }
        }
    }

    private static String requireText(String field, String value, int maxLength, String label) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) {
            throw DomainException.badRequest(label + "必填（" + field + "）",
                    Map.of("field", field, "required", true));
        }
        if (text.length() > maxLength) {
            throw DomainException.badRequest(field + " 长度不能超过 " + maxLength + " 字符",
                    Map.of("field", field, "length", text.length(), "maximum", maxLength));
        }
        return text;
    }

    private String nextChangeId(Instant at) {
        String day = DAY_FORMAT.format(at.atZone(TicketTimes.BUSINESS_ZONE));
        synchronized (sequence) {
            if (!day.equals(sequenceDay.get())) {
                sequenceDay.set(day);
                sequence.set(0);
            }
            return "FCC-" + day + "-" + String.format("%04d", sequence.incrementAndGet());
        }
    }

    private static String actorIdOf(Actor actor) {
        return actor == null || actor.actorId() == null ? "anonymous" : actor.actorId();
    }

    /**
     * 审计（失败只 warn，不影响业务）：契约 v1.2.0 专用动作 {@code fee.catalog.change} /
     * {@code fee.catalog.review} + 资源类型 {@code fee_catalog}，{@code resourceId = changeId}。
     * <b>不再复用</b> {@code mapping.publish}（v1.1.0 过渡口径）；单条请求只由这里落一条审计。
     */
    private void recordAudit(Actor actor, String changeId, AuditAction action, AuditResult result, String traceId,
            String clientIp, List<String> maskedFields) {
        try {
            auditService.record(new AuditService.AuditRecord(
                    actorIdOf(actor),
                    actor == null || actor.role() == null ? ActorRole.ADMIN : actor.role(),
                    action,
                    AuditResourceType.FEE_CATALOG,
                    changeId,
                    traceId,
                    result,
                    AuditIp.sanitize(clientIp),
                    maskedFields));
        } catch (RuntimeException ex) {
            log.warn("[fee-catalog] 审计写入失败 changeId={} action={} err={}", changeId,
                    action == null ? "" : action.value(), ex.getClass().getSimpleName());
        }
    }
}
