package com.property.ai.domain.fee;

/**
 * 费项口径变更状态（P3-032，**平台内状态机，非契约枚举**）。
 *
 * <p>为什么不在 {@code bff/dto} 定义：契约 v1.1.0 没有 {@code /api/v1/fees/catalog/changes} 端点，
 * 本任务不新增未定义端点（AGENTS.md §2.9/§3），因此状态只存在于领域层；待契约 v1.2 增补
 * {@code fee.catalog.*} 端点与枚举后再上移为 DTO/契约枚举。
 *
 * <pre>
 * pending_review --approve--> approved（生效到 FeeDictionary）
 *                --reject---> rejected
 * approved / rejected 为终态（不可再审，重复审核 409 E-3002）
 * </pre>
 */
public enum FeeCatalogChangeStatus {

    /** 已提交待审核：**不生效**（只落变更单，FeeDictionary 不变） */
    PENDING_REVIEW("pending_review"),
    /** 审核通过：口径已生效（FeeDictionary 公式覆盖表写入） */
    APPROVED("approved"),
    /** 审核驳回：口径未生效 */
    REJECTED("rejected");

    private final String value;

    FeeCatalogChangeStatus(String value) {
        this.value = value;
    }

    /** 状态 code（日志/报告用；非契约 JSON 值） */
    public String value() {
        return value;
    }

    /** 是否终态（approved / rejected 不可再审核） */
    public boolean terminal() {
        return this != PENDING_REVIEW;
    }
}
