package com.property.ai.domain.fee;

/**
 * 费项口径变更单（P3-032，平台内实体，**不是契约 DTO**）。
 *
 * <p>不可变：状态流转由 {@link FeeCatalogChangeService} 生成新快照替换，避免共享可变对象。
 *
 * @param changeId      变更单号 {@code FCC-yyyyMMdd-NNNN}
 * @param feeItemCode   费项编码（如 PROPERTY_FEE / PUBLIC_ENERGY，统一大写）
 * @param newFormula    新口径公式（人可读，如「面积 × 2.20 元/㎡·月」）
 * @param reason        变更原因（必填，审计/复核依据）
 * @param status        状态（pending_review / approved / rejected）
 * @param submittedBy   提交人 actorId（it/admin）
 * @param submittedAt   提交时间（业务时区 ISO 8601）
 * @param reviewedBy    审核人 actorId（it/admin，且不得与提交人相同）
 * @param reviewedAt    审核时间
 * @param reviewNote    审核意见（驳回时必填，通过时可选）
 */
public record FeeCatalogChange(
        String changeId,
        String feeItemCode,
        String newFormula,
        String reason,
        FeeCatalogChangeStatus status,
        String submittedBy,
        String submittedAt,
        String reviewedBy,
        String reviewedAt,
        String reviewNote) {

    /** 状态流转：返回新快照（不改原对象） */
    FeeCatalogChange reviewed(FeeCatalogChangeStatus target, String reviewer, String reviewedAt,
            String reviewNote) {
        return new FeeCatalogChange(changeId, feeItemCode, newFormula, reason, target,
                submittedBy, submittedAt, reviewer, reviewedAt, reviewNote);
    }
}
